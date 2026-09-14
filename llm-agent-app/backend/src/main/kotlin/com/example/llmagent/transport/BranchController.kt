package com.example.llmagent.transport

import com.example.llmagent.agent.Branch
import com.example.llmagent.agent.SessionBranchStore
import com.example.llmagent.agent.SessionStore
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

/**
 * Объект ветки в API (ответные shape контракта): id/name/headMessageId/createdAt.
 * sessionId в запросах идёт из пути, поэтому в объекте ветки не дублируется.
 */
data class BranchResponse(
    val id: Long,
    val name: String,
    val headMessageId: Long?,
    val createdAt: String,
)

/** Ответ GET/PUT /api/sessions/{sessionId}/branches (полный список + активная ветка). */
data class BranchesResponse(
    val sessionId: String,
    /** Ид активной ветки; null — активная не выбрана (действует ветка «Основная»). */
    val activeBranchId: Long?,
    val branches: List<BranchResponse>,
)

/** Тело POST /api/sessions/{sessionId}/branches: сообщение, от которого начинается ветка. */
data class CreateBranchRequest(val messageId: Long)

/** Тело PUT /api/sessions/{sessionId}/branches: переключение активной ветки. */
data class SetActiveBranchRequest(val activeBranchId: Long)

/**
 * Ветки диалога сессии (GET/POST/PUT /api/sessions/{sessionId}/branches).
 *
 * GET — `{ "sessionId", "activeBranchId", "branches": [BranchResponse] }` (пусто — веток нет,
 * активная не выбрана). POST — создаёт ветку с головой в указанном сообщении (fork),
 * авто-имя «Ветка N» (N = число веток + 1), делает её АКТИВНОЙ → 200 с объектом ветки;
 * 400, если messageId не принадлежит сессии. PUT — переключает активную ветку
 * `{ "activeBranchId": 2 }` → 200 с полным GET-shape; 400, если ветка неизвестна.
 *
 * Дерево parent_id в chat_messages поддерживает SessionStore.append: последующие сообщения
 * прикрепляются к голове активной ветки, поэтому каждая ветка имеет независимую цепочку
 * (общий корень до точки fork). Ветки персистятся в SQLite (session_branches) и переживают
 * перезапуск backend.
 */
@RestController
class BranchController(
    private val branchStore: SessionBranchStore,
    private val sessionStore: SessionStore,
) {

    @GetMapping("/api/sessions/{sessionId}/branches")
    fun list(@PathVariable sessionId: String): BranchesResponse {
        val branches = branchStore.list(sessionId)
        // Активная ветка по иду из хранилища; если её строки уже нет — активной нет (null).
        val activeId = branchStore.getActive(sessionId)?.id
        return BranchesResponse(sessionId, activeId, branches.map(BranchController::toResponse))
    }

    @PostMapping("/api/sessions/{sessionId}/branches")
    fun create(@PathVariable sessionId: String, @RequestBody body: CreateBranchRequest): BranchResponse {
        // Валидация: сообщение обязано принадлежать сессии (иначе fork «мёртвый»).
        if (sessionStore.getStoredById(sessionId, body.messageId) == null) {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "messageId ${body.messageId} не принадлежит сессии $sessionId",
            )
        }
        val branch = branchStore.create(sessionId, "Ветка ${branchStore.count(sessionId) + 1}", body.messageId)
        branchStore.setActive(sessionId, branch.id)
        return toResponse(branch)
    }

    @PutMapping("/api/sessions/{sessionId}/branches")
    fun setActive(@PathVariable sessionId: String, @RequestBody body: SetActiveBranchRequest): BranchesResponse {
        if (branchStore.get(sessionId, body.activeBranchId) == null) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Неизвестная ветка: ${body.activeBranchId}")
        }
        branchStore.setActive(sessionId, body.activeBranchId)
        val branches = branchStore.list(sessionId)
        return BranchesResponse(sessionId, body.activeBranchId, branches.map(BranchController::toResponse))
    }

    private companion object {
        fun toResponse(b: Branch): BranchResponse =
            BranchResponse(b.id, b.name, b.headMessageId, b.createdAt)
    }
}
