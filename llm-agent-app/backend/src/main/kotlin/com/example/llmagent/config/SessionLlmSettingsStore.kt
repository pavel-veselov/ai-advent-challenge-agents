package com.example.llmagent.config

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component

/**
 * Персистентная строка per-session настроек LLM (таблица `session_llm_settings`).
 *
 * Все значения хранятся как TEXT/NULL; типизированную интерпретацию и валидацию делают
 * [SessionLlmSettingsProvider] / [SessionScopedLlmSettings].
 *
 * Семантика полей:
 * - `model` — идентификатор модели каталога; null = не переопределено (применяется
 *   ТЕКУЩАЯ глобальная модель из app_settings/defaults);
 * - `temperature`/`topP`/`topK`/`maxTokens`/`timeoutSeconds`/`priceInputPer1M`/
 *   `priceOutputPer1M`/`reasoningEnabled` — строковые представления значений; null =
 *   не переопределено (действует ТЕКУЩЕЕ глобальное значение). Строка со значением
 *   `"null"` (маркер старых версий) трактуется интерпретатором как «не переопределено».
 *
 * Строка отсутствует → сессия ведёт себя КАК СЕГОДНЯ: в чате и в GET применяются
 * текущие ГЛОБАЛЬНЫЕ настройки (app_settings / defaults), ничего не персистится.
 */
data class StoredSessionLlmSettings(
    val sessionId: String,
    val model: String? = null,
    val temperature: String? = null,
    val topP: String? = null,
    val topK: String? = null,
    val maxTokens: String? = null,
    val timeoutSeconds: String? = null,
    val priceInputPer1M: String? = null,
    val priceOutputPer1M: String? = null,
    val reasoningEnabled: String? = null,
) {
    /** Есть ли хотя бы одно реальное переопределение (иначе строку не создаём и удаляем). */
    val hasOverrides: Boolean
        get() = model != null || temperature != null || topP != null || topK != null ||
            maxTokens != null || timeoutSeconds != null || priceInputPer1M != null ||
            priceOutputPer1M != null || reasoningEnabled != null
}

/**
 * Хранилище per-session настроек LLM (таблица `session_llm_settings`), двигающая
 * интерпретацию и валидацию в [SessionLlmSettingsProvider]. Схема задана в schema.sql
 * (CREATE TABLE IF NOT EXISTS, выполняется при старте); здесь — страховочное создание
 * и добавление недостающих колонок для старых файлов БД (тот же приём, что
 * JdbcAppSettingsStore / SessionCompressionStore).
 */
interface SessionLlmSettingsStore {
    /** Строка сессии; null — настроек нет (применяется глобальный дефолт). */
    fun get(sessionId: String): StoredSessionLlmSettings?

    /** Перезаписывает строку сессии (создаёт при отсутствии). */
    fun save(row: StoredSessionLlmSettings)

    /** Удаляет строку сессии (вызывается при DELETE /api/sessions/{sessionId}). */
    fun remove(sessionId: String)
}

/**
 * Реализация поверх SQLite через JdbcTemplate — все значения хранятся как TEXT/NULL,
 * типизированную интерпретацию делают [SessionLlmSettingsProvider]/[SessionScopedLlmSettings].
 */
@Component
class JdbcSessionLlmSettingsStore(private val jdbc: JdbcTemplate) : SessionLlmSettingsStore {

    init {
        jdbc.execute(
            """
            CREATE TABLE IF NOT EXISTS session_llm_settings (
                session_id          TEXT PRIMARY KEY,
                model               TEXT,
                temperature         TEXT,
                top_p               TEXT,
                top_k               TEXT,
                max_tokens          TEXT,
                timeout_seconds     TEXT,
                price_input_per_1m  TEXT,
                price_output_per_1m TEXT,
                reasoning_enabled   TEXT
            )
            """.trimIndent()
        )
        migrateColumns()
    }

    /** Для старых файлов БД: таблица уже существовала без новых колонок — добавляем их. */
    private fun migrateColumns() {
        val existing = jdbc
            .query("PRAGMA table_info(session_llm_settings)") { rs, _ -> rs.getString("name") }
            .toSet()
        COLUMNS.forEach { (_, column) ->
            if (column !in existing) {
                jdbc.execute("ALTER TABLE session_llm_settings ADD COLUMN $column TEXT")
            }
        }
    }

    override fun get(sessionId: String): StoredSessionLlmSettings? =
        jdbc.query(
            """
            SELECT model, temperature, top_p, top_k, max_tokens, timeout_seconds,
                   price_input_per_1m, price_output_per_1m, reasoning_enabled
            FROM session_llm_settings WHERE session_id = ?
            """.trimIndent(),
            { rs, _ ->
                StoredSessionLlmSettings(
                    sessionId = sessionId,
                    model = rs.getString("model"),
                    temperature = rs.getString("temperature"),
                    topP = rs.getString("top_p"),
                    topK = rs.getString("top_k"),
                    maxTokens = rs.getString("max_tokens"),
                    timeoutSeconds = rs.getString("timeout_seconds"),
                    priceInputPer1M = rs.getString("price_input_per_1m"),
                    priceOutputPer1M = rs.getString("price_output_per_1m"),
                    reasoningEnabled = rs.getString("reasoning_enabled"),
                )
            },
            sessionId,
        ).firstOrNull()

    override fun save(row: StoredSessionLlmSettings) {
        jdbc.update(
            """
            INSERT OR REPLACE INTO session_llm_settings
                (session_id, model, temperature, top_p, top_k, max_tokens, timeout_seconds,
                 price_input_per_1m, price_output_per_1m, reasoning_enabled)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            row.sessionId, row.model, row.temperature, row.topP, row.topK, row.maxTokens,
            row.timeoutSeconds, row.priceInputPer1M, row.priceOutputPer1M, row.reasoningEnabled,
        )
    }

    override fun remove(sessionId: String) {
        jdbc.update("DELETE FROM session_llm_settings WHERE session_id = ?", sessionId)
    }

    private companion object {
        /** Поле → колонка таблицы (порядок = объявлению в [StoredSessionLlmSettings]). */
        val COLUMNS: List<Pair<String, String>> = listOf(
            "model" to "model",
            "temperature" to "temperature",
            "topP" to "top_p",
            "topK" to "top_k",
            "maxTokens" to "max_tokens",
            "timeoutSeconds" to "timeout_seconds",
            "priceInputPer1M" to "price_input_per_1m",
            "priceOutputPer1M" to "price_output_per_1m",
            "reasoningEnabled" to "reasoning_enabled",
        )
    }
}
