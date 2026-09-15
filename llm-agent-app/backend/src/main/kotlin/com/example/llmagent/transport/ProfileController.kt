package com.example.llmagent.transport

import com.example.llmagent.agent.Profile
import com.example.llmagent.agent.ProfileStore
import com.example.llmagent.config.AppSettingsStore
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

/**
 * Профили пользователя (персонализация агента) — REST-эндпоинты БЕЗ SSE.
 *
 * Справочник профилей — ГЛОБАЛЬНЫЙ (agent_profiles, вне проектов и сессий):
 *   GET    /api/profiles         — список профилей `[{id,name,position,responseFormat,preferences,constraints,...}]`.
 *   POST   /api/profiles         — создание, тело `{name, position?, responseFormat?, preferences?, constraints?}`:
 *                                  400 — пустое имя, 409 — имя уже занято; 200 — созданный профиль.
 *   PUT    /api/profiles/{id}    — обновление полей профиля; 404 — нет; 400 — пустое имя;
 *                                  409 — новое имя занято другим профилем.
 *   DELETE /api/profiles/{id}    — 200 `{deleted:true}`; 404 — нет профиля.
 *
 * Активный профиль — ГЛОБАЛЬНАЯ настройка приложения (app_settings, ключ `profile.active` =
 * id или строка "null"), НЕ флаг на строке профиля:
 *   GET /api/profiles/active → `{activeProfileId: Long?}` (нет ключа/мусор — null).
 *   PUT /api/profiles/active → тело `{profileId: Long?}`; 404 — профиль не существует;
 *                              сохраняет id или "null"; 200 `{activeProfileId}`.
 */
@RestController
class ProfileController(
    private val profileStore: ProfileStore,
    private val appSettingsStore: AppSettingsStore,
) {

    /** Тело POST/PUT /api/profiles: name обязателен (400), остальные поля опциональны. */
    data class ProfileRequest(
        val name: String?,
        val position: String? = null,
        val responseFormat: String? = null,
        val preferences: String? = null,
        val constraints: String? = null,
    )

    /** Тело PUT /api/profiles/active: null — «Без профиля». */
    data class ActiveProfileRequest(val profileId: Long?)

    /** Ответ GET/PUT /api/profiles/active. */
    data class ActiveProfileResponse(val activeProfileId: Long?)

    data class DeleteResponse(val deleted: Boolean)

    @GetMapping("/api/profiles")
    fun profiles(): List<Profile> = profileStore.list()

    @PostMapping("/api/profiles")
    fun createProfile(@RequestBody body: ProfileRequest): Profile {
        val name = body.name?.trim()
        if (name.isNullOrBlank()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "name не должен быть пустым")
        }
        if (profileStore.findByName(name) != null) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "Профиль с именем «$name» уже существует")
        }
        val created = profileStore.create(
            name,
            body.position?.trim()?.takeIf { it.isNotEmpty() },
            body.responseFormat?.trim()?.takeIf { it.isNotEmpty() },
            body.preferences?.trim()?.takeIf { it.isNotEmpty() },
            body.constraints?.trim()?.takeIf { it.isNotEmpty() },
        )
        if (created.id == -1L) {
            throw ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Не удалось создать профиль")
        }
        return created
    }

    @PutMapping("/api/profiles/{id}")
    fun updateProfile(@PathVariable id: Long, @RequestBody body: ProfileRequest): Profile {
        val name = body.name?.trim()
        if (name.isNullOrBlank()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "name не должен быть пустым")
        }
        val existing = profileStore.findById(id)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Профиль не найден: $id")
        val sameName = profileStore.findByName(name)
        if (sameName != null && sameName.id != id) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "Профиль с именем «$name» уже существует")
        }
        if (!profileStore.update(
                id,
                name,
                body.position?.trim()?.takeIf { it.isNotEmpty() },
                body.responseFormat?.trim()?.takeIf { it.isNotEmpty() },
                body.preferences?.trim()?.takeIf { it.isNotEmpty() },
                body.constraints?.trim()?.takeIf { it.isNotEmpty() },
            )
        ) {
            throw ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Не удалось обновить профиль")
        }
        // created_at неизменен (обновляется только updated_at) — читаем свежую строку.
        return profileStore.findById(id) ?: existing
    }

    @DeleteMapping("/api/profiles/{id}")
    fun deleteProfile(@PathVariable id: Long): DeleteResponse {
        if (!profileStore.delete(id)) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "Профиль не найден: $id")
        }
        // Удалённый профиль больше не может быть активным: сбрасываем глобальный выбор.
        if (readActiveProfileId() == id) {
            appSettingsStore.save(ACTIVE_PROFILE_KEY, NO_PROFILE_VALUE)
        }
        return DeleteResponse(true)
    }

    @GetMapping("/api/profiles/active")
    fun activeProfile(): ActiveProfileResponse = ActiveProfileResponse(readActiveProfileId())

    @PutMapping("/api/profiles/active")
    fun setActiveProfile(@RequestBody body: ActiveProfileRequest): ActiveProfileResponse {
        val profileId = body.profileId
        if (profileId != null && profileStore.findById(profileId) == null) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "Профиль не найден: $profileId")
        }
        appSettingsStore.save(ACTIVE_PROFILE_KEY, profileId?.toString() ?: NO_PROFILE_VALUE)
        return ActiveProfileResponse(profileId)
    }

    /** Читает `profile.active` из app_settings: id или null (нет ключа / "null" / мусор). */
    private fun readActiveProfileId(): Long? =
        appSettingsStore.get(ACTIVE_PROFILE_KEY)?.trim()?.takeIf { it != NO_PROFILE_VALUE }?.toLongOrNull()

    private companion object {
        const val ACTIVE_PROFILE_KEY = "profile.active"
        const val NO_PROFILE_VALUE = "null"
    }
}
