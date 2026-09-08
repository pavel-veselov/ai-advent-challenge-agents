package com.example.llmagent.agent

import com.example.llmagent.config.AgentProperties
import com.example.llmagent.config.LlmSettingsProvider
import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.flux
import org.slf4j.LoggerFactory
import reactor.core.publisher.Flux

/**
 * Цикл агента: запрос -> LLM -> при tool_calls выполнить инструмент и вернуть наблюдение
 * в LLM -> повторять до финального ответа. Лимит итераций защищает от бесконечного цикла.
 */
class AgentImpl(
    private val llmClient: LlmClient,
    private val toolRegistry: ToolRegistry,
    private val sessionStore: SessionStore,
    private val agentProperties: AgentProperties,
    private val settingsProvider: LlmSettingsProvider,
    private val om: ObjectMapper,
) : Agent {

    private val log = LoggerFactory.getLogger(AgentImpl::class.java)

    override fun run(sessionId: String, userMessage: String): Flux<AgentEvent> = flux {
        sessionStore.append(sessionId, "user", userMessage)
        val messages = mutableListOf<LlmMessage>()
        messages += LlmMessage("system", SYSTEM_PROMPT)
        sessionStore.get(sessionId).forEach { m -> messages.add(LlmMessage(m.role, m.content)) }

        val toolCounters = mutableMapOf<String, Int>()
        var iteration = 0
        var errorIdx = 0

        send(AgentStarted(userMessage, settingsProvider.settings()))

        try {
            while (true) {
                iteration++
                if (iteration > agentProperties.maxToolCallIterations) {
                    val msg = "Превышен лимит итераций агента (${agentProperties.maxToolCallIterations})"
                    log.warn("session={} {}", sessionId, msg)
                    send(ErrorEvent(errorIdx++, msg))
                    return@flux
                }

                send(LlmRequestStarted(iteration, promptSnapshot(messages)))
                val text = StringBuilder()
                var toolCalls: List<LlmToolCall> = emptyList()
                var finishReason = "stop"
                var usage: LlmUsage? = null

                val events = llmClient.streamChat(messages, toolRegistry.definitions())
                    .collectList()
                    .awaitSingle()
                for (e in events) {
                    when (e) {
                        is LlmEvent.ContentDelta -> {
                            text.append(e.delta)
                            send(LlmToken(iteration, e.delta))
                        }
                        is LlmEvent.ToolCallsComplete -> toolCalls = e.toolCalls
                        is LlmEvent.Finished -> {
                            finishReason = e.finishReason
                            usage = e.usage
                        }
                    }
                }
                send(LlmResponseFinished(iteration, finishReason, usage))

                if (finishReason == "tool_calls" && toolCalls.isNotEmpty()) {
                    messages.add(LlmMessage("assistant", text.toString().ifEmpty { null }, toolCalls = toolCalls))
                    for (tc in toolCalls) {
                        val name = tc.name ?: "unknown"
                        val idx = toolCounters[name] ?: 0
                        toolCounters[name] = idx + 1
                        val args = parseArgs(tc.arguments)
                        send(ToolCallStarted(name, idx, args))

                        val tool = toolRegistry.get(name)
                        val result = if (tool == null) {
                            ToolResult("Неизвестный инструмент: $name", true)
                        } else {
                            try {
                                tool.execute(args)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                log.warn("session={} tool {} failed", sessionId, name, e)
                                ToolResult("Ошибка инструмента: ${e.message}", true)
                            }
                        }
                        send(ToolCallFinished(name, idx, if (result.isError) "error" else "success", result.result))
                        messages.add(LlmMessage("tool", result.result, toolCallId = tc.id))
                    }
                } else if (finishReason == "error" || finishReason == "length") {
                    val msg = "Ошибка LLM (finishReason=$finishReason)"
                    send(ErrorEvent(errorIdx++, msg))
                    return@flux
                } else {
                    val finalText = text.toString()
                    sessionStore.append(sessionId, "assistant", finalText)
                    send(AgentFinished(finalText))
                    return@flux
                }
            }
        } catch (e: CancellationException) {
            log.info("Run cancelled for session {}", sessionId)
            throw e
        } catch (e: Exception) {
            log.error("Agent run failed for session {}: {}", sessionId, e.message)
            try {
                send(ErrorEvent(errorIdx++, "Ошибка агента: ${e.message}"))
            } catch (ex: Exception) {
                log.debug("Downstream closed while sending error event")
            }
        }
    }

    /** Снимок промпта для события llm_request_started: точный список сообщений, ушедший в LLM. */
    private fun promptSnapshot(messages: List<LlmMessage>): List<Map<String, String>> =
        messages.map { m ->
            val content: String = m.content
                ?: m.toolCalls
                    ?.joinToString("; ") { tc -> tc.name ?: "unknown" }
                    ?.let { names -> "[вызов инструмента: $names]" }
                ?: ""
            mapOf("role" to m.role, "content" to content)
        }

    private fun parseArgs(arguments: String?): Map<String, Any?> {
        if (arguments.isNullOrBlank()) return emptyMap()
        return try {
            val node = om.readTree(arguments)
            if (node == null || !node.isObject) {
                emptyMap()
            } else {
                node.fields().asSequence().associate { (k, v) -> k to om.convertValue(v, Any::class.java) }
            }
        } catch (e: Exception) {
            emptyMap()
        }
    }

    companion object {
        private const val SYSTEM_PROMPT =
            "Ты — полезный ассистент. Отвечай кратко и по делу. " +
                "Используй доступные инструменты, когда это нужно для точного ответа " +
                "(арифметические вычисления, текущие дата и время)."
    }
}
