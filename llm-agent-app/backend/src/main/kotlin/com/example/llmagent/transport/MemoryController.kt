package com.example.llmagent.transport

import com.example.llmagent.agent.LongTermEntry
import com.example.llmagent.agent.LongTermMemoryStore
import com.example.llmagent.agent.ProjectStore
import com.example.llmagent.agent.WorkingMemory
import com.example.llmagent.agent.WorkingMemoryStore
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

/**
 * Память агента (memory layers) — REST-эндпоинты БЕЗ SSE: после мутаций фронтенд
 * сам делает refetch.
 *
 * Day-13: память пишется ТОЛЬКО пользователем (REST/UI) — агент и инструмент
 * memory_save больше память не пишут.
 *
 * РАБОЧАЯ память (WM) живёт НА ПРОЕКТЕ (общая для всех сессий проекта):
 *   GET  /api/projects/{projectId}/memory        — снапшот: `{working:{task,notes}, longTerm:[...]}`
 *                                                  (working — по project_id; longTerm — ГЛОБАЛЬНЫЙ список
 *                                                  всех сессий, sourceSessionId помнит источник).
 *                                                  Для нового/несуществующего проекта — пустые структуры
 *                                                  (НИКОГДА не 404).
 *   POST /api/projects/{projectId}/memory/notes  — добавить заметку в рабочую память ПРОЕКТА
 *                                                  (только пользователь, cap 1000 на заметку): тело
 *                                                  `{note: string}`; 400 — пустая заметка, 404 — нет проекта.
 *   POST /api/projects/{projectId}/memory/new-task — сброс рабочей памяти ПРОЕКТА
 *                                                  (task=null, notes=[] — «новая задача»); longTerm не трогает.
 *
 * Долговременная память (LTM) ГЛОБАЛЬНАЯ — остаётся session-нейтральной по формату
 * маршрутов (источник записи = любая сессия):
 *   POST   /api/sessions/{sessionId}/memory/long-term       — upsert записи по (type, key),
 *                                                             sourceSessionId = сессия запроса. Валидация:
 *                                                             type ∈ profile|decision|knowledge, key/value
 *                                                             непустые → иначе 400. Сбой записи (id=-1) → 500.
 *   DELETE /api/sessions/{sessionId}/memory/long-term/{entryId} — удаление записи: 200 — удалена, 404 — нет.
 *   DELETE /api/memory/long-term                            — глобальная очистка ЛTM: удаляет ВСЕ
 *                                                             записи всех проектов и сессий. Fail-open:
 *                                                             200 `{"deleted": <число>`; сбой БД — 0 удалённых (не 500).
 */
@RestController
class MemoryController(
    private val workingMemoryStore: WorkingMemoryStore,
    private val longTermMemoryStore: LongTermMemoryStore,
    private val projectStore: ProjectStore,
) {

    /** Тело POST /memory/long-term: null-поля (или пустые) → 400. */
    data class LongTermRequest(
        val type: String?,
        val key: String?,
        val value: String?,
    )

    /** Тело POST /memory/notes: единственное поле — текст заметки (пустое → 400). */
    data class NoteRequest(val note: String?)

    /** Ответ POST /memory/notes: добавленная заметка + полный актуальный список. */
    data class NotesResponse(val note: String, val notes: List<String>)

    data class NewTaskResponse(val cleared: Boolean)

    data class DeleteResponse(val deleted: Boolean)

    /** Ответ DELETE /api/memory/long-term: число удалённых записей (fail-open: 0 — сбой БД). */
    data class ClearResponse(val deleted: Int)

    /** Снапшот памяти: working — ПО ПРОЕКТУ, longTerm — глобальный (все сессии). */
    data class MemorySnapshot(
        val working: WorkingMemory,
        val longTerm: List<LongTermEntry>,
    )

    @GetMapping("/api/projects/{projectId}/memory")
    fun memory(@PathVariable projectId: Long): MemorySnapshot =
        MemorySnapshot(
            working = workingMemoryStore.get(projectId.toString()),
            longTerm = longTermMemoryStore.listAll(),
        )

    /** Пользователь добавляет заметку в рабочую память ПРОЕКТА (только пользователь — память агентом не пишется). */
    @PostMapping("/api/projects/{projectId}/memory/notes")
    fun addNote(@PathVariable projectId: Long, @RequestBody body: NoteRequest): NotesResponse {
        val note = body.note?.trim()
        if (note.isNullOrBlank()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "note не должен быть пустым")
        }
        if (!projectStore.exists(projectId)) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "Проект не найден: $projectId")
        }
        workingMemoryStore.appendNote(projectId.toString(), note)
        return NotesResponse(
            note = note,
            notes = workingMemoryStore.get(projectId.toString()).notes,
        )
    }

    @PostMapping("/api/projects/{projectId}/memory/new-task")
    fun newTask(@PathVariable projectId: Long): NewTaskResponse {
        workingMemoryStore.clear(projectId.toString())
        return NewTaskResponse(true)
    }

    @PostMapping("/api/sessions/{sessionId}/memory/long-term")
    fun saveLongTerm(@PathVariable sessionId: String, @RequestBody body: LongTermRequest): LongTermEntry {
        val type = body.type
        if (type == null || type !in VALID_TYPES) {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "type должен быть одним из profile|decision|knowledge, получено: ${body.type}",
            )
        }
        if (body.key.isNullOrBlank()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "key не должен быть пустым")
        }
        if (body.value.isNullOrBlank()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "value не должен быть пустым")
        }
        val entry = longTermMemoryStore.upsert(sessionId, type, body.key, body.value)
        if (entry.id == -1L) {
            throw ResponseStatusException(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "Не удалось сохранить запись долговременной памяти",
            )
        }
        return entry
    }

    @DeleteMapping("/api/sessions/{sessionId}/memory/long-term/{entryId}")
    fun deleteLongTerm(@PathVariable sessionId: String, @PathVariable entryId: Long): DeleteResponse {
        if (!longTermMemoryStore.delete(entryId)) {
            throw ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "Запись долговременной памяти не найдена: $entryId",
            )
        }
        return DeleteResponse(true)
    }

    /**
     * Глобальная очистка долговременной памяти: DELETE всех записей (LTM не скоупится
     * по проекту/сессии — очищает всех). Fail-open: сбой БД не роняет запрос,
     * возвращается 200 с 0 удалённых.
     */
    @DeleteMapping("/api/memory/long-term")
    fun clearLongTerm(): ClearResponse = ClearResponse(longTermMemoryStore.clearAll())

    private companion object {
        val VALID_TYPES = setOf("profile", "decision", "knowledge")
    }
}
