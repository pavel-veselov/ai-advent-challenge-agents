package com.example.llmagent.config

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component

/**
 * Хранилище динамических настроек приложения в виде «ключ-значение»
 * (таблица `app_settings`). Значения — строки; интерпретацию и валидацию
 * выполняет [DynamicLlmSettings].
 */
interface AppSettingsStore {
    fun save(key: String, value: String)

    fun get(key: String): String?

    fun all(): Map<String, String>
}

/**
 * Реализация поверх SQLite через JdbcTemplate. Схема (`CREATE TABLE IF NOT EXISTS`)
 * задана в schema.sql и выполняется при каждом старте; здесь — страховочное создание
 * для старых файлов БД (тот же приём, что SessionStore.migrate()).
 */
@Component
class JdbcAppSettingsStore(private val jdbc: JdbcTemplate) : AppSettingsStore {

    init {
        jdbc.execute(
            """
            CREATE TABLE IF NOT EXISTS app_settings (
                key   TEXT PRIMARY KEY,
                value TEXT NOT NULL
            )
            """.trimIndent()
        )
    }

    override fun save(key: String, value: String) {
        jdbc.update("INSERT OR REPLACE INTO app_settings (key, value) VALUES (?, ?)", key, value)
    }

    override fun get(key: String): String? =
        jdbc.query("SELECT value FROM app_settings WHERE key = ?", { rs, _ -> rs.getString("value") }, key)
            .firstOrNull()

    override fun all(): Map<String, String> =
        jdbc.query("SELECT key, value FROM app_settings") { rs, _ ->
            rs.getString("key") to rs.getString("value")
        }.toMap()
}
