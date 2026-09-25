package com.example.mcp2.tools

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.springaicommunity.mcp.annotation.McpTool
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import reactor.core.publisher.Mono
import java.time.Duration

/**
 * Единственный инструмент MCP-2: определяет город пользователя по его исходящему IP-адресу.
 *
 * Сервер обращается к публичному бесплатному API [IP_API_URL] (ip-api.com, без ключа, без
 * фильтра полей — поэтому приходит и lat/lon, и region). Точка входа возвращает геолокацию
 * запрашивающего IP (того, откуда идёт запрос от MCP-2 наружу).
 *
 * ВАЖНО — FAIL-OPEN: инструмент никогда не выбрасывает исключение и не «висит». Любая ошибка
 * (недоступность сети, таймаут, HTTP-ошибка, некорректный JSON или `result:false`) превращается
 * в Mono с человекочитаемым сообщением об ошибке.
 */
@Component
class LocationTool {

    private val webClient: WebClient = WebClient.create()

    @McpTool(
        name = "get_user_city",
        title = "Город пользователя",
        description = "Определяет город пользователя по IP-адресу (ip-api.com). " +
            "Возвращает city, country, region, lat, lon и ip.",
    )
    fun getUserCity(): Mono<Map<String, Any?>> =
        webClient.get()
            .uri(IP_API_URL)
            .retrieve()
            .bodyToMono(JsonNode::class.java)
            .map { node -> parseNode(node) }
            .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
            .onErrorResume { e -> Mono.just(errorMap(e)) }

    companion object {
        private const val IP_API_URL = "http://ip-api.com/json/"
        private const val TIMEOUT_SECONDS = 5L

        /** Точка входа чистой логики: JSON-строка -> результат инструмента (для тестов). */
        fun parse(json: String): Map<String, Any?> =
            try {
                parseNode(ObjectMapper().readTree(json))
            } catch (e: Exception) {
                errorMap(e)
            }

        /** Чистая функция разбора ответа ip-api в карту результата (тестируема без сети). */
        fun parseNode(node: JsonNode): Map<String, Any?> {
            if (isFailure(node)) {
                return mapOf(
                    "error" to "Не удалось определить город по IP",
                    "message" to node.path("message").asText(""),
                )
            }
            return mapOf(
                "result" to true,
                "city" to node.path("city").asText(""),
                "country" to node.path("country").asText(""),
                "region" to node.path("region").asText(""),
                "lat" to node.path("lat").asDouble(),
                "lon" to node.path("lon").asDouble(),
                "ip" to node.path("query").asText(""),
            )
        }

        /**
         * Успех определяется по двум маркерам: `result: false` (как в спецификации задачи)
         * либо `status != "success"` (как реально отдаёт ip-api.com v2).
         */
        private fun isFailure(node: JsonNode): Boolean {
            val result = node.path("result")
            if (result.isBoolean && !result.asBoolean()) return true
            val status = node.path("status")
            if (status.isTextual && status.asText().isNotEmpty() && !status.asText().equals("success", ignoreCase = true)) {
                return true
            }
            return false
        }

        /** Дружелюбная карта ошибки вместо исключения/зависания. */
        private fun errorMap(e: Throwable): Map<String, Any?> =
            mapOf(
                "error" to "Не удалось определить город по IP",
                "message" to (e.message ?: e.javaClass.simpleName),
            )
    }
}
