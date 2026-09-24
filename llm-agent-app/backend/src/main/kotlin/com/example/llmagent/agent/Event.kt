package com.example.llmagent.agent

/**
 * Одно событие агента в терминах контракта CONTRACT.md.
 * Транспорт (контроллер) добавляет служебные поля runId / timestamp / sequence.
 */
sealed interface AgentEvent {
    val type: String
    val stepId: String
    val payload: Map<String, Any?>
}

data class AgentStarted(
    val userMessage: String,
    val settings: Map<String, Any?>,
) : AgentEvent {
    override val type = "agent_started"
    override val stepId = "user"
    override val payload = mapOf("userMessage" to userMessage, "settings" to settings)
}

data class LlmRequestStarted(
    val iteration: Int,
    val prompt: List<Map<String, String>>,
    /** Оценки токенов больше нет (локальный подсчёт удалён); поле 0 — сохранено для схемы. */
    val estimatedRequestTokens: Int = 0,
    /** Фактическое тело HTTP-запроса к LLM API (pretty JSON); null — если клиент не отдал тело. */
    val requestBody: String? = null,
) : AgentEvent {
    override val type = "llm_request_started"
    override val stepId = "llm-$iteration"
    override val payload: Map<String, Any?> = buildMap {
        put("iteration", iteration)
        put("prompt", prompt)
        put("estimatedRequestTokens", estimatedRequestTokens)
        if (requestBody != null) put("requestBody", requestBody)
    }
}

data class LlmToken(val iteration: Int, val delta: String) : AgentEvent {
    override val type = "llm_token"
    override val stepId = "llm-$iteration"
    override val payload = mapOf("delta" to delta)
}

data class LlmResponseFinished(
    val iteration: Int,
    val finishReason: String,
    val usage: LlmUsage? = null,
    /** Локальная оценка токенов удалена; поле всегда null — сохранено для схемы. */
    val estimatedRequestTokens: Int? = null,
    /** Условная стоимость запроса+ответа в USD; null, если usage от провайдера не пришёл. */
    val costUsd: Double? = null,
    /** Тело ответа LLM API, собранное из стрима (pretty JSON); null — если клиент не отдал тело. */
    val responseBody: String? = null,
) : AgentEvent {
    override val type = "llm_response_finished"
    override val stepId = "llm-$iteration"
    override val payload: Map<String, Any?> = buildMap {
        put("finishReason", finishReason)
        put("estimatedRequestTokens", estimatedRequestTokens)
        if (costUsd != null) put("costUsd", costUsd)
        if (usage != null) put("usage", mapOf("inputTokens" to usage.inputTokens, "outputTokens" to usage.outputTokens))
        if (responseBody != null) put("responseBody", responseBody)
    }
}

data class ToolCallStarted(val toolName: String, val idx: Int, val args: Map<String, Any?>) : AgentEvent {
    override val type = "tool_call_started"
    override val stepId = "tool-$toolName-$idx"
    override val payload = mapOf("toolName" to toolName, "args" to args)
}

data class ToolCallFinished(val toolName: String, val idx: Int, val status: String, val result: String) : AgentEvent {
    override val type = "tool_call_finished"
    override val stepId = "tool-$toolName-$idx"
    override val payload = mapOf("result" to result, "status" to status)
}

data class AgentFinished(val finalText: String) : AgentEvent {
    override val type = "agent_finished"
    override val stepId = "answer"
    override val payload = mapOf("finalText" to finalText)
}

/**
 * Начало вызова LLM для сжатия истории (per-session). Идёт ДО основного цикла,
 * поэтому НЕ входит в нумерацию итераций (stepId фиксирован, без `<iteration>`).
 * `prompt` — снимок промпта вызова резюмирования: что именно ушло в LLM
 * (тот же формат, что у llm_request_started).
 */
data class ContextSummaryStarted(
    val foldCount: Int,
    val prompt: List<Map<String, String>>,
) : AgentEvent {
    override val type = "context_summary_started"
    override val stepId = "context-summary"
    override val payload = mapOf("foldCount" to foldCount, "prompt" to prompt)
}

/**
 * Завершение вызова LLM для сжатия истории: токены из usage API (0, если провайдер
 * их не прислал), текст резюме, который вернула LLM и который сохранён в сессии,
 * и эвристическая оценка размера контекста ДО сжатия и ПОСЛЕ (токены, см.
 * AgentImpl.estimateTokens).
 */
data class ContextSummaryFinished(
    val foldCount: Int,
    val promptTokens: Int,
    val completionTokens: Int,
    val summary: String,
    val contextTokensBefore: Int,
    val contextTokensAfter: Int,
) : AgentEvent {
    override val type = "context_summary_finished"
    override val stepId = "context-summary"
    override val payload = mapOf(
        "foldCount" to foldCount,
        "promptTokens" to promptTokens,
        "completionTokens" to completionTokens,
        "summary" to summary,
        "contextTokensBefore" to contextTokensBefore,
        "contextTokensAfter" to contextTokensAfter,
    )
}

data class ErrorEvent(val idx: Int, val message: String) : AgentEvent {
    override val type = "error"
    override val stepId = "error-$idx"
    override val payload = mapOf("message" to message)
}

/**
 * Строка «обычного» лога: каждое действие агентского цикла человекочитаемым текстом
 * (тот же текст, что в серверном логе с префиксом [AGENT]). Событие только для
 * отображения в панели «Логи» на фронтенде — на работу агента не влияет.
 */
data class LogEvent(val idx: Int, val text: String) : AgentEvent {
    override val type = "log"
    override val stepId = "log-$idx"
    override val payload = mapOf("text" to text)
}

