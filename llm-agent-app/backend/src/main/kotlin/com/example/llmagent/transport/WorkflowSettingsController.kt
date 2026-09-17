package com.example.llmagent.transport

import com.example.llmagent.config.WorkflowSettings
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

/**
 * Настройки human-in-the-loop воркфлоу (Day-14) — REST-эндпоинты БЕЗ SSE.
 *
 * Глобальная настройка приложения (app_settings, ключи `workflow.enabled` /
 * `workflow.mode`, см. WorkflowSettings):
 *   GET /api/workflow-settings → `{ enabled: bool, mode: "manual"|"auto" }`.
 *   PUT /api/workflow-settings → тело `{ enabled: bool, mode: "manual"|"auto" }`:
 *     400 — mode не в {manual,auto}; сохраняет оба ключа в app_settings; 200 — обновлённый.
 *
 * Если воркфлоу отключён (enabled=false), агент ведёт себя как раньше (без гейтинга).
 * Фронтенд грузит настройки при монтировании и сохраняет при изменении (toggle/режим).
 */
@RestController
class WorkflowSettingsController(
    private val workflowSettings: WorkflowSettings,
) {

    /** Тело PUT /api/workflow-settings: enabled — флаг, mode — "manual"|"auto". */
    data class WorkflowSettingsRequest(
        val enabled: Boolean?,
        val mode: String?,
    )

    /** Ответ GET/PUT /api/workflow-settings. */
    data class WorkflowSettingsResponse(
        val enabled: Boolean,
        val mode: String,
    )

    @GetMapping("/api/workflow-settings")
    fun get(): WorkflowSettingsResponse = WorkflowSettingsResponse(
        enabled = workflowSettings.isEnabled(),
        mode = workflowSettings.mode(),
    )

    @PutMapping("/api/workflow-settings")
    fun update(@RequestBody body: WorkflowSettingsRequest): WorkflowSettingsResponse {
        val enabled = body.enabled
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "enabled должен быть boolean")
        val mode = body.mode
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "mode должен быть строкой")
        if (mode !in WorkflowSettings.MODES) {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "mode должен быть ${WorkflowSettings.MODE_MANUAL} или ${WorkflowSettings.MODE_AUTO}, получено \"$mode\"",
            )
        }
        workflowSettings.set(enabled, mode)
        return WorkflowSettingsResponse(enabled, mode)
    }
}
