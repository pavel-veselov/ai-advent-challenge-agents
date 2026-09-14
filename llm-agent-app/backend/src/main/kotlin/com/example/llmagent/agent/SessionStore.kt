package com.example.llmagent.agent

import com.example.llmagent.config.LlmProperties
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.support.GeneratedKeyHolder
import org.springframework.stereotype.Component

data class ChatMessage(
    /** Идентификатор строки в chat_messages (для history/веток); null — если не загружен. */
    val id: Long? = null,
    val role: String,
    val content: String,
    /** Токены запроса (оценка) для user, точные из usage для assistant; null — неизвестно. */
    val promptTokens: Int? = null,
    /** Токены ответа из usage (assistant); null — неизвестно. */
    val completionTokens: Int? = null,
)

/** Сообщение истории вместе с идентификатором (для границ сжатия в сессии). */
data class StoredMessage(
    val id: Long,
    val role: String,
    val content: String,
    /** id предыдущего сообщения той же ветки (branching); null — корень/системная заметка. */
    val parentId: Long? = null,
)

/** Агрегаты по сессии: число сообщений и суммы колонок токенов (всегда 0, если токенов нет). */
data class SessionAggregate(
    val sessionId: String,
    val messageCount: Long,
    val promptTokens: Long,
    val completionTokens: Long,
    val lastActivity: String,
    /** Содержимое ПЕРВОГО user-сообщения сессии (для заголовка вкладки); null, если user-сообщений нет. */
    val firstUserMessage: String? = null,
)

/** Кумулятивная статистика «за всё время» (переживает удаление сессий, только растёт). */
data class LifetimeStats(
    val sessionsTotal: Long,
    val promptTokensTotal: Long,
    val completionTokensTotal: Long,
    val costUsdTotal: Double,
)

/**
 * История диалогов в SQLite (таблица chat_messages) через JdbcTemplate.
 * Данные переживают перезапуск backend: файл БД по умолчанию ./data/llm-agent.db
 * (переопределяется переменной окружения SQLITE_DB_PATH). Схема создаётся
 * автоматически из schema.sql при старте приложения; колонки токенов и таблица
 * lifetime_stats добавляются миграцией при инициализации (см. migrate()), чтобы
 * поднять старые БД. lifetime_stats при первом создании бэкафиллится из уже
 * накопленной истории (см. seedLifetimeFromHistory()).
 */
