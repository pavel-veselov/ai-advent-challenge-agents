package com.example.llmagent.config

import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource

/** SQLite-хранилище настроек (app_settings): сохранённые строки переживают «перезапуск». */
class JdbcAppSettingsStoreTest {

    @TempDir
    lateinit var tmpDir: Path

    @Test
    fun `saved rows survive store reinitialization on the same db file`() {
        val dbFile = tmpDir.resolve("app-settings.db")

        val firstRun = store(dbFile)
        firstRun.save("model", "glm-5.3-flash")
        firstRun.save("maxTokens", "512")

        // «Перезапуск»: новое хранилище поверх того же SQLite-файла — строки на месте
        val secondRun = store(dbFile)
        assertEquals("glm-5.3-flash", secondRun.get("model"))
        assertEquals(
            mapOf("model" to "glm-5.3-flash", "maxTokens" to "512"),
            secondRun.all(),
        )
    }

    @Test
    fun `save overwrites existing key`() {
        val store = store(tmpDir.resolve("overwrite.db"))
        store.save("model", "qwen3.8-27b")
        store.save("model", "deepseek-v4-flash")

        assertEquals("deepseek-v4-flash", store.get("model"))
        assertEquals(1, store.all().size)
    }

    @Test
    fun `all returns empty map for fresh database`() {
        assertTrue(store(tmpDir.resolve("fresh.db")).all().isEmpty())
    }

    @Test
    fun `get returns null for missing key`() {
        assertEquals(null, store(tmpDir.resolve("missing.db")).get("temperature"))
    }

    /** JdbcAppSettingsStore сам гарантирует схему (CREATE TABLE IF NOT EXISTS), как в проде. */
    private fun store(dbFile: Path): JdbcAppSettingsStore {
        val ds = DriverManagerDataSource()
        ds.setDriverClassName("org.sqlite.JDBC")
        // прямые слэши — чтобы jdbc:sqlite корректно распарсил путь на Windows
        ds.url = "jdbc:sqlite:${dbFile.toAbsolutePath().toString().replace('\\', '/')}"
        return JdbcAppSettingsStore(JdbcTemplate(ds))
    }
}
