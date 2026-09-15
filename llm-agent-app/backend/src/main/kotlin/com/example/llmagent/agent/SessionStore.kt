package com.example.llmagent.agent

import com.example.llmagent.config.LlmProperties
import org.slf4j.LoggerFactory
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
    /** id проекта, которому принадлежит сессия; -1 — сессия без строки в chat_sessions (легаси/осиротевшая). */
    val projectId: Long = -1,
)

/**
 * Сводка сессии ПРОЕКТА (см. [SessionStore.listByProject]): агрегаты по сообщениям/токенам
 * сессии + title из chat_sessions + projectId. costUsd — условная стоимость по ТЕКУЩИМ
 * тарифам настроек ((prompt*priceInput + completion*priceOutput)/1M), как у
 * GET /api/sessions. [lastActivity] — created_at последнего сообщения (для пустой сессии —
 * created_at самой сессии из chat_sessions).
 */
data class ProjectSession(
    val sessionId: String,
    val title: String?,
    val messageCount: Long = 0,
    val promptTokens: Long = 0,
    val completionTokens: Long = 0,
    val costUsd: Double = 0.0,
    val lastActivity: String,
    val firstUserMessage: String? = null,
    val projectId: Long,
)

/** Кумулятивная статистика «за всё время» (переживает удаление сессий, только растёт). */
data class LifetimeStats(
    val sessionsTotal: Long,
    val promptTokensTotal: Long,
    val completionTokensTotal: Long,
    val costUsdTotal: Double,
)

/**
 * История диалогов в SQLite (таблица chat_messages) через JdbcTemplate, а с появлением
 * проектов — и реестр сессий (таблица chat_sessions: server-side session_id, project_id,
 * title). Сессии СОЗДАЮТСЯ ЯВНО через [createSession] (POST /api/projects/{id}/sessions);
 * [append] по-прежнему молча пишет сообщения для любого session_id (в т.ч. сессий,
 * которых нет в chat_sessions — легаси/тестовые орфаны, см. [listSessionAggregates]).
 * Данные переживают перезапуск backend: файл БД по умолчанию ./data/llm-agent.db
 * (переопределяется переменной окружения SQLITE_DB_PATH). Схема создаётся
 * автоматически из schema.sql при старте приложения; колонки токенов и таблица
 * lifetime_stats добавляются миграцией при инициализации (см. migrate()), чтобы
 * поднять старые БД. lifetime_stats при первом создании бэкафиллится из уже
 * накопленной истории (см. seedLifetimeFromHistory). Легаси-сессии (chat_messages без
 * строки в chat_sessions) удаляются при инициализации (см. purgeLegacySessions).
 */
