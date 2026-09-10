package com.example.llmagent.config

import org.springframework.stereotype.Component

/**
 * Динамические (runtime) настройки LLM. Источники по приоритету:
 * 1) defaults — [LlmProperties] (application.yml / переменные окружения);
 * 2) сохранённые строки `app_settings` — переопределяют defaults и переживают рестарт.
 *
 * Изменяются на лету через [update] (PUT /api/llm-settings) БЕЗ перезапуска backend.
 * - `provider` неизменяем (клиент привязан к нему на старте) — изменения игнорируются;
 * - `contextLimit` выводится из выбранной модели по каталогу ([LlmCatalog]), а не хранится.
 */
@Component
class DynamicLlmSettings(
    private val llm: LlmProperties,
    private val store: AppSettingsStore,
    /** Состояние «включена/отключена» моделей каталога; null — проверки нет (все включены). */
    private val modelEnabled: ModelEnabledStore? = null,
) : LlmSettings {

    @Volatile private var model: String = llm.model
    @Volatile private var temperature: Double = llm.temperature
    @Volatile private var topP: Double = llm.topP
    @Volatile private var topK: Int? = llm.topK
    @Volatile private var maxTokens: Int? = llm.maxTokens
    @Volatile private var reasoningEnabled: Boolean = llm.reasoningEnabled
    @Volatile private var timeoutSeconds: Long = llm.timeoutSeconds
    @Volatile private var priceInputPer1M: Double = llm.priceInputPer1M
    @Volatile private var priceOutputPer1M: Double = llm.priceOutputPer1M

    init {
        loadPersisted()
    }

    override fun provider(): String = llm.provider
    override fun model(): String = model
    override fun temperature(): Double = temperature
    override fun topP(): Double = topP
    override fun topK(): Int? = topK
    override fun maxTokens(): Int? = maxTokens
    override fun reasoningEnabled(): Boolean = reasoningEnabled
    override fun timeoutSeconds(): Long = timeoutSeconds
    override fun contextLimit(): Int = LlmCatalog.contextLimit(model) ?: llm.contextLimit
    override fun priceInputPer1M(): Double = priceInputPer1M
    override fun priceOutputPer1M(): Double = priceOutputPer1M

    override fun settings(): Map<String, Any?> = mapOf(
        "provider" to provider(),
        "model" to model(),
        "temperature" to temperature(),
        "topP" to topP(),
        // 0/не задано нормализуем в null — «параметр не применяется»
        "topK" to topK?.takeIf { it > 0 },
        "maxTokens" to maxTokens?.takeIf { it > 0 },
        "reasoningEnabled" to reasoningEnabled(),
        "timeoutSeconds" to timeoutSeconds(),
        "contextLimit" to contextLimit(),
        "priceInputPer1M" to priceInputPer1M(),
        "priceOutputPer1M" to priceOutputPer1M(),
    )

    /**
     * Частичное обновление настроек (PUT /api/llm-settings). Валидация:
     * - `provider` игнорируется (неизменяем);
     * - `model` обязана быть в каталоге ([LlmCatalog]) И быть включённой ([ModelEnabledStore]);
     *   `contextLimit` пересчитывается автоматически; отключённая модель → «Модель отключена в каталоге»;
     * - `temperature` >= 0, `maxTokens` > 0 (null — вернуть к значению по умолчанию из конфигурации, 10000);
     * - `reasoningEnabled` — boolean true/false; null — вернуть к значению по умолчанию (true);
     * - ошибки бросаются как [LlmSettingsValidationException] → HTTP 400 в контроллере.
     *
     * Каждое изменённое поле сразу персистится в `app_settings`, поэтому изменения
     * переживают перезапуск backend.
     */
    fun update(patch: Map<String, Any?>) {
        if (patch.containsKey(KEY_MODEL)) {
            val v = patch[KEY_MODEL]
            if (v != null) {
                if (v !is String) throw LlmSettingsValidationException("model: ожидалась строка, получено '$v'")
                val id = v.trim()
                if (id.isNotEmpty()) {
                    if (!LlmCatalog.isKnown(id)) {
                        throw LlmSettingsValidationException(
                            "Неизвестная модель: '$id'. Доступные модели: " +
                                LlmCatalog.MODELS.joinToString(", ") { it.id }
                        )
                    }
                    if (!isEnabled(id)) {
                        throw LlmSettingsValidationException("Модель отключена в каталоге: $id")
                    }
                    model = id
                    save(KEY_MODEL, id)
                }
            }
        }
        if (patch.containsKey(KEY_TEMPERATURE)) {
            patch[KEY_TEMPERATURE]?.let { v ->
                val d = asDouble(v, KEY_TEMPERATURE)
                if (d < 0) throw LlmSettingsValidationException("$KEY_TEMPERATURE не может быть отрицательной: $v")
                temperature = d
                save(KEY_TEMPERATURE, d.toString())
            }
        }
        if (patch.containsKey(KEY_TOP_P)) {
            patch[KEY_TOP_P]?.let { v ->
                val d = asDouble(v, KEY_TOP_P)
                topP = d
                save(KEY_TOP_P, d.toString())
            }
        }
        if (patch.containsKey(KEY_TOP_K)) {
            val v = patch[KEY_TOP_K]
            topK = if (v == null) null else asInt(v, KEY_TOP_K).takeIf { it > 0 }
            save(KEY_TOP_K, topK?.toString() ?: NULL_VALUE)
        }
        if (patch.containsKey(KEY_MAX_TOKENS)) {
            val v = patch[KEY_MAX_TOKENS]
            if (v == null) {
                // «сброс» — вернуть к настроенному значению по умолчанию (не null)
                maxTokens = llm.maxTokens
                save(KEY_MAX_TOKENS, NULL_VALUE)
            } else {
                val parsed = asInt(v, KEY_MAX_TOKENS)
                if (parsed <= 0) throw LlmSettingsValidationException("$KEY_MAX_TOKENS должен быть больше нуля: $v")
                maxTokens = parsed
                save(KEY_MAX_TOKENS, parsed.toString())
            }
        }
        if (patch.containsKey(KEY_REASONING)) {
            val v = patch[KEY_REASONING]
            if (v == null) {
                // «сброс» — вернуть к настроенному значению по умолчанию (true)
                reasoningEnabled = llm.reasoningEnabled
                save(KEY_REASONING, NULL_VALUE)
            } else {
                reasoningEnabled = asBoolean(v, KEY_REASONING)
                save(KEY_REASONING, reasoningEnabled.toString())
            }
        }
        if (patch.containsKey(KEY_TIMEOUT)) {
            patch[KEY_TIMEOUT]?.let { v ->
                val l = asLong(v, KEY_TIMEOUT)
                if (l <= 0) throw LlmSettingsValidationException("$KEY_TIMEOUT должен быть больше нуля: $v")
                timeoutSeconds = l
                save(KEY_TIMEOUT, l.toString())
            }
        }
        if (patch.containsKey(KEY_PRICE_IN)) {
            patch[KEY_PRICE_IN]?.let { v ->
                val d = asDouble(v, KEY_PRICE_IN)
                if (d < 0) throw LlmSettingsValidationException("$KEY_PRICE_IN не может быть отрицательной: $v")
                priceInputPer1M = d
                save(KEY_PRICE_IN, d.toString())
            }
        }
        if (patch.containsKey(KEY_PRICE_OUT)) {
            patch[KEY_PRICE_OUT]?.let { v ->
                val d = asDouble(v, KEY_PRICE_OUT)
                if (d < 0) throw LlmSettingsValidationException("$KEY_PRICE_OUT не может быть отрицательной: $v")
                priceOutputPer1M = d
                save(KEY_PRICE_OUT, d.toString())
            }
        }
    }

    /** Применяет сохранённые в `app_settings` значения поверх defaults (вызывается при старте). */
    private fun loadPersisted() {
        store.all().forEach { (key, raw) ->
            when (key) {
                // Модель, выпиленная из каталога или отключённая, больше не применима — остаёмся на default.
                KEY_MODEL -> if (LlmCatalog.isKnown(raw) && isEnabled(raw)) model = raw
                KEY_TEMPERATURE -> raw.toDoubleOrNull()?.let { if (it >= 0) temperature = it }
                KEY_TOP_P -> raw.toDoubleOrNull()?.let { topP = it }
                KEY_TOP_K -> topK = parseNullableInt(raw)?.takeIf { it > 0 }
                // маркер «null» = reset к дефолту конфигурации, а не «без лимита»
                KEY_MAX_TOKENS -> maxTokens = if (raw == NULL_VALUE) llm.maxTokens else parseNullableInt(raw)?.takeIf { it > 0 }
                // маркер «null» = reset к дефолту конфигурации (true); нераспознанное значение — тоже дефолт
                KEY_REASONING -> reasoningEnabled = if (raw == NULL_VALUE) llm.reasoningEnabled else parseNullableBoolean(raw) ?: llm.reasoningEnabled
                KEY_TIMEOUT -> raw.toLongOrNull()?.let { if (it > 0) timeoutSeconds = it }
                KEY_PRICE_IN -> raw.toDoubleOrNull()?.let { if (it >= 0) priceInputPer1M = it }
                KEY_PRICE_OUT -> raw.toDoubleOrNull()?.let { if (it >= 0) priceOutputPer1M = it }
            }
        }
    }

    /** `"null"` — сохранённый маркер «не задано» (topK) / «вернуть к дефолту» (maxTokens). */
    private fun parseNullableInt(raw: String): Int? =
        if (raw == NULL_VALUE) null else raw.toIntOrNull()

    /** Парсит сохранённую строку "true"/"false"; null — не распознано (тогда применяется дефолт). */
    private fun parseNullableBoolean(raw: String): Boolean? = when (raw.trim().lowercase()) {
        "true" -> true
        "false" -> false
        else -> null
    }

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

    /** true, если модель включена в каталоге; без [modelEnabled] — всегда включена. */
    private fun isEnabled(id: String): Boolean = modelEnabled?.isEnabled(id) ?: true

    private fun save(key: String, value: String) {
        store.save(key, value)
    }

    private companion object {
        const val KEY_MODEL = "model"
        const val KEY_TEMPERATURE = "temperature"
        const val KEY_TOP_P = "topP"
        const val KEY_TOP_K = "topK"
        const val KEY_MAX_TOKENS = "maxTokens"
        const val KEY_REASONING = "reasoningEnabled"
        const val KEY_TIMEOUT = "timeoutSeconds"
        const val KEY_PRICE_IN = "priceInputPer1M"
        const val KEY_PRICE_OUT = "priceOutputPer1M"
        const val NULL_VALUE = "null"
    }
}

/** Ошибка валидации настроек LLM (PUT /api/llm-settings) — контроллер переводит её в HTTP 400. */
class LlmSettingsValidationException(message: String) : IllegalArgumentException(message)
