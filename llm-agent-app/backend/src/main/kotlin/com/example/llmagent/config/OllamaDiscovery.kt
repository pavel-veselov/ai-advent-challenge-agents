package com.example.llmagent.config

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Component
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Живое обнаружение моделей Ollama (провайдер «ollama»):
 * - `GET {baseUrl}/api/tags` — список моделей (`name`, `details.parameter_size`,
 *   `details.quantization_level`);
 * - `POST {baseUrl}/api/show` с телом `{"model":name}` — контекстное окно:
 *   `model_info["<name>.context_length"]` (ключ может быть архитектурным, например
 *   `qwen2.context_length` — тогда берётся любой `*.context_length` из model_info);
 *   ничего не найдено → дефолт [DEFAULT_CONTEXT_LIMIT].
 *
 * Результат кэшируется на [CACHE_TTL] (30 секунд), включая отрицательный (недоступная
 * Ollama — пустой список): недоступность не должна порождать шторм запросов.
 * Ошибки сети/разбора НИКОГДА не бросаются наружу — недоступная Ollama это просто
 * пустой каталог (требование контракта /api/llm/providers и PUT-переключения).
 *
 * Транспорт — синхронный JDK HttpClient, а НЕ WebClient: discovery вызывается из
 * WebFlux event-loop потоков (GET /api/llm/providers, PUT /api/llm-settings, сессии
 * агента), где Reactor `block()` бросает IllegalStateException («not supported in
 * thread reactor-http-nio-*»): запрос при этом уже уходит, но ожидание ответа падает,
 * ошибка молча проглатывается — и каталог выглядит пустым. JDK-клиент блокирует
 * вызывающий поток без ограничений Reactor и работает из любого потока.
 */
@Component
class OllamaDiscovery(
    private val props: LlmProperties,
    private val om: ObjectMapper,
) {

    /** Модель живого каталога Ollama: идентификатор, контекстное окно, человекочитаемое описание. */
    data class OllamaModel(
        val id: String,
        val contextLimit: Int,
        val parameterSize: String?,
        val quantizationLevel: String?,
    ) {
        /** Формат описания зафиксирован контрактом: «Ollama · <parameter_size> · <quantization_level>». */
        val description: String
            get() = "Ollama · ${parameterSize.orEmpty()} · ${quantizationLevel.orEmpty()}"
    }

    private val baseUrl = props.ollamaBaseUrl.trim().trimEnd('/')

    private val http = HttpClient.newBuilder()
        .connectTimeout(REQUEST_TIMEOUT)
        .build()

    /** Кэш discovery: (время снимка, модели). Пустой список тоже кэшируется. */
    @Volatile
    private var cache: Pair<Long, List<OllamaModel>>? = null

    /** Модели Ollama (кэш 30 c); недоступна/ошибка → пустой список. */
    fun models(): List<OllamaModel> {
        val now = System.nanoTime()
        cache?.let { (at, value) -> if (now - at < CACHE_TTL_NANOS) return value }
        val fresh = fetchModels()
        cache = now to fresh
        return fresh
    }

    /** Идентификаторы моделей (для валидации выбора модели). */
    fun modelIds(): Set<String> = models().map { it.id }.toSet()

    /** true, если Ollama отвечает и имеет хотя бы одну модель. */
    fun available(): Boolean = models().isNotEmpty()

    /** Контекстное окно модели Ollama; null — модель не в живом каталоге. */
    fun contextLimitOf(id: String): Int? = models().firstOrNull { it.id == id }?.contextLimit

    private fun fetchModels(): List<OllamaModel> {
        return try {
            val body = get("/api/tags") ?: return emptyList()
            val modelsNode = om.readTree(body).path("models")
            if (!modelsNode.isArray) return emptyList()
            modelsNode.mapNotNull { node ->
                val name = node.path("name").takeIf { it.isTextual }?.asText()?.trim().orEmpty()
                if (name.isEmpty()) return@mapNotNull null
                val details = node.path("details")
                OllamaModel(
                    id = name,
                    contextLimit = fetchContextLength(name),
                    parameterSize = details.path("parameter_size").takeIf { it.isTextual }?.asText(),
                    quantizationLevel = details.path("quantization_level").takeIf { it.isTextual }?.asText(),
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** GET {baseUrl}{path}: тело ответа или null (не 200 / сетевая ошибка / таймаут). */
    private fun get(path: String): String? = try {
        val response = http.send(
            HttpRequest.newBuilder(URI.create("$baseUrl$path"))
                .timeout(REQUEST_TIMEOUT)
                .header("Accept", "application/json")
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        response.takeIf { it.statusCode() == 200 }?.body()
    } catch (e: Exception) {
        null
    }

    /** Контекстное окно через POST /api/show; модель недоступна/нет ключа → [DEFAULT_CONTEXT_LIMIT]. */
    private fun fetchContextLength(name: String): Int {
        return try {
            val payload = om.writeValueAsString(om.createObjectNode().put("model", name))
            val response = http.send(
                HttpRequest.newBuilder(URI.create("$baseUrl/api/show"))
                    .timeout(REQUEST_TIMEOUT)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload))
                    .build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            if (response.statusCode() != 200) return DEFAULT_CONTEXT_LIMIT
            val info = om.readTree(response.body()).path("model_info")
            contextLengthFrom(info, name) ?: DEFAULT_CONTEXT_LIMIT
        } catch (e: Exception) {
            DEFAULT_CONTEXT_LIMIT
        }
    }

    /**
     * Ищет контекстное окно в model_info: сначала точный ключ `<model>.context_length`,
     * затем любой ключ вида `<…>.context_length` (новые версии Ollama пишут архитектуру,
     * например `qwen2.context_length`). Ничего нет → null.
     */
    private fun contextLengthFrom(info: JsonNode, model: String): Int? {
        info.path("$model.context_length").takeIf { it.isInt }?.let { return it.asInt() }
        info.fieldNames().asSequence()
            .filter { it.endsWith(".context_length") }
            .map { info.path(it) }
            .firstOrNull { it.isInt }
            ?.let { return it.asInt() }
        return null
    }

    private companion object {
        /** Кэш discovery — 30 секунд (требование контракта). */
        val CACHE_TTL_NANOS: Long = Duration.ofSeconds(30).toNanos()

        /** Таймаут одного HTTP-запроса к Ollama (discovery не должен подвешивать вызовы). */
        val REQUEST_TIMEOUT: Duration = Duration.ofSeconds(3)

        /** Контекстное окно по умолчанию, если /api/show не ответил или не содержит ключа. */
        const val DEFAULT_CONTEXT_LIMIT = 8192
    }
}
