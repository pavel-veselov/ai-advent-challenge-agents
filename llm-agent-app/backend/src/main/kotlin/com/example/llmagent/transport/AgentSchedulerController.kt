package com.example.llmagent.transport

import com.example.llmagent.agent.AgentSchedulerStore
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
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
 * REST управления периодическими задачами планировщика (Day-17/18): фронтенд читает и
 * редактирует jobs из agent_scheduler_jobs через [AgentSchedulerStore]. Провод snake_case
 * (контракт фронтенда) — тот же двусторонний приём, что в SchedulerController: входные
 * тела принимают snake_case (@JsonProperty), ответы отдаются рекурсивно конвертированными
 * camelCase → snake_case (см. [toSnake]).
 *
 * GET    /api/agent-scheduler            → список задач (snake_case, всегда 200)
 * POST   /api/agent-scheduler            → 201 созданная задача (snake_case); 400 — невалидное тело
 * PATCH  /api/agent-scheduler/{id}       → 200 обновлённая задача; 400 — некорректный id; 404 — нет записи
 * DELETE /api/agent-scheduler/{id}       → {ok:true}; 400 — некорректный id; 404 — нет записи
 */
@RestController
@RequestMapping("/api/agent-scheduler")
class AgentSchedulerController(
    private val store: AgentSchedulerStore,
    private val om: ObjectMapper,
) {

    /** Тело POST (snake_case в JSON — контракт фронтенда). */
    data class CreateRequest(
        val name: String,
        @JsonProperty("interval_seconds") val intervalSeconds: Long,
        val prompt: String,
    )

    /** Тело PATCH: все поля опциональны (частичное обновление). */
    data class UpdateRequest(
        val name: String? = null,
        @JsonProperty("interval_seconds") val intervalSeconds: Long? = null,
        val prompt: String? = null,
        val enabled: Boolean? = null,
    )

    data class ErrorResponse(val error: String)

    @GetMapping
    fun list(): ResponseEntity<Any> {
        val array = om.createArrayNode()
        store.list().forEach { job -> array.add(toSnake(om.valueToTree(job))) }
        return ResponseEntity.ok(array)
    }

    @PostMapping
    fun create(@RequestBody body: CreateRequest): ResponseEntity<Any> {
        val name = body.name.trim()
        val prompt = body.prompt.trim()
        if (name.isBlank() || prompt.isBlank() || body.intervalSeconds <= 0) {
            return ResponseEntity.badRequest().body(ErrorResponse("Поля name (непустая), interval_seconds (> 0) и prompt (непустой) обязательны"))
        }
        val job = store.create(name, body.intervalSeconds, prompt)
            ?: return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ErrorResponse("Не удалось создать задачу (сбой БД)"))
        return ResponseEntity.status(HttpStatus.CREATED).body(toSnake(om.valueToTree(job)))
    }

    @PatchMapping("/{id}")
    fun update(@PathVariable id: String, @RequestBody body: UpdateRequest): ResponseEntity<Any> {
        val idLong = id.toLongOrNull() ?: return badId()
        val job = store.update(idLong, body.name, body.intervalSeconds, body.prompt, body.enabled)
            ?: return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ErrorResponse("Задача $idLong не найдена"))
        return ResponseEntity.ok(toSnake(om.valueToTree(job)))
    }

    @DeleteMapping("/{id}")
    fun delete(@PathVariable id: String): ResponseEntity<Any> {
        val idLong = id.toLongOrNull() ?: return badId()
        return if (store.delete(idLong)) {
            ResponseEntity.ok(mapOf("ok" to true))
        } else {
            ResponseEntity.status(HttpStatus.NOT_FOUND).body(ErrorResponse("Задача $idLong не найдена"))
        }
    }

    private fun badId(): ResponseEntity<Any> =
        ResponseEntity.badRequest().body(ErrorResponse("Некорректный id задачи"))

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

    /** intervalSeconds → interval_seconds; lastRunAt → last_run_at. */
    private fun camelToSnake(s: String): String {
        val sb = StringBuilder(s.length + 4)
        for (c in s) {
            if (c.isUpperCase()) sb.append('_').append(c.lowercaseChar()) else sb.append(c)
        }
        return sb.toString()
    }
}
