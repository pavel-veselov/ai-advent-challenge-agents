package com.example.llmagent.transport

import com.example.llmagent.agent.SessionStore
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController

/** Восстановление истории диалога после перезагрузки страницы. */
@RestController
class HistoryController(private val sessionStore: SessionStore) {

    data class HistoryMessage(val role: String, val content: String)
    data class HistoryResponse(val sessionId: String, val messages: List<HistoryMessage>)

    @GetMapping("/api/sessions/{sessionId}/history")
    fun history(@PathVariable sessionId: String): HistoryResponse =
        HistoryResponse(
            sessionId,
            sessionStore.get(sessionId).map { HistoryMessage(it.role, it.content) },
        )
}
