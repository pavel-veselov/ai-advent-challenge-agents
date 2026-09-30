package com.example.llmagent.kb

import com.example.llmagent.agent.SqliteTestSupport
import com.example.llmagent.config.AppSettingsStore
import com.example.llmagent.config.JdbcAppSettingsStore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * Юнит-тесты [KbRagSettingsService]: дефолты на пустом хранилище, roundtrip персиста
 * в `app_settings`, порченые значения -> дефолты, валидация update.
 */
class KbRagSettingsServiceTest {

    @TempDir
    lateinit var tempDir: Path

    /** In-memory стор: как в DynamicLlmSettingsTest — без сети и без БД. */
    private class InMemoryStore : AppSettingsStore {
        private val map = mutableMapOf<String, String>()
        override fun save(key: String, value: String) {
            map[key] = value
        }
        override fun get(key: String): String? = map[key]
        override fun all(): Map<String, String> = map.toMap()
    }

    /** Реальный SQLite-стор поверх временного файла БД (как в KbRagServiceTest). */
    private fun sqliteStore(dbFile: String): AppSettingsStore =
        JdbcAppSettingsStore(SqliteTestSupport.jdbc(tempDir.resolve(dbFile)))

    // --- Дефолты ---

    @Test
    fun `defaults on empty in-memory store`() {
        val s = KbRagSettingsService(InMemoryStore())
        assertEquals(
            KbRagSettings(filterEnabled = false, minScore = 0.35, candidateK = 8, topK = 4, rewriteEnabled = false),
            s.load(),
        )
    }

    @Test
    fun `defaults on empty sqlite db`() {
        val s = KbRagSettingsService(sqliteStore("kb-settings-empty.db"))
        assertEquals(KbRagSettings(), s.load())
    }

    // --- Roundtrip персиста ---

    @Test
    fun `update persists every key and new instance on same store picks up saved values`() {
        val store = InMemoryStore()
        val service = KbRagSettingsService(store)
        val req = KbRagSettings(filterEnabled = true, minScore = 0.5, candidateK = 20, topK = 5, rewriteEnabled = true)
        val saved = service.update(req)
        assertEquals(req, saved, "update возвращает сохранённые настройки")

        assertEquals("true", store.get("kb.filterEnabled"))
        assertEquals("0.5", store.get("kb.minScore"))
        assertEquals("20", store.get("kb.candidateK"))
        assertEquals("5", store.get("kb.topK"))
        assertEquals("true", store.get("kb.rewriteEnabled"))

        // «Перезапуск backend»: новый сервис над тем же стором видит сохранённые значения
        assertEquals(req, KbRagSettingsService(store).load())
    }

    @Test
    fun `roundtrip survives real sqlite persistence`() {
        val store = sqliteStore("kb-settings-roundtrip.db")
        val req = KbRagSettings(filterEnabled = true, minScore = 0.8, candidateK = 50, topK = 10, rewriteEnabled = true)
        KbRagSettingsService(store).update(req)
        assertEquals(req, KbRagSettingsService(store).load())
    }

    // --- Порченые значения -> дефолт (load не бросает) ---

    @Test
    fun `corrupt filterEnabled falls back to default`() {
        val store = InMemoryStore()
        store.save("kb.filterEnabled", "not-a-bool")
        assertEquals(false, KbRagSettingsService(store).load().filterEnabled)
    }

    @Test
    fun `corrupt or out-of-range minScore falls back to default`() {
        val store = InMemoryStore()
        for (raw in listOf("abc", "2.5", "-0.1")) {
            store.save("kb.minScore", raw)
            assertEquals(0.35, KbRagSettingsService(store).load().minScore, "minScore='$raw' -> дефолт")
        }
    }

    @Test
    fun `corrupt or out-of-range candidateK falls back to default`() {
        val store = InMemoryStore()
        for (raw in listOf("abc", "0", "101", "-5")) {
            store.save("kb.candidateK", raw)
            assertEquals(8, KbRagSettingsService(store).load().candidateK, "candidateK='$raw' -> дефолт")
        }
    }

    @Test
    fun `corrupt or out-of-range topK falls back to default`() {
        val store = InMemoryStore()
        for (raw in listOf("abc", "0", "-3")) {
            store.save("kb.topK", raw)
            assertEquals(4, KbRagSettingsService(store).load().topK, "topK='$raw' -> дефолт")
        }
    }

    @Test
    fun `corrupt rewriteEnabled falls back to default`() {
        val store = InMemoryStore()
        store.save("kb.rewriteEnabled", "yes")
        assertEquals(false, KbRagSettingsService(store).load().rewriteEnabled)
    }

    // --- Валидация update ---

    @Test
    fun `validation rejects minScore outside 0 to 1 with russian message`() {
        val service = KbRagSettingsService(InMemoryStore())
        for (bad in listOf(-0.01, 1.01)) {
            val e = assertThrows(IllegalArgumentException::class.java) {
                service.update(KbRagSettings(minScore = bad))
            }
            assertTrue(e.message!!.contains("minScore"), e.message)
        }
    }

    @Test
    fun `validation rejects candidateK outside 1 to 100`() {
        val service = KbRagSettingsService(InMemoryStore())
        for (bad in listOf(0, 101)) {
            val e = assertThrows(IllegalArgumentException::class.java) {
                service.update(KbRagSettings(candidateK = bad))
            }
            assertTrue(e.message!!.contains("candidateK"), e.message)
        }
    }

    @Test
    fun `validation rejects topK outside 1 to candidateK`() {
        val service = KbRagSettingsService(InMemoryStore())
        for (bad in listOf(0, 9)) {
            val e = assertThrows(IllegalArgumentException::class.java) {
                service.update(KbRagSettings(topK = bad, candidateK = 8))
            }
            assertTrue(e.message!!.contains("topK"), e.message)
        }
    }

    @Test
    fun `failed validation persists nothing`() {
        val store = InMemoryStore()
        val service = KbRagSettingsService(store)
        assertThrows(IllegalArgumentException::class.java) {
            service.update(KbRagSettings(minScore = 2.0))
        }
        assertTrue(store.all().isEmpty(), "при ошибке валидации ничего не персистится")
        assertEquals(KbRagSettings(), service.load())
    }

    @Test
    fun `accepts boundary values and returns saved settings`() {
        val service = KbRagSettingsService(InMemoryStore())
        val low = KbRagSettings(minScore = 0.0, candidateK = 1, topK = 1)
        assertEquals(low, service.update(low))
        val high = KbRagSettings(minScore = 1.0, candidateK = 100, topK = 100)
        assertEquals(high, service.update(high))
    }
}
