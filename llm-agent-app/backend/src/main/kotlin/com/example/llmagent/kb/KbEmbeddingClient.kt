package com.example.llmagent.kb

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Поставщик эмбеддингов для баз знаний. Интерфейс — чтобы тесты RAG-сборки работали
 * на фейке без сети (KbRagServiceTest), реализация ходит в OpenAI-совместимый API
 * GPUStack (порт rag/embed/EmbeddingClient дня 21, но на Jackson + JDK HttpClient).
 */
interface KbEmbedder {

    /** Эмбеддинги для списка текстов (батчинг — внутри реализации). */
    fun embedAll(texts: List<String>, model: String): List<FloatArray>

    /** Эмбеддинг одного текста (запрос аг RAG-поиске). */
    fun embed(text: String, model: String): FloatArray = embedAll(listOf(text), model).first()
}

/**
 * Клиент эмбеддингов: POST {baseUrl}/v1/embeddings, тело {"model", "input"} —
 * OpenAI-совместимый формат. Батчи <= [MAX_BATCH_SIZE], retry с паузами, таймаут на запрос.
 * Ключ API передаётся только в заголовке Authorization и НИКОГДА не попадает в логи
 * (в сообщениях об ошибках — только статус и начало тела ответа сервера).
 */
class KbEmbeddingClient(
    private val baseUrl: String,
    private val apiKey: String,
) : KbEmbedder {

    private val mapper = ObjectMapper().registerKotlinModule()
    private val http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build()

    override fun embedAll(texts: List<String>, model: String): List<FloatArray> {
        if (texts.isEmpty()) return emptyList()
        return texts.chunked(MAX_BATCH_SIZE).flatMap { batch -> embedBatch(batch, model) }
    }

    private fun embedBatch(batch: List<String>, model: String): List<FloatArray> {
        var lastError: Exception? = null
        repeat(ATTEMPTS) { attempt ->
            try {
                val body = mapper.writeValueAsString(EmbeddingRequest(model = model, input = batch))
                val request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl.trimEnd('/') + "/v1/embeddings"))
                    .timeout(Duration.ofSeconds(REQUEST_TIMEOUT_SECONDS))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer $apiKey")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build()
                val response = http.send(request, HttpResponse.BodyHandlers.ofString())
                if (response.statusCode() !in 200..299) {
                    throw IllegalStateException(
                        "HTTP ${response.statusCode()}: ${response.body().take(300)}",
                    )
                }
                val parsed = mapper.readValue(response.body(), EmbeddingResponse::class.java)
                val embeddings = parsed.data.sortedBy { it.index }.map { vec ->
                    vec.embedding.map { it.toFloat() }.toFloatArray()
                }
                if (embeddings.size != batch.size) {
                    throw IllegalStateException(
                        "ожидалось ${batch.size} эмбеддингов, получено ${embeddings.size}",
                    )
                }
                return embeddings
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IllegalStateException("Эмбеддинг-запрос прерван", e)
            } catch (e: Exception) {
                lastError = e
                if (attempt < ATTEMPTS - 1) Thread.sleep(RETRY_DELAY_MS * (attempt + 1))
            }
        }
        throw IllegalStateException(
            "Эмбеддинг-запрос не удался после $ATTEMPTS попыток: ${lastError?.message}",
        )
    }

    // OpenAI-совместимые DTO (Jackson + kotlin-модуль). Серверы могут добавлять
    // дополнительные поля ("object", "model", "usage", ...) — игнорируем неизвестное
    // на уровне DTO, не ослабляя глобальные настройки ObjectMapper.
    @JsonIgnoreProperties(ignoreUnknown = true)
    internal data class EmbeddingRequest(val model: String, val input: List<String>)

    @JsonIgnoreProperties(ignoreUnknown = true)
    internal data class EmbeddingResponse(
        val data: List<EmbeddingData> = emptyList(),
        val model: String? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    internal data class EmbeddingData(
        val index: Int = 0,
        val embedding: List<Float> = emptyList(),
        val `object`: String? = null,
    )

    companion object {
        private const val MAX_BATCH_SIZE = 32
        private const val ATTEMPTS = 3
        private const val RETRY_DELAY_MS = 1000L
        private const val REQUEST_TIMEOUT_SECONDS = 60L
    }
}
