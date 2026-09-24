package com.example.mcpserver.tools

import com.example.mcpserver.collector.SourceCollector
import com.fasterxml.jackson.databind.ObjectMapper
import org.springaicommunity.mcp.annotation.McpTool
import org.springaicommunity.mcp.annotation.McpToolParam
import org.springframework.stereotype.Component
import reactor.core.publisher.Mono
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

/**
 * Композиция MCP-инструментов (Day-19): пайплайн из трёх инструментов, где каждый следующий
 * получает данные от предыдущего — search (получает данные) → summarize (обрабатывает) →
 * save_to_file (сохраняет результат). Инструмент [runPipeline] выполняет всю цепочку АВТОМАТИЧЕСКИ
 * за один вызов и возвращает результат каждого шага (корректность передачи данных между звеньями).
 *
 * Источник данных [search] — top-новости Hacker News через [SourceCollector] (fail-open).
 * Чистые функции (фильтр/сводка/запись) вынесены в companion и покрываются тестами без сети.
 */
@Component
class PipelineTools(
    private val source: SourceCollector,
    private val om: ObjectMapper,
) {

    /** Первое звено пайплайна: получает данные по запросу (топ-новости, фильтр по query). */
    @McpTool(
        name = "search",
        title = "Поиск",
        description = "Получает данные: топовые истории Hacker News, отфильтрованные по запросу (query — " +
            "подстрока в заголовке). Возвращает список результатов {id, title, author, score, comments, url, time}.",
    )
    fun search(
        @McpToolParam(description = "Поисковый запрос — подстрока заголовка.", required = true) query: String,
        @McpToolParam(description = "Сколько результатов вернуть (1..25, по умолчанию 10).", required = false) limit: Int?,
    ): Mono<List<Map<String, Any>>> =
        source.news(limit).map { items -> filterResults(items, query, limit ?: DEFAULT_LIMIT) }

    /** Второе звено пайплайна: обрабатывает список результатов в компактную сводку. */
    @McpTool(
        name = "summarize",
        title = "Сводка",
        description = "Обрабатывает список результатов (из search) в сводку: число записей, топ-N заголовков, " +
            "максимальный рейтинг. Возвращает {count, titles, topScore, source}.",
    )
    fun summarize(
        @McpToolParam(description = "Список результатов (из search).", required = true) items: List<Map<String, Any>>,
        @McpToolParam(description = "Сколько заголовков включить в сводку (по умолчанию 5).", required = false) top: Int?,
    ): Mono<Map<String, Any?>> = Mono.just(buildSummary(items, top ?: DEFAULT_TOP))

    /** Третье звено пайплайна: сохраняет результат в файл и возвращает путь/размер. */
    @McpTool(
        name = "save_to_file",
        title = "Сохранить в файл",
        description = "Сохраняет текст в файл в каталоге вывода и возвращает {filename, path, bytes, writtenAt}.",
    )
    fun saveToFile(
        @McpToolParam(description = "Содержимое файла.", required = true) content: String,
        @McpToolParam(description = "Имя файла (по умолчанию pipeline-<timestamp>.txt).", required = false) filename: String?,
    ): Mono<Map<String, Any?>> = Mono.just(writeFile(content, filename))

    /** Автоматический пайплайн: search → summarize → save_to_file (один вызов, передача данных). */
    @McpTool(
        name = "run_pipeline",
        title = "Пайплайн search → summarize → save_to_file",
        description = "Автоматически выполняет цепочку: search(query) → summarize(результат) → save_to_file(сводка). " +
            "Демонстрирует корректную передачу данных между инструментами. Возвращает {query, resultsCount, summary, file, steps}.",
    )
    fun runPipeline(
        @McpToolParam(description = "Поисковый запрос.", required = true) query: String,
        @McpToolParam(description = "Лимит результатов (1..25, по умолчанию 10).", required = false) limit: Int?,
        @McpToolParam(description = "Имя файла для сохранения сводки.", required = false) filename: String?,
    ): Mono<Map<String, Any?>> =
        source.news(limit).map { items ->
            val results = filterResults(items, query, limit ?: DEFAULT_LIMIT)
            val summary = buildSummary(results, DEFAULT_TOP)
            val file = writeFile(om.writeValueAsString(summary), filename)
            mapOf(
                "query" to query,
                "resultsCount" to results.size,
                "summary" to summary,
                "file" to file,
                "steps" to listOf("search", "summarize", "save_to_file"),
            )
        }

    companion object {
        private const val DEFAULT_LIMIT = 10
        private const val DEFAULT_TOP = 5
        private const val MAX_LIMIT = 25

        /** Фильтр результатов по подстроке в заголовке + лимит (чистая функция, тестируема). */
        fun filterResults(items: List<Map<String, Any>>, query: String, limit: Int): List<Map<String, Any>> {
            val q = query.trim().lowercase()
            val filtered = if (q.isEmpty()) items
            else items.filter { (it["title"] as? String)?.lowercase()?.contains(q) == true }
            return filtered.take(limit.coerceIn(1, MAX_LIMIT))
        }

        /** Экстрактивная сводка результатов (чистая функция, тестируема без сети). */
        fun buildSummary(items: List<Map<String, Any>>, top: Int): Map<String, Any?> {
            val titles = items.take(top.coerceAtLeast(1)).mapNotNull { it["title"] as? String }
            val topScore = items.mapNotNull { (it["score"] as? Number)?.toInt() }.maxOrNull()
            return mapOf(
                "count" to items.size,
                "titles" to titles,
                "topScore" to topScore,
                "source" to "hacker-news",
            )
        }

        /** Запись результата в файл в каталоге вывода (чистая функция от параметров). */
        fun writeFile(content: String, filename: String?): Map<String, Any?> {
            val dir = Path.of(System.getProperty("java.io.tmpdir"), "papkin-helper-out")
            Files.createDirectories(dir)
            val name = filename?.takeIf { it.isNotBlank() } ?: "pipeline-${Instant.now().toEpochMilli()}.txt"
            val safeName = name.replace(Regex("[^a-zA-Z0-9._-]"), "_")
            val path = dir.resolve(safeName)
            Files.writeString(path, content)
            return mapOf(
                "filename" to safeName,
                "path" to path.toAbsolutePath().toString(),
                "bytes" to Files.size(path),
                "writtenAt" to Instant.now().toString(),
            )
        }
    }
}
