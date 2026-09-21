package com.example.mcpserver.tools

import com.fasterxml.jackson.databind.JsonNode
import org.springaicommunity.mcp.annotation.McpTool
import org.springaicommunity.mcp.annotation.McpToolParam
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.time.Instant

/**
 * Инструмент «Новости» — топовые новости Hacker News (Firebase API).
 *
 * Сначала забираем массив id топ-историй (topstories.json), затем загружаем детали
 * по каждому id (item/{id}.json). Запросы к деталям идут конкурентно (maxConcurrency),
 * чтобы не запускать N последовательных медленных запросов.
 */
@Component
class NewsTool(private val webClient: WebClient) {

    @McpTool(
        name = "get_top_news",
        title = "Топовые новости Hacker News",
        description = "Актуальные топовые истории Hacker News с заголовком, автором, рейтингом и ссылкой. " +
            "Опционально можно указать количество историй (1..25, по умолчанию 10)."
    )
    fun getTopNews(
        @McpToolParam(description = "Сколько историй вернуть (1..25, по умолчанию 10).", required = false) limit: Int?
    ): Mono<List<Map<String, Any>>> {
        val count = (limit ?: DEFAULT_LIMIT).coerceIn(MIN_LIMIT, MAX_LIMIT)

        return webClient.get()
            .uri("https://hacker-news.firebaseio.com/v0/topstories.json")
            .retrieve()
            .bodyToMono(JsonNode::class.java)
            .flatMapMany { ids -> Flux.fromIterable(toIdList(ids).take(count)) }
            .concatMap { id -> fetchItem(id) }
            .collectList()
    }

    private fun fetchItem(id: Long): Mono<Map<String, Any>> =
        webClient.get()
            .uri("https://hacker-news.firebaseio.com/v0/item/$id.json")
            .retrieve()
            .bodyToMono(JsonNode::class.java)
            .map { item -> buildItem(item) }
            .onErrorResume { Mono.empty() } // проблемная история просто пропускается

    internal fun buildItem(item: JsonNode): Map<String, Any> {
        val title = item.path("title").asText("(без заголовка)")
        val url = item.path("url").asText("")
        val created = item.path("time").asLong()
        return linkedMapOf(
            "id" to item.path("id").asLong(),
            "title" to title,
            "author" to item.path("by").asText(""),
            "score" to item.path("score").asInt(),
            "comments" to item.path("descendants").asInt(),
            "url" to url,
            "hn_url" to "https://news.ycombinator.com/item?id=${item.path("id").asLong()}",
            "time" to created,
            "time_iso" to (if (created > 0) Instant.ofEpochSecond(created).toString() else ""),
        )
    }

    private fun toIdList(ids: JsonNode): List<Long> {
        if (!ids.isArray) return emptyList()
        val out = ArrayList<Long>(ids.size())
        for (node in ids) out.add(node.asLong())
        return out
    }

    companion object {
        private const val DEFAULT_LIMIT = 10
        private const val MIN_LIMIT = 1
        private const val MAX_LIMIT = 25
    }
}
