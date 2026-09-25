package com.example.mcpserver.collector

import com.fasterxml.jackson.databind.JsonNode
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
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
     * Текущая погода: геокодинг города через Open-Meteo (geocoding-api.open-meteo.com,
     * принимает названия и кириллицей, и латиницей), затем текущая погода от met.no
     * locationforecast (api.met.no, формат GeoJSON). met.no требует осмысленный
     * User-Agent — без него запрос отклоняется.
     * Отказоустойчивость: любая ошибка (неизвестный город, недоступность API, сбой
     * разбора) превращается в дружелюбную карту с ключом "error" — поток не падает.
     */
    override fun weather(city: String, units: String?): Mono<Map<String, Any>> {
        val unit = units?.lowercase() ?: "celsius"
        return webClient.get()
            .uri("$GEOCODING_URL?name={city}&count=1&language=ru", city.trim())
            .retrieve()
            .bodyToMono(JsonNode::class.java)
            .flatMap { geo ->
                val place = geo.path("results").path(0)
                if (place.isMissingNode || place.path("latitude").isMissingNode) {
                    Mono.error(IllegalStateException("Не удалось найти город: $city"))
                } else {
                    fetchMetNo(place, place.path("latitude").asDouble(), place.path("longitude").asDouble(), unit)
                }
            }
            .onErrorResume { e ->
                Mono.just(
                    linkedMapOf(
                        "error" to (e.message ?: "Не удалось получить погоду"),
                        "city" to city,
                        "source" to MET_NO_SOURCE,
                    )
                )
            }
    }

    /** Запрос текущей погоды к met.no для уже найденных координат (User-Agent обязателен). */
    private fun fetchMetNo(place: JsonNode, lat: Double, lon: Double, unit: String): Mono<Map<String, Any>> =
        webClient.get()
            .uri("$MET_NO_URL?lat=$lat&lon=$lon")
            .header("User-Agent", MET_NO_USER_AGENT)
            .retrieve()
            .bodyToMono(JsonNode::class.java)
            .map { root ->
                buildWeather(
                    root,
                    unit,
                    place.path("name").asText(""),
                    place.path("country").asText(""),
                )
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
        private const val GEOCODING_URL = "https://geocoding-api.open-meteo.com/v1/search"
        private const val MET_NO_URL = "https://api.met.no/weatherapi/locationforecast/2.0/compact"
        private const val MET_NO_USER_AGENT = "ai-advent-challenge/0.1.0 (info@pavelveselov.ru)"
        private const val MET_NO_SOURCE = "met.no"
        private const val DEFAULT_LIMIT = 10
        private const val MIN_LIMIT = 1
        private const val MAX_LIMIT = 25
        private const val DEFAULT_CITY = "Москва"

        /** Человеческие подписи для базовых symbol_code met.no (суффикс _day/_night отбрасывается). */
        private val SYMBOL_LABELS = mapOf(
            "clearsky" to "Ясно",
            "fair" to "Малооблачно",
            "partlycloudy" to "Переменная облачность",
            "cloudy" to "Облачно",
            "fog" to "Туман",
            "lightrain" to "Небольшой дождь",
            "rain" to "Дождь",
            "heavyrain" to "Сильный дождь",
            "lightrainshowers" to "Небольшие дожди",
            "rainshowers" to "Ливни",
            "heavyrainshowers" to "Сильные ливни",
            "lightsnow" to "Небольшой снег",
            "snow" to "Снег",
            "heavysnow" to "Сильный снег",
            "lightsnowshowers" to "Небольшой снег",
            "snowshowers" to "Снегопад",
            "heavysnowshowers" to "Сильный снегопад",
            "sleet" to "Мокрый снег",
            "lightsleet" to "Небольшой мокрый снег",
            "heavysleet" to "Сильный мокрый снег",
            "thunderstorm" to "Гроза",
        )

        /** Короткая русская подпись погоды по symbol_code met.no; фолбэк — сырой код. */
        private fun describeSymbol(symbolCode: String): String {
            if (symbolCode.isBlank()) return "нет данных"
            return SYMBOL_LABELS[symbolCode.substringBefore("_")] ?: symbolCode
        }

        /**
         * Чистая функция сборки ответа о погоде из формата met.no (GeoJSON) — покрывается
         * тестами без сети. Город и страна приходят из геокодинга Open-Meteo.
         */
        fun buildWeather(root: JsonNode, unit: String, city: String, country: String): Map<String, Any> {
            val fahrenheit = unit.lowercase() in setOf("fahrenheit", "f")
            val slot = root.path("properties").path("timeseries").path(0)
            val details = slot.path("data").path("instant").path("details")
            val symbolCode = slot.path("data").path("next_1_hours").path("summary").path("symbol_code").asText("")

            val tempC = details.path("air_temperature").asDouble()
            return linkedMapOf(
                "city" to city,
                "country" to country,
                "temperature" to (if (fahrenheit) round2(tempC * 9.0 / 5.0 + 32.0) else tempC),
                "temperature_unit" to (if (fahrenheit) "°F" else "°C"),
                "humidity" to details.path("relative_humidity").asDouble(),
                "wind_speed" to details.path("wind_speed").asDouble(),
                "wind_speed_unit" to "m/s",
                "weather" to describeSymbol(symbolCode),
                "time" to slot.path("time").asText(""),
                "source" to MET_NO_SOURCE,
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
