package com.example.llmagent.kb

/**
 * Настройки RAG (Day-23): фильтр релевантности и query rewrite.
 * Глобальные (не per-base): все базы используют одну эмбеддинг-модель
 * (qwen3-vl-embedding-8b), распределения score сопоставимы.
 *
 * Дефолты = поведение дня 22 (фильтр выключен, rewrite выключен, topK=4) —
 * полная обратная совместимость. Изменяются на лету через
 * [KbRagSettingsService.update] (PUT /api/kb/settings) без перезапуска backend.
 */
data class KbRagSettings(
    /** Фильтр релевантности включён: порог [minScore] применяется после candidateK. */
    val filterEnabled: Boolean = false,
    /** Порог cosine для отсечения нерелевантных чанков, диапазон [0..1]. */
    val minScore: Double = 0.35,
    /** Сколько лучших чанков берётся до порога (верх воронки), диапазон [1..100]. */
    val candidateK: Int = 8,
    /** Сколько чанков попадает в промпт после фильтра, диапазон [1..candidateK]. */
    val topK: Int = 4,
    /** Перезапись запроса LLM перед ретривалом (query rewrite). */
    val rewriteEnabled: Boolean = false,
    /**
     * Отказ «не знаю» (Day-24): если активная проиндексированная база есть, но релевантность
     * ниже порога (usedChunks=0 или лучший score < [minScore]), агент пропускает LLM-цикл и
     * отвечает фиксированным отказом. По умолчанию вкл — требование задания дня 24.
     */
    val refusalEnabled: Boolean = true,
)
