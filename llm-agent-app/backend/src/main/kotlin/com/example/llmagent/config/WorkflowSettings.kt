package com.example.llmagent.config

import org.springframework.stereotype.Component

/**
 * Настройки human-in-the-loop воркфлоу (Day-14) поверх `app_settings` (таблица
 * `app_settings`, ключ-значение, переживают перезапуск backend). Два ключа:
 * - `workflow.enabled` = "true"/"false" (дефолт "false"): следовать воркфлоу
 *   planning → execution → validation → done (кнопки «Продолжить»/«Отмена»);
 * - `workflow.mode` = "manual"/"auto" (дефолт "manual"): manual — пауза на границе
 *   каждого этапа и подтверждение пользователем кнопками; auto — агент проходит все
 *   этапы подряд за один run без паузы.
 *
 * Интерпретация сырых строк (`get`) ленивая и валидна по умолчанию: мусор/отсутствие
 * ключа → false / "manual". Запись (`set`) сохраняет строки в `app_settings`.
 * Контроллер (WorkflowSettingsController) валидирует значение mode перед записью.
 */
@Component
class WorkflowSettings(
    private val store: AppSettingsStore,
) {

    /** Следовать воркфлоу? Отсутствие/мусор — false (агент ведёт себя как сегодня). */
    fun isEnabled(): Boolean = parseBoolean(store.get(KEY_ENABLED)) ?: false

    /** Режим: "manual" | "auto"; отсутствие/мусор — "manual". */
    fun mode(): String {
        val raw = store.get(KEY_MODE)
        return if (raw != null && raw in MODES) raw else MODE_MANUAL
    }

    /** Записывает оба ключа (значения уже нормализованы вызывающим кодом). */
    fun set(enabled: Boolean, mode: String) {
        store.save(KEY_ENABLED, enabled.toString())
        store.save(KEY_MODE, mode)
    }

    fun setEnabled(enabled: Boolean) {
        store.save(KEY_ENABLED, enabled.toString())
    }

    fun setMode(mode: String) {
        store.save(KEY_MODE, mode)
    }

    private fun parseBoolean(raw: String?): Boolean? = when (raw?.trim()?.lowercase()) {
        "true" -> true
        "false" -> false
        else -> null
    }

    companion object {
        const val KEY_ENABLED = "workflow.enabled"
        const val KEY_MODE = "workflow.mode"
        const val MODE_MANUAL = "manual"
        const val MODE_AUTO = "auto"

        /** Допустимые режимы воркфлоу. */
        val MODES: Set<String> = setOf(MODE_MANUAL, MODE_AUTO)
    }
}