@Component
class SessionStore(
    private val jdbcTemplate: JdbcTemplate,
    /** Тарифы для условной стоимости — только для бэкафилла lifetime_stats и listByProject (costUsd). */
    private val llm: LlmProperties = LlmProperties(),
    /**
     * Хранилище веток (branching). Дерево parent_id поддерживается ВО ВСЕХ стратегиях
     * (см. [append]), но только когда ветки подключены — юнит-тесты без него строятся
     * как раньше (веток нет, parent_id остаётся NULL).
     */
    private val branchStore: SessionBranchStore? = null,
) {

    private val log = LoggerFactory.getLogger(SessionStore::class.java)

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
     * Создаёт сессию В ПРОЕКТЕ (POST /api/projects/{id}/sessions): server-side id
     * (UUID), title (null — без заголовка), project_id = FK. Должен вызываться после
     * того, как проект создан (ProjectStore.create). Fail-open: сбой записи — warn и
     * "unknown" (вызывающий код обычно проверяет проект до создания сессии).
     */
    fun createSession(projectId: Long, title: String?): String {
        val sessionId = java.util.UUID.randomUUID().toString()
        try {
            jdbcTemplate.update(
                "INSERT INTO chat_sessions (session_id, project_id, title) VALUES (?, ?, ?)",
                sessionId, projectId, title,
            )
            return sessionId
        } catch (e: Exception) {
            log.warn("SessionStore.createSession(project={}) не удался: {}", projectId, e.message)
            // Fail-open: вернуть "unknown" — запись в чат с таким id потом молча не найдёт проект.
            return SESSION_ID_FALLBACK
        }
    }

    /** id проекта, которому принадлежит сессия; null — строки в chat_sessions нет (или сбой БД). */
    fun getProjectId(sessionId: String): Long? {
        return try {
            jdbcTemplate.query(
                "SELECT project_id FROM chat_sessions WHERE session_id = ?",
                { rs, _ -> rs.getLong("project_id") },
                sessionId,
            ).firstOrNull()
        } catch (e: Exception) {
            log.warn("SessionStore.getProjectId({}) не удался: {}", sessionId, e.message)
            null
        }
    }

    /** Заголовок сессии из chat_sessions; null — строки нет или заголовок не задан. */
    fun titleOf(sessionId: String): String? {
        return try {
            jdbcTemplate.query(
                "SELECT title FROM chat_sessions WHERE session_id = ?",
                { rs, _ -> rs.getString("title") },
                sessionId,
            ).firstOrNull()
        } catch (e: Exception) {
            log.warn("SessionStore.titleOf({}) не удался: {}", sessionId, e.message)
            null
        }
    }

    /**
     * Сессии ПРОЕКТА со сводкой по сообщениям (chat_sessions LEFT JOIN chat_messages):
     * ПУСТЫЕ сессии тоже входят (messageCount=0, lastActivity = created_at сессии).
     * Сортировка — по последней активности DESC (самая свежая первой); для одинакового
     * времени — по session_id ASC (детерминизм). costUsd считается по ТЕКУЩИМ тарифам.
     */
    fun listByProject(projectId: Long): List<ProjectSession> {
        val rows = jdbcTemplate.query(
            """
            SELECT s.session_id, s.title, s.created_at AS s_created,
                   COUNT(m.id) AS cnt,
                   COALESCE(SUM(m.prompt_tokens), 0) AS p,
                   COALESCE(SUM(m.completion_tokens), 0) AS c,
                   COALESCE(MAX(m.created_at), s.created_at) AS last_at,
                   (SELECT m2.content FROM chat_messages m2
                    WHERE m2.session_id = s.session_id AND m2.role = 'user'
                    ORDER BY m2.id LIMIT 1) AS first_user_message
            FROM chat_sessions s
            LEFT JOIN chat_messages m ON m.session_id = s.session_id
            WHERE s.project_id = ?
            GROUP BY s.session_id, s.title, s.created_at
            ORDER BY last_at DESC, s.session_id ASC
            """.trimIndent(),
            { rs, _ ->
                ProjectSession(
                    sessionId = rs.getString("session_id"),
                    title = rs.getString("title"),
                    messageCount = rs.getLong("cnt"),
                    promptTokens = rs.getLong("p"),
                    completionTokens = rs.getLong("c"),
                    costUsd = costUsd(rs.getLong("p"), rs.getLong("c")),
                    lastActivity = rs.getString("last_at"),
                    firstUserMessage = rs.getString("first_user_message"),
                    projectId = projectId,
                )
            },
            projectId,
        )
        return rows
    }

    /** (prompt*priceInput + completion*priceOutput) / 1M; с нулевыми токенами — 0.0. */
    private fun costUsd(promptTokens: Long, completionTokens: Long): Double {
        val p = promptTokens * llm.priceInputPer1M
        val c = completionTokens * llm.priceOutputPer1M
        return (p + c) / 1_000_000.0
    }

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
     * время последней активности. Источник — обе сущности: сессии из `chat_sessions`
     * (LEFT JOIN chat_messages — ПУСТЫЕ сессии тоже входят) ПЛЮС «осиротевшие» сессии,
     * у которых сообщения есть, а строки в chat_sessions нет (легаси/тестовые орфаны).
     * Суммы пустых/нулевых токенов схлопываются в 0; lastActivity — строка created_at
     * последнего сообщения (для пустой сессии — created_at самой сессии из chat_sessions);
     * firstUserMessage — содержание первого user-сообщения (сабквери), null, если таких нет.
     * Сортировка — по последней активности DESC (самая свежая первой), для одинакового
     * времени — по session_id ASC (детерминизм).
     */
    fun listSessionAggregates(): List<SessionAggregate> {
        // Как только появляется реестр сессий (projects/chat_sessions) — агрегируем ОБЕ
        // сущности: сессии из chat_sessions (LEFT JOIN — ПУСТЫЕ тоже) + «осиротевшие»
        // сессии из chat_messages (легаси/тесты). Для старых БД/юнит-схемы без
        // chat_sessions — прежний путь (только chat_messages).
        val sql = if (tableExists("chat_sessions")) {
            """
            SELECT s.session_id, s.project_id,
                   COUNT(m.id) AS cnt,
                   COALESCE(SUM(m.prompt_tokens),0) AS p,
                   COALESCE(SUM(m.completion_tokens),0) AS c,
                   COALESCE(MAX(m.created_at), s.created_at) AS last_at,
                   (SELECT m2.content FROM chat_messages m2
                    WHERE m2.session_id = s.session_id AND m2.role = 'user'
                    ORDER BY m2.id LIMIT 1) AS first_user_message
            FROM chat_sessions s
            LEFT JOIN chat_messages m ON m.session_id = s.session_id
            GROUP BY s.session_id, s.project_id

            UNION ALL

            -- «Осиротевшие» сессии: сообщения есть, строки в chat_sessions нет (легаси/тесты).
            SELECT m3.session_id, -1 AS project_id,
                   COUNT(*) AS cnt,
                   COALESCE(SUM(m3.prompt_tokens),0) AS p,
                   COALESCE(SUM(m3.completion_tokens),0) AS c,
                   MAX(m3.created_at) AS last_at,
                   (SELECT m5.content FROM chat_messages m5
                    WHERE m5.session_id = m3.session_id AND m5.role = 'user'
                    ORDER BY m5.id LIMIT 1) AS first_user_message
            FROM chat_messages m3
            WHERE m3.session_id NOT IN (SELECT session_id FROM chat_sessions)
            GROUP BY m3.session_id

            ORDER BY last_at DESC, session_id ASC
            """.trimIndent()
        } else {
            """
            SELECT session_id, -1 AS project_id, COUNT(*) AS cnt,
                   COALESCE(SUM(prompt_tokens),0) AS p,
                   COALESCE(SUM(completion_tokens),0) AS c,
                   MAX(created_at) AS last_at,
                   (SELECT m2.content FROM chat_messages m2
                    WHERE m2.session_id = m.session_id AND m2.role = 'user'
                    ORDER BY m2.id LIMIT 1) AS first_user_message
            FROM chat_messages m
            GROUP BY session_id
            ORDER BY last_at DESC, session_id ASC
            """.trimIndent()
        }
        return jdbcTemplate.query(sql) { rs, _ ->
            SessionAggregate(
                sessionId = rs.getString("session_id"),
                messageCount = rs.getLong("cnt"),
                promptTokens = rs.getLong("p"),
                completionTokens = rs.getLong("c"),
                lastActivity = rs.getString("last_at"),
                firstUserMessage = rs.getString("first_user_message"),
                projectId = rs.getLong("project_id"),
            )
        }
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

    /**
     * Удаляет всю историю сессии и (при наличии таблицы chat_sessions) саму сессию из
     * реестра. DELETE /api/sessions/{sessionId}: строка chat_sessions тоже удаляется —
     * пересоздание сессии возможно только явным createSession. Осиротевшие сообщения
     * вместе с сессией не остаются.
     */
    fun delete(sessionId: String) {
        jdbcTemplate.update("DELETE FROM chat_messages WHERE session_id = ?", sessionId)
        if (tableExists("chat_sessions")) {
            jdbcTemplate.update("DELETE FROM chat_sessions WHERE session_id = ?", sessionId)
        }
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
        // Легаси-сессии до появления проектов: сообщения без строки в chat_sessions.
        // По решению пользователя старые сессии УДАЛЯЮТСЯ — чистим их следы до
        // бэкафилла lifetime_stats (см. purgeLegacySessions).
        purgeLegacySessions()
        // Гарантируем наличие одиночной строки-счётчика (id = 1) и бэкафиллим её из
        // уже существующей истории — только пока строка ещё не накопила данные
        // (см. seedLifetimeFromHistory). Повторные рестарты не дублируют счётчики.
        seedLifetimeFromHistory()
    }

    /**
     * Удаляет «осиротевшие» сообщения chat_messages: строки, чей session_id НЕ ссылается
     * на chat_sessions (легаси-сессии, созданные неявным append'ом до появления проектов).
     * По решению пользователя (день 12) такие сессии УДАЛЯЮТСЯ — приложение стартует
     * «чисто», с проектами, без «Без проекта». Идемпотентно: повторный рестарт ничего
     * не удаляет (осиротевших строк уже нет). Пропускается, если таблицы chat_sessions
     * нет (старые БД/юнит-тесты без неё) или при сбое — fail-open.
     */
    private fun purgeLegacySessions() {
        if (!tableExists("chat_sessions")) return
        try {
            jdbcTemplate.update(
                "DELETE FROM chat_messages WHERE session_id NOT IN (SELECT session_id FROM chat_sessions)"
            )
        } catch (e: Exception) {
            log.warn("SessionStore: вычистить легаси-сессии не удалось: {}", e.message)
        }
    }

    private fun tableExists(table: String): Boolean =
        jdbcTemplate.query(
            "SELECT name FROM sqlite_master WHERE type = 'table' AND name = ?",
            { rs, _ -> rs.getString(1) },
            table,
        ).isNotEmpty()

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

    companion object {
        /** Fail-open fallback для createSession при сбое записи (см. KDoc метода). */
        const val SESSION_ID_FALLBACK = "unknown"
    }
}
