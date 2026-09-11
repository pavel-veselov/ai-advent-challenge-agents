package com.example.llmagent.agent

import com.example.llmagent.config.LlmProperties
import com.example.llmagent.config.LlmSettings
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.web.reactive.function.client.WebClient
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.time.Duration

/**
 * Клиент к GPUStack (OpenAI-совместимый /v1/chat/completions) со стримингом токенов.
 * Ключ передаётся только как Bearer-заголовок из LlmProperties (env), не логируется.
 * Параметры запроса (model, temperature, top_p, top_k, max_tokens, timeout) читаются
 * из [LlmSettings] на каждый запрос — динамические изменения применяются без рестарта.
 */
class GpuStackLlmClient(
    private val props: LlmProperties,
    private val om: ObjectMapper,
    private val settings: LlmSettings = LlmSettings.from(props),
) : LlmClient {

    private val baseUrl = normalizeBaseUrl(props.baseUrl)

    private val webClient = WebClient.builder()
        .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer ${props.apiKey}")
        .baseUrl(baseUrl)
        .build()

    /**
     * Старый 2-аргументный вызов — для совместимости (тесты/смоук): тянет настройки из
     * конструктора. Чат-поток (AgentImpl) всегда передаёт per-session настройки явно.
     */
    fun streamChat(messages: List<LlmMessage>, tools: List<ToolDefinition>): Flux<LlmEvent> =
        streamChat(messages, tools, this.settings)

    override fun streamChat(
        messages: List<LlmMessage>,
        tools: List<ToolDefinition>,
        settings: LlmSettings,
    ): Flux<LlmEvent> {
        // Пустые tools — отдельный случай: некоторые OpenAI-совместимые бэкенды (gpustack) отвечают
        // 400 и на `"tools": []`, и на `"tool_choice"` без `tools`. Оба поля уходят ЛИБО вместе
        // (в основном цикле, где инструменты всегда регистрируются), ЛИБО ни одно — при пустом
        // списке (вызов резюмирования истории). Прочие параметры (model/temperature/top_p/stream,
        // опциональные top_k/max_tokens/chat_template_kwargs/stream_options) к инструментам
        // отношения не имеют и уходят как раньше.
        val hasTools = tools.isNotEmpty()
        val body = om.createObjectNode()
            .put("model", settings.model())
            .put("temperature", settings.temperature())
            .put("top_p", settings.topP())
            .put("stream", true)
        if (hasTools) body.put("tool_choice", "auto")
        // top_k и max_tokens отправляем только если заданы (>0): не все OpenAI-совместимые
        // бэкенды принимают top_k, а max_tokens=0 бессмыслен.
        settings.topK()?.takeIf { it > 0 }?.let { body.put("top_k", it) }
        settings.maxTokens()?.takeIf { it > 0 }?.let { body.put("max_tokens", it) }
        // Выключаем «рассуждение» модели (thinking): vLLM понимает chat_template_kwargs.enable_thinking=false
        // (проверено для qwen3.8-27b / deepseek-v4-flash). Для glm* thinking форсирован — kwarg НЕ уходит,
        // иначе текст рассуждений протекает в content. Включено — ничего не шлём (thinking включён по умолчанию).
        if (!settings.reasoningEnabled() && !settings.model().startsWith("glm", ignoreCase = true)) {
            body.putObject("chat_template_kwargs").put("enable_thinking", false)
        }
        body.putObject("stream_options").put("include_usage", true)
        val msgArr = body.putArray("messages")
        messages.forEach { msgArr.add(messageNode(it)) }
        if (hasTools) {
            val toolsArr = body.putArray("tools")
            tools.forEach { toolsArr.add(toolNode(it)) }
        }

        return Flux.defer {
            val acc = Pending()
            webClient.post()
                .uri("/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue(body)
                .retrieve()
                .onStatus({ it.is4xxClientError || it.is5xxServerError }) { response ->
                    // 4xx/5xx — ошибка API (неверный ключ/модель/тело, сервер недоступен и т.п.):
                    // вычитываем тело и превращаем в LlmApiException с HTTP-статусом и сообщением,
                    // чтобы error-событие содержало и статус, и текст от апстрима.
                    response.bodyToMono(String::class.java)
                        .defaultIfEmpty("")
                        .map { body -> LlmApiException(response.statusCode().value(), extractErrorMessage(body)) }
                }
                .bodyToFlux(String::class.java)
                .timeout(Duration.ofSeconds(settings.timeoutSeconds()))
                .flatMapIterable { raw -> parseChunks(raw) }
                .concatMap { node -> processChunk(node, acc) }
                .concatWith(Flux.defer { Flux.fromIterable(emitPending(acc)) })
        }
    }

    private fun messageNode(m: LlmMessage): JsonNode {
        val n = om.createObjectNode()
            .put("role", m.role)
        if (m.content != null) n.put("content", m.content)
        if (!m.toolCalls.isNullOrEmpty()) {
            val arr = n.putArray("tool_calls")
            m.toolCalls.forEach { tc ->
                val c = arr.addObject()
                if (tc.id != null) c.put("id", tc.id)
                c.put("type", "function")
                val fn = c.putObject("function")
                fn.put("name", tc.name ?: "")
                fn.put("arguments", tc.arguments)
            }
        }
        if (m.toolCallId != null) n.put("tool_call_id", m.toolCallId)
        return n
    }

    private fun toolNode(t: ToolDefinition): JsonNode {
        val n = om.createObjectNode()
            .put("type", "function")
        val fn = n.putObject("function")
        fn.put("name", t.name)
        fn.put("description", t.description)
        fn.set<JsonNode>("parameters", t.parameters)
        return n
    }

    /**
     * Разбирает сырой фрагмент потока в JSON-узлы. Устойчив к двум форматам доставки:
     * - сырые SSE-строки ("data: {...}", "event: message", "[DONE]") — когда тело декодируется построчно;
     * - уже распарсенные SSE-ридером payload'ы (голый JSON без префикса "data:") — так
     *   Spring 6.x отдаёт bodyToFlux(String) для text/event-stream.
     */
    private fun parseChunks(raw: String): List<JsonNode> =
        raw.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && it != "[DONE]" && !it.startsWith("event:") && !it.startsWith(":") }
            .map { line -> if (line.startsWith("data:")) line.removePrefix("data:").trim() else line }
            .filter { it.isNotEmpty() && it != "[DONE]" }
            .mapNotNull { line ->
                try {
                    om.readTree(line)
                } catch (e: Exception) {
                    null
                }
            }
            .toList()

    /** Сырые фрагменты tool_calls, finish_reason и usage на время стрима. */
    private class Pending {
        val toolCalls = sortedMapOf<Int, MutableList<String>>() // index -> [id:, name:, args:]
        var finishReason: String? = null
        var usage: LlmUsage? = null
    }

    private fun processChunk(node: JsonNode, acc: Pending): Flux<LlmEvent> {
        // usage приходит в финальном чанке с пустым choices (stream_options.include_usage)
        val usageNode = node.path("usage")
        if (!usageNode.isMissingNode && !usageNode.isNull) {
            val input = usageNode.path("prompt_tokens").asInt(-1)
            val output = usageNode.path("completion_tokens").asInt(-1)
            if (input >= 0 && output >= 0) acc.usage = LlmUsage(input, output)
        }

        val choice = node.path("choices").firstOrNull() ?: return Flux.empty()
        val fr = choice.path("finish_reason").takeIf { !it.isMissingNode && !it.isNull }?.asText()
        if (fr != null) acc.finishReason = fr

        val out = mutableListOf<LlmEvent>()
        val delta = choice.path("delta")
        val content = delta.path("content").takeIf { it.isTextual }?.asText()
        if (!content.isNullOrEmpty()) out += LlmEvent.ContentDelta(content)

        val tcs = delta.path("tool_calls")
        if (tcs.isArray) {
            tcs.forEach { tc ->
                val idx = tc.path("index").asInt(0)
                val slot = acc.toolCalls.getOrPut(idx) { mutableListOf() }
                tc.path("id").takeIf { it.isTextual }?.asText()?.let { slot.add("id:$it") }
                val fn = tc.path("function")
                fn.path("name").takeIf { it.isTextual }?.asText()?.let { slot.add("name:$it") }
                fn.path("arguments").takeIf { it.isTextual }?.asText()?.let { slot.add("args:$it") }
            }
        }
        return if (out.isEmpty()) Flux.empty() else Flux.fromIterable(out)
    }

    private fun emitPending(acc: Pending): List<LlmEvent> {
        val out = mutableListOf<LlmEvent>()
        if (acc.toolCalls.isNotEmpty()) {
            val calls = acc.toolCalls.map { (idx, parts) ->
                var id: String? = null
                var name: String? = null
                val sb = StringBuilder()
                for (p in parts) {
                    when {
                        p.startsWith("id:") -> id = p.removePrefix("id:")
                        p.startsWith("name:") -> name = p.removePrefix("name:")
                        p.startsWith("args:") -> sb.append(p.removePrefix("args:"))
                    }
                }
                LlmToolCall(index = idx, id = id, name = name, arguments = sb.toString().ifEmpty { "{}" })
            }
            out += LlmEvent.ToolCallsComplete(calls)
        }
        out += LlmEvent.Finished(
            acc.finishReason ?: if (acc.toolCalls.isNotEmpty()) "tool_calls" else "stop",
            acc.usage,
        )
        return out
    }

    private fun normalizeBaseUrl(raw: String): String {
        var b = raw.trim().trimEnd('/')
        if (b.isNotEmpty() && !b.endsWith("/v1")) b = "$b/v1"
        return b
    }

    /**
     * Достаёт человекочитаемое сообщение из тела ошибки: предпочитает {error:{message}},
     * иначе — сырое тело (обрезанное), чтобы в error-событии было, что показать пользователю.
     */
    private fun extractErrorMessage(body: String): String {
        if (body.isBlank()) return "пустое тело ответа"
        return try {
            val node = om.readTree(body)
            node.path("error").path("message")
                .takeIf { it.isTextual && it.asText().isNotBlank() }
                ?.asText()
                ?: body.take(MAX_ERROR_BODY)
        } catch (e: Exception) {
            body.take(MAX_ERROR_BODY)
        }
    }

    private companion object {
        /** Ограничиваем размер тела ошибки, попадающего в событие/логи. */
        const val MAX_ERROR_BODY = 500
    }
}

/**
 * Ошибка LLM API с HTTP-статусом (к примеру 4xx) — перехватывается в AgentImpl
 * и превращается в понятное error-событие («Ошибка LLM API (HTTP <status>): <message>»).
 */
class LlmApiException(val status: Int, message: String) : RuntimeException(message)
