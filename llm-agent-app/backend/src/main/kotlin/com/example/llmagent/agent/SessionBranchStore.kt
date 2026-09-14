package com.example.llmagent.agent

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.support.GeneratedKeyHolder
import org.springframework.stereotype.Component

/** Ветка диалога сессии (строка session_branches). */
data class Branch(
    val id: Long,
    val sessionId: String,
    val name: String,
    /** id последнего сообщения ветки (chat_messages.id); null — сообщений ещё нет. */
    val headMessageId: Long? = null,
    /** created_at — UTC 'YYYY-MM-DD HH:MM:SS'. */
    val createdAt: String,
)

/**
 * Персистентность веток диалога (таблица `session_branches`) и активной ветки сессии.
 * Активная ветка хранится в колонке `session_context_strategy.active_branch_id` (см.
 * SessionContextStore — обоснование выбора: одна строка на сессию уже есть, отдельная
 * таблица не нужна; ON CONFLICT DO UPDATE пишет только эту колонку, не затирая стратегию).
 * Здесь — страховочное создание обеих таблиц для старых файлов БД (как SessionContextStore).
 *
 * Дерево parent_id в chat_messages поддерживает SessionStore.append (для ВСЕХ стратегий):
 * не-системное сообщение получает parent_id = голову активной (или «эффективной») ветки,
 * после чего голова двигается к новому сообщению. SessionBranchStore только хранит головы.
 *
 * «Эффективная» ветка (см. [effectiveBranch]) — активная, если выбрана, иначе ветка по
 * умолчанию «Основная» (первая): до первого fork/переключения дерево растёт в ней.
 */
@Component
class SessionBranchStore(private val jdbc: JdbcTemplate) {

    init {
        jdbc.execute(
            """
            CREATE TABLE IF NOT EXISTS session_branches (
                id              INTEGER PRIMARY KEY AUTOINCREMENT,
                session_id      TEXT NOT NULL,
                name            TEXT NOT NULL,
                head_message_id INTEGER,
                created_at      TEXT NOT NULL DEFAULT (datetime('now'))
            )
            """.trimIndent()
        )
        // Активная ветка живёт в session_context_strategy.active_branch_id; досоздаём
        // таблицу страховочно (иначе setActive упадёт, если SessionContextStore ещё не инициализирован).
        jdbc.execute(
            """
            CREATE TABLE IF NOT EXISTS session_context_strategy (
                session_id       TEXT PRIMARY KEY,
                strategy         TEXT NOT NULL DEFAULT 'none',
                window_size      INTEGER NOT NULL DEFAULT 12,
                active_branch_id INTEGER NULL
            )
            """.trimIndent()
        )
    }

    private val rowMapper = RowMapper<Branch> { rs, _ ->
        Branch(
            id = rs.getLong("id"),
            sessionId = rs.getString("session_id"),
            name = rs.getString("name"),
            headMessageId = rs.getLong("head_message_id").takeIf { !rs.wasNull() },
            createdAt = rs.getString("created_at"),
        )
    }

    /** Все ветки сессии в порядке создания (ORDER BY id). */
    fun list(sessionId: String): List<Branch> =
        jdbc.query(
            "SELECT id, session_id, name, head_message_id, created_at FROM session_branches " +
                "WHERE session_id = ? ORDER BY id",
            rowMapper,
            sessionId,
        )

    /** Ветка по id; null — нет такой ветки у сессии. */
    fun get(sessionId: String, branchId: Long): Branch? =
        jdbc.query(
            "SELECT id, session_id, name, head_message_id, created_at FROM session_branches " +
                "WHERE session_id = ? AND id = ?",
            rowMapper,
            sessionId, branchId,
        ).firstOrNull()

    /** Создаёт ветку с заданными именем и головой; возвращает строку с id/created_at из БД. */
    fun create(sessionId: String, name: String, headMessageId: Long?): Branch {
        val keyHolder = GeneratedKeyHolder()
        jdbc.update({ connection ->
            val ps = connection.prepareStatement(
                "INSERT INTO session_branches (session_id, name, head_message_id) VALUES (?, ?, ?)",
                arrayOf("id"),
            )
            ps.setString(1, sessionId)
            ps.setString(2, name)
            if (headMessageId != null) ps.setLong(3, headMessageId) else ps.setNull(3, java.sql.Types.INTEGER)
            ps
        }, keyHolder)
        val id = keyHolder.key?.toLong()
            ?: throw IllegalStateException("Не удалось получить id новой ветки сессии $sessionId")
        return get(sessionId, id)!!
    }

    /** Число веток сессии (для авто-имени «Ветка N» в POST /branches). */
    fun count(sessionId: String): Int {
        val v = jdbc.queryForObject(
            "SELECT COUNT(*) FROM session_branches WHERE session_id = ?",
            Integer::class.java,
            sessionId,
        )
        return v?.toInt() ?: 0
    }

    /** Активная ветка сессии; null — активная не выбрана. */
    fun getActive(sessionId: String): Branch? {
        val activeId = jdbc.query(
            "SELECT active_branch_id FROM session_context_strategy WHERE session_id = ?",
            { rs, _ -> rs.getLong("active_branch_id").takeIf { !rs.wasNull() } },
            sessionId,
        ).firstOrNull() ?: return null
        return get(sessionId, activeId)
    }

    /** Делает ветку активной: POST /branches (после создания) и PUT /branches (переключение). */
    fun setActive(sessionId: String, branchId: Long) {
        jdbc.update(
            "INSERT INTO session_context_strategy (session_id, active_branch_id) VALUES (?, ?) " +
                "ON CONFLICT(session_id) DO UPDATE SET active_branch_id = excluded.active_branch_id",
            sessionId, branchId,
        )
    }

    /**
     * «Эффективная» ветка сессии: активная, если выбрана; иначе ветка по умолчанию —
     * «Основная» (первая созданная). Через неё растёт дерево до первого fork/переключения.
     */
    fun effectiveBranch(sessionId: String): Branch? =
        getActive(sessionId) ?: list(sessionId).firstOrNull()

    /** Продвигает голову ветки к новому сообщению (вызывается SessionStore.append). */
    fun advanceHead(sessionId: String, branchId: Long, newHeadMessageId: Long) {
        jdbc.update(
            "UPDATE session_branches SET head_message_id = ? WHERE id = ? AND session_id = ?",
            newHeadMessageId, branchId, sessionId,
        )
    }

    /**
     * Ветка по умолчанию «Основная» при первом обращении: создаётся, если веток ещё нет.
     * headMessageId — стартовая голова цепочки (после backfillLinearParents это последнее
     * не-системное сообщение истории; если истории нет — null). Идемпотентно.
     */
    fun ensureDefault(sessionId: String, headMessageId: Long?): Branch {
        list(sessionId).firstOrNull()?.let { return it }
        return create(sessionId, DEFAULT_BRANCH_NAME, headMessageId)
    }

    /** Удаляет все ветки сессии (вызывается при DELETE /api/sessions/{sessionId}). */
    fun remove(sessionId: String) {
        jdbc.update("DELETE FROM session_branches WHERE session_id = ?", sessionId)
    }

    companion object {
        /** Имя ветки по умолчанию (первой/«Основной» в сессии). */
        const val DEFAULT_BRANCH_NAME = "Основная"
    }
}
