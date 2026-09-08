package com.example.llmagent.agent

import com.example.llmagent.config.LlmProperties
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
 */
class GpuStackLlmClient(
    private val props: LlmProperties,
    private val om: ObjectMapper,
) : LlmClient {

    private val baseUrl = normalizeBaseUrl(props.baseUrl)

    private val webClient = WebClient.builder()
        .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer ${props.apiKey}")
        .baseUrl(baseUrl)
        .build()

    override fun streamChat(messages: List<LlmMessage>, tools: List<ToolDefinition>): Flux<LlmEvent> {
        val body = om.createObjectNode()
            .put("model", props.model)
            .put("temperature", props.temperature)
            .put("top_p", props.topP)
            .put("stream", true)
            .put("tool_choice", "auto")
        // top_k и max_tokens отправляем только если заданы (>0): не все OpenAI-совместимые
        // бэкенды принимают top_k, а max_tokens=0 бессмыслен.
        props.topK?.takeIf { it > 0 }?.let { body.put("top_k", it) }
        props.maxTokens?.takeIf { it > 0 }?.let { body.put("max_tokens", it) }
        body.putObject("stream_options").put("include_usage", true)
        val msgArr = body.putArray("messages")
        messages.forEach { msgArr.add(messageNode(it)) }
        val toolsArr = body.putArray("tools")
        tools.forEach { toolsArr.add(toolNode(it)) }

        return Flux.defer {
            val acc = Pending()
            webClient.post()
                .uri("/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue(body)
                .retrieve()
                .bodyToFlux(String::class.java)
                .timeout(Duration.ofSeconds(props.timeoutSeconds))
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
}
