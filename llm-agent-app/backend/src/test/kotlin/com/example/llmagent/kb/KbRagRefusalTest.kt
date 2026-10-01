package com.example.llmagent.kb

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * День-24: детерминированное решение об отказе «не знаю» — [KbRagService.shouldRefuse].
 * Чистые юнит-тесты без БД и сети: сценарии собраны из реальных KbRagResult/KbRagSettings.
 */
class KbRagRefusalTest {

    private fun hit(label: Int, score: Double) = KbChunkHit(
        kbName = "Ноутбуки",
        source = "notes.md",
        section = "сек$label",
        score = score,
        contentChars = 10,
        chunkId = label.toLong(),
        label = label,
        content = "чанк$label",
    )

    private fun result(
        usedChunks: Int,
        hits: List<KbChunkHit>,
        error: String? = null,
    ) = KbRagResult(
        block = if (usedChunks > 0) "### База знаний" else null,
        chunks = hits,
        bases = listOf("Ноутбуки"),
        candidateChunks = hits.size,
        usedChunks = usedChunks,
        topK = 4,
        maxBlockChars = KbRagService.MAX_BLOCK_CHARS,
        embeddingModel = "qwen3-vl-embedding-8b",
        error = error,
    )

    // --- Базовые условия отказа ---

    @Test
    fun `used chunks zero means refusal`() {
        val s = KbRagSettings(refusalEnabled = true, minScore = 0.35)
        // Базы активны, но после воронки пусто (всё отсечено фильтром)
        assertTrue(KbRagService.shouldRefuse(result(0, emptyList()), s), "usedChunks=0 — «не знаю»")
        // Чанки оценены, но ни один не вошёл в блок
        assertTrue(KbRagService.shouldRefuse(result(0, listOf(hit(1, 0.9))), s), "чанки не влезли — «не знаю»")
    }

    @Test
    fun `max score below threshold means refusal`() {
        val s = KbRagSettings(refusalEnabled = true, minScore = 0.35)
        // Чанки в блоке, но лучший score 0.2 < 0.35 (фильтр выключен — порог не применялся)
        val r = result(2, listOf(hit(1, 0.2), hit(2, 0.1)))
        assertTrue(KbRagService.shouldRefuse(r, s), "maxScore < minScore — «не знаю»")
    }

    @Test
    fun `max score at or above threshold means no refusal`() {
        val s = KbRagSettings(refusalEnabled = true, minScore = 0.35)
        val belowOnlySecond = result(2, listOf(hit(1, 0.4), hit(2, 0.1)))
        assertFalse(
            KbRagService.shouldRefuse(belowOnlySecond, s),
            "лучший score >= minScore — отвечаем",
        )
        val exactThreshold = result(1, listOf(hit(1, 0.35)))
        assertFalse(
            KbRagService.shouldRefuse(exactThreshold, s),
            "score == minScore — НЕ отказ (строго ниже порога)",
        )
    }

    @Test
    fun `refusal disabled never refuses`() {
        val s = KbRagSettings(refusalEnabled = false, minScore = 0.35)
        assertFalse(KbRagService.shouldRefuse(result(0, emptyList()), s), "выключено — без отказа при usedChunks=0")
        assertFalse(
            KbRagService.shouldRefuse(result(2, listOf(hit(1, 0.1), hit(2, 0.2))), s),
            "выключено — без отказа при низких score",
        )
    }

    @Test
    fun `no bases or search error never refuses`() {
        val s = KbRagSettings(refusalEnabled = true, minScore = 0.35)
        assertFalse(KbRagService.shouldRefuse(null, s), "нет активных баз — отказа нет")
        assertFalse(
            KbRagService.shouldRefuse(result(0, emptyList(), error = "эмбеддер недоступен"), s),
            "сбой ретривала (fail-open) — отказа нет, агент отвечает как раньше",
        )
    }

    // --- Сценарии с учётом фильтра релевантности ---

    @Test
    fun `filter enabled keeps used chunks above threshold so no refusal`() {
        // filterEnabled=true: в блок проходят ТОЛЬКО чанки >= minScore — если блок собран,
        // maxScore по определению >= minScore, отказ срабатывает только при usedChunks=0.
        val s = KbRagSettings(filterEnabled = true, minScore = 0.8, refusalEnabled = true)
        val passed = result(3, listOf(hit(1, 0.99), hit(2, 0.87), hit(3, 0.8)))
        assertFalse(KbRagService.shouldRefuse(passed, s), "фильтр вкл, все >= порога — отвечаем")
        val nothingPassed = result(0, emptyList())
        assertTrue(KbRagService.shouldRefuse(nothingPassed, s), "фильтр вкл, всё отсечено — «не знаю»")
    }

    @Test
    fun `filter off lets low score chunks into block and triggers score check`() {
        // filterEnabled=false: чанки ниже порога попадают в блок (поведение дня 22),
        // поэтому проверка maxScore < minScore — единственный сигнал для отказа.
        val s = KbRagSettings(filterEnabled = false, minScore = 0.35, refusalEnabled = true)
        val lowScores = result(4, listOf(hit(1, 0.3), hit(2, 0.2), hit(3, 0.1), hit(4, 0.05)))
        assertTrue(KbRagService.shouldRefuse(lowScores, s), "фильтр выкл, все score ниже порога — «не знаю»")
        val goodEnough = result(4, listOf(hit(1, 0.4), hit(2, 0.2), hit(3, 0.1), hit(4, 0.05)))
        assertFalse(KbRagService.shouldRefuse(goodEnough, s), "фильтр выкл, но лучший >= порога — отвечаем")
    }
}
