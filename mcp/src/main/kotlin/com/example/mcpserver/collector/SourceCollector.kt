package com.example.mcpserver.collector

import reactor.core.publisher.Mono

/**
 * Единая точка обращения к «источникам данных» (погода, курсы валют, новости).
 *
 * Используется двумя способами:
 *  - сами MCP-инструменты ([com.example.mcpserver.tools.WeatherTool], CurrencyTool,
 *    NewsTool) делегируют сюда сетевой вызов — логика внешних API не дублируется;
 *  - планировщик (Day-18) вызывает [collect] по строковому имени источника и
 *    параметрам задачи, чтобы периодически собирать данные и складывать их в БД.
 *
 * Реализация по умолчанию — [HttpSourceCollector] (WebClient поверх публичных API).
 */
interface SourceCollector {

    /** Текущая погода в городе; [units] — celsius | fahrenheit. */
    fun weather(city: String, units: String?): Mono<Map<String, Any>>

    /** Курс валюты ЦБ РФ по коду (или все курсы, если [code] == null). */
    fun currency(code: String?): Mono<Map<String, Any>>

    /** Топовые новости Hacker News; [limit] — количество историй. */
    fun news(limit: Int?): Mono<List<Map<String, Any>>>

    /**
     * Унифицированный вызов по строковому имени источника и параметрам задачи
     * (используется планировщиком). Источники: weather | currency | news.
     * Неизвестный источник — Mono с ошибкой в результате.
     */
    fun collect(source: String, params: Map<String, Any?>?): Mono<Any?>
}
