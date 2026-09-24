package com.example.mcpserver.scheduler

import com.example.mcpserver.collector.SourceCollector
import reactor.core.publisher.Mono

/**
 * Фейковый [SourceCollector] для тестов планировщика: возвращает готовые данные без
 * обращения к сети. Позволяет проверить, что движок записывает запуски в task_runs.
 */
class FakeSourceCollector : SourceCollector {

    var weatherResult: Map<String, Any> = mapOf("temperature" to 20.0, "city" to "Москва")
    var currencyResult: Map<String, Any> = mapOf(
        "base" to "RUB",
        "rates" to mapOf("USD" to mapOf("rate" to 90.5, "name" to "Доллар США")),
    )
    var newsResult: List<Map<String, Any>> = listOf(mapOf("title" to "Первая новость"))
    var throwOnCollect: Boolean = false
    var collectCount: Int = 0
        private set

    override fun weather(city: String, units: String?): Mono<Map<String, Any>> =
        Mono.just(weatherResult)

    override fun currency(code: String?): Mono<Map<String, Any>> =
        Mono.just(currencyResult)

    override fun news(limit: Int?): Mono<List<Map<String, Any>>> =
        Mono.just(newsResult)

    override fun collect(source: String, params: Map<String, Any?>?): Mono<Any?> {
        collectCount++
        if (throwOnCollect) {
            return Mono.error(RuntimeException("источник упал"))
        }
        return when (source.lowercase()) {
            "weather" -> Mono.just(weatherResult).map { it as Any? }
            "currency" -> Mono.just(currencyResult).map { it as Any? }
            "news" -> Mono.just(newsResult).map { it as Any? }
            else -> Mono.just(mapOf("error" to "неизвестный источник")).map { it as Any? }
        }
    }
}
