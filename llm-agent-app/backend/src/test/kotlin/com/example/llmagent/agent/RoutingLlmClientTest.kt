package com.example.llmagent.agent

import com.example.llmagent.config.LlmProperties
import com.example.llmagent.config.LlmSettings
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import reactor.core.publisher.Flux

/**
 * Юнит-тесты маршрутизирующего клиента: вызов уходит к клиенту провайдера, выбранного
 * В НАСТРОЙКАХ ЭТОГО ВЫЗОВА (gpustack → GPUStack-клиент, ollama → Ollama-клиент);
 * проверяются оба оверлоада streamChat (с колбэком тела и без).
 */
class RoutingLlmClientTest {

    /** Фейковый клиент: запоминает провайдера из переданных настроек. */
    private class FakeClient : LlmClient {
        val seen = mutableListOf<String>()
        var bodyCallbacks = 0

        override fun streamChat(
            messages: List<LlmMessage>,
            tools: List<ToolDefinition>,
            settings: LlmSettings,
        ): Flux<LlmEvent> {
            seen += settings.provider()
            return Flux.just(LlmEvent.Finished("stop"))
        }

        override fun streamChat(
            messages: List<LlmMessage>,
            tools: List<ToolDefinition>,
            settings: LlmSettings,
            onRequestBody: (String) -> Unit,
        ): Flux<LlmEvent> {
            seen += settings.provider()
            bodyCallbacks++
            onRequestBody("{}")
            return Flux.just(LlmEvent.Finished("stop"))
        }
    }

    private fun settings(provider: String) = LlmSettings.from(LlmProperties(provider = provider))

    @Test
    fun `routes to gpustack client for gpustack provider settings`() {
        val gpu = FakeClient()
        val ollama = FakeClient()
        val routing = RoutingLlmClient(gpu, ollama)

        routing.streamChat(emptyList(), emptyList(), settings("gpustack")).blockLast()
        routing.streamChat(emptyList(), emptyList(), settings("gpustack")) { }.blockLast()

        assertEquals(listOf("gpustack", "gpustack"), gpu.seen)
        assertEquals(0, ollama.seen.size, "ollama-клиент не должен вызываться")
        assertEquals(1, gpu.bodyCallbacks, "колбэк тела доходит до делегата")
    }

    @Test
    fun `routes to ollama client for ollama provider settings`() {
        val gpu = FakeClient()
        val ollama = FakeClient()
        val routing = RoutingLlmClient(gpu, ollama)

        routing.streamChat(emptyList(), emptyList(), settings("ollama")).blockLast()
        routing.streamChat(emptyList(), emptyList(), settings("ollama")) { }.blockLast()

        assertEquals(listOf("ollama", "ollama"), ollama.seen)
        assertEquals(0, gpu.seen.size, "gpustack-клиент не должен вызываться")
        assertEquals(1, ollama.bodyCallbacks, "колбэк тела доходит до делегата")
    }
}