/**
 * Обновление «липких фактов» сессии (strategy=sticky_facts): факты только что извлечены
 * LLM, сохранены и в текущем run подмешаны в контекст. Идёт ДО основного цикла (как
 * контекстные events сжатия), поэтому НЕ входит в нумерацию итераций: stepId фиксирован
 * ("facts", вне numbering context_summary_*). Полезной нагрузки минимум — поля токенов
 * намеренно нет: факты нужны только для контекста, а не для метрик.
 */
data class FactsUpdated(val facts: Map<String, String>) : AgentEvent {
    override val type = "facts_updated"
    override val stepId = "facts"
    override val payload = mapOf("facts" to facts)
}

/**
 * Полный снапшот памяти агента (memory layers): рабочая память ПРОЕКТА (task/notes,
 * общая для всех сессий проекта) и ГЛОБАЛЬНАЯ долговременная память (все записи всех
 * сессий, sourceSessionId помнит происхождение).
 *
 * Day-13: класс оставлен для обратной совместимости схемы событий, НО больше НЕ
 * ЭМИТИРУЕТСЯ агентом — память пишется ТОЛЬКО пользователем через REST/UI, а REST
 * в этом приложении SSE не отправляет (фронтенд после мутаций сам делает refetch
 * GET /api/projects/{projectId}/memory). Авто-эмиссии были привязаны к удалённым
 * авто-записям агента (task на старте, заметки после tool-результатов, tool memory_save).
 */
data class MemoryUpdated(
    val projectId: String,
    val working: WorkingMemory,
    val longTerm: List<LongTermEntry>,
) : AgentEvent {
    override val type = "memory_updated"
    override val stepId = "memory"
    override val payload = mapOf(
        "projectId" to projectId,
        "working" to working,
        "longTerm" to longTerm,
    )
}

/**
 * Обновление состояния задачи (Day-13, FSM task_state): агент только что успешно
 * выполнил инструмент task_state — этап/шаг/ожидаемое действие сохранены в per-session
 * task_state (см. TaskStateStore). Идёт в основном цикле сразу после tool_call_finished,
 * но НЕ входит в нумерацию итераций: stepId фиксирован ("task-state"). currentStep /
 * expectedAction/plan/implementation/validation добавляются в payload только если не null
 * (условные puts, как в llm_response_finished); paused и awaitConfirmation — всегда.
 * REST-мутации состояния (пауза через UI, /continue, /cancel) SSE НЕ отправляют —
 * фронтенд обновляет панель из ответа PUT/POST.
 */
data class TaskStateChanged(
    val stage: String,
    val currentStep: String?,
    val expectedAction: String?,
    val paused: Boolean,
    val plan: String? = null,
    val implementation: String? = null,
    val validation: String? = null,
    val awaitConfirmation: Boolean = false,
) : AgentEvent {
    override val type = "task_state_changed"
    override val stepId = "task-state"
    override val payload: Map<String, Any?> = buildMap {
        put("stage", stage)
        if (currentStep != null) put("currentStep", currentStep)
        if (expectedAction != null) put("expectedAction", expectedAction)
        put("paused", paused)
        if (plan != null) put("plan", plan)
        if (implementation != null) put("implementation", implementation)
        if (validation != null) put("validation", validation)
        put("awaitConfirmation", awaitConfirmation)
    }
}

/**
 * Воркфлоу Day-14: агент завершил этап и ждёт подтверждения пользователя (ручной
 * режим, `workflow.mode=manual`). Эмитится в конце run (после agent_finished), когда
 * этап != done: результат этапа сохранён в task_state (plan/implementation/validation),
 * флаг awaitConfirmation поднят. Фронтенд по этому событию показывает под последним
 * сообщением ассистента кнопки «Продолжить»/«Отмена». В авто-режиме не эмитится.
 * stepId фиксирован ("workflow").
 */
data class WorkflowPaused(
    val stage: String,
    val output: String,
    val await: Boolean,
) : AgentEvent {
    override val type = "workflow_paused"
    override val stepId = "workflow"
    override val payload = mapOf("stage" to stage, "output" to output, "await" to await)
}

/**
 * Воркфлоу Day-14 (авто-режим): этап завершён и его повествование уже сохранено и в историю
 * (sessionStore.append), и как результат этапа (колонка plan/implementation/validation через
 * setStageOutputKeepStage). Эмитится в блоке сохранения повествования (см. AgentImpl, авто
 * stage-save) СРАЗУ после коммита — это НАДЁЖНАЯ граница этапа. Фронтенд по этому событию
 * финализирует пузырь повествования завершённого этапа и открывает новый пузырь для
 * следующего — каждая стадия видна отдельным сообщением ассистента (а не одним затираемым
 * пузырём, где остаётся только финальный текст). В ручном режиме не эмитится (там этап
 * завершается финальным ответом + workflow_paused). stepId фиксирован ("workflow-stage").
 */
data class WorkflowStageFinished(
    val stage: String,
    val output: String,
) : AgentEvent {
    override val type = "workflow_stage_finished"
    override val stepId = "workflow-stage"
    override val payload = mapOf("stage" to stage, "output" to output)
}

/**
 * Результат периодической задачи планировщика (Day-17/18): агент выполнил scheduled-промпт
 * и финальный текст доставляется активной сессии по SSE. Эмитится в фоновый SSE-канал
 * сессии (SessionEventBus) после `AgentFinished`, а сам текст уже сохранён в историю чата —
 * фронтенд по типу `scheduler_result` помечает сообщение как автоматическое (не user-вопрос).
 * stepId фиксирован ("scheduler").
 */
data class SchedulerResult(val text: String) : AgentEvent {
    override val type = "scheduler_result"
    override val stepId = "scheduler"
    override val payload = mapOf("text" to text)
}
