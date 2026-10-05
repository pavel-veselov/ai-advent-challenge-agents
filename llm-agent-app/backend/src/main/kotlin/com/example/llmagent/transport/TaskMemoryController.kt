package com.example.llmagent.transport

import com.example.llmagent.agent.TaskMemoryService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController

/**
 * Память задачи сессии (Day-25, task_memory) — чтение структурированного состояния
 * диалога {goal, clarifications, constraints} для UI-панели «Память задачи».
 *
 * GET /api/sessions/{sessionId}/task-memory — текущее состояние
 * `{ "goal": string, "clarifications": string[], "constraints": string[], "updatedAt": string|null }`.
 * Состояние пишется ТОЛЬКО автоматически (LLM-извлечение после завершённых обменов,
 * см. TaskMemoryService.updateFromExchange); REST-мутаций у памяти задачи нет.
 * Строки в task_memory нет / состояние пустое → пустая структура с `updatedAt: null`
 * и статусом 200 — НИКОГДА не 404 (панель у новой сессии просто пустая).
 * НЕ путать с task_state (Day-13, FSM воркфлоу) — таблицы и код независимы.
 */
@RestController
class TaskMemoryController(private val taskMemoryService: TaskMemoryService) {

    @GetMapping("/api/sessions/{sessionId}/task-memory")
    fun get(@PathVariable sessionId: String): Map<String, Any?> {
        val memory = taskMemoryService.load(sessionId)
        return mapOf(
            "goal" to memory.goal,
            "clarifications" to memory.clarifications,
            "constraints" to memory.constraints,
            "updatedAt" to memory.updatedAt,
        )
    }
}
