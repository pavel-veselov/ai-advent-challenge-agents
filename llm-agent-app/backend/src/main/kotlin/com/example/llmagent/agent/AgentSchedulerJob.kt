package com.example.llmagent.agent

/**
 * Периодическая задача планировщика (Day-17/18): имя, интервал в секундах, промпт для агента
 * и флаг включения. [nextRunAt] — момент следующего запуска (ISO-строка); null — задача ещё
 * не запускалась (или запуск ещё не планировался). [lastRunAt] — последний запуск; null — не было.
 * Схема — в schema.sql (`agent_scheduler_jobs`); storing and CRUD — [AgentSchedulerStore].
 */
data class AgentSchedulerJob(
    val id: Long,
    val name: String,
    val intervalSeconds: Long,
    val prompt: String,
    val enabled: Boolean,
    val lastRunAt: String?,
    val nextRunAt: String?,
    val createdAt: String,
    val updatedAt: String,
)
