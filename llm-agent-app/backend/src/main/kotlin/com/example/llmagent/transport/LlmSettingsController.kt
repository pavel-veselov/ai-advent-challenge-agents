package com.example.llmagent.transport

import com.example.llmagent.config.LlmSettingsProvider
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

/** Применённые настройки LLM — фронтенд забирает их при открытии страницы, до первого запроса. */
@RestController
class LlmSettingsController(private val settingsProvider: LlmSettingsProvider) {

    @GetMapping("/api/llm-settings")
    fun llmSettings(): Map<String, Any?> = settingsProvider.settings()
}
