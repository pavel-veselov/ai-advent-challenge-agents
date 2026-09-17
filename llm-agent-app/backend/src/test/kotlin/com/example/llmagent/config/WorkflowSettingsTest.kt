package com.example.llmagent.config

import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource

/**
 * Юнит-тесты WorkflowSettings (Day-14, human-in-the-loop воркфлоу): чтение ключей
 * `workflow.enabled` / `workflow.mode` из app_settings с дефолтами (false / manual),
 * запись через set и переживание «перезапуска» хранилища на том же файле БД.
 */
class WorkflowSettingsTest {

    @TempDir
    lateinit var tmpDir: Path

    private fun settings(dbFile: Path): WorkflowSettings {
        val ds = DriverManagerDataSource()
        ds.setDriverClassName("org.sqlite.JDBC")
        ds.url = "jdbc:sqlite:${dbFile.toAbsolutePath().toString().replace('\\', '/')}"
        return WorkflowSettings(JdbcAppSettingsStore(JdbcTemplate(ds)))
    }

    @Test
    fun `defaults are disabled and manual`() {
        val s = settings(tmpDir.resolve("ws-default.db"))
        assertFalse(s.isEnabled(), "воркфлоу по умолчанию выключен")
        assertEquals(WorkflowSettings.MODE_MANUAL, s.mode(), "режим по умолчанию — manual")
    }

    @Test
    fun `set persists both keys and read back`() {
        val s = settings(tmpDir.resolve("ws-set.db"))
        s.set(true, WorkflowSettings.MODE_AUTO)
        assertTrue(s.isEnabled())
        assertEquals(WorkflowSettings.MODE_AUTO, s.mode())
    }

    @Test
    fun `setEnabled and setMode update individual keys`() {
        val s = settings(tmpDir.resolve("ws-individual.db"))
        s.setEnabled(true)
        s.setMode(WorkflowSettings.MODE_AUTO)
        assertTrue(s.isEnabled())
        assertEquals(WorkflowSettings.MODE_AUTO, s.mode())
    }

    @Test
    fun `mode falls back to manual on garbage`() {
        val s = settings(tmpDir.resolve("ws-garbage.db"))
        // запишем мусор напрямую в app_settings
        val store = JdbcAppSettingsStore(jdbc(tmpDir.resolve("ws-garbage.db")))
        store.save(WorkflowSettings.KEY_MODE, "похуй")
        store.save(WorkflowSettings.KEY_ENABLED, "maybe")
        assertFalse(s.isEnabled(), "мусор в enabled → false")
        assertEquals(WorkflowSettings.MODE_MANUAL, s.mode(), "мусор в mode → manual")
    }

    @Test
    fun `settings survive reinitialization on same db file`() {
        val dbFile = tmpDir.resolve("ws-restart.db")
        val first = settings(dbFile)
        first.set(true, WorkflowSettings.MODE_MANUAL)
        // «Перезапуск» хранилища
        val second = settings(dbFile)
        assertTrue(second.isEnabled())
        assertEquals(WorkflowSettings.MODE_MANUAL, second.mode())
    }

    private fun jdbc(dbFile: Path): JdbcTemplate {
        val ds = DriverManagerDataSource()
        ds.setDriverClassName("org.sqlite.JDBC")
        ds.url = "jdbc:sqlite:${dbFile.toAbsolutePath().toString().replace('\\', '/')}"
        return JdbcTemplate(ds)
    }
}
