package com.example.llmagent.agent

import org.springframework.stereotype.Component

/** Реестр инструментов: объявления для LLM + поиск по имени. */
@Component
class ToolRegistry(tools: List<Tool>) {
    private val byName: Map<String, Tool> = tools.associateBy { it.name }

    fun definitions(): List<ToolDefinition> =
        byName.values.map { ToolDefinition(it.name, it.description, it.parameters) }

    fun get(name: String): Tool? = byName[name]

    fun names(): Set<String> = byName.keys
}
