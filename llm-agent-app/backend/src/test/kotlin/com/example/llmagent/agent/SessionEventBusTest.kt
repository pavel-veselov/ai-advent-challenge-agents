package com.example.llmagent.agent

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Юнит-тесты фонового SSE-канала сессии (Day-17/18): push доставляет событие подписчику,
 *  sinkFor идемпотентен для одной сессии, activeSessions отражает подключённые, remove
 *  удаляет канал, push без подписчика не бросает. */
class SessionEventBusTest {

    @Test
    fun `push delivers event to subscriber`() {
        val bus = SessionEventBus()
        lateinit var got: AgentEvent
        val latch = CountDownLatch(1)
        bus.sinkFor("s1").asFlux().subscribe { got = it; latch.countDown() }

        bus.push("s1", SchedulerResult("привет"))

        assertTrue(latch.await(5, TimeUnit.SECONDS), "событие доставлено подписчику")
        assertEquals("scheduler_result", got.type, "тип события — scheduler_result")
        assertEquals("привет", (got as SchedulerResult).text, "текст результата")
    }

    @Test
    fun `sinkFor is idempotent for the same session`() {
        val bus = SessionEventBus()
        val a = bus.sinkFor("s1")
        val b = bus.sinkFor("s1")
        assertTrue(a === b, "один и тот же sink для одной сессии")
    }

    @Test
    fun `activeSessions reflects connected sessions and remove clears them`() {
        val bus = SessionEventBus()
        bus.sinkFor("s1")
        bus.sinkFor("s2")
        assertEquals(setOf("s1", "s2"), bus.activeSessions())

        bus.remove("s1")
        assertEquals(setOf("s2"), bus.activeSessions(), "после remove сессия исключается")
    }

    @Test
    fun `push without subscriber does not throw`() {
        val bus = SessionEventBus()
        bus.push("absent", SchedulerResult("текст"))
        assertTrue(bus.activeSessions().contains("absent"), "push создаёт канал сессии")
    }
}
