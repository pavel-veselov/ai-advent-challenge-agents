package com.example.llmagent.agent

import java.time.OffsetDateTime
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component

/** Профиль пользователя (персонализация агента): должность, формат ответа, предпочтения, ограничения. */
data class Profile(
    val id: Long,
    val name: String,
    val position: String?,
    val responseFormat: String?,
    val preferences: String?,
    val constraints: String?,
    val createdAt: String,
    val updatedAt: String,
)

/**
 * Персистентность профилей пользователя (таблица `agent_profiles`): ГЛОБАЛЬНЫЙ справочник
 * профилей — не скоупится ни по проекту, ни по сессии. name уникален (UNIQUE): повторное
 * создание того же имени не удается (create вернёт id = -1). Активный профиль выбирается
 * ОТДЕЛЬНО — ключ `profile.active` в app_settings (см. ProfileController), здесь его нет.
 * Текстовые поля обрезаются: name до 120, остальные до 2000 символов.
 *
 * Схема — в schema.sql; здесь — страховочное создание для старых файлов БД (тот же
 * приём, что LongTermMemoryStore). Все методы fail-open: сбой БД не роняет агент —
 * warn в лог и дефолт (null-профиль / false / id=-1 — сбой операции).
 */
@Component
class ProfileStore(private val jdbc: JdbcTemplate) {

    private val log = LoggerFactory.getLogger(ProfileStore::class.java)

    init {
        jdbc.execute(
            """
            CREATE TABLE IF NOT EXISTS agent_profiles (
                id              INTEGER PRIMARY KEY AUTOINCREMENT,
                name            TEXT NOT NULL UNIQUE,
                position        TEXT,
                response_format TEXT,
                preferences     TEXT,
                constraints     TEXT,
                created_at      TEXT NOT NULL,
                updated_at      TEXT NOT NULL
            )
            """.trimIndent()
        )
    }

    /** Все профили по порядку создания (ORDER BY id); сбой БД — пустой список. */
    fun list(): List<Profile> {
        return try {
            jdbc.query(
                """
                SELECT id, name, position, response_format, preferences, constraints, created_at, updated_at
                FROM agent_profiles ORDER BY id
                """.trimIndent(),
                { rs, _ -> mapRow(rs) },
            )
        } catch (e: Exception) {
            log.warn("Profile.list() не удался: {}", e.message)
            emptyList()
        }
    }

    /** Профиль по id; null — нет такой записи (или сбой БД). */
    fun findById(id: Long): Profile? {
        return try {
            jdbc.query(
                """
                SELECT id, name, position, response_format, preferences, constraints, created_at, updated_at
                FROM agent_profiles WHERE id = ?
                """.trimIndent(),
                { rs, _ -> mapRow(rs) },
                id,
            ).firstOrNull()
        } catch (e: Exception) {
            log.warn("Profile.findById({}) не удался: {}", id, e.message)
            null
        }
    }

    /** Профиль по имени (UNIQUE-колонка); null — нет такой записи (или сбой БД). */
    fun findByName(name: String): Profile? {
        return try {
            jdbc.query(
                """
                SELECT id, name, position, response_format, preferences, constraints, created_at, updated_at
                FROM agent_profiles WHERE name = ?
                """.trimIndent(),
                { rs, _ -> mapRow(rs) },
                name,
            ).firstOrNull()
        } catch (e: Exception) {
            log.warn("Profile.findByName({}) не удался: {}", name, e.message)
            null
        }
    }

    /**
     * Создаёт профиль. Возвращает созданную строку из БД; имя уже занято (UNIQUE) или
     * сбой БД — id = -1 (fail-open, не бросает; тот же приём, что upsert LTM).
     */
    fun create(
        name: String,
        position: String?,
        responseFormat: String?,
        preferences: String?,
        constraints: String?,
    ): Profile {
        val now = OffsetDateTime.now().toString()
        val safeName = name.take(NAME_MAX_LENGTH)
        try {
            jdbc.update(
                """
                INSERT INTO agent_profiles
                    (name, position, response_format, preferences, constraints, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                safeName,
                position?.take(TEXT_MAX_LENGTH),
                responseFormat?.take(TEXT_MAX_LENGTH),
                preferences?.take(TEXT_MAX_LENGTH),
                constraints?.take(TEXT_MAX_LENGTH),
                now, now,
            )
            return jdbc.query(
                """
                SELECT id, name, position, response_format, preferences, constraints, created_at, updated_at
                FROM agent_profiles WHERE name = ?
                """.trimIndent(),
                { rs, _ -> mapRow(rs) },
                safeName,
            ).first()
        } catch (e: Exception) {
            log.warn("Profile.create({}) не удался: {}", safeName, e.message)
            return Profile(
                id = -1,
                name = safeName,
                position = position?.take(TEXT_MAX_LENGTH),
                responseFormat = responseFormat?.take(TEXT_MAX_LENGTH),
                preferences = preferences?.take(TEXT_MAX_LENGTH),
                constraints = constraints?.take(TEXT_MAX_LENGTH),
                createdAt = now,
                updatedAt = now,
            )
        }
    }

    /**
     * Обновляет поля профиля по id (updated_at перезаписывается, created_at сохраняется).
     * true — обновлён; false — нет такого id, имя занято другим профилем или сбой БД.
     */
    fun update(
        id: Long,
        name: String,
        position: String?,
        responseFormat: String?,
        preferences: String?,
        constraints: String?,
    ): Boolean {
        return try {
            jdbc.update(
                """
                UPDATE agent_profiles
                SET name = ?, position = ?, response_format = ?, preferences = ?, constraints = ?, updated_at = ?
                WHERE id = ?
                """.trimIndent(),
                name.take(NAME_MAX_LENGTH),
                position?.take(TEXT_MAX_LENGTH),
                responseFormat?.take(TEXT_MAX_LENGTH),
                preferences?.take(TEXT_MAX_LENGTH),
                constraints?.take(TEXT_MAX_LENGTH),
                OffsetDateTime.now().toString(),
                id,
            ) > 0
        } catch (e: Exception) {
            log.warn("Profile.update({}) не удался: {}", id, e.message)
            false
        }
    }

    /** Удаляет профиль по id; true — удалён, false — нет такой записи (или сбой БД). */
    fun delete(id: Long): Boolean {
        return try {
            jdbc.update("DELETE FROM agent_profiles WHERE id = ?", id) > 0
        } catch (e: Exception) {
            log.warn("Profile.delete({}) не удался: {}", id, e.message)
            false
        }
    }

    /** Число профилей; сбой БД — 0. */
    fun count(): Long {
        return try {
            jdbc.queryForObject("SELECT COUNT(*) FROM agent_profiles", Long::class.java) ?: 0L
        } catch (e: Exception) {
            log.warn("Profile.count() не удался: {}", e.message)
            0L
        }
    }

    private fun mapRow(rs: java.sql.ResultSet): Profile = Profile(
        id = rs.getLong("id"),
        name = rs.getString("name"),
        position = rs.getString("position"),
        responseFormat = rs.getString("response_format"),
        preferences = rs.getString("preferences"),
        constraints = rs.getString("constraints"),
        createdAt = rs.getString("created_at"),
        updatedAt = rs.getString("updated_at"),
    )

    private companion object {
        const val NAME_MAX_LENGTH = 120
        const val TEXT_MAX_LENGTH = 2000
    }
}
