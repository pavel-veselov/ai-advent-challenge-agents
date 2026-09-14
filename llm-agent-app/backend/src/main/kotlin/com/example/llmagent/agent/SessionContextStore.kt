package com.example.llmagent.agent

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component

/** Настройки стратегии контекста сессии (строка в session_context_strategy). */
data class ContextStrategySettings(
    val sessionId: String,
    /**
     * Сохранённая стратегия: none | sliding_window | sticky_facts | summary | branching.
     * РЕАЛЬНО применённую для run/запроса определяет [SessionContextStore.resolve]
     * (fallback 'none' + включённое legacy-сжатие → 'summary').
     */
    val strategy: String = SessionContextStore.STRATEGY_NONE,
    /** Размер окна последних сообщений в контексте (sliding_window / sticky_facts). */
    val windowSize: Int = SessionContextStore.DEFAULT_WINDOW_SIZE,
    /** Ид активной ветки (branching, см. SessionBranchStore); null — активная не выбрана. */
    val activeBranchId: Long? = null,
)

/**
 * Персистентность стратегии контекста (per-session): строка в `session_context_strategy`.
 * Схема — в schema.sql (CREATE TABLE IF NOT EXISTS); здесь — страховочное создание для
 * старых файлов БД (тот же приём, что JdbcAppSettingsStore / SessionCompressionStore).
 *
 * Таблица разделяется с SessionBranchStore: strategy/window_size обновляются этой
 * операцией через INSERT ... ON CONFLICT DO UPDATE (active_branch_id не трогается),
 * колонку active_branch_id пишет SessionBranchStore.setActive (strategy/window_size
 * при этом сохраняются). Поэтому INSERT OR REPLACE здесь не используется — он затирал
 * бы активную ветку.
 */
@Component
class SessionContextStore(private val jdbc: JdbcTemplate) {

    init {
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

    /** Настройки сессии; строка отсутствует → значения по умолчанию (none, windowSize=12). */
    fun get(sessionId: String): ContextStrategySettings =
        jdbc.query(
            "SELECT strategy, window_size, active_branch_id FROM session_context_strategy WHERE session_id = ?",
            { rs, _ ->
                ContextStrategySettings(
                    sessionId = sessionId,
                    strategy = rs.getString("strategy"),
                    windowSize = rs.getInt("window_size"),
                    activeBranchId = rs.getLong("active_branch_id").takeIf { !rs.wasNull() },
                )
            },
            sessionId,
        ).firstOrNull() ?: ContextStrategySettings(sessionId)

    /**
     * Частичное обновление (PUT /api/sessions/{sessionId}/context-strategy).
     * Поля, отсутствующие в [patch] (или неизвестные ключи), не меняются.
     * Валидация: `strategy` — одна из известных, `windowSize` 1..50 → невалидное значение
     * [ContextStrategyValidationException] → HTTP 400. Активная ветка (active_branch_id)
     * этой операцией НЕ трогается: INSERT ... ON CONFLICT DO UPDATE меняет только
     * strategy/window_size (иначе PUT стратегии сбрасывал бы активную ветку).
     */
    fun update(sessionId: String, patch: Map<String, Any?>): ContextStrategySettings {
        val current = get(sessionId)
        var strategy = current.strategy
        var windowSize = current.windowSize

        if (patch.containsKey("strategy")) {
            val s = asString(requireValue(patch, "strategy"), "strategy")
            if (s !in STRATEGIES) {
                throw ContextStrategyValidationException(
                    "Неизвестная стратегия контекста: '$s'. Доступные: ${STRATEGIES.sorted().joinToString(", ")}"
                )
            }
            strategy = s
        }
        if (patch.containsKey("windowSize")) {
            val v = asInt(requireValue(patch, "windowSize"), "windowSize")
            if (v !in WINDOW_SIZE_RANGE) {
                throw ContextStrategyValidationException(
                    "windowSize должен быть от ${WINDOW_SIZE_RANGE.first} до ${WINDOW_SIZE_RANGE.last} (получено: $v)"
                )
            }
            windowSize = v
        }

        jdbc.update(
            "INSERT INTO session_context_strategy (session_id, strategy, window_size) VALUES (?, ?, ?) " +
                "ON CONFLICT(session_id) DO UPDATE SET strategy = excluded.strategy, window_size = excluded.window_size",
            sessionId, strategy, windowSize,
        )
        return get(sessionId)
    }

    /**
     * Записывает активную ветку (branching, вызывается SessionBranchStore.setActive).
     * Только колонка active_branch_id (strategy/window_size сохраняются — ON CONFLICT
     * DO UPDATE), поэтому переключение ветки не меняет стратегию. branchId == null —
     * активная ветка сброшена (действует ветка по умолчанию «Основная»).
     */
    fun setActiveBranchId(sessionId: String, branchId: Long?) {
        jdbc.update(
            "INSERT INTO session_context_strategy (session_id, active_branch_id) VALUES (?, ?) " +
                "ON CONFLICT(session_id) DO UPDATE SET active_branch_id = excluded.active_branch_id",
            sessionId, branchId,
        )
    }

    /** Удаляет строку стратегии сессии (вызывается при DELETE /api/sessions/{sessionId}). */
    fun remove(sessionId: String) {
        jdbc.update("DELETE FROM session_context_strategy WHERE session_id = ?", sessionId)
    }

    /**
     * Разрешение ЭФФЕКТИВНОЙ стратегии для run/запроса: сохранённое значение, а при
     * 'none' — fallback на 'summary', когда включено legacy-сжатие (compression.enabled).
     * Это правило сохраняет старое поведение сжатия для сессий, никогда не выбиравших
     * стратегию: AgentCompressionTest работает неизменным именно через этот путь.
     */
    fun resolve(sessionId: String, compressionEnabled: Boolean): String {
        val strategy = get(sessionId).strategy
        return if (strategy == STRATEGY_NONE && compressionEnabled) STRATEGY_SUMMARY else strategy
    }

    /** Значение ключа; null (явно переданный) — невалиден для обязательных полей. */
    private fun requireValue(patch: Map<String, Any?>, key: String): Any =
        patch[key] ?: throw ContextStrategyValidationException("$key: ожидалось значение, получено null")

    private fun asString(value: Any, name: String): String = when (value) {
        is String -> value.trim()
        else -> throw ContextStrategyValidationException("$name: ожидалась строка, получено '$value'")
    }

    private fun asInt(value: Any, name: String): Int = when (value) {
        is Int -> value
        is Number -> value.toInt()
        is String -> value.toIntOrNull()
            ?: throw ContextStrategyValidationException("$name: ожидалось целое число, получено '$value'")
        else -> throw ContextStrategyValidationException("$name: ожидалось целое число, получено '$value'")
    }

    companion object {
        const val STRATEGY_NONE = "none"
        const val STRATEGY_SLIDING_WINDOW = "sliding_window"
        const val STRATEGY_STICKY_FACTS = "sticky_facts"
        const val STRATEGY_SUMMARY = "summary"
        const val STRATEGY_BRANCHING = "branching"

        const val DEFAULT_WINDOW_SIZE = 12
        val WINDOW_SIZE_RANGE = 1..50
        val STRATEGIES = setOf(
            STRATEGY_NONE, STRATEGY_SLIDING_WINDOW, STRATEGY_STICKY_FACTS, STRATEGY_SUMMARY, STRATEGY_BRANCHING,
        )
    }
}

/** Ошибка валидации стратегии контекста (PUT .../context-strategy) — контроллер переводит её в HTTP 400. */
class ContextStrategyValidationException(message: String) : IllegalArgumentException(message)