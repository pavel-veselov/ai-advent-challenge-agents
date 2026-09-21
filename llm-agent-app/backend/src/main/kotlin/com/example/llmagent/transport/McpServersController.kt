package com.example.llmagent.transport

import com.example.llmagent.agent.McpServer
import com.example.llmagent.agent.McpServersStore
import com.example.llmagent.mcp.McpToolsManager
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Управление MCP-серверами (Day-16) — REST-эндпоинты БЕЗ SSE: после мутаций фронтенд сам
 * делает refetch. Агент-клиент управляет серверами инструментов Streamable-HTTP:
 *   GET    /api/mcp-servers           — список серверов (всегда 200, пустой список для новых).
 *   POST   /api/mcp-servers           — добавить сервер `{name,url}`: name/url blank → 400;
 *                                          дубликат имени → 409; успех → 201 (enabled=false).
 *   PUT    /api/mcp-servers/{id}/enabled — включить/отключить `{enabled}`: 404 — нет сервера;
 *                                          при включении инструменты активируются, при
 *                                          отключении — снимаются из реестра.
 *   DELETE /api/mcp-servers/{id}      — удалить сервер: 404 — нет; иначе `{deleted:true}`.
 *
 * Транспорт не знает про MCP-протокол — всё делегируется [McpToolsManager] (активация)
 * и [McpServersStore] (персистентность).
 */
@RestController
@RequestMapping("/api/mcp-servers")
class McpServersController(
    private val store: McpServersStore,
    private val manager: McpToolsManager,
) {

    /** Тело POST: name и url обязательны (blank → 400). */
    data class McpServerRequest(val name: String? = null, val url: String? = null)

    /** Тело PUT /{id}/enabled: только флаг enabled. */
    data class EnabledRequest(val enabled: Boolean? = null)

    /** Сводка инструмента сервера: имя + описание (для UI). */
    data class McpToolSummary(val name: String, val description: String)

    /** Ответ по серверу: сам сервер + активные инструменты. */
    data class McpServerDto(
        val id: Long,
        val name: String,
        val url: String,
        val enabled: Boolean,
        val createdAt: String,
        val updatedAt: String,
        val tools: List<McpToolSummary>,
    )

    data class DeleteResponse(val deleted: Boolean)

    data class ErrorResponse(val error: String)

    @GetMapping
    fun list(): List<McpServerDto> = store.list().map { toDto(it) }

    @PostMapping
    fun add(@RequestBody body: McpServerRequest): ResponseEntity<Any> {
        val name = body.name?.trim()
        val url = body.url?.trim()
        if (name.isNullOrBlank() || url.isNullOrBlank()) {
            return ResponseEntity.badRequest().body(ErrorResponse("name и url не должны быть пустыми"))
        }
        val created = store.add(name, url)
        if (created != null) {
            return ResponseEntity.status(HttpStatus.CREATED).body(toDto(created))
        }
        // add вернул null — дубликат имени (UNIQUE) либо сбой БД
        if (store.list().any { it.name == name }) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(ErrorResponse("MCP-сервер с именем '$name' уже существует"))
        }
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ErrorResponse("Не удалось сохранить MCP-сервер"))
    }

    @PutMapping("/{id}/enabled")
    suspend fun setEnabled(
        @PathVariable id: Long,
        @RequestBody body: EnabledRequest,
    ): ResponseEntity<Any> {
        val enabled = body.enabled
            ?: return ResponseEntity.badRequest().body(ErrorResponse("enabled: ожидалось true/false"))
        if (store.findById(id) == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ErrorResponse("MCP-сервер не найден: $id"))
        }
        val updated = store.setEnabled(id, enabled)
            ?: return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ErrorResponse("MCP-сервер не найден: $id"))
        if (enabled) {
            manager.activate(updated)
        } else {
            manager.deactivate(id)
        }
        return ResponseEntity.ok(toDto(updated))
    }

    @DeleteMapping("/{id}")
    suspend fun delete(@PathVariable id: Long): ResponseEntity<Any> {
        val server = store.findById(id)
            ?: return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ErrorResponse("MCP-сервер не найден: $id"))
        if (server.enabled) manager.deactivate(id)
        if (store.delete(id)) {
            return ResponseEntity.ok(DeleteResponse(true))
        }
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ErrorResponse("MCP-сервер не найден: $id"))
    }

    private fun toDto(server: McpServer): McpServerDto = McpServerDto(
        id = server.id,
        name = server.name,
        url = server.url,
        enabled = server.enabled,
        createdAt = server.createdAt,
        updatedAt = server.updatedAt,
        tools = manager.toolsFor(server.id).map { McpToolSummary(it.name, it.description) },
    )
}
