package com.example.llmagent.transport

import com.example.llmagent.agent.Agent
import com.example.llmagent.agent.AgentEvent
import com.example.llmagent.agent.ErrorEvent
import com.example.llmagent.agent.TaskState
import com.example.llmagent.agent.TaskStateStore
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.codec.ServerSentEvent
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import reactor.core.publisher.Flux
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * Состояние задачи сессии (Day-13, FSM task_state + воркфлоу Day-14) — чтение для
 * панели состояния, ручные правки пользователя (пауза/продолжение, смена этапа) и
 * подтверждение перехода на следующий этап воркфлоу.
 *
 * GET  /api/sessions/{sessionId}/task-state — текущее состояние; 404 — задача не начата
 *   (строки в task_state нет).
 * PUT  /api/sessions/{sessionId}/task-state — тело `{stage?, currentStep?,
 *   expectedAction?, paused?, plan?, implementation?, validation?, awaitConfirmation?}`:
 *   - с `stage` — полная запись (upsert): валидация этапа и перехода FSM (те же правила,
 *     что у инструмента task_state, см. TaskStateStore.canTransition); 400 — неизвестный
 *     этап / недопустимый переход / текстовые поля не строки;
 *   - без `stage`, с `paused` — только флаг паузы (TaskStateStore.setPaused); 400 —
 *     задачи ещё нет (сначала нужен stage) или paused не boolean;
 *   - без `stage` и без `paused` — 400.
 *   Поля воркфлоу (plan/implementation/validation/awaitConfirmation) сохраняются, если
 *   переданы; иначе остаются прежними (не обнуляются).
 * POST /api/sessions/{sessionId}/task-state/continue — подтверждение «Продолжить»
 *   (воркфлоу Day-14): переводит на следующий линейный этап (planning → execution →
 *   validation → done), сбрасывает awaitConfirmation и запускает агента для следующего
 *   этапа (SSE-поток как /api/chat). 400 — задача не начата или этап «done».
 * POST /api/sessions/{sessionId}/task-state/cancel — «Отмена»: останавливает воркфлоу —
 *   сохраняет этап, ставит паузу (task halts) и сбрасывает awaitConfirmation; 200 —
 *   обновлённое состояние (JSON, без SSE).
 *
 * REST-мутации SSE НЕ отправляют (кроме /continue — он запускает агентеский run и
 * возвращает SSE): фронтенд обновляет панель из ответа PUT/POST.
 */
