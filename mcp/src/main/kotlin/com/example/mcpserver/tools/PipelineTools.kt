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
 * РљРѕРјРїРѕР·РёС†РёСЏ MCP-РёРЅСЃС‚СЂСѓРјРµРЅС‚РѕРІ (Day-19): РїР°Р№РїР»Р°Р№РЅ РёР· С‚СЂС‘С… РёРЅСЃС‚СЂСѓРјРµРЅС‚РѕРІ, РіРґРµ РєР°Р¶РґС‹Р№ СЃР»РµРґСѓСЋС‰РёР№
 * РїРѕР»СѓС‡Р°РµС‚ РґР°РЅРЅС‹Рµ РѕС‚ РїСЂРµРґС‹РґСѓС‰РµРіРѕ вЂ” search (РїРѕР»СѓС‡Р°РµС‚ РґР°РЅРЅС‹Рµ) в†’ summarize (РѕР±СЂР°Р±Р°С‚С‹РІР°РµС‚) в†’
 * save_to_file (СЃРѕС…СЂР°РЅСЏРµС‚ СЂРµР·СѓР»СЊС‚Р°С‚). РРЅСЃС‚СЂСѓРјРµРЅС‚ [runPipeline] РІС‹РїРѕР»РЅСЏРµС‚ РІСЃСЋ С†РµРїРѕС‡РєСѓ РђР’РўРћРњРђРўРР§Р•РЎРљР
 * Р·Р° РѕРґРёРЅ РІС‹Р·РѕРІ Рё РІРѕР·РІСЂР°С‰Р°РµС‚ СЂРµР·СѓР»СЊС‚Р°С‚ РєР°Р¶РґРѕРіРѕ С€Р°РіР° (РєРѕСЂСЂРµРєС‚РЅРѕСЃС‚СЊ РїРµСЂРµРґР°С‡Рё РґР°РЅРЅС‹С… РјРµР¶РґСѓ Р·РІРµРЅСЊСЏРјРё).
 *
 * РСЃС‚РѕС‡РЅРёРє РґР°РЅРЅС‹С… [search] вЂ” top-РЅРѕРІРѕСЃС‚Рё Hacker News С‡РµСЂРµР· [SourceCollector] (fail-open).
 * Р§РёСЃС‚С‹Рµ С„СѓРЅРєС†РёРё (С„РёР»СЊС‚СЂ/СЃРІРѕРґРєР°/Р·Р°РїРёСЃСЊ) РІС‹РЅРµСЃРµРЅС‹ РІ companion Рё РїРѕРєСЂС‹РІР°СЋС‚СЃСЏ С‚РµСЃС‚Р°РјРё Р±РµР· СЃРµС‚Рё.
 */
