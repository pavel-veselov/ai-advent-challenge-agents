package com.example.llmagent.agent

import com.example.llmagent.config.LlmProperties
import com.fasterxml.jackson.databind.ObjectMapper
import java.time.Duration
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable

/**
 * Opt-in smoke against the real GPUStack endpoint.
 * Runs only when LLM_API_KEY (+ LLM_BASE_URL) env vars are present;
 * otherwise the whole class is skipped, so `gradlew test` stays green.
 */
@EnabledIfEnvironmentVariable(named = "LLM_API_KEY", matches = ".+")
class GpuStackRealClientSmokeIT {

    @Test
    fun `real gpustack streams tokens`() {
        val baseUrl = System.getenv("LLM_BASE_URL").orEmpty()
        assertTrue(baseUrl.isNotBlank(), "LLM_BASE_URL is not set")
        val props = LlmProperties(
            provider = "gpustack",
            baseUrl = baseUrl,
            apiKey = System.getenv("LLM_API_KEY").orEmpty(),
            model = System.getenv("LLM_MODEL")?.takeIf { it.isNotBlank() } ?: "default-coding",
        )
        val client = GpuStackLlmClient(props, ObjectMapper())
        val events = client.streamChat(
            listOf(LlmMessage("user", "Ответь одним словом: привет.")),
            emptyList(),
        ).collectList().block(Duration.ofSeconds(120))!!
        assertTrue(events.isNotEmpty(), "no llm events received from GPUStack")
    }
}
