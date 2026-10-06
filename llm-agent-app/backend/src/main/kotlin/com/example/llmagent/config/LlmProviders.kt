package com.example.llmagent.config

/**
 * Идентификаторы провайдеров LLM и их отображаемые имена (GET /api/llm/providers).
 * Стартовое значение — env LLM_PROVIDER; текущее — runtime-выбор в [DynamicLlmSettings].
 */
object LlmProviders {

    const val GPUSTACK = "gpustack"

    const val OLLAMA = "ollama"

    /** Все допустимые провайдеры в фиксированном порядке выдачи GET /api/llm/providers. */
    val ALL = listOf(GPUSTACK, OLLAMA)

    /** Отображаемое имя провайдера (label в /api/llm/providers). */
    fun label(id: String): String = when (id) {
        OLLAMA -> "свой лунапарк"
        else -> GPUSTACK
    }

    /** true — допустимое значение провайдера (env LLM_PROVIDER / PUT /api/llm-settings). */
    fun isKnown(id: String): Boolean = id == GPUSTACK || id == OLLAMA

    /** Сообщение об ошибке при переключении на Ollama, когда discovery не вернул моделей. */
    const val OLLAMA_UNAVAILABLE_MESSAGE = "Ollama недоступна"
}