@Component
class PipelineTools(
    private val source: SourceCollector,
    private val om: ObjectMapper,
) {

    /** РџРµСЂРІРѕРµ Р·РІРµРЅРѕ РїР°Р№РїР»Р°Р№РЅР°: РїРѕР»СѓС‡Р°РµС‚ РґР°РЅРЅС‹Рµ РїРѕ Р·Р°РїСЂРѕСЃСѓ (С‚РѕРї-РЅРѕРІРѕСЃС‚Рё, С„РёР»СЊС‚СЂ РїРѕ query). */
    @McpTool(
        name = "search",
        title = "РџРѕРёСЃРє",
        description = "РџРѕР»СѓС‡Р°РµС‚ РґР°РЅРЅС‹Рµ: С‚РѕРїРѕРІС‹Рµ РёСЃС‚РѕСЂРёРё Hacker News, РѕС‚С„РёР»СЊС‚СЂРѕРІР°РЅРЅС‹Рµ РїРѕ Р·Р°РїСЂРѕСЃСѓ (query вЂ” " +
            "РїРѕРґСЃС‚СЂРѕРєР° РІ Р·Р°РіРѕР»РѕРІРєРµ). Р’РѕР·РІСЂР°С‰Р°РµС‚ СЃРїРёСЃРѕРє СЂРµР·СѓР»СЊС‚Р°С‚РѕРІ {id, title, author, score, comments, url, time}.",
    )
    fun search(
        @McpToolParam(description = "РџРѕРёСЃРєРѕРІС‹Р№ Р·Р°РїСЂРѕСЃ вЂ” РїРѕРґСЃС‚СЂРѕРєР° Р·Р°РіРѕР»РѕРІРєР°.", required = true) query: String,
        @McpToolParam(description = "РЎРєРѕР»СЊРєРѕ СЂРµР·СѓР»СЊС‚Р°С‚РѕРІ РІРµСЂРЅСѓС‚СЊ (1..25, РїРѕ СѓРјРѕР»С‡Р°РЅРёСЋ 10).", required = false) limit: Int?,
    ): Mono<List<Map<String, Any>>> =
        source.news(limit).map { items -> filterResults(items, query, limit ?: DEFAULT_LIMIT) }

    /** Р’С‚РѕСЂРѕРµ Р·РІРµРЅРѕ РїР°Р№РїР»Р°Р№РЅР°: РѕР±СЂР°Р±Р°С‚С‹РІР°РµС‚ СЃРїРёСЃРѕРє СЂРµР·СѓР»СЊС‚Р°С‚РѕРІ РІ РєРѕРјРїР°РєС‚РЅСѓСЋ СЃРІРѕРґРєСѓ. */
    @McpTool(
        name = "summarize",
        title = "РЎРІРѕРґРєР°",
        description = "РћР±СЂР°Р±Р°С‚С‹РІР°РµС‚ СЃРїРёСЃРѕРє СЂРµР·СѓР»СЊС‚Р°С‚РѕРІ (РёР· search) РІ СЃРІРѕРґРєСѓ: С‡РёСЃР»Рѕ Р·Р°РїРёСЃРµР№, С‚РѕРї-N Р·Р°РіРѕР»РѕРІРєРѕРІ, " +
            "РјР°РєСЃРёРјР°Р»СЊРЅС‹Р№ СЂРµР№С‚РёРЅРі. Р’РѕР·РІСЂР°С‰Р°РµС‚ {count, titles, topScore, source}.",
    )
    fun summarize(
        @McpToolParam(description = "РЎРїРёСЃРѕРє СЂРµР·СѓР»СЊС‚Р°С‚РѕРІ (РёР· search).", required = true) items: List<Map<String, Any>>,
        @McpToolParam(description = "РЎРєРѕР»СЊРєРѕ Р·Р°РіРѕР»РѕРІРєРѕРІ РІРєР»СЋС‡РёС‚СЊ РІ СЃРІРѕРґРєСѓ (РїРѕ СѓРјРѕР»С‡Р°РЅРёСЋ 5).", required = false) top: Int?,
    ): Mono<Map<String, Any?>> = Mono.just(buildSummary(items, top ?: DEFAULT_TOP))

    /** РўСЂРµС‚СЊРµ Р·РІРµРЅРѕ РїР°Р№РїР»Р°Р№РЅР°: СЃРѕС…СЂР°РЅСЏРµС‚ СЂРµР·СѓР»СЊС‚Р°С‚ РІ С„Р°Р№Р» Рё РІРѕР·РІСЂР°С‰Р°РµС‚ РїСѓС‚СЊ/СЂР°Р·РјРµСЂ. */
    @McpTool(
        name = "save_to_file",
        title = "РЎРѕС…СЂР°РЅРёС‚СЊ РІ С„Р°Р№Р»",
        description = "РЎРѕС…СЂР°РЅСЏРµС‚ С‚РµРєСЃС‚ РІ С„Р°Р№Р» РІ РєР°С‚Р°Р»РѕРіРµ РІС‹РІРѕРґР° Рё РІРѕР·РІСЂР°С‰Р°РµС‚ {filename, path, bytes, writtenAt}.",
    )
    fun saveToFile(
        @McpToolParam(description = "РЎРѕРґРµСЂР¶РёРјРѕРµ С„Р°Р№Р»Р°.", required = true) content: String,
        @McpToolParam(description = "РРјСЏ С„Р°Р№Р»Р° (РїРѕ СѓРјРѕР»С‡Р°РЅРёСЋ pipeline-<timestamp>.txt).", required = false) filename: String?,
    ): Mono<Map<String, Any?>> = Mono.just(writeFile(content, filename))

    /** РђРІС‚РѕРјР°С‚РёС‡РµСЃРєРёР№ РїР°Р№РїР»Р°Р№РЅ: search в†’ summarize в†’ save_to_file (РѕРґРёРЅ РІС‹Р·РѕРІ, РїРµСЂРµРґР°С‡Р° РґР°РЅРЅС‹С…). */
    @McpTool(
        name = "run_pipeline",
        title = "РџР°Р№РїР»Р°Р№РЅ search в†’ summarize в†’ save_to_file",
        description = "РђРІС‚РѕРјР°С‚РёС‡РµСЃРєРё РІС‹РїРѕР»РЅСЏРµС‚ С†РµРїРѕС‡РєСѓ: search(query) в†’ summarize(СЂРµР·СѓР»СЊС‚Р°С‚) в†’ save_to_file(СЃРІРѕРґРєР°). " +
            "Р”РµРјРѕРЅСЃС‚СЂРёСЂСѓРµС‚ РєРѕСЂСЂРµРєС‚РЅСѓСЋ РїРµСЂРµРґР°С‡Сѓ РґР°РЅРЅС‹С… РјРµР¶РґСѓ РёРЅСЃС‚СЂСѓРјРµРЅС‚Р°РјРё. Р’РѕР·РІСЂР°С‰Р°РµС‚ {query, resultsCount, summary, file, steps}.",
    )
    fun runPipeline(
        @McpToolParam(description = "РџРѕРёСЃРєРѕРІС‹Р№ Р·Р°РїСЂРѕСЃ.", required = true) query: String,
        @McpToolParam(description = "Р›РёРјРёС‚ СЂРµР·СѓР»СЊС‚Р°С‚РѕРІ (1..25, РїРѕ СѓРјРѕР»С‡Р°РЅРёСЋ 10).", required = false) limit: Int?,
        @McpToolParam(description = "РРјСЏ С„Р°Р№Р»Р° РґР»СЏ СЃРѕС…СЂР°РЅРµРЅРёСЏ СЃРІРѕРґРєРё.", required = false) filename: String?,
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

        /** Р¤РёР»СЊС‚СЂ СЂРµР·СѓР»СЊС‚Р°С‚РѕРІ РїРѕ РїРѕРґСЃС‚СЂРѕРєРµ РІ Р·Р°РіРѕР»РѕРІРєРµ + Р»РёРјРёС‚ (С‡РёСЃС‚Р°СЏ С„СѓРЅРєС†РёСЏ, С‚РµСЃС‚РёСЂСѓРµРјР°). */
        fun filterResults(items: List<Map<String, Any>>, query: String, limit: Int): List<Map<String, Any>> {
            val q = query.trim().lowercase()
            val filtered = if (q.isEmpty()) items
            else items.filter { (it["title"] as? String)?.lowercase()?.contains(q) == true }
            return filtered.take(limit.coerceIn(1, MAX_LIMIT))
        }

        /** Р­РєСЃС‚СЂР°РєС‚РёРІРЅР°СЏ СЃРІРѕРґРєР° СЂРµР·СѓР»СЊС‚Р°С‚РѕРІ (С‡РёСЃС‚Р°СЏ С„СѓРЅРєС†РёСЏ, С‚РµСЃС‚РёСЂСѓРµРјР° Р±РµР· СЃРµС‚Рё). */
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

        /** Р—Р°РїРёСЃСЊ СЂРµР·СѓР»СЊС‚Р°С‚Р° РІ С„Р°Р№Р» РІ РєР°С‚Р°Р»РѕРіРµ РІС‹РІРѕРґР° (С‡РёСЃС‚Р°СЏ С„СѓРЅРєС†РёСЏ РѕС‚ РїР°СЂР°РјРµС‚СЂРѕРІ). */
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
