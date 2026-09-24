package com.example.mcpserver.tools

import com.example.mcpserver.collector.SourceCollector
import org.springaicommunity.mcp.annotation.McpTool
import org.springaicommunity.mcp.annotation.McpToolParam
import org.springframework.stereotype.Component
import reactor.core.publisher.Mono

/**
 * Инструмент «Новости» — топовые новости Hacker News (Firebase API).
 *
 * Сетевой вызов (список id + конкурентная загрузка деталей) вынесен в общий
 * [SourceCollector]; инструмент делегирует, сохраняя прежнее имя и поведение.
 */
@Component
class NewsTool(private val collector: SourceCollector) {

    @McpTool(
        name = "get_top_news",
        title = "Топовые новости Hacker News",
        description = "Актуальные топовые истории Hacker News с заголовком, автором, рейтингом и ссылкой. " +
            "Опционально можно указать количество историй (1..25, по умолчанию 10)."
    )
    fun getTopNews(
        @McpToolParam(description = "Сколько историй вернуть (1..25, по умолчанию 10).", required = false) limit: Int?
    ): Mono<List<Map<String, Any>>> = collector.news(limit)
}
