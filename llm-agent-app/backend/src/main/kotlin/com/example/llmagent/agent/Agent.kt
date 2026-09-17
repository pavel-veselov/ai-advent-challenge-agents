package com.example.llmagent.agent

import reactor.core.publisher.Flux

/**
 * Самостоятельная сущность: принимает сообщение пользователя и эмитит события агента.
 * Инкапсулирует общение с LLM и цикл вызова инструментов — транспорт ничего не знает об LLM API.
 */
interface Agent {
    fun run(sessionId: String, userMessage: String): Flux<AgentEvent>

    /**
     * Продолжение воркфлоу Day-14: агент запускает СЛЕДУЮЩИЙ этап, читая сохранённую
     * историю сессии (и состояние задачи) — без добавления нового user-сообщения.
     * По умолчанию делегирует в [run] с пустым сообщением (реализация переопределяет
     * этот дефолт, чтобы не оставлять пустой user-пузырь в истории).
     */
    fun continueRun(sessionId: String): Flux<AgentEvent> = run(sessionId, "")
}
