package com.example.llmagent.agent

import java.nio.file.Path
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** Юнит-тесты стратегии контекста: дефолты, частичное обновление, валидация, resolve-правило. */
class SessionContextStoreTest {

    @TempDir
    lateinit var tmpDir: Path

    private fun store(): SessionContextStore =
        SessionContextStore(SqliteTestSupport.jdbc(tmpDir.resolve("ctx-${UUID.randomUUID()}.db")))

    @Test
    fun `GET returns defaults for unknown session`() {
        val s = store().get("s1")
        assertEquals("s1", s.sessionId)
        assertEquals("none", s.strategy)
        assertEquals(12, s.windowSize)
        assertNull(s.activeBranchId)
    }

    @Test
    fun `PUT partial update changes only provided fields`() {
        val c = store()
        val afterStrategy = c.update("s1", mapOf("strategy" to "sliding_window"))
        assertEquals("sliding_window", afterStrategy.strategy)
        assertEquals(12, afterStrategy.windowSize, "windowSize не должен измениться")

        val afterWindow = c.update("s1", mapOf("windowSize" to 5))
        assertEquals("sliding_window", afterWindow.strategy, "strategy не должна измениться")
        assertEquals(5, afterWindow.windowSize)

        val full = c.get("s1")
        assertEquals("sliding_window", full.strategy)
        assertEquals(5, full.windowSize)
    }

    @Test
    fun `PUT full update roundtrips through GET`() {
        val c = store()
        c.update("s1", mapOf("strategy" to "sticky_facts", "windowSize" to 3))
        val s = c.get("s1")
        assertEquals("sticky_facts", s.strategy)
        assertEquals(3, s.windowSize)
    }

    @Test
    fun `PUT accepts all five strategies and window boundary values`() {
        val c = store()
        for (strategy in listOf("none", "sliding_window", "sticky_facts", "summary", "branching")) {
            assertEquals(strategy, c.update("s1", mapOf("strategy" to strategy)).strategy)
        }
        assertEquals(1, c.update("s1", mapOf("windowSize" to 1)).windowSize)
        assertEquals(50, c.update("s1", mapOf("windowSize" to 50)).windowSize)
    }

    @Test
    fun `PUT validation rejects unknown strategy and bad windowSize`() {
        val c = store()
        assertThrows(ContextStrategyValidationException::class.java) {
            c.update("s1", mapOf("strategy" to "quantum"))
        }
        for (ws in listOf(0, -1, 51, "abc")) {
            assertThrows(ContextStrategyValidationException::class.java) {
                c.update("s1", mapOf("windowSize" to ws))
            }
        }
        // после серии ошибок — дефолты
        val s = c.get("s1")
        assertEquals("none", s.strategy)
        assertEquals(12, s.windowSize)
    }

    @Test
    fun `update preserves active branch id`() {
        val c = store()
        c.update("s1", mapOf("strategy" to "branching"))
        c.setActiveBranchId("s1", 42L)
        assertEquals(42L, c.get("s1").activeBranchId)

        // PUT стратегии не должен сбрасывать активную ветку
        c.update("s1", mapOf("strategy" to "sliding_window"))
        val s = c.get("s1")
        assertEquals("sliding_window", s.strategy)
        assertEquals(42L, s.activeBranchId)
    }

    @Test
    fun `remove clears strategy row`() {
        val c = store()
        c.update("s1", mapOf("strategy" to "branching"))
        c.setActiveBranchId("s1", 7L)
        c.remove("s1")
        val s = c.get("s1")
        assertEquals("none", s.strategy)
        assertNull(s.activeBranchId)
    }

    @Test
    fun `resolve falls back to summary for enabled legacy compression and honors explicit strategy`() {
        val c = store()
        // строка отсутствует ('none') + сжатие выключено → none
        assertEquals("none", c.resolve("s1", compressionEnabled = false))
        // 'none' + сжатие включено (legacy-сессии) → summary — старое поведение сохраняется
        assertEquals("summary", c.resolve("s1", compressionEnabled = true))
        // явная стратегия побеждает сжатие в любую сторону
        c.update("s1", mapOf("strategy" to "sliding_window"))
        assertEquals("sliding_window", c.resolve("s1", compressionEnabled = true))
        c.update("s1", mapOf("strategy" to "summary"))
        assertEquals("summary", c.resolve("s1", compressionEnabled = false))
        // явный 'none' со включённым сжатием — всё равно summary (legacy-правило не отключено)
        c.update("s1", mapOf("strategy" to "none"))
        assertEquals("summary", c.resolve("s1", compressionEnabled = true))
    }
}
