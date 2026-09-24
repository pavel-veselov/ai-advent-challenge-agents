package com.example.mcpserver.tools

import com.example.mcpserver.collector.SourceCollector
import org.springaicommunity.mcp.annotation.McpTool
import org.springaicommunity.mcp.annotation.McpToolParam
import org.springframework.stereotype.Component
import reactor.core.publisher.Mono

/**
 * Инструмент «Курс валют» — официальные курсы Банка России (cbr-xml-daily.ru).
 *
 * Сетевой вызов и разбор переехали в общий [SourceCollector] — инструмент лишь
 * делегирует, сохраняя прежнее имя, параметры и форму результата.
 */
@Component
class CurrencyTool(private val collector: SourceCollector) {

    @McpTool(
        name = "get_exchange_rate",
        title = "Курс валюты ЦБ РФ",
        description = "Официальный курс валюты Банка России. Вернёт курс, изменение за день и номинал для " +
            "указанного кода, либо все курсы сразу."
    )
    fun getExchangeRate(
        @McpToolParam(
            description = "Код валюты по ISO 4217 (например USD, EUR, CNY). Если не указан — вернуть все курсы.",
            required = false
        ) code: String?
    ): Mono<Map<String, Any>> = collector.currency(code)
}
