package com.example.llmagent.agent

import com.example.llmagent.config.LlmSettings
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.reactor.awaitSingle
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Структурированный результат извлечения памяти задачи — ПОЛНАЯ замена состояния
 * (инкрементальных слияний нет: модель возвращает цельное обновлённое состояние,
 * прежнее подаётся ей в промпт для контекста).
 */
data class ExtractedTaskMemory(
    val goal: String,
    val clarifications: List<String>,
    val constraints: List<String>,
)

/**
 * Память задачи сессии (Day-25, task_memory) — логика ПОВЕРХ [JdbcTaskMemoryStore]:
 * автоматическое LLM-извлечение структурированного состояния диалога
 * {goal, clarifications, constraints} после каждого завершённого обмена и чтение
 * для REST (GET /api/sessions/{sessionId}/task-memory).
 *
 * Извлечение ([updateFromExchange]) по паттерну extractFacts (Day-11, sticky_facts):
 * post-run вызов LLM БЕЗ инструментов через ОБЁРНУТЫЙ клиент (LlmCallLoggingClient —
 * записи «Запрос в llm»/«Ответ от llm» уходят в панель «Логи»), один ретрай на
 * пустой/сбойный/не-JSON ответ, строгий парсинг JSON. Ограничения (caps) применяются
 * в коде ([sanitize]): ≤[MAX_ITEMS] пунктов на список, ≤[ITEM_MAX_CHARS] символов
 * на пункт, trim, пустые выбрасываются.
 *
 * Сбои здесь НЕ глотаются: [updateFromExchange] бросает [LlmSummaryException] /
 * [LlmApiException] / [TimeoutException] — вызывающий код (AgentImpl) переводит это
 * в fail-open (предыдущее состояние сохраняется, событие task_memory_updated не
 * эмитится, run продолжается). НЕ путать с task_state (FSM воркфлоу Day-13) —
 * таблицы и код независимы.
 */
