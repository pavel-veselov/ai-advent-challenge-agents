package com.example.llmagent.transport

import com.example.llmagent.agent.Agent
import com.example.llmagent.agent.AgentEvent
import com.example.llmagent.agent.ErrorEvent
import com.example.llmagent.agent.SessionStore
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.http.codec.ServerSentEvent
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import reactor.core.publisher.Flux
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * SSE-транспорт. Ничего не знает об LLM API — общается только с Agent.
 * Каждое событие оборачивается в контракт: {type, runId, stepId, timestamp, payload, sequence}.
 */
@RestController
class ChatController(
    private val agent: Agent,
    private val sessionStore: SessionStore,
    private val om: ObjectMapper,
) {

    companion object {
        private val log = LoggerFactory.getLogger(ChatController::class.java)
    }

    data class ChatRequest(val sessionId: String, val message: String)

    @PostMapping("/api/chat", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    fun chat(@RequestBody req: ChatRequest): Flux<ServerSentEvent<String>> {
        val runId = UUID.randomUUID().toString()
        // Day-12: сессия принадлежит проекту (chat_sessions) — проект достаём здесь для
        // лога/диагностики; WM агент ключует по projectId сам (см. AgentImpl.run).
        val projectId = sessionStore.getProjectId(req.sessionId)
        log.info("chat run={} session={} project={} message={}", runId, req.sessionId, projectId, req.message)
        val sequence = AtomicInteger(0)
        return agent.run(req.sessionId, req.message)
            .map { ev -> sse(ev, runId, sequence.getAndIncrement()) }
            // Страховка «поток не закрывается молча»: если из агента вылетела ошибка, минуя
            // обработчики (например сбой хранилища до входа в цикл), превращаем её в обычное
            // error-событие контракта, чтобы фронтенд не увидел голую сетевую ошибку «Failed to fetch».
            .onErrorResume { err ->
                log.error("chat run={} session={} failed: {}", runId, req.sessionId, err.message)
                Flux.just(sse(ErrorEvent(0, "Ошибка сервера: ${err.message}"), runId, sequence.getAndIncrement()))
            }
    }

    private fun sse(ev: AgentEvent, runId: String, seq: Int): ServerSentEvent<String> {
        val node = om.createObjectNode()
        node.put("type", ev.type)
        node.put("runId", runId)
        node.put("stepId", ev.stepId)
        node.put("timestamp", Instant.now().toString())
        node.set<JsonNode>("payload", om.valueToTree(ev.payload))
        node.put("sequence", seq)
        return ServerSentEvent.builder<String>(om.writeValueAsString(node))
            .event(ev.type)
            .build()
    }
}