@Component
class SessionStore(
    private val jdbcTemplate: JdbcTemplate,
    /** Тарифы для условной стоимости — только для бэкафилла lifetime_stats. */
    private val llm: LlmProperties = LlmProperties(),
    /**
     * Хранилище веток (branching). Дерево parent_id поддерживается ВО ВСЕХ стратегиях
     * (см. [append]), но только когда ветки подключены — юнит-тесты без него строятся
     * как раньше (веток нет, parent_id остаётся NULL).
     */
    private val branchStore: SessionBranchStore? = null,
) {

    init {
        migrate()
    }

    private val rowMapper = RowMapper<ChatMessage> { rs, _ ->
        ChatMessage(
            id = rs.getLong("id"),
            role = rs.getString("role"),
            content = rs.getString("content"),
            promptTokens = rs.getNullableInt("prompt_tokens"),
            completionTokens = rs.getNullableInt("completion_tokens"),
        )
    }

    /** Добавляет сообщение в историю сессии без токенов (совместимость). Возвращает id нового сообщения. */
    fun append(sessionId: String, role: String, content: String): Long {
        return append(sessionId, role, content, null, null)
    }

    /** Добавляет сообщение с учётом токенов (prompt_tokens / completion_tokens). Возвращает id нового сообщения. */
    fun append(sessionId: String, role: String, content: String, promptTokens: Int?, completionTokens: Int?): Long {
        // Первая запись «создаёт» сессию — увеличиваем кумулятивный счётчик «за всё время».
        val isNewSession = (jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM chat_messages WHERE session_id = ?",
            Long::class.java,
            sessionId,
        ) ?: 0L) == 0L

        // Поддержка дерева веток (branching): каждый не-системный append прикрепляет новое
        // сообщение к голове активной ветки и двигает голову к нему; системные заметки
        // (о сжатии) голову НЕ двигают и parent_id не получают. Тот же путь для ВСЕХ
        // стратегий — дерево остаётся согласованным без дополнительного обслуживания в AgentImpl.
        val newId: Long = if (role != "system" && branchStore != null) {
            if (branchStore.list(sessionId).isEmpty()) {
                // Первое не-системное сообщение после отсутствия веток: линейный бэккафилл
                // уже накопленной истории (parent_id = предыдущее не-системное) и ветка
                // «Основная», голова которой — последнее не-системное сообщение истории.
                backfillLinearParents(sessionId)
                branchStore.ensureDefault(sessionId, getStored(sessionId).lastOrNull { it.role != "system" }?.id)
            }
            val branch = branchStore.effectiveBranch(sessionId)
                ?: error("branching невозможно: нет ни одной ветки сессии $sessionId")
            val id = insert(sessionId, role, content, promptTokens, completionTokens, branch.headMessageId)
            branchStore.advanceHead(sessionId, branch.id, id)
            id
        } else {
            insert(sessionId, role, content, promptTokens, completionTokens, null)
        }

        if (isNewSession) {
            incrementLifetimeSessions()
        }
        return newId
    }

    /** Вставляет сообщение и возвращает его id (GeneratedKeyHolder — надёжно для SQLite). */
    private fun insert(
        sessionId: String,
        role: String,
        content: String,
        promptTokens: Int?,
        completionTokens: Int?,
        parentId: Long?,
    ): Long {
        val keyHolder = GeneratedKeyHolder()
        jdbcTemplate.update({ connection ->
            val ps = if (parentId != null) {
                connection.prepareStatement(
                    "INSERT INTO chat_messages (session_id, role, content, prompt_tokens, completion_tokens, parent_id) " +
                        "VALUES (?, ?, ?, ?, ?, ?)",
                    arrayOf("id"),
                )
            } else {
                connection.prepareStatement(
                    "INSERT INTO chat_messages (session_id, role, content, prompt_tokens, completion_tokens) " +
                        "VALUES (?, ?, ?, ?, ?)",
                    arrayOf("id"),
                )
            }
            ps.setString(1, sessionId)
            ps.setString(2, role)
            ps.setString(3, content)
            ps.setObject(4, promptTokens)
            ps.setObject(5, completionTokens)
            if (parentId != null) ps.setLong(6, parentId)
            ps
        }, keyHolder)
        return keyHolder.key?.toLong()
            ?: throw IllegalStateException("Не удалось получить id нового сообщения сессии $sessionId")
    }

    /** Возвращает историю сессии в порядке вставки (ORDER BY id). */
    fun get(sessionId: String): List<ChatMessage> =
        jdbcTemplate.query(
            "SELECT id, role, content, prompt_tokens, completion_tokens FROM chat_messages WHERE session_id = ? ORDER BY id",
            rowMapper,
            sessionId,
        )

    /** Возвращает историю сессии с id в порядке вставки (ORDER BY id) — для сжатия и веток. */
    fun getStored(sessionId: String): List<StoredMessage> =
        jdbcTemplate.query(
            "SELECT id, role, content, parent_id FROM chat_messages WHERE session_id = ? ORDER BY id",
            { rs, _ ->
                StoredMessage(
                    id = rs.getLong("id"),
                    role = rs.getString("role"),
                    content = rs.getString("content"),
                    parentId = rs.getNullableLong("parent_id"),
                )
            },
            sessionId,
        )

    /** Сообщение сессии по id (для валидации messageId в POST /api/sessions/{id}/branches); null — нет. */
    fun getStoredById(sessionId: String, id: Long): StoredMessage? =
        jdbcTemplate.query(
            "SELECT id, role, content, parent_id FROM chat_messages WHERE session_id = ? AND id = ?",
            { rs, _ ->
                StoredMessage(
                    id = rs.getLong("id"),
                    role = rs.getString("role"),
                    content = rs.getString("content"),
                    parentId = rs.getNullableLong("parent_id"),
                )
            },
            sessionId, id,
        ).firstOrNull()

    /**
     * Цепочка сообщений ветки: поднимаемся по parent_id от головы до корня (parent_id IS NULL)
     * и разворачиваем в хронологическом порядке (корень → голова). ВОЗВРАЩАЕТ ВСЕ роли
     * (включая system) — фильтрацию «не-системных» делает вызывающий код (AgentImpl /
     * HistoryController), как и для истории. Если цепочка разорвана (родитель удалён) —
     * обрываемся на первом найденном предке.
     */
    fun getBranchChain(sessionId: String, headMessageId: Long): List<StoredMessage> {
        val byId = jdbcTemplate.query(
            "SELECT id, parent_id, role, content FROM chat_messages WHERE session_id = ?",
            { rs, _ ->
                StoredMessage(
                    id = rs.getLong("id"),
                    role = rs.getString("role"),
                    content = rs.getString("content"),
                    parentId = rs.getNullableLong("parent_id"),
                )
            },
            sessionId,
        ).associateBy { it.id }
        val chain = mutableListOf<StoredMessage>()
        var current = byId[headMessageId]
        while (current != null) {
            chain += current
            current = current.parentId?.let { byId[it] }
        }
        return chain.asReversed()
    }

    /**
     * Линейный бэккафилл parent_id для истории, накопленной ДО подключения веток:
     * каждое не-системное сообщение с parent_id IS NULL связывается с предыдущим
     * не-системным (первые два «уже связанных» сообщения пропускаются — курсор двигается),
     * корневое остаётся NULL; системные заметки в связывание не входят и parent_id
     * не получают. Идемпотентно: повторный запуск ничего не меняет. Вызывается при
     * активации стратегии 'branching' и при первом не-системном append без веток.
     */
    fun backfillLinearParents(sessionId: String) {
        val rows = jdbcTemplate.query(
            "SELECT id, role, parent_id FROM chat_messages WHERE session_id = ? ORDER BY id",
            { rs, _ ->
                Triple(rs.getLong("id"), rs.getString("role"), rs.getLong("parent_id").takeIf { !rs.wasNull() })
            },
            sessionId,
        )
        var prevLinked: Long? = null
        for ((id, role, parent) in rows) {
            if (role == "system") {
                continue
            }
            if (parent == null) {
                if (prevLinked != null) {
                    jdbcTemplate.update(
                        "UPDATE chat_messages SET parent_id = ? WHERE id = ? AND session_id = ?",
                        prevLinked, id, sessionId,
                    )
                } else {
                    // Корневое сообщение: страховочно гарантируем NULL (могло остаться от старой схемы)
                    jdbcTemplate.update(
                        "UPDATE chat_messages SET parent_id = NULL WHERE id = ? AND session_id = ?",
                        id, sessionId,
                    )
                }
            }
            prevLinked = id
        }
    }

    /** true, если у сессии есть хотя бы одно сохранённое сообщение. */
    fun exists(sessionId: String): Boolean =
        (jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM chat_messages WHERE session_id = ?",
            Long::class.java,
            sessionId,
        ) ?: 0L) > 0L

    /**
     * Агрегаты по токенам для каждой сессии: число сообщений, суммы колонок токенов и
     * время последней активности. Суммы пустых/нулевых токенов схлопываются в 0;
     * lastActivity — строка created_at (UTC 'YYYY-MM-DD HH:MM:SS') последнего сообщения;
     * firstUserMessage — содержание первого user-сообщения (сабквери), null, если таких нет.
     */
    fun listSessionAggregates(): List<SessionAggregate> =
        jdbcTemplate.query(
            """
            SELECT m.session_id, COUNT(*) AS cnt, COALESCE(SUM(m.prompt_tokens),0) AS p,
                   COALESCE(SUM(m.completion_tokens),0) AS c, MAX(m.created_at) AS last_at,
                   (SELECT m2.content FROM chat_messages m2
                    WHERE m2.session_id = m.session_id AND m2.role = 'user'
                    ORDER BY m2.id LIMIT 1) AS first_user_message
            FROM chat_messages m
            GROUP BY m.session_id
            ORDER BY MAX(m.id) DESC
            """.trimIndent()
        ) { rs, _ ->
            SessionAggregate(
                sessionId = rs.getString("session_id"),
                messageCount = rs.getLong("cnt"),
                promptTokens = rs.getLong("p"),
                completionTokens = rs.getLong("c"),
                lastActivity = rs.getString("last_at"),
                firstUserMessage = rs.getString("first_user_message"),
            )
        }

    /**
     * Кумулятивная статистика «за всё время»: число созданных сессий и суммы токенов/стоимости.
     * Счётчики только растут — удаление сессий их не уменьшает.
     */
    fun getLifetimeStats(): LifetimeStats =
        jdbcTemplate.query(
            "SELECT sessions_total, prompt_tokens_total, completion_tokens_total, cost_usd_total FROM lifetime_stats WHERE id = 1"
        ) { rs, _ ->
            LifetimeStats(
                sessionsTotal = rs.getLong("sessions_total"),
                promptTokensTotal = rs.getLong("prompt_tokens_total"),
                completionTokensTotal = rs.getLong("completion_tokens_total"),
                costUsdTotal = rs.getDouble("cost_usd_total"),
            )
        }.firstOrNull() ?: LifetimeStats(0, 0, 0, 0.0)

    /** Увеличивает кумулятивные счётчики токенов/стоимости при финализации assistant-сообщения. */
    fun addLifetimeTokens(promptTokens: Int, completionTokens: Int, costUsd: Double) {
        jdbcTemplate.update(
            "UPDATE lifetime_stats SET prompt_tokens_total = prompt_tokens_total + ?, " +
                "completion_tokens_total = completion_tokens_total + ?, " +
                "cost_usd_total = cost_usd_total + ? WHERE id = 1",
            promptTokens, completionTokens, costUsd,
        )
    }

    private fun incrementLifetimeSessions() {
        jdbcTemplate.update("UPDATE lifetime_stats SET sessions_total = sessions_total + 1 WHERE id = 1")
    }

    /** Удаляет всю историю сессии. */
    fun delete(sessionId: String) {
        jdbcTemplate.update("DELETE FROM chat_messages WHERE session_id = ?", sessionId)
    }

    /**
     * Необязательная миграция: schema.sql с CREATE TABLE IF NOT EXISTS не добавит
     * новые колонки в уже существующий файл БД — досоздаём их здесь через ALTER TABLE.
     * Идемпотентно: колонка добавляется только если её ещё нет.
     */
    private fun migrate() {
        if (!hasColumn("prompt_tokens")) {
            jdbcTemplate.execute("ALTER TABLE chat_messages ADD COLUMN prompt_tokens INTEGER")
        }
        if (!hasColumn("completion_tokens")) {
            jdbcTemplate.execute("ALTER TABLE chat_messages ADD COLUMN completion_tokens INTEGER")
        }
        // parent_id (ветки) — досоздаём для старых файлов БД без потери данных.
        if (!hasColumn("parent_id")) {
            jdbcTemplate.execute("ALTER TABLE chat_messages ADD COLUMN parent_id INTEGER")
        }
        // Таблица кумулятивной статистики — досоздаём и для старых файлов БД
        // (schema.sql с CREATE TABLE IF NOT EXISTS добавит её только при чистом старте).
        jdbcTemplate.execute(
            """
            CREATE TABLE IF NOT EXISTS lifetime_stats (
                id INTEGER PRIMARY KEY CHECK (id = 1),
                sessions_total INTEGER NOT NULL DEFAULT 0,
                prompt_tokens_total INTEGER NOT NULL DEFAULT 0,
                completion_tokens_total INTEGER NOT NULL DEFAULT 0,
                cost_usd_total REAL NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
        // Гарантируем наличие одиночной строки-счётчика (id = 1) и бэкафиллим её из
        // уже существующей истории — только пока строка ещё не накопила данные
        // (см. seedLifetimeFromHistory). Повторные рестарты не дублируют счётчики.
        seedLifetimeFromHistory()
    }

    /**
     * Бэкафилл lifetime_stats из существующей истории chat_messages. Сначала гарантируем
     * наличие одиночной строки-счётчика (id = 1), затем дозаполняем её из истории ТОЛЬКО
     * пока счётчики ещё в нулях: первый старт (строка только что создана) или вырожденное
     * состояние после промежуточного релиза. Строка с уже накопленными счётчиками не
     * перезаписывается — повторные старты/рестарты не приводят к двойному учёту.
     * Тарифы стоимости — те же, что у текущего расчёта costUsd в контроллерах.
     */
    private fun seedLifetimeFromHistory() {
        jdbcTemplate.update("INSERT OR IGNORE INTO lifetime_stats (id) VALUES (1)")
        val empty = getLifetimeStats().let {
            it.sessionsTotal == 0L && it.promptTokensTotal == 0L &&
                it.completionTokensTotal == 0L && it.costUsdTotal == 0.0
        }
        if (!empty) return
        jdbcTemplate.update(
            """
            UPDATE lifetime_stats SET
                sessions_total = (SELECT COUNT(DISTINCT session_id) FROM chat_messages),
                prompt_tokens_total = (SELECT COALESCE(SUM(prompt_tokens), 0) FROM chat_messages),
                completion_tokens_total = (SELECT COALESCE(SUM(completion_tokens), 0) FROM chat_messages),
                cost_usd_total = (SELECT (COALESCE(SUM(prompt_tokens), 0) * ? + COALESCE(SUM(completion_tokens), 0) * ?) / 1000000.0 FROM chat_messages)
            WHERE id = 1
            """.trimIndent(),
            llm.priceInputPer1M,
            llm.priceOutputPer1M,
        )
    }

    private fun hasColumn(name: String): Boolean =
        jdbcTemplate.query("PRAGMA table_info(chat_messages)") { rs, _ -> rs.getString("name") }
            .any { it == name }

    /** Читает INTEGER-колонку, допуская NULL (старые строки без токенов). */
    private fun java.sql.ResultSet.getNullableInt(column: String): Int? {
        val v = getObject(column)
        return when (v) {
            null -> null
            is Number -> v.toInt()
            else -> null
        }
    }

    /** Читает INTEGER-колонку, допуская NULL (parent_id). */
    private fun java.sql.ResultSet.getNullableLong(column: String): Long? {
        val v = getObject(column)
        return when (v) {
            null -> null
            is Number -> v.toLong()
            else -> null
        }
    }
}
