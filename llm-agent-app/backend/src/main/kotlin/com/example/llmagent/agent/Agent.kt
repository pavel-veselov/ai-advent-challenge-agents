package com.example.llmagent.agent

import reactor.core.publisher.Flux

/**
 * Самостоятельная сущность: принимает сообщение пользователя и эмитит события агента.
 * Инкапсулирует общение с LLM и цикл вызова инструментов — транспорт ничего не знает об LLM API.
 */
interface Agent {
    fun run(sessionId: String, userMessage: String): Flux<AgentEvent>
}
