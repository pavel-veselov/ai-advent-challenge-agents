package com.example.llmagent.mcp

import com.example.llmagent.agent.McpServersStore
import com.example.llmagent.agent.ToolRegistry
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Клиент к планировщику papkin-helper (Day-17): единая точка вызова его инструментов
 * (scheduler_list_tasks, scheduler_add_task, scheduler_update_interval,
 * scheduler_remove_task, scheduler_summary). Сначала пробует УЖЕ зарегистрированный
 * инструмент через [ToolRegistry] (если MCP-сервер включён и активирован в McpToolsManager),
 * иначе строит свежий [McpStreamableClient] к включённому серверу papkin-helper напрямую.
 *
 * Всё fail-open: недоступный сервер/ошибка протокола → null + warn, вызывающий код
 * (SchedulerPusher / SchedulerController) решает, что с этим делать (пропустить тик или
 * вернуть 502). Клиент кэшируется на время жизни сервера (по URL) — сессия MCP держится
 * между вызовами и не пере-рукопожатывается каждый тик.
 */
@Component
class McpSchedulerClient(
    private val store: McpServersStore,
    private val registry: ToolRegistry,
    private val om: ObjectMapper,
) {

    private val log = LoggerFactory.getLogger(McpSchedulerClient::class.java)

    @Volatile
    private var cachedClient: McpStreamableClient? = null

    @Volatile
    private var cachedUrl: String? = null

    /** Вызывает инструмент планировщика по имени; null — не удалось (fail-open). */
    suspend fun callTool(name: String, args: Map<String, Any?>): McpCallResult? {
        // Путь 1: инструмент уже зарегистрирован (MCP-сервер активирован) — вызываем его.
        registry.get(name)?.let { tool ->
            return try {
                val r = tool.execute(args)
                if (r.isError) {
                    log.warn("MCP scheduler '{}' вернул ошибку: {}", name, r.result)
                    McpCallResult(r.result, true)
                } else {
                    McpCallResult(r.result, false)
                }
            } catch (e: Exception) {
                log.warn("MCP scheduler '{}' упал: {}", name, e.message)
                null
            }
        }
        // Путь 2: инструмент не в реестре — обращаемся к включённому серверу напрямую.
        val client = resolveClient() ?: return null
        return try {
            client.callTool(name, args)
        } catch (e: Exception) {
            log.warn("MCP scheduler '{}' не вызван: {}", name, e.message)
            null
        }
    }

    /** Включённый сервер papkin-helper (по имени/порту 8080) либо первый включённый. */
    private fun resolveClient(): McpStreamableClient? {
        val servers = store.list().filter { it.enabled }
        if (servers.isEmpty()) return null
        val server = servers.firstOrNull {
            it.name.contains("papkin", ignoreCase = true) || it.url.contains(":8080")
        } ?: servers.first()
        val url = server.url
        val cached = cachedClient
        if (cached != null && cachedUrl == url) return cached
        val client = McpStreamableClient(url, om = om)
        cachedClient = client
        cachedUrl = url
        return client
    }
}
