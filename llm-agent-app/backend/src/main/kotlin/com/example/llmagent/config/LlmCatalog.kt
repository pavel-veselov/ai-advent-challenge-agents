package com.example.llmagent.config

/**
 * Каталог моделей LLM — единственный источник выбора `model`, контекстного окна
 * и человекочитаемого описания модели (GET /api/models).
 * При смене модели (PUT /api/llm-settings) `contextLimit` пересчитывается по каталогу:
 * лимит контекста всегда выводится из выбранной модели, а не хранится отдельно.
 *
 * Порядок [MODELS] фиксирует порядок выдачи GET /api/models — не менять без согласования
 * контракта с фронтендом.
 */
object LlmCatalog {

    /** 1K токенов. */
    const val K = 1024

    /** Спека модели каталога: идентификатор, контекстное окно в токенах и описание. */
    data class ModelSpec(val id: String, val contextWindow: Int, val description: String)

    /** Доступные для выбора модели (единственные selectable в PUT /api/llm-settings). */
    val MODELS: List<ModelSpec> = listOf(
        ModelSpec(
            "qwen3.8-27b",
            198 * K, // 198K
            "Универсальная чат-модель с окном 202 752 токена. Режим рассуждений включается и отключается в настройках.",
        ),
        ModelSpec(
            "deepseek-v4-flash",
            1024 * K, // 1M
            "Быстрая и экономичная чат-модель. Режим рассуждений включается и отключается в настройках.",
        ),
        ModelSpec(
            "glm-5.3-flash",
            256 * K, // 256K
            "Чат-модель с принудительным режимом рассуждений — шлюз не позволяет его отключить.",
        ),
    )

    private val byId: Map<String, ModelSpec> = MODELS.associateBy { it.id }

    /** Спека модели по идентификатору; null — модель не из каталога. */
    fun spec(id: String): ModelSpec? = byId[id]

    /** true, если модель можно выбрать в PUT /api/llm-settings. */
    fun isKnown(id: String): Boolean = byId.containsKey(id)

    /** Лимит контекста модели в токенах; null — модель не из каталога. */
    fun contextLimit(id: String): Int? = byId[id]?.contextWindow
}
