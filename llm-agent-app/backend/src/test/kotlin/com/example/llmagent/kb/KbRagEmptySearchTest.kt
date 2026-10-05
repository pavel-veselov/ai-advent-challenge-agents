package com.example.llmagent.kb

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * День-24: детерминированное решение «поиск не дал результатов» — [KbRagService.isEmptySearch].
 * true → LLM получает системную заметку [KbRagService.EMPTY_SEARCH_NOTE] и отвечает сам
 * (фиксированного отказа «не знаю» больше нет). Чистые юнит-тесты без БД и сети:
 * сценарии собраны из реальных KbRagResult/KbRagSettings.
 */
class KbRagEmptySearchTest {

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

    // --- Базовые условия «пустого поиска» ---

    @Test
    fun `used chunks zero means empty search`() {
        val s = KbRagSettings(refusalEnabled = true, minScore = 0.35)
        // Базы активны, но после воронки пусто (всё отсечено фильтром)
        assertTrue(KbRagService.isEmptySearch(result(0, emptyList()), s), "usedChunks=0 — пустой поиск")
        // Чанки оценены, но ни один не вошёл в блок
        assertTrue(KbRagService.isEmptySearch(result(0, listOf(hit(1, 0.9))), s), "чанки не влезли — пустой поиск")
    }

    @Test
    fun `max score below threshold means empty search`() {
        val s = KbRagSettings(refusalEnabled = true, minScore = 0.35)
        // Чанки в блоке, но лучший score 0.2 < 0.35 (фильтр выключен — порог не применялся)
        val r = result(2, listOf(hit(1, 0.2), hit(2, 0.1)))
        assertTrue(KbRagService.isEmptySearch(r, s), "maxScore < minScore — пустой поиск")
    }

    @Test
    fun `max score at or above threshold means normal search`() {
        val s = KbRagSettings(refusalEnabled = true, minScore = 0.35)
        val belowOnlySecond = result(2, listOf(hit(1, 0.4), hit(2, 0.1)))
        assertFalse(
            KbRagService.isEmptySearch(belowOnlySecond, s),
            "лучший score >= minScore — поиск непустой, отвечаем по KB",
        )
        val exactThreshold = result(1, listOf(hit(1, 0.35)))
        assertFalse(
            KbRagService.isEmptySearch(exactThreshold, s),
            "score == minScore — НЕ пустой поиск (строго ниже порога)",
        )
    }

    @Test
    fun `refusal disabled never treats search as empty`() {
        val s = KbRagSettings(refusalEnabled = false, minScore = 0.35)
        assertFalse(KbRagService.isEmptySearch(result(0, emptyList()), s), "выключено — без заметки при usedChunks=0")
        assertFalse(
            KbRagService.isEmptySearch(result(2, listOf(hit(1, 0.1), hit(2, 0.2))), s),
            "выключено — без заметки при низких score",
        )
    }

    @Test
    fun `no bases or search error never means empty search`() {
        val s = KbRagSettings(refusalEnabled = true, minScore = 0.35)
        assertFalse(KbRagService.isEmptySearch(null, s), "нет активных баз — заметки нет")
        assertFalse(
            KbRagService.isEmptySearch(result(0, emptyList(), error = "эмбеддер недоступен"), s),
            "сбой ретривала (fail-open) — заметки нет, агент отвечает как раньше",
        )
    }

    // --- Сценарии с учётом фильтра релевантности ---

    @Test
    fun `filter enabled keeps used chunks above threshold so search is not empty`() {
        // filterEnabled=true: в блок проходят ТОЛЬКО чанки >= minScore — если блок собран,
        // maxScore по определению >= minScore, «пустой поиск» срабатывает только при usedChunks=0.
        val s = KbRagSettings(filterEnabled = true, minScore = 0.8, refusalEnabled = true)
        val passed = result(3, listOf(hit(1, 0.99), hit(2, 0.87), hit(3, 0.8)))
        assertFalse(KbRagService.isEmptySearch(passed, s), "фильтр вкл, все >= порога — поиск непустой")
        val nothingPassed = result(0, emptyList())
        assertTrue(KbRagService.isEmptySearch(nothingPassed, s), "фильтр вкл, всё отсечено — пустой поиск")
    }

    @Test
    fun `filter off lets low score chunks into block and triggers score check`() {
        // filterEnabled=false: чанки ниже порога попадают в блок (поведение дня 22),
        // поэтому проверка maxScore < minScore — единственный сигнал «пустого поиска».
        val s = KbRagSettings(filterEnabled = false, minScore = 0.35, refusalEnabled = true)
        val lowScores = result(4, listOf(hit(1, 0.3), hit(2, 0.2), hit(3, 0.1), hit(4, 0.05)))
        assertTrue(KbRagService.isEmptySearch(lowScores, s), "фильтр выкл, все score ниже порога — пустой поиск")
        val goodEnough = result(4, listOf(hit(1, 0.4), hit(2, 0.2), hit(3, 0.1), hit(4, 0.05)))
        assertFalse(KbRagService.isEmptySearch(goodEnough, s), "фильтр выкл, но лучший >= порога — поиск непустой")
    }
}
