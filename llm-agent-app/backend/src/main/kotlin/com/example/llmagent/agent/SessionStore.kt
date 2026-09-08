package com.example.llmagent.agent

import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap

data class ChatMessage(val role: String, val content: String)

/** История диалогов в памяти по sessionId (без БД). */
@Component
class SessionStore {
    private val sessions = ConcurrentHashMap<String, MutableList<ChatMessage>>()

    fun append(sessionId: String, role: String, content: String) {
        sessions.computeIfAbsent(sessionId) { mutableListOf() }.add(ChatMessage(role, content))
    }

    fun get(sessionId: String): List<ChatMessage> = sessions[sessionId]?.toList() ?: emptyList()
}
