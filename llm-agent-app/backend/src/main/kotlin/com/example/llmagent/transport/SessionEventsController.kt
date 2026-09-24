package com.example.llmagent.transport

import com.example.llmagent.agent.SessionEventBus
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.http.MediaType
import org.springframework.http.codec.ServerSentEvent
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController
import reactor.core.publisher.Flux
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * Фоновый SSE-канал сессии (Day-17/18): подключившийся клиент (вкладка сессии) получает
 * события, которые агент кладёт в [SessionEventBus] вне обычного run — например результат
 * периодической задачи планировщика ([SchedulerResult]). Поток живой: держится до отключения
 * клиента, после чего канал сессии удаляется из шины.
 *
 * Контракт событий — тот же конверт {type, runId, stepId, timestamp, payload, sequence},
 * что у /api/chat (см. SseEnvelope).
 */
@RestController
class SessionEventsController(
    private val bus: SessionEventBus,
    private val om: ObjectMapper,
) {

    @GetMapping("/api/sessions/{sessionId}/events", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    fun events(@PathVariable sessionId: String): Flux<ServerSentEvent<String>> {
        val runId = UUID.randomUUID().toString()
        val sequence = AtomicInteger(0)
        return bus.sinkFor(sessionId).asFlux()
            .map { ev -> SseEnvelope.sse(om, ev, runId, sequence.getAndIncrement()) }
            // Клиент ушёл (завершение/отмена) — канал сессии больше не нужен.
            .doOnTerminate { bus.remove(sessionId) }
            .doOnCancel { bus.remove(sessionId) }
    }
}
