package com.example.llmagent.config

import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource

/** SQLite-хранилище состояния моделей (app_models): включение/отключение переживает «перезапуск». */
class JdbcModelEnabledStoreTest {

    @TempDir
    lateinit var tmpDir: Path

    @Test
    fun `absent model is enabled by default`() {
        assertTrue(store(tmpDir.resolve("fresh.db")).isEnabled("qwen3.8-27b"))
    }

    @Test
    fun `disabled flag survives store reinitialization on the same db file`() {
        val dbFile = tmpDir.resolve("models.db")

        val firstRun = store(dbFile)
        firstRun.setEnabled("deepseek-v4-flash", false)
        firstRun.setEnabled("glm-5.3-flash", true)

        // «Перезапуск»: новое хранилище поверх того же SQLite-файла — состояние на месте
        val secondRun = store(dbFile)
        assertEquals(false, secondRun.isEnabled("deepseek-v4-flash"))
        assertEquals(
            mapOf("deepseek-v4-flash" to false, "glm-5.3-flash" to true),
            secondRun.all(),
        )
    }

    @Test
    fun `disabled row also reflected after toggling back to enabled`() {
        val dbFile = tmpDir.resolve("toggle.db")

        val first = store(dbFile)
        first.setEnabled("qwen3.8-27b", false)
        assertEquals(false, first.isEnabled("qwen3.8-27b"))
        first.setEnabled("qwen3.8-27b", true)
        assertEquals(true, first.isEnabled("qwen3.8-27b"))

        val second = store(dbFile)
        assertEquals(true, second.isEnabled("qwen3.8-27b"), "повторное включение тоже переживает рестарт")
    }

    @Test
    fun `all returns empty map for fresh database`() {
        assertTrue(store(tmpDir.resolve("fresh2.db")).all().isEmpty())
    }

    /** JdbcModelEnabledStore сам гарантирует схему (CREATE TABLE IF NOT EXISTS), как в проде. */
    private fun store(dbFile: Path): JdbcModelEnabledStore {
        val ds = DriverManagerDataSource()
        ds.setDriverClassName("org.sqlite.JDBC")
        // прямые слэши — чтобы jdbc:sqlite корректно распарсил путь на Windows
        ds.url = "jdbc:sqlite:${dbFile.toAbsolutePath().toString().replace('\\', '/')}"
        return JdbcModelEnabledStore(JdbcTemplate(ds))
    }
}
