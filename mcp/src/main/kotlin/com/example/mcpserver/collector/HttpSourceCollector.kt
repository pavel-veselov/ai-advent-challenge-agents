package com.example.mcpserver.collector

import com.fasterxml.jackson.databind.JsonNode
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.net.URLEncoder
import java.time.Instant

/**
 * Реализация [SourceCollector] поверх реальных публичных API (тот же WebClient, что у
 * инструментов). Сетевая логика переехала сюда из WeatherTool/CurrencyTool/NewsTool,
 * чтобы инструменты и планировщик (Day-18) делили один код без дублирования.
 *
 * Чистые функции разбора ([buildWeather], [parseRates], [buildItem]) вынесены в
 * companion и покрываются тестами без обращения к сети.
 */
@Component
class HttpSourceCollector(private val webClient: WebClient) : SourceCollector {

    /**
     * Текущая погода от wttr.in (бесплатно, без ключа, принимает название города напрямую —
     * не нужен отдельный геокодинг). Формат j1 отдаёт и метрические, и имперские поля.
     */
    override fun weather(city: String, units: String?): Mono<Map<String, Any>> {
        val unit = units?.lowercase() ?: "celsius"
        val cityEncoded = URLEncoder.encode(city.trim(), "UTF-8")
        return webClient.get()
            .uri("https://wttr.in/$cityEncoded?format=j1")
            .retrieve()
            .bodyToMono(JsonNode::class.java)
            .map { root -> buildWeather(root, unit) }
    }

    /** Курс валюты (или все курсы) от ЦБ РФ. */
    override fun currency(code: String?): Mono<Map<String, Any>> =
        webClient.get()
            .uri(CBR_URL)
            .retrieve()
            .bodyToMono(JsonNode::class.java)
            .map { root -> parseRates(root, code) }

    /** Топовые истории Hacker News: id списка, затем детали конкурентно. */
    override fun news(limit: Int?): Mono<List<Map<String, Any>>> {
        val count = (limit ?: DEFAULT_LIMIT).coerceIn(MIN_LIMIT, MAX_LIMIT)

        return webClient.get()
            .uri("https://hacker-news.firebaseio.com/v0/topstories.json")
            .retrieve()
            .bodyToMono(JsonNode::class.java)
            .flatMapMany { ids -> Flux.fromIterable(toIdList(ids).take(count)) }
            .concatMap { id -> fetchItem(id) }
            .collectList()
    }

    /** Унифицированный вызов планировщиком по имени источника и параметрам задачи. */
    override fun collect(source: String, params: Map<String, Any?>?): Mono<Any?> =
        when (source.lowercase()) {
            "weather" -> weather(
                params?.get("city") as? String ?: DEFAULT_CITY,
                params?.get("units") as? String,
            ).map { it as Any? }
            "currency" -> currency(params?.get("code") as? String).map { it as Any? }
            "news" -> news((params?.get("limit") as? Number)?.toInt()).map { it as Any? }
            else -> Mono.just<Any?>(mapOf("error" to "Неизвестный источник: $source"))
        }

    private fun fetchItem(id: Long): Mono<Map<String, Any>> =
        webClient.get()
            .uri("https://hacker-news.firebaseio.com/v0/item/$id.json")
            .retrieve()
            .bodyToMono(JsonNode::class.java)
            .map { item -> buildItem(item) }
            .onErrorResume { Mono.empty() } // проблемная история просто пропускается

    private fun toIdList(ids: JsonNode): List<Long> {
        if (!ids.isArray) return emptyList()
        val out = ArrayList<Long>(ids.size())
        for (node in ids) out.add(node.asLong())
        return out
    }

    companion object {
        private const val CBR_URL = "https://www.cbr-xml-daily.ru/daily_json.js"
        private const val DEFAULT_LIMIT = 10
        private const val MIN_LIMIT = 1
        private const val MAX_LIMIT = 25
        private const val DEFAULT_CITY = "Москва"

        /** Чистая функция сборки ответа о погоде из формата wttr.in (j1) — покрывается тестами без сети. */
        fun buildWeather(root: JsonNode, unit: String): Map<String, Any> {
            val condition = root.path("current_condition").path(0)
            val area = root.path("nearest_area").path(0)
            val fahrenheit = unit.lowercase() in setOf("fahrenheit", "f")
            return linkedMapOf(
                "city" to area.path("areaName").path(0).path("value").asText(""),
                "country" to area.path("country").path(0).path("value").asText(""),
                "temperature" to condition.path(if (fahrenheit) "temp_F" else "temp_C").asDouble(),
                "temperature_unit" to (if (fahrenheit) "°F" else "°C"),
                "apparent_temperature" to condition.path(if (fahrenheit) "FeelsLikeF" else "FeelsLikeC").asDouble(),
                "wind_speed" to condition.path(if (fahrenheit) "windspeedMiles" else "windspeedKmph").asDouble(),
                "wind_speed_unit" to (if (fahrenheit) "mph" else "km/h"),
                "weather_code" to condition.path("weatherCode").asInt(),
                "weather" to condition.path("weatherDesc").path(0).path("value").asText(""),
                "time" to condition.path("localObsDateTime").asText(""),
                "source" to "wttr.in",
            )
        }

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

        /** Чистая функция сборки карточки истории Hacker News. */
        fun buildItem(item: JsonNode): Map<String, Any> {
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

        private fun round2(v: Double): Double = Math.round(v * 100.0) / 100.0
    }
}
