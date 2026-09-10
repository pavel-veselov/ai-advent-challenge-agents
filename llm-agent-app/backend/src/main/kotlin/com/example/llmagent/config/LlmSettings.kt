package com.example.llmagent.config

/**
 * Применённые настройки LLM — живое представление, которое читают `AgentImpl`
 * (лимит контекста, maxTokens, тарифы стоимости) и `GpuStackLlmClient`
 * (model, temperature, topP/topK, maxTokens, timeout) в КАЖДОМ запросе.
 *
 * Реализации:
 * - [StaticLlmSettings] — фиксированный снимок [LlmProperties] (юнит-тесты агентского слоя,
 *   default-аргументы конструкторов);
 * - [DynamicLlmSettings] — изменяемые настройки, персистятся в `app_settings` и переживают рестарт.
 */
interface LlmSettings {
    fun provider(): String

    /** Идентификатор модели из каталога (или из окружения, если ещё не выбиралась). */
    fun model(): String

    fun temperature(): Double

    /** Nucleus sampling (top_p) — уходит в API всегда. */
    fun topP(): Double

    /** Top-k sampling; null/<=0 — параметр в API не отправляется. */
    fun topK(): Int?

    /** Лимит выходных токенов (max_tokens); null/<=0 — параметр в API не отправляется. */
    fun maxTokens(): Int?

    /** Включено ли «рассуждение» модели (thinking). false — клиент шлёт chat_template_kwargs.enable_thinking=false (кроме glm*). */
    fun reasoningEnabled(): Boolean

    fun timeoutSeconds(): Long

    /** Лимит контекста модели в токенах — для пресечения переполнения до отправки запроса. */
    fun contextLimit(): Int

    /** Условная цена за 1M входных токенов, USD (для расчёта стоимости ответа). */
    fun priceInputPer1M(): Double

    /** Условная цена за 1M выходных токенов, USD (для расчёта стоимости ответа). */
    fun priceOutputPer1M(): Double

    /** Применённые настройки в виде карты (для GET /api/llm-settings и события agent_started). */
    fun settings(): Map<String, Any?>

    companion object {
        /** Фиксированный снимок поверх [LlmProperties] — для юнит-тестов и конструкторов по умолчанию. */
        fun from(props: LlmProperties): LlmSettings = StaticLlmSettings(props)
    }
}

/**
 * Неизменяемый снимок [LlmProperties] — поведение «как было до динамических настроек»
 * для юнит-тестов агентского слоя и default-аргументов (contextLimit берётся из свойств
 * как есть, без вывода по каталогу).
 */
class StaticLlmSettings(private val props: LlmProperties) : LlmSettings {
    override fun provider() = props.provider
    override fun model() = props.model
    override fun temperature() = props.temperature
    override fun topP() = props.topP
    override fun topK() = props.topK
    override fun maxTokens() = props.maxTokens
    override fun reasoningEnabled() = props.reasoningEnabled
    override fun timeoutSeconds() = props.timeoutSeconds
    override fun contextLimit() = props.contextLimit
    override fun priceInputPer1M() = props.priceInputPer1M
    override fun priceOutputPer1M() = props.priceOutputPer1M

    override fun settings(): Map<String, Any?> = mapOf(
        "provider" to provider(),
        "model" to model(),
        "temperature" to temperature(),
        "topP" to topP(),
        // 0/не задано нормализуем в null — «параметр не применяется»
        "topK" to topK()?.takeIf { it > 0 },
        "maxTokens" to maxTokens()?.takeIf { it > 0 },
        "reasoningEnabled" to reasoningEnabled(),
        "timeoutSeconds" to timeoutSeconds(),
        "contextLimit" to contextLimit(),
        "priceInputPer1M" to priceInputPer1M(),
        "priceOutputPer1M" to priceOutputPer1M(),
    )
}
