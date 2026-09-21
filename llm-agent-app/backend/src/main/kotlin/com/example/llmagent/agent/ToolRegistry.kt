package com.example.llmagent.agent

import java.util.concurrent.ConcurrentHashMap
import org.springframework.stereotype.Component

/**
 * Реестр инструментов: объявления для LLM + поиск по имени. Встроенные инструменты
 * (передаются как `List<Tool>` в конструкторе и полностью определяются Spring-сканированием)
 * дополняются ДИНАМИЧЕСКИМИ инструментами (Day-16, MCP-серверы): последние регистрируются
 * и снимаются на лету через [register]/[unregister].
 *
 * Встроенные инструменты всегда перекрывают динамические по имени (в [get] и итоговой
 * карте — сначала static, затем dynamic), при появлении зарегистрированного MCP-инструмента
 * с тем же именем он просто занимает место в `merged()` (последняя запись побеждает).
 */
@Component
class ToolRegistry(builtInTools: List<Tool>) {

    private val staticByName: Map<String, Tool> = builtInTools.associateBy { it.name }

    private val dynamic: ConcurrentHashMap<String, Tool> = ConcurrentHashMap()

    /** Итоговая карта: встроенные + динамические (динамические перекрывают по имени). */
    private fun merged(): Map<String, Tool> {
        val m = LinkedHashMap<String, Tool>()
        m.putAll(staticByName)
        m.putAll(dynamic)
        return m
    }

    fun definitions(): List<ToolDefinition> =
        merged().values.map { ToolDefinition(it.name, it.description, it.parameters) }

    fun get(name: String): Tool? = dynamic[name] ?: staticByName[name]

    fun names(): Set<String> = merged().keys

    /** Регистрирует динамический инструмент (MCP). Перезапись по имени — последняя выигрывает. */
    fun register(tool: Tool) {
        dynamic[tool.name] = tool
    }

    fun unregister(name: String) {
        dynamic.remove(name)
    }

    fun unregisterAll(names: Collection<String>) {
        names.forEach { dynamic.remove(it) }
    }

    fun dynamicNames(): Set<String> = dynamic.keys
}
