package com.example.llmagent.config

import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource

/** SQLite-хранилище per-session настроек LLM (session_llm_settings): roundtrip переживает «перезапуск». */
class SessionLlmSettingsStoreTest {

    @TempDir
    lateinit var tmpDir: Path

    @Test
    fun `absent session returns null and has no overrides`() {
        val store = store(tmpDir.resolve("absent.db"))
        assertNull(store.get("ghost"))
        assertFalse(StoredSessionLlmSettings("ghost").hasOverrides)
    }

    @Test
    fun `save roundtrips all fields through reinitialization on the same db file`() {
        val dbFile = tmpDir.resolve("roundtrip.db")

        val first = store(dbFile)
        first.save(
            StoredSessionLlmSettings(
                sessionId = "s1",
                model = "qwen3.8-27b",
                temperature = "0.2",
                topP = "0.9",
                topK = "40",
                maxTokens = "500",
                timeoutSeconds = "30",
                priceInputPer1M = "0.25",
                priceOutputPer1M = "0.75",
                reasoningEnabled = "false",
            )
        )
        first.save(
            StoredSessionLlmSettings(
                sessionId = "s2",
                model = "glm-5.3-flash",
                maxTokens = "null", // legacy-маркер «сброс» — интерпретатор трактует как «не переопределено»
            )
        )

        // «Перезапуск»: новое хранилище поверх того же SQLite-файла — строки на месте
        val second = store(dbFile)
        assertEquals(
            StoredSessionLlmSettings(
                sessionId = "s1",
                model = "qwen3.8-27b",
                temperature = "0.2",
                topP = "0.9",
                topK = "40",
                maxTokens = "500",
                timeoutSeconds = "30",
                priceInputPer1M = "0.25",
                priceOutputPer1M = "0.75",
                reasoningEnabled = "false",
            ),
            second.get("s1"),
        )
        assertEquals(
            StoredSessionLlmSettings(sessionId = "s2", model = "glm-5.3-flash", maxTokens = "null"),
            second.get("s2"),
        )
        assertNull(second.get("no-such"))
    }

    @Test
    fun `save overwrites previous row`() {
        val store = store(tmpDir.resolve("overwrite.db"))
        store.save(StoredSessionLlmSettings("s", model = "qwen3.8-27b"))
        store.save(StoredSessionLlmSettings("s", maxTokens = "77", reasoningEnabled = "true"))
        assertEquals(
            StoredSessionLlmSettings("s", maxTokens = "77", reasoningEnabled = "true"),
            store.get("s"),
        )
    }

    @Test
    fun `remove deletes the row`() {
        val store = store(tmpDir.resolve("remove.db"))
        store.save(StoredSessionLlmSettings("s", model = "qwen3.8-27b"))
        store.remove("s")
        assertNull(store.get("s"))
    }

    @Test
    fun `old table without new columns is migrated in place preserving data`() {
        val dbFile = tmpDir.resolve("migrate.db")
        val ds = DriverManagerDataSource()
        ds.setDriverClassName("org.sqlite.JDBC")
        ds.url = "jdbc:sqlite:${dbFile.toAbsolutePath().toString().replace('\\', '/')}"
        val jdbc = JdbcTemplate(ds)
        // старая схема (была у предыдущей волны): без новых колонок
        jdbc.execute(
            """
            CREATE TABLE session_llm_settings (
                session_id        TEXT PRIMARY KEY,
                model             TEXT,
                max_tokens        TEXT,
                reasoning_enabled TEXT
            )
            """.trimIndent()
        )
        jdbc.update(
            "INSERT INTO session_llm_settings (session_id, model, max_tokens, reasoning_enabled) VALUES (?, ?, ?, ?)",
            "legacy", "qwen3.8-27b", "500", "false",
        )

        val migrated = JdbcSessionLlmSettingsStore(jdbc)
        val row = migrated.get("legacy")
        assertNotNull(row, "старые данные должны пережить миграцию")
        assertEquals("qwen3.8-27b", row!!.model)
        assertEquals("500", row.maxTokens)
        assertEquals("false", row.reasoningEnabled)

        // новые колонки добавлены — можно сохранять (и читать) полный набор полей
        migrated.save(StoredSessionLlmSettings("legacy", model = "glm-5.3-flash", temperature = "0.1"))
        assertEquals("glm-5.3-flash", migrated.get("legacy")!!.model)
        assertEquals("0.1", migrated.get("legacy")!!.temperature)
    }

    /** JdbcSessionLlmSettingsStore сам гарантирует схему (CREATE TABLE IF NOT EXISTS), как в проде. */
    private fun store(dbFile: Path): JdbcSessionLlmSettingsStore {
        val ds = DriverManagerDataSource()
        ds.setDriverClassName("org.sqlite.JDBC")
        // прямые слэши — чтобы jdbc:sqlite корректно распарсил путь на Windows
        ds.url = "jdbc:sqlite:${dbFile.toAbsolutePath().toString().replace('\\', '/')}"
        return JdbcSessionLlmSettingsStore(JdbcTemplate(ds))
    }
}
