package com.example.llmagent.config

import org.springframework.stereotype.Component

/**
 * Per-session настройки LLM (GET/PUT /api/sessions/{sessionId}/llm-settings).
 *
 * Полное состояние сессии — эффективный набор из того же списка полей, что у глобального
 * /api/llm-settings:
 * `{ model, contextLimit, temperature, topP, topK, maxTokens, timeoutSeconds,
 *   priceInputPer1M, priceOutputPer1M, reasoningEnabled, toolsEnabled }` (БЕЗ `provider` — тот определён
 * на глобальном уровне и меняется только через PUT /api/llm-settings) :
 * - per-field правило: сохранённое значение сессии, если есть, ИНАЧЕ текущее ГЛОБАЛЬНОЕ
 *   значение ([LlmSettings]/[DynamicLlmSettings] из app_settings/defaults);
 * - `contextLimit` — выводится из эффективно выбранной модели ([LlmCatalog] для gpustack,
 *   живое обнаружение [OllamaDiscovery] для ollama; отдельно НЕ хранится, как в глобальном PUT);
 * - `model` — модель каталога ТЕКУЩЕГО провайдера; переопределение сессии, не входящее
 *   в каталог текущего провайдера (например после переключения провайдера), игнорируется —
 *   применяется текущая глобальная модель.
 *
 * Строка в `session_llm_settings` отсутствует → сессия ведёт себя КАК СЕГОДНЯ: применяются
 * текущие ГЛОБАЛЬНЫЕ настройки без их сохранения (fallback без персистентности).
 * Изменение настроек сессии A не влияет на сессию B.
 *
 * PUT — частичное обновление: отсутствующие ключи не меняются; null поля = УДАЛИТЬ
 * переопределение сессии (значение снова берётся из ТЕКУЩИХ глобальных). Валидация — та же,
 * что в глобальном PUT: модель обязана быть в каталоге И включённой (сообщения те же),
 * числовые поля — с теми же проверками и формулировками; maxTokens дополнительно проверяется
 * на контекстное окно эффективно выбранной модели. Ошибки — [LlmSettingsValidationException] → 400.
 */
