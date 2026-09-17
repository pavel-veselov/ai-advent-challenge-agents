package com.example.llmagent.transport

import com.example.llmagent.agent.Project
import com.example.llmagent.agent.ProjectSession
import com.example.llmagent.agent.ProjectStore
import com.example.llmagent.agent.InvariantsStore
import com.example.llmagent.agent.SessionBranchStore
import com.example.llmagent.agent.SessionCompressionStore
import com.example.llmagent.agent.SessionContextStore
import com.example.llmagent.agent.SessionFactsStore
import com.example.llmagent.agent.SessionStore
import com.example.llmagent.agent.TaskStateStore
import com.example.llmagent.agent.WorkingMemoryStore
import com.example.llmagent.config.SessionLlmSettingsStore
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

/**
 * Проекты (задача НАД сессиями, иерархия Проект → Сессии) + сессии внутри проектов.
 *
 * GET    /api/projects                — список проектов `[{id,name,createdAt,updatedAt}]` (по созданию).
 * POST   /api/projects                — создание проекта, тело `{name}` → 200 с созданным проектом; 400 — пустое имя.
 * PATCH  /api/projects/{id}           — переименование, тело `{name}` → 200 с проектом; 404 — нет; 400 — пустое имя.
 * DELETE /api/projects/{id}           — КАСКАДНОЕ удаление: все сессии проекта (chat_messages + chat_sessions),
 *                                       ВСЕ per-session данные (сжатие, настройки LLM, стратегия контекста, факты,
 *                                       ветки), рабочая память ПРОЕКТА (agent_working_memory по project_id) и
 *                                       инварианты проекта (agent_invariants по project_id) →
 *                                       `{deleted:true}`; 404 — нет. Долговременная память (LTM) ГЛОБАЛЬНАЯ —
 *                                       удаление проекта её НЕ трогает.
 * POST   /api/projects/{id}/sessions  — создание сессии В проекте (server-side id), тело `{title?}` →
 *                                       `{sessionId, projectId, title}`; 404 — нет проекта.
 * GET    /api/projects/{id}/sessions  — сессии проекта со сводкой (SessionSummary + projectId); 404 — нет проекта.
 *
 * «Без проекта»/миграция старых сессий НЕ создаются — приложение стартует чисто с проектами.
 */
@RestController
class ProjectController(
    private val projectStore: ProjectStore,
    private val sessionStore: SessionStore,
    private val compressionStore: SessionCompressionStore,
    private val sessionLlmSettingsStore: SessionLlmSettingsStore,
    private val contextStore: SessionContextStore,
    private val factsStore: SessionFactsStore,
    private val branchStore: SessionBranchStore,
    private val workingMemoryStore: WorkingMemoryStore,
    private val taskStateStore: TaskStateStore,
    private val invariantsStore: InvariantsStore,
) {

    data class ProjectRequest(val name: String?)

    data class CreateSessionRequest(val title: String?)

    data class CreateSessionResponse(val sessionId: String, val projectId: Long, val title: String?)

    data class DeleteResponse(val deleted: Boolean)

    @GetMapping("/api/projects")
    fun projects(): List<Project> = projectStore.list()

    @PostMapping("/api/projects")
    fun createProject(@RequestBody body: ProjectRequest): Project {
        val name = body.name?.trim()
        if (name.isNullOrBlank()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "name не должен быть пустым")
        }
        val id = projectStore.create(name)
        if (id == -1L) {
            throw ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Не удалось создать проект")
        }
        return projectStore.get(id)
            ?: throw ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Проект создан, но не найден")
    }

    @PatchMapping("/api/projects/{id}")
    fun renameProject(@PathVariable id: Long, @RequestBody body: ProjectRequest): Project {
        val name = body.name?.trim()
        if (name.isNullOrBlank()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "name не должен быть пустым")
        }
        if (!projectStore.exists(id)) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "Проект не найден: $id")
        }
        if (!projectStore.updateName(id, name)) {
            throw ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Не удалось переименовать проект")
        }
        return projectStore.get(id)!!
    }

    /**
     * Каскадное удаление проекта: как DELETE /api/sessions/{sessionId} (HistoryController)
     * для КАЖДОЙ сессии проекта (sessionStore.delete + все per-session store'ы) ПЛЮС
     * рабочая память проекта (agent_working_memory по project_id), инварианты проекта
     * (agent_invariants по project_id) и сам проект.
     * LTM (agent_long_term_memory) ГЛОБАЛЬНАЯ — записи переживают удаление проектов.
     */
    @DeleteMapping("/api/projects/{id}")
    fun deleteProject(@PathVariable id: Long): DeleteResponse {
        if (!projectStore.exists(id)) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "Проект не найден: $id")
        }
        for (s in sessionStore.listByProject(id)) {
            sessionStore.delete(s.sessionId)
            compressionStore.remove(s.sessionId)
            sessionLlmSettingsStore.remove(s.sessionId)
            contextStore.remove(s.sessionId)
            factsStore.remove(s.sessionId)
            branchStore.remove(s.sessionId)
            taskStateStore.remove(s.sessionId)
        }
        workingMemoryStore.deleteByProject(id.toString())
        invariantsStore.deleteByProject(id.toString())
        if (!projectStore.delete(id)) {
            throw ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Не удалось удалить проект")
        }
        return DeleteResponse(true)
    }

    @PostMapping("/api/projects/{id}/sessions")
    fun createSession(@PathVariable id: Long, @RequestBody body: CreateSessionRequest): CreateSessionResponse {
        if (!projectStore.exists(id)) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "Проект не найден: $id")
        }
        val sessionId = sessionStore.createSession(id, body.title?.trim()?.takeIf { it.isNotEmpty() })
        if (sessionId == SessionStore.SESSION_ID_FALLBACK) {
            throw ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Не удалось создать сессию")
        }
        return CreateSessionResponse(sessionId, id, sessionStore.titleOf(sessionId))
    }

    @GetMapping("/api/projects/{id}/sessions")
    fun sessions(@PathVariable id: Long): List<ProjectSession> {
        if (!projectStore.exists(id)) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "Проект не найден: $id")
        }
        return sessionStore.listByProject(id)
    }
}
