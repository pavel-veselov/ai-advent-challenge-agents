package com.example.llmagent.agent

import com.example.llmagent.config.LlmProperties
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Component

data class ChatMessage(
    val role: String,
    val content: String,
    /** Токены запроса (оценка) для user, точные из usage для assistant; null — неизвестно. */
    val promptTokens: Int? = null,
    /** Токены ответа из usage (assistant); null — неизвестно. */
    val completionTokens: Int? = null,
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
) {

    init {
        migrate()
    }

    private val rowMapper = RowMapper<ChatMessage> { rs, _ ->
        ChatMessage(
            role = rs.getString("role"),
            content = rs.getString("content"),
            promptTokens = rs.getNullableInt("prompt_tokens"),
            completionTokens = rs.getNullableInt("completion_tokens"),
        )
    }

    /** Добавляет сообщение в историю сессии без токенов (совместимость). */
    fun append(sessionId: String, role: String, content: String) {
        append(sessionId, role, content, null, null)
    }

    /** Добавляет сообщение с учётом токенов (prompt_tokens / completion_tokens). */
    fun append(sessionId: String, role: String, content: String, promptTokens: Int?, completionTokens: Int?) {
        // Первая запись «создаёт» сессию — увеличиваем кумулятивный счётчик «за всё время».
        val isNewSession = (jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM chat_messages WHERE session_id = ?",
            Long::class.java,
            sessionId,
        ) ?: 0L) == 0L
        jdbcTemplate.update(
            "INSERT INTO chat_messages (session_id, role, content, prompt_tokens, completion_tokens) VALUES (?, ?, ?, ?, ?)",
            sessionId, role, content, promptTokens, completionTokens,
        )
        if (isNewSession) {
            incrementLifetimeSessions()
        }
    }

    /** Возвращает историю сессии в порядке вставки (ORDER BY id). */
    fun get(sessionId: String): List<ChatMessage> =
        jdbcTemplate.query(
            "SELECT role, content, prompt_tokens, completion_tokens FROM chat_messages WHERE session_id = ? ORDER BY id",
            rowMapper,
            sessionId,
        )

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
}
