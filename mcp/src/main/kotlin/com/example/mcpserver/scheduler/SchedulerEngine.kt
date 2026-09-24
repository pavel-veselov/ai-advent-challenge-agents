package com.example.mcpserver.scheduler

import com.example.mcpserver.collector.SourceCollector
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Планировщик периодичеcкого сбора данных (Day-18).
 *
 * На старте подгружает активные задачи из [SchedulerStore] и ставит каждую на
 * фиксированный интервал в [ScheduledExecutorService]. Каждый запуск вызывает
 * [SourceCollector.collect] для нужного источника, результат сериализует в JSON и
 * кладёт строкой в `task_runs` (status SUCCESS либо ERROR). Сбой одной задачи не
 * останавливает другие и не роняет исполнитель (перехват в runTask).
 *
 * Тред-безопасность: сопоставление taskId -> ScheduledFuture в ConcurrentHashMap;
 * при смене интервала старый future отменяется и задача ставится заново.
 */
@Component
class SchedulerEngine(
    private val store: SchedulerStore,
    private val collector: SourceCollector,
    private val om: ObjectMapper,
) {

    private val log = LoggerFactory.getLogger(SchedulerEngine::class.java)

    private val executor: ScheduledExecutorService = Executors.newScheduledThreadPool(POOL_SIZE) { r ->
        Thread(r, "scheduler-engine").apply { isDaemon = true }
    }
    private val futures = ConcurrentHashMap<Long, ScheduledFuture<*>>()

    @PostConstruct
    fun start() {
        log.info("SchedulerEngine: загрузка активных задач из БД")
        for (task in store.listActiveTasks()) {
            schedule(task)
        }
    }

    @PreDestroy
    fun shutdown() {
        log.info("SchedulerEngine: остановка исполнителя")
        futures.values.forEach { it.cancel(true) }
        futures.clear()
        executor.shutdownNow()
    }

    /** Добавляет задачу в БД и ставит её на расписание. Возвращает созданную задачу или null. */
    fun addTask(name: String, source: String, intervalSeconds: Int, paramsJson: String?): ScheduledTask? {
        val task = store.addTask(name, source, intervalSeconds, paramsJson) ?: return null
        schedule(task)
        return task
    }

    /** Меняет интервал задачи: обновляет БД и пере-расписывает. Возвращает задачу или null. */
    fun updateInterval(id: Long, intervalSeconds: Int): ScheduledTask? {
        val task = store.updateInterval(id, intervalSeconds) ?: return null
        schedule(task)
        return task
    }

    /** Удаляет задачу: отменяет future и стирает строку в БД. */
    fun removeTask(id: Long): Boolean {
        futures.remove(id)?.cancel(true)
        return store.deleteTask(id)
    }

    /** Ставит задачу на расписание; при смене интервала старый future отменяется. */
    fun schedule(task: ScheduledTask) {
        futures.remove(task.id)?.cancel(false)
        if (!task.active) return
        log.info("SchedulerEngine: задача {} ({}) каждые {}с", task.id, task.source, task.intervalSeconds)
        val future = executor.scheduleWithFixedDelay(
            { runTask(task) },
            task.intervalSeconds.toLong(),
            task.intervalSeconds.toLong(),
            TimeUnit.SECONDS,
        )
        futures[task.id] = future
    }

    /**
     * Единичный запуск задачи: собрать данные, записать успех/ошибку в task_runs.
     * Любое исключение ловится и фиксируется как ERROR — исполнитель не падает.
     */
    @Suppress("UNCHECKED_CAST")
    fun runTask(task: ScheduledTask) {
        try {
            val params = task.paramsJson?.let { om.readValue(it, Map::class.java) as? Map<String, Any?> }
            val result = collector.collect(task.source, params)
                .block(Duration.ofSeconds(BLOCK_TIMEOUT_SECONDS))
            val resultJson = om.writeValueAsString(result)
            store.recordRun(task.id, task.source, STATUS_SUCCESS, resultJson, null)
        } catch (e: Exception) {
            log.warn("SchedulerEngine: задача {} упала: {}", task.id, e.message)
            store.recordRun(task.id, task.source, STATUS_ERROR, null, e.message)
        }
    }

    private companion object {
        const val POOL_SIZE = 2
        const val BLOCK_TIMEOUT_SECONDS = 30L
        const val STATUS_SUCCESS = "SUCCESS"
        const val STATUS_ERROR = "ERROR"
    }
}
