package com.example.llmagent.transport

import com.example.llmagent.mcp.McpSchedulerClient
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * REST для панели планировщика (Day-17/18): фронтенд управляет задачами papkin-helper и
 * запрашивает агрегированную сводку. Транспорт не знает MCP-протокол — все вызовы
 * делегируются [McpSchedulerClient] по ИМЕНИ инструмента:
 *
 *   GET    /api/scheduler/tasks              → scheduler_list_tasks
 *   POST   /api/scheduler/tasks              → scheduler_add_task     {name, source, interval_seconds, params?}
 *   PATCH  /api/scheduler/tasks/{id}/interval → scheduler_update_interval {id, interval_seconds}
 *   DELETE /api/scheduler/tasks/{id}          → scheduler_remove_task   {id}
 *   POST   /api/scheduler/summary             → scheduler_summary       {task_id?, since_hours?}
 *
 * Контракт с papkin-helper (MCP): аргументы и поля ответа — camelCase (`intervalSeconds`,
 * `taskId`, `sinceHours`; `id:Long`, `intervalSeconds:Int`, `sinceHours:Int`). Фронтенд же
 * общается по snake_case (`interval_seconds`, `task_id`, `since_hours`). Поэтому этот слой
 * двусторонне преобразует: ВХОД (фронт snake_case) → camelCase в аргументы MCP; ВЫХОД
 * (MCP camelCase) → snake_case в ответ фронтенду.
 *
 * Fail-open: MCP недоступен/ошибка → JSON "{error: ...}" + 502.
 */
@RestController
@RequestMapping("/api/scheduler")
class SchedulerController(
    private val mcpClient: McpSchedulerClient,
    private val om: ObjectMapper,
) {

    companion object {
        private val log = LoggerFactory.getLogger(SchedulerController::class.java)
    }

    /** Тело POST /tasks (snake_case в JSON — контракт фронтенда). */
    data class AddTaskRequest(
        val name: String? = null,
        val source: String? = null,
        @JsonProperty("interval_seconds") val intervalSeconds: Long? = null,
        val params: Map<String, Any?>? = null,
    )

    /** Тело PATCH /tasks/{id}/interval. */
    data class IntervalRequest(
        @JsonProperty("interval_seconds") val intervalSeconds: Long? = null,
    )

    /** Тело POST /summary: оба поля опциональны (snake_case в JSON). */
    data class SummaryRequest(
        @JsonProperty("task_id") val taskId: String? = null,
        @JsonProperty("since_hours") val sinceHours: Long? = null,
    )

    data class ErrorResponse(val error: String)

    @GetMapping("/tasks")
    suspend fun listTasks(): ResponseEntity<Any> = proxy("scheduler_list_tasks", emptyMap())

    @PostMapping("/tasks")
    suspend fun addTask(@RequestBody body: AddTaskRequest): ResponseEntity<Any> {
        val args = HashMap<String, Any?>()
        body.name?.let { args["name"] = it }
        body.source?.let { args["source"] = it }
        body.intervalSeconds?.let { args["intervalSeconds"] = it.toInt() }
        body.params?.let { args["params"] = it }
        return proxy("scheduler_add_task", args)
    }

    @PatchMapping("/tasks/{id}/interval")
    suspend fun updateInterval(
        @PathVariable id: String,
        @RequestBody body: IntervalRequest,
    ): ResponseEntity<Any> {
        val idLong = id.toLongOrNull()
        if (idLong == null) return badId()
        val args = HashMap<String, Any?>()
        args["id"] = idLong
        body.intervalSeconds?.let { args["intervalSeconds"] = it.toInt() }
        return proxy("scheduler_update_interval", args)
    }

    @DeleteMapping("/tasks/{id}")
    suspend fun removeTask(@PathVariable id: String): ResponseEntity<Any> {
        val idLong = id.toLongOrNull()
        if (idLong == null) return badId()
        return proxy("scheduler_remove_task", mapOf("id" to idLong))
    }

    @PostMapping("/summary")
    suspend fun summary(@RequestBody body: SummaryRequest): ResponseEntity<Any> {
        val args = HashMap<String, Any?>()
        body.taskId?.toLongOrNull()?.let { args["taskId"] = it }
        body.sinceHours?.let { args["sinceHours"] = it.toInt() }
        return proxy("scheduler_summary", args)
    }

    private fun badId(): ResponseEntity<Any> =
        ResponseEntity.badRequest().body(ErrorResponse("Некорректный id задачи"))

    /**
     * Единая точка вызова MCP-инструмента: успех → JSON (тело распарсено, поля
     * рекурсивно конвертированы camelCase→snake_case) либо raw-обёртка, если тело не JSON;
     * недоступный сервер / ошибка инструмента → {error} + 502 (fail-open).
     */
    private suspend fun proxy(tool: String, args: Map<String, Any?>): ResponseEntity<Any> {
        val result = mcpClient.callTool(tool, args)
        if (result == null) {
            log.warn("scheduler proxy '{}' -> 502: MCP-сервер недоступен", tool)
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(ErrorResponse("MCP-сервер планировщика недоступен (инструмент $tool)"))
        }
        if (result.isError) {
            log.warn("scheduler proxy '{}' -> 502: {}", tool, result.text)
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(ErrorResponse(result.text))
        }
        val text = result.text.trim()
        if (text.isEmpty()) return ResponseEntity.ok(om.createObjectNode())
        return try {
            ResponseEntity.ok(toSnake(om.readTree(text)))
        } catch (_: Exception) {
            // Тело инструмента не JSON (например plain-текст) — отдаём raw-обёрткой.
            ResponseEntity.ok(mapOf("result" to text))
        }
    }

    /** Рекурсивно переводит все ключи объекта/массива camelCase → snake_case. */
    private fun toSnake(node: JsonNode): JsonNode = when {
        node.isObject -> {
            val obj = om.createObjectNode()
            node.properties().forEach { (k, v) -> obj.set<JsonNode>(camelToSnake(k), toSnake(v)) }
            obj
        }
        node.isArray -> {
            val arr = om.createArrayNode()
            node.forEach { arr.add(toSnake(it)) }
            arr
        }
        else -> node
    }

    /** intervalSeconds → interval_seconds; taskId → task_id. */
    private fun camelToSnake(s: String): String {
        val sb = StringBuilder(s.length + 4)
        for (c in s) {
            if (c.isUpperCase()) sb.append('_').append(c.lowercaseChar()) else sb.append(c)
        }
        return sb.toString()
    }
}
