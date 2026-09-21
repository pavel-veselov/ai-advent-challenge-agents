package com.example.mcpserver.config

import com.fasterxml.jackson.databind.ObjectMapper
import io.netty.channel.ChannelOption
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.MediaType
import org.springframework.http.client.reactive.ReactorClientHttpConnector
import org.springframework.http.codec.json.Jackson2JsonDecoder
import org.springframework.web.reactive.function.client.ExchangeStrategies
import org.springframework.web.reactive.function.client.WebClient
import reactor.netty.http.client.HttpClient
import java.time.Duration

/**
 * Общий реактивный HTTP-клиент (Netty + WebClient) для инструментов, которые
 * обращаются к внешним публичным API. Настроен на разумные таймауты, чтобы
 * зависший внешний сервис не держал запрос к MCP-серверу вечно.
 */
@Configuration
class HttpConfig {

    @Bean
    fun httpClient(): HttpClient =
        HttpClient.create()
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5_000)
            .responseTimeout(Duration.ofSeconds(15))

    @Bean
    fun webClient(httpClient: HttpClient, objectMapper: ObjectMapper): WebClient {
        // cbr-xml-daily.ru отдаёт JSON с Content-Type: application/javascript (и иногда
        // text/javascript), который стандартный JSON-декодер WebClient не принимает.
        // Jackson2JsonDecoder принимает media types через конструктор — расширяем их,
        // чтобы такие ответы парсились как JSON.
        val jsonDecoder = Jackson2JsonDecoder(
            objectMapper,
            MediaType.APPLICATION_JSON,
            MediaType.valueOf("application/*+json"),
            MediaType.valueOf("application/javascript"),
            MediaType.valueOf("text/javascript"),
        )

        val exchangeStrategies = ExchangeStrategies.builder()
            .codecs { configurer ->
                configurer.defaultCodecs().maxInMemorySize(4 * 1024 * 1024)
                configurer.customCodecs().registerWithDefaultConfig(jsonDecoder)
            }
            .build()

        return WebClient.builder()
            .clientConnector(ReactorClientHttpConnector(httpClient))
            .exchangeStrategies(exchangeStrategies)
            .build()
    }
}
