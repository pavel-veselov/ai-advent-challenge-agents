package com.example.llmagent.agent

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component

/** Настройки сжатия истории для одной сессии (строка в session_compression). */
data class CompressionSettings(
    val sessionId: String,
    val enabled: Boolean = false,
    /** Сколько последних сообщений всегда остаётся «как есть» в контексте. */
    val keepLast: Int = 5,
    /** Порог числа «складываемых» сообщений, при котором запускается сжатие. */
    val summaryEvery: Int = 10,
)

/**
 * Уже свёрнутое резюме части истории сессии. [uptoOrder] — id последнего свёрнутого
 * сообщения (chat_messages.id): сообщения с id <= uptoOrder считаются «покрытыми резюме».
 * Содержимое НИКОГДА не отдаётся наружу через history/sessions-эндпоинты — его читает
 * только агент при построении контекста LLM-запроса.
 */
data class SessionSummary(
    val sessionId: String,
    val summary: String,
    val uptoOrder: Long,
)

/**
 * Персистентность сжатия истории (per-session): настройки (`session_compression`)
 * и свёрнутые резюме (`session_summaries`). Схема задана в schema.sql
 * (CREATE TABLE IF NOT EXISTS, выполняется при старте); здесь — страховочное создание
 * для старых файлов БД (тот же приём, что JdbcAppSettingsStore / SessionStore.migrate).
 */
@Component
class SessionCompressionStore(private val jdbc: JdbcTemplate) {

    init {
        jdbc.execute(
            """
            CREATE TABLE IF NOT EXISTS session_compression (
                session_id    TEXT PRIMARY KEY,
                enabled       INTEGER NOT NULL DEFAULT 0,
                keep_last     INTEGER NOT NULL DEFAULT 5,
                summary_every INTEGER NOT NULL DEFAULT 10
            )
            """.trimIndent()
        )
        jdbc.execute(
            """
            CREATE TABLE IF NOT EXISTS session_summaries (
                session_id TEXT PRIMARY KEY,
                summary    TEXT NOT NULL,
                upto_order INTEGER NOT NULL
            )
            """.trimIndent()
        )
    }

    /** Настройки сессии; строка отсутствует → значения по умолчанию (сжатие выключено). */
    fun getSettings(sessionId: String): CompressionSettings =
        jdbc.query(
            "SELECT enabled, keep_last, summary_every FROM session_compression WHERE session_id = ?",
            { rs, _ ->
                CompressionSettings(
                    sessionId = sessionId,
                    enabled = rs.getInt("enabled") != 0,
                    keepLast = rs.getInt("keep_last"),
                    summaryEvery = rs.getInt("summary_every"),
                )
            },
            sessionId,
        ).firstOrNull() ?: CompressionSettings(sessionId)

    /**
     * Частичное обновление настроек (PUT /api/sessions/{sessionId}/compression).
     * Поля, отсутствующие в [patch] (или неизвестные ключи), не меняются.
     * Валидация: `enabled` — boolean, `keepLast` 1..50, `summaryEvery` 2..100;
     * невалидное значение → [CompressionSettingsValidationException] → HTTP 400.
     * Изменения персистятся сразу и переживают перезапуск backend.
     */
    fun updateSettings(sessionId: String, patch: Map<String, Any?>): CompressionSettings {
        val current = getSettings(sessionId)
        var enabled = current.enabled
        var keepLast = current.keepLast
        var summaryEvery = current.summaryEvery

        if (patch.containsKey("enabled")) {
            enabled = asBoolean(requireValue(patch, "enabled"), "enabled")
        }
        if (patch.containsKey("keepLast")) {
            val v = asInt(requireValue(patch, "keepLast"), "keepLast")
            if (v !in KEEP_LAST_RANGE) {
                throw CompressionSettingsValidationException("keepLast должен быть от ${KEEP_LAST_RANGE.first} до ${KEEP_LAST_RANGE.last}: $v")
            }
            keepLast = v
        }
        if (patch.containsKey("summaryEvery")) {
            val v = asInt(requireValue(patch, "summaryEvery"), "summaryEvery")
            if (v !in SUMMARY_EVERY_RANGE) {
                throw CompressionSettingsValidationException("summaryEvery должен быть от ${SUMMARY_EVERY_RANGE.first} до ${SUMMARY_EVERY_RANGE.last}: $v")
            }
            summaryEvery = v
        }

        jdbc.update(
            "INSERT OR REPLACE INTO session_compression (session_id, enabled, keep_last, summary_every) VALUES (?, ?, ?, ?)",
            sessionId, if (enabled) 1 else 0, keepLast, summaryEvery,
        )
        return CompressionSettings(sessionId, enabled, keepLast, summaryEvery)
    }

    /** Резюме сессии; null — свёрнутых сообщений пока нет. */
    fun getSummary(sessionId: String): SessionSummary? =
        jdbc.query(
            "SELECT session_id, summary, upto_order FROM session_summaries WHERE session_id = ?",
            { rs, _ ->
                SessionSummary(
                    sessionId = rs.getString("session_id"),
                    summary = rs.getString("summary"),
                    uptoOrder = rs.getLong("upto_order"),
                )
            },
            sessionId,
        ).firstOrNull()

    /** Сохраняет/перезаписывает резюме после успешного сжатия. */
    fun saveSummary(sessionId: String, summary: String, uptoOrder: Long) {
        jdbc.update(
            "INSERT OR REPLACE INTO session_summaries (session_id, summary, upto_order) VALUES (?, ?, ?)",
            sessionId, summary, uptoOrder,
        )
    }

    /** Удаляет настройки и резюме сессии (вызывается при DELETE /api/sessions/{sessionId}). */
    fun remove(sessionId: String) {
        jdbc.update("DELETE FROM session_compression WHERE session_id = ?", sessionId)
        jdbc.update("DELETE FROM session_summaries WHERE session_id = ?", sessionId)
    }

    /** Значение ключа; null (явно переданный) — невалиден для обязательных полей. */
    private fun requireValue(patch: Map<String, Any?>, key: String): Any =
        patch[key] ?: throw CompressionSettingsValidationException("$key: ожидалось значение, получено null")

    private fun asInt(value: Any, name: String): Int = when (value) {
        is Int -> value
        is Number -> value.toInt()
        is String -> value.toIntOrNull()
            ?: throw CompressionSettingsValidationException("$name: ожидалось целое число, получено '$value'")
        else -> throw CompressionSettingsValidationException("$name: ожидалось целое число, получено '$value'")
    }

    private fun asBoolean(value: Any, name: String): Boolean = when (value) {
        is Boolean -> value
        is String -> when (value.trim().lowercase()) {
            "true" -> true
            "false" -> false
            else -> throw CompressionSettingsValidationException("$name: ожидалось true/false, получено '$value'")
        }
        else -> throw CompressionSettingsValidationException("$name: ожидалось true/false, получено '$value'")
    }

    private companion object {
        val KEEP_LAST_RANGE = 1..50
        val SUMMARY_EVERY_RANGE = 2..100
    }
}

/** Ошибка валидации настроек сжатия (PUT .../compression) — контроллер переводит её в HTTP 400. */
class CompressionSettingsValidationException(message: String) : IllegalArgumentException(message)
