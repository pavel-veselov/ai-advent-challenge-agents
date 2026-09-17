package com.example.llmagent.transport

import com.example.llmagent.agent.Invariant
import com.example.llmagent.agent.InvariantsStore
import com.example.llmagent.agent.ProjectStore
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

/**
 * Инварианты проекта (Day-14) — REST-эндпоинты БЕЗ SSE: после мутаций фронтенд сам
 * делает refetch. Инварианты — обязательные ограничения ассистента, хранятся ОТДЕЛЬНО
 * от диалога, скоупятся ПО ПРОЕКТУ (общая для всех сессий проекта):
 *   GET    /api/projects/{projectId}/invariants        — список инвариантов проекта
 *                                                          (никогда не 404: пустой список для нового проекта).
 *   POST   /api/projects/{projectId}/invariants        — добавить инвариант `{category?, text}`:
 *                                                          text пустой/blank → 400; нет проекта → 404;
 *                                                          сбой записи → 500; успех → созданная запись.
 *   PUT    /api/projects/{projectId}/invariants/{id}   — обновить инвариант `{category?, text}`:
 *                                                          text blank → 400; нет записи → 404; успех → обновлённая.
 *   DELETE /api/projects/{projectId}/invariants/{id}   — удалить инвариант: 404 — нет; иначе `{deleted:true}`.
 *
 * Валидация пустого text — обязанность контроллера (store лишь fail-open тримит и
 * обрезает до 2000 символов). Для DELETE/PUT projectId в маршруте служит для скоупа
 * (хранилище уже per-project); существование проекта проверяем только на POST.
 */
@RestController
class InvariantsController(
    private val invariantsStore: InvariantsStore,
    private val projectStore: ProjectStore,
) {

    /** Тело POST/PUT инварианта: text обязателен (blank → 400), category опционален. */
    data class InvariantRequest(
        val category: String?,
        val text: String?,
    )

    data class DeleteResponse(val deleted: Boolean)

    @GetMapping("/api/projects/{projectId}/invariants")
    fun list(@PathVariable projectId: Long): List<Invariant> =
        invariantsStore.list(projectId.toString())

    @PostMapping("/api/projects/{projectId}/invariants")
    fun add(@PathVariable projectId: Long, @RequestBody body: InvariantRequest): Invariant {
        val text = body.text?.trim()
        if (text.isNullOrBlank()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "text не должен быть пустым")
        }
        if (!projectStore.exists(projectId)) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "Проект не найден: $projectId")
        }
        val category = body.category?.trim()?.takeIf { it.isNotBlank() }
        val created = invariantsStore.add(projectId.toString(), category, text)
            ?: throw ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Не удалось сохранить инвариант")
        return created
    }

    @PutMapping("/api/projects/{projectId}/invariants/{id}")
    fun update(
        @PathVariable projectId: Long,
        @PathVariable id: Long,
        @RequestBody body: InvariantRequest,
    ): Invariant {
        val text = body.text?.trim()
        if (text.isNullOrBlank()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "text не должен быть пустым")
        }
        val category = body.category?.trim()?.takeIf { it.isNotBlank() }
        val updated = invariantsStore.update(id, category, text)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Инвариант не найден: $id")
        return updated
    }

    @DeleteMapping("/api/projects/{projectId}/invariants/{id}")
    fun delete(@PathVariable projectId: Long, @PathVariable id: Long): DeleteResponse {
        if (!invariantsStore.delete(id)) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "Инвариант не найден: $id")
        }
        return DeleteResponse(true)
    }
}