@Component
class TaskMemoryService(
    private val store: JdbcTaskMemoryStore,
    private val om: ObjectMapper,
) {

    private val log = LoggerFactory.getLogger(TaskMemoryService::class.java)

    /** Текущее состояние памяти задачи; строки нет — пустая структура (никогда не 404). */
    fun load(sessionId: String): TaskMemory = store.get(sessionId)

    /**
     * Обновляет память задачи по завершённому обмену ([userMessage] + [finalAnswer]):
     * LLM-извлечение полной замены состояния, caps ([sanitize]), запись в store.
     * Возвращает сохранённое состояние; null — сбой БД при записи (вызывающий код
     * тогда НЕ эмитит событие task_memory_updated). Бросает исключения LLM-вызова
     * и парсинга — fail-open (сохранение прежнего состояния) делает вызывающий код.
     */
    suspend fun updateFromExchange(
        sessionId: String,
        previous: TaskMemory,
        userMessage: String,
        finalAnswer: String,
        client: LlmClient,
        runSettings: LlmSettings,
    ): TaskMemory? {
        val prompt = buildExtractionPrompt(previous, userMessage, finalAnswer)
        // Один ретрай на пустой/сбойный/не-JSON ответ: модель изредка отдаёт пустоту
        // или markdown вместо JSON на коротких служебных промптах — повторная попытка
        // обычно успешна (тот же приём, что extractFacts; fail-open остаётся крайним случаем).
        val sanitized = try {
            extractAndSanitize(client, prompt, runSettings)
        } catch (e: LlmSummaryException) {
            extractAndSanitize(client, prompt, runSettings)
        }
        return store.upsert(sessionId, sanitized.goal, sanitized.clarifications, sanitized.constraints)
    }

    /**
     * Промпт извлечения: прежнее состояние (JSON), сообщение пользователя и ответ
     * ассистента (каждое обрезано до [MAX_MESSAGE_CHARS] символов), ПОСЛЕДНИМ
     * user-сообщением — запрос строгого JSON ([TASK_MEMORY_REQUEST_PROMPT]) — так
     * модель отвечает на запрос извлечения, а не продолжает диалог по обмену.
     */
    fun buildExtractionPrompt(
        previous: TaskMemory,
        userMessage: String,
        finalAnswer: String,
    ): List<LlmMessage> {
        val prompt = mutableListOf<LlmMessage>()
        prompt += LlmMessage("user", "Текущее состояние памяти задачи (JSON):\n" + previousJson(previous))
        prompt += LlmMessage("user", "Сообщение пользователя:\n" + userMessage.take(MAX_MESSAGE_CHARS))
        prompt += LlmMessage("user", "Ответ ассистента:\n" + finalAnswer.take(MAX_MESSAGE_CHARS))
        prompt += LlmMessage("user", TASK_MEMORY_REQUEST_PROMPT)
        return prompt
    }

    /**
     * Строгий разбор ответа модели: JSON-объект
     * `{"goal": string, "clarifications": string[], "constraints": string[]}`.
     * Отсутствующие поля — пустые значения; нестроковые элементы списков молча
     * пропускаются. Бросает [LlmSummaryException] при не-JSON-ответе или не-объекте.
     */
    fun parseTaskMemory(raw: String): ExtractedTaskMemory {
        val node = try {
            om.readTree(raw)
        } catch (e: Exception) {
            throw LlmSummaryException("LLM вернул не-JSON вместо памяти задачи: ${e.message}")
        }
        if (node == null || !node.isObject) {
            throw LlmSummaryException("LLM вернул не-JSON-объект вместо памяти задачи")
        }
        val goal = node.get("goal")?.takeIf { it.isTextual }?.asText() ?: ""
        val clarifications = stringList(node.get("clarifications"))
        val constraints = stringList(node.get("constraints"))
        return ExtractedTaskMemory(goal, clarifications, constraints)
    }

    /**
     * Ограничения (caps) поверх распарсенного состояния: trim, ≤[MAX_ITEMS] пунктов
     * на список, ≤[ITEM_MAX_CHARS] символов на пункт, пустые пункты выбрасываются.
     */
    fun sanitize(extracted: ExtractedTaskMemory): ExtractedTaskMemory = ExtractedTaskMemory(
        goal = extracted.goal.trim().take(ITEM_MAX_CHARS),
        clarifications = capped(extracted.clarifications),
        constraints = capped(extracted.constraints),
    )

    /**
     * Вызов LLM извлечения (без инструментов, как extractFacts/rewriteQuery). Возвращает
     * СЫРОЙ текст ответа; парсинг — в [parseTaskMemory]. Бросает [LlmSummaryException]
     * при finishReason error/tool_calls или пустом ответе — вызывающий код (через
     * [updateFromExchange]) делает один ретрай, крайний случай — fail-open наверху.
     */
    private suspend fun requestExtraction(
        client: LlmClient,
        prompt: List<LlmMessage>,
        runSettings: LlmSettings,
    ): String {
        val text = StringBuilder()
        var finishReason = "stop"
        val events = client.streamChat(prompt, emptyList(), runSettings).collectList().awaitSingle()
        for (e in events) {
            when (e) {
                is LlmEvent.ContentDelta -> text.append(e.delta)
                is LlmEvent.Finished -> finishReason = e.finishReason
                is LlmEvent.ToolCallsComplete -> Unit
                is LlmEvent.ResponseAssembled -> Unit
            }
        }
        if (finishReason == "error" || finishReason == "tool_calls") {
            throw LlmSummaryException("LLM вернул finishReason=$finishReason вместо памяти задачи")
        }
        if (text.isBlank()) {
            throw LlmSummaryException("LLM вернул пустой ответ вместо памяти задачи")
        }
        return text.toString()
    }

    private suspend fun extractAndSanitize(
        client: LlmClient,
        prompt: List<LlmMessage>,
        runSettings: LlmSettings,
    ): ExtractedTaskMemory = sanitize(parseTaskMemory(requestExtraction(client, prompt, runSettings)))

    /** Список строк из JSON-узла; узел не массив — пустой список, нестроковые пропускаются. */
    private fun stringList(node: JsonNode?): List<String> {
        if (node == null || !node.isArray) return emptyList()
        return (0 until node.size()).mapNotNull { i -> node.get(i) }
            .filter { it.isTextual }
            .map { it.asText() }
    }

    /** Список после caps: trim → не пустые → ≤ITEM_MAX_CHARS → ≤MAX_ITEMS пунктов. */
    private fun capped(items: List<String>): List<String> =
        items.asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { it.take(ITEM_MAX_CHARS) }
            .take(MAX_ITEMS)
            .toList()

    private fun previousJson(previous: TaskMemory): String = try {
        om.writeValueAsString(
            mapOf(
                "goal" to previous.goal,
                "clarifications" to previous.clarifications,
                "constraints" to previous.constraints,
            ),
        )
    } catch (e: Exception) {
        log.warn("TaskMemory: не удалось сериализовать прежнее состояние: {}", e.message)
        "{}"
    }

    companion object {
        /** Лимит пунктов на список уточнений/ограничений. */
        const val MAX_ITEMS = 10

        /** Лимит символов на один пункт (цель/уточнение/ограничение). */
        const val ITEM_MAX_CHARS = 300

        /** Лимит символов сообщения пользователя/ответа ассистента в промпте извлечения. */
        const val MAX_MESSAGE_CHARS = 1500

        /**
         * Запрос обновления памяти задачи (Day-25) — обычное user-сообщение, замыкающее
         * промпт извлечения (без системного промпта). Ставится последним, чтобы модель
         * отвечала именно на него, а не продолжала диалог по обмену (тот же приём, что
         * FACTS_REQUEST_PROMPT / REWRITE_REQUEST_PROMPT). Ответ — ТОЛЬКО JSON-объект
         * полной замены состояния, без пояснений и markdown.
         */
        const val TASK_MEMORY_REQUEST_PROMPT =
            "Обнови память задачи — структурированное состояние диалога: цель (goal), уточнения " +
                "пользователя (clarifications) и ограничения/термины (constraints). Дополни прежнее " +
                "состояние по последнему обмену: уточняющие ответы пользователя — в clarifications, " +
                "зафиксированные ограничения, термины и требования — в constraints; цель переформулируй, " +
                "если она изменилась. Верни ТОЛЬКО JSON-объект вида " +
                "{\"goal\": string, \"clarifications\": string[], \"constraints\": string[]} " +
                "без пояснений и без markdown. Это служебное сообщение — не отвечай на него как на часть диалога."
    }
}
