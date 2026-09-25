package com.example.mcpserver.tools

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * Тесты чистой логики пайплайна Day-19 (search → summarize → save_to_file) — без сети:
 * фильтр результатов, экстрактивная сводка, запись в файл и санитизация имени.
 */
class PipelineToolsTest {

    private fun item(title: String, score: Int): Map<String, Any> = mapOf(
        "id" to 1L,
        "title" to title,
        "author" to "u",
        "score" to score,
        "comments" to 0,
        "url" to "https://example.com",
        "time" to 0L,
    )

    @Test
    fun `filterResults фильтрует по подстроке в заголовке`() {
        val items = listOf(
            item("OpenAI releases MCP tools", 100),
            item("Kotlin news", 50),
            item("MCP server crash", 80),
            item("Unrelated", 10),
        )
        val res = PipelineTools.filterResults(items, "mcp", 10)
        assertEquals(2, res.size)
        assertTrue(res.all { (it["title"] as String).contains("MCP", ignoreCase = true) })
    }

    @Test
    fun `filterResults пустой запрос возвращает всё и ограничивает лимит`() {
        val items = (1..30).map { item("News $it", it) }
        // без запроса + лимит 25 → вернёт 25 (take 25)
        assertEquals(25, PipelineTools.filterResults(items, "", 25).size)
        // лимит 10
        assertEquals(10, PipelineTools.filterResults(items, "", 10).size)
        // лимит клампится до 25 (MAX_LIMIT)
        assertEquals(25, PipelineTools.filterResults(items, "", 500).size)
    }

    @Test
    fun `buildSummary считает количество, топ-заголовки и максимальный рейтинг`() {
        val items = listOf(item("Заголовок A", 10), item("Заголовок B", 20), item("Заголовок C", 30))
        val s = PipelineTools.buildSummary(items, 2)
        assertEquals(3, s["count"])
        assertEquals(listOf("Заголовок A", "Заголовок B"), s["titles"])
        assertEquals(30, s["topScore"])
        assertEquals("hacker-news", s["source"])
    }

    @Test
    fun `buildSummaryText рендерит человекочитаемую сводку с нумерацией`() {
        val items = listOf(item("Заголовок A", 10), item("Заголовок B", 20), item("Заголовок C", 30))
        val text = PipelineTools.buildSummaryText(items, 2)
        assertTrue(text.contains("Сводка по hacker-news"), "источник должен быть в шапке")
        assertTrue(text.contains("Найдено: 3"), "должно быть количество записей")
        assertTrue(text.contains("Лучший рейтинг: 30"), "должен быть максимальный рейтинг")
        assertTrue(text.contains("Топ-2 заголовков:"), "должен быть заголовок блока")
        assertTrue(text.contains("1. Заголовок A"), "нумерованный элемент 1")
        assertTrue(text.contains("2. Заголовок B"), "нумерованный элемент 2")
        assertFalse(text.contains("3. Заголовок C"), "топ-2 должен ограничить список")
        assertFalse(text.contains("{"), "не должно быть JSON-скобок")
    }

    @Test
    fun `buildSummaryText пустой список рендерит нули без рейтинга`() {
        val text = PipelineTools.buildSummaryText(emptyList(), 5)
        assertTrue(text.contains("Сводка по hacker-news"))
        assertTrue(text.contains("Найдено: 0"))
        assertFalse(text.contains("Лучший рейтинг"), "при пустом списке нет рейтинга")
        assertTrue(text.contains("(нет заголовков)"), "пустой блок заголовков")
    }

    @Test
    fun `writeFile пишет файл и возвращает путь размер и имя`() {
        val content = "сводка: 3 записи"
        val res = PipelineTools.writeFile(content, "pipeline-test.txt")
        val path = res["path"] as String
        assertTrue(Files.exists(Path.of(path)), "файл должен существовать: $path")
        assertEquals(content.toByteArray().size, (res["bytes"] as Number).toInt())
        assertEquals("pipeline-test.txt", res["filename"])
        Files.deleteIfExists(Path.of(path))
    }

    @Test
    fun `writeFile санитаризует небезопасное имя файла`() {
        val res = PipelineTools.writeFile("x", "bad:name/with=chars?")
        val name = res["filename"] as String
        assertFalse(name.contains(":"), "двоеточие должно быть вырезано")
        assertFalse(name.contains("/"), "слэш должен быть вырезан")
        Files.deleteIfExists(Path.of(res["path"] as String?))
    }

    @Test
    fun `readFileContent возвращает содержимое существующего файла`() {
        val w = PipelineTools.writeFile("hello-day19", "read-test.txt")
        val read = PipelineTools.readFileContent("read-test.txt")
        assertEquals("hello-day19", read["content"])
        assertEquals("read-test.txt", read["filename"])
        assertTrue((read["error"] as String?) == null)
        Files.deleteIfExists(Path.of(w["path"] as String?))
    }

    @Test
    fun `readFileContent отсутствующего файла возвращает error`() {
        val read = PipelineTools.readFileContent("no-such-file-12345.txt")
        assertTrue((read["error"] as String?) != null)
    }
}
