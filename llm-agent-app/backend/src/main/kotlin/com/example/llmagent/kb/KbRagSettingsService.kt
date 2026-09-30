package com.example.llmagent.kb

import com.example.llmagent.config.AppSettingsStore

/**
 * Сервис настроек RAG (Day-23). Паттерн повторяет
 * [com.example.llmagent.config.DynamicLlmSettings]: дефолты -> сохранённые строки
 * таблицы `app_settings`, изменения применяются БЕЗ перезапуска backend.
 *
 * - [load] вызывается при каждом обращении к ретривалу; отсутствующее или порченое
 *   (нераспознаваемое либо вне допустимого диапазона) значение ключа даёт дефолт
 *   этого поля — load не бросает исключений на содержимом хранилища;
 * - [update] валидирует объект целиком, затем персистит КАЖДЫЙ ключ
 *   (изменения переживают рестарт) и возвращает сохранённые настройки;
 *   при нарушении валидации ничего не персистится, бросается
 *   [IllegalArgumentException] с русским сообщением (контроллер переводит в HTTP 400).
 */
class KbRagSettingsService(private val store: AppSettingsStore) {

    /** Текущие настройки: дефолты, перекрытые сохранёнными в `app_settings` значениями. */
    fun load(): KbRagSettings {
        val raw = store.all()
        return KbRagSettings(
            filterEnabled = raw[KEY_FILTER_ENABLED]?.let(::parseBoolean) ?: DEFAULT.filterEnabled,
            minScore = raw[KEY_MIN_SCORE]?.let(::parseMinScore) ?: DEFAULT.minScore,
            candidateK = raw[KEY_CANDIDATE_K]?.let(::parseCandidateK) ?: DEFAULT.candidateK,
            topK = raw[KEY_TOP_K]?.let(::parseTopK) ?: DEFAULT.topK,
            rewriteEnabled = raw[KEY_REWRITE_ENABLED]?.let(::parseBoolean) ?: DEFAULT.rewriteEnabled,
        )
    }

    /**
     * Полное обновление настроек. Валидация (русские сообщения, ошибка -> HTTP 400):
     * - `minScore` ∈ [0..1];
     * - `candidateK` ∈ [1..100];
     * - `topK` ∈ [1..candidateK].
     *
     * После валидации персистит каждый ключ в `app_settings`
     * и возвращает сохранённые настройки.
     */
    fun update(request: KbRagSettings): KbRagSettings {
        validate(request)
        store.save(KEY_FILTER_ENABLED, request.filterEnabled.toString())
        store.save(KEY_MIN_SCORE, request.minScore.toString())
        store.save(KEY_CANDIDATE_K, request.candidateK.toString())
        store.save(KEY_TOP_K, request.topK.toString())
        store.save(KEY_REWRITE_ENABLED, request.rewriteEnabled.toString())
        return request
    }

    private fun validate(s: KbRagSettings) {
        if (!s.minScore.isFinite() || s.minScore < 0.0 || s.minScore > 1.0) {
            throw IllegalArgumentException("minScore должен быть в диапазоне от 0 до 1: ${s.minScore}")
        }
        if (s.candidateK < 1 || s.candidateK > 100) {
            throw IllegalArgumentException("candidateK должен быть в диапазоне от 1 до 100: ${s.candidateK}")
        }
        if (s.topK < 1 || s.topK > s.candidateK) {
            throw IllegalArgumentException(
                "topK должен быть в диапазоне от 1 до candidateK=${s.candidateK}: ${s.topK}"
            )
        }
    }

    /** "true"/"false" (регистр и пробелы не важны); null — не распознано -> дефолт. */
    private fun parseBoolean(raw: String): Boolean? = when (raw.trim().lowercase()) {
        "true" -> true
        "false" -> false
        else -> null
    }

    private fun parseMinScore(raw: String): Double? =
        raw.trim().toDoubleOrNull()?.takeIf { it >= 0.0 && it <= 1.0 }

    private fun parseCandidateK(raw: String): Int? =
        raw.trim().toIntOrNull()?.takeIf { it in 1..100 }

    private fun parseTopK(raw: String): Int? =
        raw.trim().toIntOrNull()?.takeIf { it >= 1 }

    private companion object {
        const val KEY_FILTER_ENABLED = "kb.filterEnabled"
        const val KEY_MIN_SCORE = "kb.minScore"
        const val KEY_CANDIDATE_K = "kb.candidateK"
        const val KEY_TOP_K = "kb.topK"
        const val KEY_REWRITE_ENABLED = "kb.rewriteEnabled"
        val DEFAULT = KbRagSettings()
    }
}
