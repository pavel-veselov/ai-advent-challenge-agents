package com.example.llmagent.config

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component

/**
 * Состояние «включена/отключена» для каждой модели каталога ([LlmCatalog]).
 * Модель можно запретить к выбору в PUT /api/llm-settings и скрыть из активного
 * использования, не удаляя из каталога.
 *
 * Персистится в SQLite-таблице `app_models` и переживает перезапуск backend.
 */
interface ModelEnabledStore {
    fun setEnabled(id: String, enabled: Boolean)

    /** true, если модель включена. Строка в таблице отсутствует → модель включена (дефолт). */
    fun isEnabled(id: String): Boolean

    /** Состояния всех моделей, у которых хоть раз менялось (отсутствующие = включены по умолчанию). */
    fun all(): Map<String, Boolean>
}

/**
 * Реализация поверх SQLite через JdbcTemplate. Схема (`CREATE TABLE IF NOT EXISTS`)
 * задана в schema.sql и выполняется при каждом старте; здесь — страховочное создание
 * для старых файлов БД (тот же приём, что JdbcAppSettingsStore).
 */
@Component
class JdbcModelEnabledStore(private val jdbc: JdbcTemplate) : ModelEnabledStore {

    init {
        jdbc.execute(
            """
            CREATE TABLE IF NOT EXISTS app_models (
                id      TEXT PRIMARY KEY,
                enabled INTEGER NOT NULL
            )
            """.trimIndent()
        )
    }

    override fun setEnabled(id: String, enabled: Boolean) {
        jdbc.update(
            "INSERT OR REPLACE INTO app_models (id, enabled) VALUES (?, ?)",
            id,
            if (enabled) 1 else 0,
        )
    }

    override fun isEnabled(id: String): Boolean =
        jdbc.query("SELECT enabled FROM app_models WHERE id = ?", { rs, _ -> rs.getInt("enabled") != 0 }, id)
            .firstOrNull()
            ?: true // нет строки — модель не отключалась, значит включена

    override fun all(): Map<String, Boolean> =
        jdbc.query("SELECT id, enabled FROM app_models") { rs, _ ->
            rs.getString("id") to (rs.getInt("enabled") != 0)
        }.toMap()
}
