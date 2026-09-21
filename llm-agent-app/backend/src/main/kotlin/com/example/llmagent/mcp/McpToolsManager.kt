package com.example.llmagent.mcp

import com.example.llmagent.agent.McpServer
import com.example.llmagent.agent.McpServersStore
import com.example.llmagent.agent.ToolRegistry
import com.fasterxml.jackson.databind.ObjectMapper
import java.util.concurrent.ConcurrentHashMap
import jakarta.annotation.PostConstruct
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Оркестратор MCP-серверов (Day-16): связывает персистентное хранилище [McpServersStore]
 * с динамическим реестром инструментов [ToolRegistry]. При активации сервера клиент
 * ([McpStreamableClient]) списывает у него список инструментов, и каждый инструмент
 * регистрируется в реестре, чтобы агент (через существующий путь `toolRegistry.get(name)`)
 * мог его вызвать.
 *
 * Всё fail-open: сбой подключения/списка инструментов не роняет агент — warn в лог и
 * пустой список инструментов (сервер просто не активируется).
 */
@Component
class McpToolsManager(
    private val store: McpServersStore,
    private val registry: ToolRegistry,
    private val om: ObjectMapper,
) {

    private val log = LoggerFactory.getLogger(McpToolsManager::class.java)

    private val toolsByServer = ConcurrentHashMap<Long, List<McpToolInfo>>()

    /** При старте активируем все включённые серверы (fail-open по каждому). */
    @PostConstruct
    fun init() {
        store.list().filter { it.enabled }.forEach { server ->
            try {
                runBlocking { activate(server) }
            } catch (e: Exception) {
                log.warn("MCP автоактивация '{}' не удалась: {}", server.name, e.message)
            }
        }
    }

    /**
     * Активирует сервер: читает список инструментов и регистрирует каждый в [ToolRegistry].
     * Возвращает список зарегистрированных инструментов (пустой — при сбое, fail-open).
     */
    suspend fun activate(server: McpServer): List<McpToolInfo> {
        return try {
            val client = McpStreamableClient(server.url, om = om)
            val tools = client.listTools()
            tools.forEach { t ->
                registry.register(McpToolAdapter(t.name, t.description, t.inputSchema, client))
            }
            toolsByServer[server.id] = tools
            log.info("MCP-сервер '{}' активирован: {} инструментов", server.name, tools.size)
            tools
        } catch (e: Exception) {
            log.warn("MCP активация '{}' не удалась: {}", server.name, e.message)
            emptyList()
        }
    }

    /** Деактивирует сервер: снимает его инструменты из реестра и из toolsByServer. */
    fun deactivate(serverId: Long) {
        val tools = toolsByServer.remove(serverId)
        tools?.forEach { registry.unregister(it.name) }
    }

    /** Инструменты, зарегистрированные для сервера (пустой список — не активирован). */
    fun toolsFor(serverId: Long): List<McpToolInfo> = toolsByServer[serverId] ?: emptyList()
}
