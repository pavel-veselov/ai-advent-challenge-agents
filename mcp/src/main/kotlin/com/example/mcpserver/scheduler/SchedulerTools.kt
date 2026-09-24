package com.example.mcpserver.scheduler

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.springaicommunity.mcp.annotation.McpTool
import org.springaicommunity.mcp.annotation.McpToolParam
import org.springframework.stereotype.Component
import reactor.core.publisher.Mono

/**
 * MCP-инструменты планировщика (Day-18): управление задачами периодического сбора и
 * агрегация собранных результатов.
 *
 * Возвращаем Mono<Map> / Mono<List> (как существующие инструменты), потому что
 * MCP-представление сериализует их в `.text` как JSON — backend (McpStreamableClient)
 * читает именно `.text`. Контракт полей зафиксирован ниже; намеренно НЕ возвращаем
 * Mono<String> с уже сериализованным JSON, чтобы избежать двойного экранирования.
 */
@Component
class SchedulerTools(
    private val store: SchedulerStore,
    private val engine: SchedulerEngine,
    private val om: ObjectMapper,
) {

    @McpTool(
        name = "scheduler_add_task",
        title = "Добавить задачу планировщика",
        description = "Создаёт периодическую задачу сбора данных (weather | currency | news) и ставит её на расписание. " +
            "interval_seconds — период в секундах (минимум 5). params — параметры источника (например city/units).",
    )
    fun addTask(
        @McpToolParam(description = "Название задачи.", required = true) name: String,
        @McpToolParam(description = "Источник: weather | currency | news.", required = true) source: String,
        @McpToolParam(description = "Период в секундах (>= 5).", required = true) intervalSeconds: Int,
        @McpToolParam(description = "Параметры источника (JSON-объект).", required = false) params: Map<String, Any?>?,
    ): Mono<Map<String, Any?>> {
        val validSource = source.trim().lowercase() in VALID_SOURCES
        if (intervalSeconds < MIN_INTERVAL || !validSource) {
            return Mono.just(mapOf("error" to "interval_seconds должен быть >= $MIN_INTERVAL, source — один из $VALID_SOURCES"))
        }
        val paramsJson = params?.let { om.writeValueAsString(it) }
        val task = engine.addTask(name, source, intervalSeconds, paramsJson) ?: return Mono.just(mapOf("error" to "Не удалось создать задачу"))
        return Mono.just(
            mapOf(
                "id" to task.id,
                "name" to task.name,
                "source" to task.source,
                "intervalSeconds" to task.intervalSeconds,
                "active" to task.active,
            ),
        )
    }

    @McpTool(
        name = "scheduler_update_interval",
        title = "Обновить интервал задачи",
        description = "Меняет период опроса задачи (interval_seconds >= 5). Исполнитель перенастраивается.",
    )
    fun updateInterval(
        @McpToolParam(description = "ID задачи.", required = true) id: Long,
        @McpToolParam(description = "Новый период в секундах (>= 5).", required = true) intervalSeconds: Int,
    ): Mono<Map<String, Any?>> {
        if (intervalSeconds < MIN_INTERVAL) {
            return Mono.just(mapOf("error" to "interval_seconds должен быть >= $MIN_INTERVAL"))
        }
        val task = engine.updateInterval(id, intervalSeconds)
            ?: return Mono.just(mapOf("error" to "Задача $id не найдена"))
        return Mono.just(
            mapOf(
                "id" to task.id,
                "intervalSeconds" to task.intervalSeconds,
                "lastRunAt" to task.lastRunAt,
            ),
        )
    }

    @McpTool(
        name = "scheduler_remove_task",
        title = "Удалить задачу планировщика",
        description = "Отменяет и удаляет задачу. Историю запусков не трогает.",
    )
    fun removeTask(
        @McpToolParam(description = "ID задачи.", required = true) id: Long,
    ): Mono<Map<String, Any?>> =
        Mono.just(mapOf("ok" to engine.removeTask(id)))

    @McpTool(
        name = "scheduler_list_tasks",
        title = "Список задач планировщика",
        description = "Возвращает все задачи с интервалом, признаком активности и количеством запусков.",
    )
    fun listTasks(): Mono<List<Map<String, Any?>>> {
        val tasks = store.listTasks()
        val result = tasks.map { t ->
            mapOf(
                "id" to t.id,
                "name" to t.name,
                "source" to t.source,
                "intervalSeconds" to t.intervalSeconds,
                "active" to t.active,
                "lastRunAt" to t.lastRunAt,
                "runsCount" to store.countRuns(t.id),
            )
        }
        return Mono.just(result)
    }

    @McpTool(
        name = "scheduler_summary",
        title = "Сводка данных за период",
        description = "Агрегирует собранные данные из task_runs: для weather — min/max/avg температуры, " +
            "для currency — последние курсы, для news — последние заголовки. since_hours — окно (по умолчанию 24).",
    )
    fun summary(
        @McpToolParam(description = "ID задачи (по умолчанию — все, без разбора по источнику).", required = false) taskId: Long?,
        @McpToolParam(description = "Окно агрегации в часах (по умолчанию 24).", required = false) sinceHours: Int?,
    ): Mono<Map<String, Any?>> {
        val window = (sinceHours ?: DEFAULT_SINCE_HOURS).coerceAtLeast(1)
        val source = taskId?.let { store.findTask(it)?.source }
        val runs = store.listRuns(taskId, window)
        return Mono.just(buildSummary(source, taskId, window, runs))
    }

    @McpTool(
        name = "scheduler_data_range",
        title = "Данные за период",
        description = "Возвращает собранные данные (task_runs) за окно since_hours для построения сводки. " +
            "Каждый элемент runs — {id, taskId, source, status, startedAt, finishedAt, resultJson, error}; " +
            "result_json разобран в объект (temperature/rates/titles). since_hours — окно (по умолчанию 24).",
    )
    fun dataRange(
        @McpToolParam(description = "ID задачи (по умолчанию — все).", required = false) taskId: Long?,
        @McpToolParam(description = "Окно в часах (по умолчанию 24).", required = false) sinceHours: Int?,
    ): Mono<Map<String, Any?>> {
        val window = (sinceHours ?: DEFAULT_SINCE_HOURS).coerceAtLeast(1)
        val runs = store.listRuns(taskId, window)
        val list = runs.map { r ->
            mapOf(
                "id" to r.id,
                "taskId" to r.taskId,
                "source" to r.source,
                "status" to r.status,
                "startedAt" to r.startedAt,
                // Колонки finished_at в task_runs нет — запуск фиксируется одной записью (started_at).
                "finishedAt" to null,
                "resultJson" to parseResult(r.resultJson),
                "error" to r.error,
            )
        }
        return Mono.just(
            mapOf(
                "taskId" to taskId,
                "count" to list.size,
                "since" to window,
                "runs" to list,
            ),
        )
    }

    /** Разбирает result_json в простую структуру (Map/List/примитив); сбой — null. */
    private fun parseResult(json: String?): Any? {
        return try {
            json?.let { om.convertValue(om.readTree(it), Any::class.java) }
        } catch (e: Exception) {
            null
        }
    }

    /** Собирает сводку в зависимости от источника. */
    private fun buildSummary(source: String?, taskId: Long?, sinceHours: Int, runs: List<TaskRun>): Map<String, Any?> {
        val successful = runs.filter { it.status == STATUS_SUCCESS && it.resultJson != null }
        val latest = runs.lastOrNull()

        return when (source?.lowercase()) {
            "weather" -> weatherSummary(taskId, sinceHours, successful)
            "currency" -> currencySummary(sinceHours, successful, latest)
            "news" -> newsSummary(sinceHours, successful, latest)
            else -> mapOf(
                "count" to runs.size,
                "since" to sinceHours,
                "latestAt" to latest?.startedAt,
            )
        }
    }

    private fun weatherSummary(taskId: Long?, sinceHours: Int, runs: List<TaskRun>): Map<String, Any?> {
        val temps = runs.mapNotNull { it.resultJson?.let { json -> temperatureOf(json) } }
        val latest = temps.lastOrNull()
        return mapOf(
            "taskId" to taskId,
            "count" to temps.size,
            "since" to sinceHours,
            "min" to (temps.minOrNull()?.let { formatDouble(it) }),
            "max" to (temps.maxOrNull()?.let { formatDouble(it) }),
            "avg" to (if (temps.isNotEmpty()) formatDouble(temps.average()) else null),
            "latest" to latest,
        )
    }

    private fun currencySummary(sinceHours: Int, runs: List<TaskRun>, latest: TaskRun?): Map<String, Any?> {
        val latestResult = latest?.resultJson?.let { om.readTree(it) }
        return mapOf(
            "count" to runs.size,
            "since" to sinceHours,
            "latest" to latestResult,
            "ratesList" to rateList(latestResult),
        )
    }

    private fun newsSummary(sinceHours: Int, runs: List<TaskRun>, latest: TaskRun?): Map<String, Any?> {
        val titles = latest?.resultJson?.let { json -> titlesOf(json) }
        return mapOf(
            "count" to runs.size,
            "since" to sinceHours,
            "latestTitles" to titles,
        )
    }

    /** Температура из result_json (поле temperature). */
    private fun temperatureOf(json: String): Double? {
        return try {
            val t = om.readTree(json).path("temperature")
            if (t.isNumber) t.asDouble() else null
        } catch (e: Exception) {
            null
        }
    }

    /** Заголовки из result_json (массив объектов с полем title). */
    private fun titlesOf(json: String): List<String> {
        return try {
            val node = om.readTree(json)
            if (node.isArray) {
                node.mapNotNull { it.path("title").asText(null) }
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** Список записей курсов из последнего результата: [{code, rate, change_from_previous, nominal, name}]. */
    private fun rateList(node: JsonNode?): List<Map<String, Any?>> {
        val rates = node?.path("rates") ?: return emptyList()
        if (!rates.isObject) return emptyList()
        return rates.properties()
            .map { (code, v) ->
                mapOf(
                    "code" to code,
                    "rate" to v.path("rate").asDouble(),
                    "change_from_previous" to v.path("change_from_previous").asDouble(),
                    "nominal" to v.path("nominal").asInt(),
                    "name" to v.path("name").asText(code),
                )
            }
            .sortedBy { it["code"] as? String }
    }

    private fun formatDouble(v: Double): Double = Math.round(v * 100.0) / 100.0

    private companion object {
        const val MIN_INTERVAL = 5
        const val DEFAULT_SINCE_HOURS = 24
        const val STATUS_SUCCESS = "SUCCESS"
        val VALID_SOURCES = setOf("weather", "currency", "news")
    }
}
