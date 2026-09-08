package com.example.llmagent.transport

import com.example.llmagent.agent.SessionStore
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController

/** Восстановление и удаление истории диалога по sessionId. */
@RestController
class HistoryController(private val sessionStore: SessionStore) {

    data class HistoryMessage(val role: String, val content: String)
    data class HistoryResponse(val sessionId: String, val messages: List<HistoryMessage>)
    data class DeleteResponse(val deleted: Boolean)

    @GetMapping("/api/sessions/{sessionId}/history")
    fun history(@PathVariable sessionId: String): HistoryResponse =
        HistoryResponse(
            sessionId,
            sessionStore.get(sessionId).map { HistoryMessage(it.role, it.content) },
        )

    /** Удаляет всю историю сессии (в том числе для несуществующей — всё равно 200). */
    @DeleteMapping("/api/sessions/{sessionId}")
    fun delete(@PathVariable sessionId: String): DeleteResponse {
        sessionStore.delete(sessionId)
        return DeleteResponse(true)
    }
}
