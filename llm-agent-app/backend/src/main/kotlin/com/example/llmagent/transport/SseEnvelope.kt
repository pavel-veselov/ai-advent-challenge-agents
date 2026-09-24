package com.example.llmagent.transport

import com.example.llmagent.agent.AgentEvent
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.http.codec.ServerSentEvent
import java.time.Instant

/**
 * Общий конверт SSE-события контракта: {type, runId, stepId, timestamp, payload, sequence}.
 * Используется и [ChatController] (/api/chat), и [TaskStateController] (/continue) — чтобы не
 * дублировать один и тот же маппинг и не разойтись между потоками. Тело — JSON-строка события, у
 * самого ServerSentEvent поле `event:` = тип события (клиент разделяет потоки по типу).
 */
object SseEnvelope {

    fun sse(om: ObjectMapper, ev: AgentEvent, runId: String, seq: Int): ServerSentEvent<String> {
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
