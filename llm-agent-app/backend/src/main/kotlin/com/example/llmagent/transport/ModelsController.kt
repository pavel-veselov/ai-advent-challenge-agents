package com.example.llmagent.transport

import com.example.llmagent.config.DynamicLlmSettings
import com.example.llmagent.config.LlmCatalog
import com.example.llmagent.config.ModelEnabledStore
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

/**
 * Каталог моделей для фронтенда: список моделей с описаниями (order = [LlmCatalog.MODELS])
 * и включение/отключение отдельных моделей без удаления из каталога.
 *
 * GET  /api/models            — список всех моделей каталога с enabled.
 * PUT  /api/models/{id}/enabled — включить/отключить модель; 404 — неизвестный id;
 *                                400 — попытка отключить ТЕКУЩУЮ активную модель (llm-settings.model)
 *                                или невалидное тело.
 *
 * Состояние enabled персистится в SQLite (`app_models`) и переживает перезапуск backend.
 */
@RestController
class ModelsController(
    private val modelEnabled: ModelEnabledStore,
    private val dynamicSettings: DynamicLlmSettings,
) {

    @GetMapping("/api/models")
    fun models(): Map<String, Any> = mapOf(
        "models" to LlmCatalog.MODELS.map { spec ->
            mapOf(
                "id" to spec.id,
                "description" to spec.description,
                "enabled" to modelEnabled.isEnabled(spec.id),
            )
        }
    )

    @PutMapping("/api/models/{id}/enabled")
    fun setEnabled(@PathVariable id: String, @RequestBody body: Map<String, Any?>): Map<String, Any> {
        val spec = LlmCatalog.spec(id)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Модель не найдена: $id")

        val enabled = when (val v = body["enabled"]) {
            is Boolean -> v
            else -> throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "enabled: ожидалось true/false, получено '$v'"
            )
        }

        if (!enabled && dynamicSettings.model() == spec.id) {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Нельзя отключить активную модель '${spec.id}' — сначала выберите другую модель в настройках (llm-settings)."
            )
        }

        modelEnabled.setEnabled(spec.id, enabled)
        return mapOf("id" to spec.id, "enabled" to enabled)
    }
}