@Component
class SessionLlmSettingsProvider(
    private val store: SessionLlmSettingsStore,
    /** Глобальные применённые настройки (defaults + app_settings) — источник fallback. */
    private val global: LlmSettings,
    /** Состояние «включена/отключена» моделей каталога; null — проверки нет (все включены). */
    private val modelEnabled: ModelEnabledStore? = null,
    /** Живой каталог моделей Ollama; null — проверки ollama-моделей нет. */
    private val ollama: OllamaDiscovery? = null,
) {

    /**
     * Полное эффективное состояние сессии для GET: глобальные значения для не переопределённых
     * полей (не персистит ничего — только читает и рендерит).
     */
    fun get(sessionId: String): Map<String, Any?> = render(resolve(sessionId))

    /**
     * Частичное обновление (PUT). Отсутствующие ключи не меняются; null поля = снять
     * переопределение сессии (эффективно применяется ТЕКУЩЕЕ глобальное значение).
     * Если после применения патча не осталось ни одного переопределения — строка сессии
     * удаляется. Валидирует и персистит, возвращает полное эффективное состояние.
     */
    fun update(sessionId: String, patch: Map<String, Any?>): Map<String, Any?> {
        val current = store.get(sessionId) ?: StoredSessionLlmSettings(sessionId)
        var model = current.model
        var temperature = current.temperature
        var topP = current.topP
        var topK = current.topK
        var maxTokens = current.maxTokens
        var timeoutSeconds = current.timeoutSeconds
        var priceInputPer1M = current.priceInputPer1M
        var priceOutputPer1M = current.priceOutputPer1M
        var reasoningEnabled = current.reasoningEnabled
        var toolsEnabled = current.toolsEnabled

        if (patch.containsKey(KEY_MODEL)) {
            val v = patch[KEY_MODEL]
            if (v != null) {
                if (v !is String) throw LlmSettingsValidationException("model: ожидалась строка, получено '$v'")
                val id = v.trim()
                if (id.isNotEmpty()) {
                    validateModelForProvider(id)
                    model = id
                }
            } else {
                // null — снять переопределение модели (применяется текущая глобальная)
                model = null
            }
        }
        if (patch.containsKey(KEY_CONTEXT_LIMIT)) {
            patch[KEY_CONTEXT_LIMIT]?.let { v ->
                val parsed = asInt(v, KEY_CONTEXT_LIMIT)
                if (parsed <= 0) throw LlmSettingsValidationException("$KEY_CONTEXT_LIMIT должен быть больше нуля: $v")
            }
            // contextLimit выводится из модели по каталогу — отдельно не хранится (как в глобальном PUT).
        }
        if (patch.containsKey(KEY_TEMPERATURE)) {
            val v = patch[KEY_TEMPERATURE]
            if (v != null) {
                val d = asDouble(v, KEY_TEMPERATURE)
                if (d < 0) throw LlmSettingsValidationException("$KEY_TEMPERATURE не может быть отрицательной: $v")
                temperature = d.toString()
            } else {
                temperature = null
            }
        }
        if (patch.containsKey(KEY_TOP_P)) {
            val v = patch[KEY_TOP_P]
            if (v != null) {
                val d = asDouble(v, KEY_TOP_P)
                topP = d.toString()
            } else {
                topP = null
            }
        }
        if (patch.containsKey(KEY_TOP_K)) {
            val v = patch[KEY_TOP_K]
            if (v != null) {
                val parsed = asInt(v, KEY_TOP_K)
                // <= 0 трактуется как «не задано» (тем же правилом, что в глобальном PUT)
                topK = parsed.toString()
            } else {
                topK = null
            }
        }
        if (patch.containsKey(KEY_MAX_TOKENS)) {
            val v = patch[KEY_MAX_TOKENS]
            if (v != null) {
                val parsed = asInt(v, KEY_MAX_TOKENS)
                if (parsed <= 0) throw LlmSettingsValidationException("$KEY_MAX_TOKENS должен быть больше нуля: $v")
                val window = contextWindowOf(model)
                if (parsed > window) {
                    throw LlmSettingsValidationException(
                        "$KEY_MAX_TOKENS не может превышать контекстное окно модели ($window токенов): $v"
                    )
                }
                maxTokens = parsed.toString()
            } else {
                maxTokens = null
            }
        }
        if (patch.containsKey(KEY_TIMEOUT)) {
            val v = patch[KEY_TIMEOUT]
            if (v != null) {
                val l = asLong(v, KEY_TIMEOUT)
                if (l <= 0) throw LlmSettingsValidationException("$KEY_TIMEOUT должен быть больше нуля: $v")
                timeoutSeconds = l.toString()
            } else {
                timeoutSeconds = null
            }
        }
        if (patch.containsKey(KEY_PRICE_IN)) {
            val v = patch[KEY_PRICE_IN]
            if (v != null) {
                val d = asDouble(v, KEY_PRICE_IN)
                if (d < 0) throw LlmSettingsValidationException("$KEY_PRICE_IN не может быть отрицательной: $v")
                priceInputPer1M = d.toString()
            } else {
                priceInputPer1M = null
            }
        }
        if (patch.containsKey(KEY_PRICE_OUT)) {
            val v = patch[KEY_PRICE_OUT]
            if (v != null) {
                val d = asDouble(v, KEY_PRICE_OUT)
                if (d < 0) throw LlmSettingsValidationException("$KEY_PRICE_OUT не может быть отрицательной: $v")
                priceOutputPer1M = d.toString()
            } else {
                priceOutputPer1M = null
            }
        }
        if (patch.containsKey(KEY_REASONING)) {
            val v = patch[KEY_REASONING]
            if (v != null) {
                reasoningEnabled = asBoolean(v, KEY_REASONING).toString()
            } else {
                reasoningEnabled = null
            }
        }
        if (patch.containsKey(KEY_TOOLS)) {
            val v = patch[KEY_TOOLS]
            if (v != null) {
                toolsEnabled = asBoolean(v, KEY_TOOLS).toString()
            } else {
                toolsEnabled = null
            }
        }

        val merged = StoredSessionLlmSettings(
            sessionId = sessionId,
            model = model,
            temperature = temperature,
            topP = topP,
            topK = topK,
            maxTokens = maxTokens,
            timeoutSeconds = timeoutSeconds,
            priceInputPer1M = priceInputPer1M,
            priceOutputPer1M = priceOutputPer1M,
            reasoningEnabled = reasoningEnabled,
            toolsEnabled = toolsEnabled,
        )
        if (merged.hasOverrides) store.save(merged) else store.remove(sessionId)
        return get(sessionId)
    }

    /**
     * Per-session представление [LlmSettings] для чат-потока (AgentImpl): глобальные
     * настройки + переопределения сессии по ВСЕМ редактируемым полям.
     * Строки нет → поведение ровно как сегодня (всё из глобального источника).
     */
    fun resolve(sessionId: String): LlmSettings = SessionScopedLlmSettings(global, store.get(sessionId), ollama)

    /** Эффективный набор для GET/PUT: те же поля, что у глобального /api/llm-settings, БЕЗ provider. */
    private fun render(s: LlmSettings): Map<String, Any?> = mapOf(
        "model" to s.model(),
        "contextLimit" to s.contextLimit(),
        "temperature" to s.temperature(),
        "topP" to s.topP(),
        "topK" to s.topK()?.takeIf { it > 0 },
        "maxTokens" to s.maxTokens()?.takeIf { it > 0 },
        "timeoutSeconds" to s.timeoutSeconds(),
        "priceInputPer1M" to s.priceInputPer1M(),
        "priceOutputPer1M" to s.priceOutputPer1M(),
        "reasoningEnabled" to s.reasoningEnabled(),
        "toolsEnabled" to s.toolsEnabled(),
    )

    /** Контекстное окно эффективно выбранной модели (после применения патча); не из каталога — глобальный лимит. */
    private fun contextWindowOf(modelOverride: String?): Int {
        val resolved = modelOverride?.takeIf { it.isNotBlank() } ?: global.model()
        return LlmCatalog.contextLimit(resolved) ?: ollamaContextLimit(resolved) ?: global.contextLimit()
    }

    /** Контекстное окно модели Ollama (только когда провайдер — ollama); null — не из живого каталога. */
    private fun ollamaContextLimit(id: String): Int? =
        if (global.provider() == LlmProviders.OLLAMA) ollama?.contextLimitOf(id) else null

    /**
     * Валидация модели по каталогу ТЕКУЩЕГО провайдера (PUT сессии; сообщения те же,
     * что в глобальном PUT): gpustack — [LlmCatalog] + включённость; ollama — живое
     * обнаружение ([OllamaDiscovery]); недоступная Ollama → «Ollama недоступна».
     */
    private fun validateModelForProvider(id: String) {
        if (global.provider() == LlmProviders.OLLAMA) {
            val ids = ollama?.modelIds() ?: emptySet()
            if (id !in ids) {
                if (ids.isEmpty()) throw LlmSettingsValidationException(LlmProviders.OLLAMA_UNAVAILABLE_MESSAGE)
                throw LlmSettingsValidationException(
                    "Неизвестная модель: '$id'. Доступные модели: ${ids.sorted().joinToString(", ")}"
                )
            }
        } else {
            if (!LlmCatalog.isKnown(id)) {
                throw LlmSettingsValidationException(
                    "Неизвестная модель: '$id'. Доступные модели: " +
                        LlmCatalog.MODELS.joinToString(", ") { it.id }
                )
            }
            if (!isEnabled(id)) {
                throw LlmSettingsValidationException("Модель отключена в каталоге: $id")
            }
        }
    }

    /** true, если модель включена в каталоге; без [modelEnabled] — всегда включена. */
    private fun isEnabled(id: String): Boolean = modelEnabled?.isEnabled(id) ?: true

    private fun asDouble(value: Any, name: String): Double = when (value) {
        is Number -> value.toDouble()
        is String -> value.toDoubleOrNull()
            ?: throw LlmSettingsValidationException("$name: ожидалось число, получено '$value'")
        else -> throw LlmSettingsValidationException("$name: ожидалось число, получено '$value'")
    }

    private fun asInt(value: Any, name: String): Int = when (value) {
        is Int -> value
        is Number -> value.toInt()
        is String -> value.toIntOrNull()
            ?: throw LlmSettingsValidationException("$name: ожидалось целое число, получено '$value'")
        else -> throw LlmSettingsValidationException("$name: ожидалось целое число, получено '$value'")
    }

    private fun asLong(value: Any, name: String): Long = when (value) {
        is Long -> value
        is Number -> value.toLong()
        is String -> value.toLongOrNull()
            ?: throw LlmSettingsValidationException("$name: ожидалось целое число, получено '$value'")
        else -> throw LlmSettingsValidationException("$name: ожидалось целое число, получено '$value'")
    }

    private fun asBoolean(value: Any, name: String): Boolean = when (value) {
        is Boolean -> value
        is String -> when (value.trim().lowercase()) {
            "true" -> true
            "false" -> false
            else -> throw LlmSettingsValidationException("$name: ожидалось true/false, получено '$value'")
        }
        else -> throw LlmSettingsValidationException("$name: ожидалось true/false, получено '$value'")
    }

    private companion object {
        const val KEY_MODEL = "model"
        const val KEY_CONTEXT_LIMIT = "contextLimit"
        const val KEY_TEMPERATURE = "temperature"
        const val KEY_TOP_P = "topP"
        const val KEY_TOP_K = "topK"
        const val KEY_MAX_TOKENS = "maxTokens"
        const val KEY_TIMEOUT = "timeoutSeconds"
        const val KEY_PRICE_IN = "priceInputPer1M"
        const val KEY_PRICE_OUT = "priceOutputPer1M"
        const val KEY_REASONING = "reasoningEnabled"
        const val KEY_TOOLS = "toolsEnabled"
    }
}

