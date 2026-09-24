package com.example.llmagent.agent

import java.util.concurrent.ConcurrentHashMap
import org.springframework.stereotype.Component
import reactor.core.publisher.Sinks

/**
 * Фоновый SSE-канал событий сессии (Day-17/18): агент (планировщик) кладёт события
 * ([SchedulerResult]) в hot-sink сессии, а подключённый клиент получает их по SSE
 * (см. SessionEventsController). В отличие от основного /api/chat этот канал не запускает run —
 * только ДОСТАВЛЯЕТ уже произошедшие события активной сессии.
 *
 * Синк на сессию создаётся лениво ([sinkFor], computeIfAbsent) и удаляется при отключении
 * клиента ([remove]) — keep map маленькой, [activeSessions] отражает подключённые сессии.
 */
@Component
class SessionEventBus {

    private val sinks = ConcurrentHashMap<String, Sinks.Many<AgentEvent>>()

    /** Hot-sink сессии (multicast, backpressure-buffer); создаётся при первом обращении. */
    fun sinkFor(sessionId: String): Sinks.Many<AgentEvent> =
        sinks.computeIfAbsent(sessionId) { Sinks.many().multicast().onBackpressureBuffer() }

    /** Кладёт событие в канал сессии; без подключённого клиента событие молча отбрасывается. */
    fun push(sessionId: String, event: AgentEvent) {
        sinkFor(sessionId).tryEmitNext(event)
    }

    /** Удаляет канал сессии (клиент отключился). */
    fun remove(sessionId: String) {
        sinks.remove(sessionId)
    }

    /** Сессии с активными (созданными) каналами. */
    fun activeSessions(): Set<String> = sinks.keys
}
