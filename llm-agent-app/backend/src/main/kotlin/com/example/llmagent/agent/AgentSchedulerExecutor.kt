package com.example.llmagent.agent

import java.time.OffsetDateTime
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import reactor.core.scheduler.Schedulers

/**
 * Фоновый исполнитель периодических задач планировщика (Day-17/18): раз в [tick-ms]
 * опрашивает "дозревшие" задачи ([AgentSchedulerStore.findDue]), для каждой запускает
 * [Agent.run] с промптом задачи, собирает финальный ответ ([AgentFinished]), кладёт его
 * в историю сессии и доставляет активной сессии через [SessionEventBus] событием
 * [SchedulerResult].
 *
 * Сессия доставки: env `AGENT_SCHEDULER_DELIVERY_SESSION`, иначе первая активная сессия
 * (см. [SessionEventBus.activeSessions]); если активных сессий нет — тик пропускается.
 * Fail-open: сбой одной задачи (или БД) не роняет тик — warn и продолжение.
 */
@Component
class AgentSchedulerExecutor(
    private val store: AgentSchedulerStore,
    private val sessionStore: SessionStore,
    private val agent: Agent,
    private val bus: SessionEventBus,
) {

    companion object {
        private val log = LoggerFactory.getLogger(AgentSchedulerExecutor::class.java)
    }

    @Scheduled(fixedDelayString = "\${agent.scheduler.tick-ms:15000}")
    fun runDueJobs() {
        try {
            val now = OffsetDateTime.now()
            val due = store.findDue(now.toString())
            if (due.isEmpty()) return
            val sessionId = resolveDeliverySession()
            if (sessionId == null) {
                log.info("AgentScheduler: найдено {} дозревших задач, но нет активной сессии доставки — пропускаю тик", due.size)
                return
            }
            log.info("AgentScheduler: выполняю {} дозревших задач в сессию {}", due.size, sessionId)
            due.forEach { job -> runJob(sessionId, job) }
        } catch (e: Exception) {
            log.warn("AgentScheduler: тик runDueJobs не удался: {}", e.message)
        }
    }

    /** Сессия доставки результата: env AGENT_SCHEDULER_DELIVERY_SESSION или первая активная. */
    private fun resolveDeliverySession(): String? {
        val env = System.getenv("AGENT_SCHEDULER_DELIVERY_SESSION")
        if (!env.isNullOrBlank()) return env
        return bus.activeSessions().firstOrNull()
    }

    /**
     * Запускает одну задачу асинхронно (subscribe на реактивном потоке). Собирает финальный
     * текст [AgentFinished], после завершения кладёт его в историю (aссистент), шлёт
     * [SchedulerResult] в SSE-канал и отмечает запуск в БД. Сбой задачи — warn, тик продолжается.
     */
    private fun runJob(sessionId: String, job: AgentSchedulerJob) {
        val runAt = OffsetDateTime.now()
        val finalText = StringBuilder()
        agent.run(sessionId, job.prompt)
            .subscribeOn(Schedulers.boundedElastic())
            .subscribe(
                { ev ->
                    if (ev is AgentFinished) {
                        finalText.setLength(0)
                        finalText.append(ev.finalText)
                    }
                },
                { err ->
                    log.warn("AgentScheduler: задача '{}' (id={}) упала: {}", job.name, job.id, err.message)
                    markRun(job, runAt)
                },
                {
                    // Fail-open: сбой доставки результата не роняет тик (см. KDoc класса).
                    try {
                        val text = finalText.toString()
                        if (text.isNotEmpty()) {
                            sessionStore.append(sessionId, "assistant", text)
                            bus.push(sessionId, SchedulerResult(text))
                            log.info("AgentScheduler: задача '{}' (id={}) — результат доставлен в сессию {}", job.name, job.id, sessionId)
                        }
                    } catch (e: Exception) {
                        log.warn("AgentScheduler: доставка результата задачи '{}' (id={}) не удалась: {}", job.name, job.id, e.message)
                    }
                    markRun(job, runAt)
                },
            )
    }

    /** Отмечает запуск: last_run_at = время старта, next_run_at = время старта + интервал. */
    private fun markRun(job: AgentSchedulerJob, runAt: OffsetDateTime) {
        store.markRun(job.id, runAt.toString(), runAt.plusSeconds(job.intervalSeconds).toString())
    }
}
