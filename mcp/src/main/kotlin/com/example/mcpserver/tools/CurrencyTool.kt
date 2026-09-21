package com.example.mcpserver.tools

import com.fasterxml.jackson.databind.JsonNode
import org.springaicommunity.mcp.annotation.McpTool
import org.springaicommunity.mcp.annotation.McpToolParam
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import reactor.core.publisher.Mono

/**
 * Инструмент «Курс валют» — официальные курсы Банка России (cbr-xml-daily.ru).
 *
 * Внешний API: https://www.cbr-xml-daily.ru/daily_json.js
 * Возвращает JSON вида { "Date": ..., "Valute": { "USD": { "CharCode", "Nominal",
 * "Value", "Previous", "Name", ... }, ... } }.
 */
@Component
class CurrencyTool(private val webClient: WebClient) {

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
    ): Mono<Map<String, Any>> =
        webClient.get()
            .uri(CBR_URL)
            .retrieve()
            .bodyToMono(JsonNode::class.java)
            .map { root -> parseRates(root, code) }

    companion object {
        private const val CBR_URL = "https://www.cbr-xml-daily.ru/daily_json.js"

        /** Чистая функция разбора ЦБ-ответа — вынесена отдельно, чтобы её можно было покрыть тестами без сети. */
        fun parseRates(root: JsonNode, code: String?): Map<String, Any> {
            val date = root.path("Date").asText("")
            val valute = root.path("Valute")
            val rates = linkedMapOf<String, Any>()

            if (valute.isObject) {
                for ((key, node) in valute.properties()) {
                    val charCode = node.path("CharCode").asText(key)
                    if (code != null && !charCode.equals(code, ignoreCase = true)) continue

                    val nominal = node.path("Nominal").asInt(1)
                    val value = node.path("Value").asDouble()
                    val previous = node.path("Previous").asDouble()
                    val name = node.path("Name").asText(charCode)
                    val perUnit = if (nominal > 0) value / nominal else value
                    val change = if (nominal > 0) (value - previous) / nominal else value - previous

                    rates[charCode] = mapOf(
                        "rate" to round2(perUnit),
                        "change_from_previous" to round2(change),
                        "nominal" to nominal,
                        "name" to name,
                    )
                }
            }

            return linkedMapOf(
                "base" to "RUB",
                "date" to date,
                "rate_source" to "ЦБ РФ (cbr-xml-daily.ru)",
                "rates" to rates,
            )
        }

        private fun round2(v: Double): Double = Math.round(v * 100.0) / 100.0
    }
}