@RestController
class TaskStateController(
    private val taskStateStore: TaskStateStore,
    private val agent: Agent,
    private val om: ObjectMapper,
) {

    companion object {
        private val log = LoggerFactory.getLogger(TaskStateController::class.java)
    }

    @GetMapping("/api/sessions/{sessionId}/task-state")
    fun get(@PathVariable sessionId: String): TaskState =
        taskStateStore.get(sessionId)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Задача не начата (состояние не сохранено)")

    @PutMapping("/api/sessions/{sessionId}/task-state")
    fun update(@PathVariable sessionId: String, @RequestBody body: Map<String, Any?>): TaskState {
        val existing = taskStateStore.get(sessionId)
        val stage = (body["stage"] as? String)?.trim().orEmpty()

        // Флаг паузы: читаем всегда (с ним и с stage, и без) — это единственное поле,
        // которое меняет ТОЛЬКО пользователь (инструмент task_state паузу не трогает).
        val pausedExplicit = if (body.containsKey("paused")) {
            (body["paused"] as? Boolean)
                ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "paused должен быть boolean")
        } else {
            null
        }

        return if (stage.isNotEmpty()) {
            // Полная запись с этапом: как инструмент task_state — та же валидация FSM.
            if (!TaskStateStore.isValidStage(stage)) {
                throw ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Неизвестный этап \"$stage\". Допустимые этапы: ${TaskStateStore.STAGES.sorted().joinToString(" | ")}",
                )
            }
            // День-15: задача ВСЕГДА начинается с этапа planning — первый stage не может
            // быть execution/validation/done (строгий линейный конвейер).
            if (existing == null && stage != TaskStateStore.STAGE_PLANNING) {
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, TaskStateStore.firstStageErrorMessage(stage))
            }
            if (existing != null && existing.stage != stage && !TaskStateStore.canTransition(existing.stage, stage)) {
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, TaskStateStore.transitionErrorMessage(existing.stage, stage))
            }
            val currentStep = textField(body["currentStep"], "currentStep")
            val expectedAction = textField(body["expectedAction"], "expectedAction")
            val paused = pausedExplicit ?: existing?.paused ?: false
            // Воркфлоу Day-14: поля сохраняются, если переданы; иначе остаются прежними
            // (иначе такой PUT обнулил бы уже сохранённый результат этапа).
            val plan = textField(body["plan"], "plan") ?: existing?.plan
            val implementation = textField(body["implementation"], "implementation") ?: existing?.implementation
            val validation = textField(body["validation"], "validation") ?: existing?.validation
            val await = if (body.containsKey("awaitConfirmation")) {
                (body["awaitConfirmation"] as? Boolean)
                    ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "awaitConfirmation должен быть boolean")
            } else {
                existing?.awaitConfirmation ?: false
            }
            taskStateStore.upsert(
                sessionId, stage, currentStep, expectedAction, paused,
                plan, implementation, validation, await,
            ) ?: throw ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Не удалось сохранить состояние задачи")
        } else if (pausedExplicit != null) {
            // Только пауза/продолжение (кнопки UI): этап/шаги не трогаются.
            if (existing == null) {
                throw ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Задача не начата — поставить паузу не на что (сначала начните задачу или укажите stage)",
                )
            }
            taskStateStore.setPaused(sessionId, pausedExplicit)
                ?: throw ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Не удалось сохранить состояние задачи")
        } else {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Укажите stage (planning | execution | validation | done) или paused (boolean)",
            )
        }
    }

    /**
     * «Продолжить»/«Снять паузу» воркфлоу Day-14: если задача стоит на паузе (авто,
     * «Снять паузу») — ВОЗОБНОВЛЯЕМ текущий этап (снимаем паузу и запускаем агента для
     * этого ЖЕ этапа, а не переходим к следующему); иначе — переводим на следующий
     * линейный этап и сбрасываем ожидание подтверждения. SSE-поток, как /api/chat.
     * Контекст продолжается — агент читает сохранённую историю и состояние задачи.
     */
    @PostMapping("/api/sessions/{sessionId}/task-state/continue", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    fun continueWorkflow(@PathVariable sessionId: String): Flux<ServerSentEvent<String>> {
        val state = taskStateStore.get(sessionId)
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Задача не начата — продолжать нечего")
        val runId = UUID.randomUUID().toString()
        val sequence = AtomicInteger(0)
        if (state.paused) {
            // «Снять паузу» в авто: ВОЗОБНОВЛЯЕМ с текущего этапа (не переходим к следующему).
            if (state.stage == TaskStateStore.STAGE_DONE) {
                throw ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Задача уже выполнена (этап \"${state.stage}\") — переходить дальше некуда",
                )
            }
            taskStateStore.setPaused(sessionId, false)
                ?: throw ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Не удалось снять паузу")
            taskStateStore.clearAwait(sessionId)
                ?: throw ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Не удалось сбросить ожидание подтверждения")
            log.info("workflow resume session={} stage={} — пауза снята, продолжаю с текущего этапа", sessionId, state.stage)
        } else {
            val next = TaskStateStore.nextWorkflowStage(state.stage)
                ?: throw ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Задача уже выполнена (этап \"${state.stage}\") — переходить дальше некуда",
                )
            val updated = taskStateStore.advance(sessionId, next)
                ?: throw ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Не удалось сохранить состояние задачи")
            log.info("workflow continue session={} stage={} -> {}", sessionId, state.stage, updated.stage)
        }
        return agent.continueRun(sessionId)
            .map { ev -> sse(ev, runId, sequence.getAndIncrement()) }
            .onErrorResume { err ->
                log.error("workflow continue run={} session={} failed: {}", runId, sessionId, err.message)
                Flux.just(sse(ErrorEvent(0, "Ошибка сервера: ${err.message}"), runId, sequence.getAndIncrement()))
            }
    }

    /** «Отмена» воркфлоу Day-14: останавливает задачу — пауза + сброс ожидания подтверждения. */
    @PostMapping("/api/sessions/{sessionId}/task-state/cancel")
    fun cancelWorkflow(@PathVariable sessionId: String): TaskState {
        val state = taskStateStore.get(sessionId)
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Задача не начата — отменять нечего")
        taskStateStore.setPaused(sessionId, true)
            ?: throw ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Не удалось сохранить состояние задачи")
        taskStateStore.clearAwait(sessionId)
            ?: throw ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Не удалось сохранить состояние задачи")
        log.info("workflow cancel session={} — задача на паузе, ожидание подтверждения сброшено", sessionId)
        return taskStateStore.get(sessionId)
            ?: throw ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Состояние задачи недоступно")
    }

    /** Текстовое поле body: строка → trim → null, если пустая; не строка → 400. */
    private fun textField(value: Any?, name: String): String? {
        if (value == null) return null
        return (value as? String)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "$name должен быть строкой")
    }

    /** Обертка события агента в контракт SSE {type, runId, stepId, timestamp, payload, sequence}. */
    private fun sse(ev: AgentEvent, runId: String, seq: Int): ServerSentEvent<String> =
        SseEnvelope.sse(om, ev, runId, seq)
}