/**
 * Per-session представление [LlmSettings]: глобальный источник ([delegate]) + переопределения
 * сессии по ВСЕМ редактируемым полям (таблица `session_llm_settings`). Для каждого поля —
 * сохранённое значение, если есть, иначе делегирование глобальному.
 * Строки нет → все поля делегируются глобальным настройкам (поведение «как сегодня»).
 *
 * `contextLimit` выводится из эффективно выбранной модели ([LlmCatalog] для gpustack,
 * живое обнаружение для ollama); модель не из каталога — глобальный лимит.
 * Эффективная модель: переопределение сессии ТОЛЬКО если оно принадлежит каталогу
 * текущего провайдера; иначе — текущая глобальная модель. `provider` наследуется
 * от глобального источника (runtime-выбор в /api/llm-settings).
 * Распознавание нечисловых/нераспознанных строк: topK/maxTokens — нечисловое или <= 0 →
 * «не задано»; reasoningEnabled — не "true"/"false" → глобальное значение. Маркер `"null"`
 * (старые версии хранили его для «сброса» к дефолту) трактуется как «не переопределено».
 */
class SessionScopedLlmSettings(
    private val delegate: LlmSettings,
    private val stored: StoredSessionLlmSettings?,
    /** Живой каталог моделей Ollama; null — проверки ollama-моделей нет. */
    private val ollama: OllamaDiscovery? = null,
) : LlmSettings {

    override fun provider(): String = delegate.provider()

    /**
     * Эффективная модель: переопределение сессии ТОЛЬКО если оно валидно для каталога
     * ТЕКУЩЕГО провайдера ([LlmCatalog] для gpustack, живое обнаружение для ollama);
     * иначе (не задано / не из каталога текущего провайдера, например после переключения
     * провайдера) — текущая ГЛОБАЛЬНАЯ модель.
     */
    override fun model(): String {
        val override = stored?.model?.takeIf { it.isNotBlank() }
        if (override != null && inCurrentCatalog(override)) return override
        return delegate.model()
    }

    override fun temperature(): Double = stored?.temperature?.toDoubleOrNull()?.takeIf { it >= 0 } ?: delegate.temperature()

    override fun topP(): Double = stored?.topP?.toDoubleOrNull()?.takeIf { it >= 0 } ?: delegate.topP()

    override fun topK(): Int? = parseNullableInt(stored?.topK) ?: delegate.topK()

    override fun maxTokens(): Int? = parseNullableInt(stored?.maxTokens) ?: delegate.maxTokens()

    override fun reasoningEnabled(): Boolean = parseNullableBoolean(stored?.reasoningEnabled) ?: delegate.reasoningEnabled()

    override fun toolsEnabled(): Boolean = parseNullableBoolean(stored?.toolsEnabled) ?: delegate.toolsEnabled()

    override fun timeoutSeconds(): Long = stored?.timeoutSeconds?.toLongOrNull()?.takeIf { it > 0 } ?: delegate.timeoutSeconds()

    override fun contextLimit(): Int =
        LlmCatalog.contextLimit(model()) ?: ollamaContextLimit(model()) ?: delegate.contextLimit()

    override fun priceInputPer1M(): Double = stored?.priceInputPer1M?.toDoubleOrNull()?.takeIf { it >= 0 } ?: delegate.priceInputPer1M()

    override fun priceOutputPer1M(): Double = stored?.priceOutputPer1M?.toDoubleOrNull()?.takeIf { it >= 0 } ?: delegate.priceOutputPer1M()

    override fun settings(): Map<String, Any?> = mapOf(
        "provider" to provider(),
        "model" to model(),
        "temperature" to temperature(),
        "topP" to topP(),
        // 0/не задано нормализуем в null — «параметр не применяется»
        "topK" to topK()?.takeIf { it > 0 },
        "maxTokens" to maxTokens()?.takeIf { it > 0 },
        "reasoningEnabled" to reasoningEnabled(),
        "toolsEnabled" to toolsEnabled(),
        "timeoutSeconds" to timeoutSeconds(),
        "contextLimit" to contextLimit(),
        "priceInputPer1M" to priceInputPer1M(),
        "priceOutputPer1M" to priceOutputPer1M(),
    )

    /** Число > 0; null/нечисловое/<=0 — «не задано» (подхватывается глобальное значение). */
    private fun parseNullableInt(raw: String?): Int? = raw?.toIntOrNull()?.takeIf { it > 0 }

    /** "true"/"false"; null/нераспознанное — глобальное значение. */
    private fun parseNullableBoolean(raw: String?): Boolean? = when (raw?.trim()?.lowercase()) {
        "true" -> true
        "false" -> false
        else -> null
    }

    /** true, если модель принадлежит каталогу ТЕКУЩЕГО провайдера. */
    private fun inCurrentCatalog(id: String): Boolean =
        if (provider() == LlmProviders.OLLAMA) {
            ollama != null && id in ollama.modelIds()
        } else {
            LlmCatalog.isKnown(id)
        }

    /** Контекстное окно модели Ollama (только когда провайдер — ollama). */
    private fun ollamaContextLimit(id: String): Int? =
        if (provider() == LlmProviders.OLLAMA) ollama?.contextLimitOf(id) else null
}
