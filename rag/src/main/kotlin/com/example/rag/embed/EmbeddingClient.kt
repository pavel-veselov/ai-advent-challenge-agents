package com.example.rag.embed

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.nio.charset.StandardCharsets
import java.time.Duration

/**
 * Тело запроса к OpenAI-совместимому эндпоинту эмбеддингов (POST /v1/embeddings).
 */
@Serializable
internal data class EmbeddingRequestDto(
	val model: String,
	val input: List<String>,
)

/**
 * Элемент массива `data` в ответе: позиция входного текста в батче + вектор.
 */
@Serializable
internal data class EmbeddingItemDto(
	val index: Int,
	val embedding: List<Float>,
)

/**
 * Ответ OpenAI-совместимого API: `{"object":"list","data":[{"index":..,"embedding":[..]}],"usage":{..}}`.
 * Неизвестные поля (object, model, usage и т.п.) игнорируются.
 */
@Serializable
internal data class EmbeddingResponseDto(
	val data: List<EmbeddingItemDto>,
)

/** Максимальное число текстов в одном запросе к API эмбеддингов. */
internal const val MAX_BATCH_SIZE = 32

/**
 * Разбивает тексты на батчи не больше [maxBatchSize], сохраняя исходный порядок.
 * Чистая функция — покрывается юнит-тестами без HTTP.
 */
internal fun batch(texts: List<String>, maxBatchSize: Int): List<List<String>> {
	require(maxBatchSize > 0) { "maxBatchSize должен быть положительным: $maxBatchSize" }
	return texts.chunked(maxBatchSize)
}

/**
 * Ошибка HTTP-вызова API эмбеддингов. Сообщение содержит статус и (усечённое) тело
 * ответа сервера, но НИКОГДА не содержит ключ API — он живёт только в заголовке запроса.
 */
class EmbeddingApiException(
	message: String,
	val statusCode: Int,
) : RuntimeException(message)

/**
 * HTTP-клиент GPUStack/OpenAI-совместимого эндпоинта эмбеддингов.
 *
 * Семантика env/модели/размерности повторяет `config/CorpusConfig`:
 * baseUrl — env LLM_BASE_URL, apiKey — env LLM_API_KEY (никогда не печатать),
 * модель по умолчанию qwen3-vl-embedding-8b, размерность 4096 (передаются явно).
 *
 * @param baseUrl        базовый URL API (завершающий «/» обрезается, путь /v1/embeddings добавляется сам)
 * @param apiKey         ключ API (Authorization: Bearer); не попадает в исключения/toString/логи
 * @param model          имя модели эмбеддингов
 * @param dim            ожидаемая размерность вектора (проверяется при разборе ответа)
 * @param timeoutSeconds таймаут HTTP-запроса (и соединения)
 */
class EmbeddingClient(
	baseUrl: String,
	private val apiKey: String,
	val model: String,
	val dim: Int,
	val timeoutSeconds: Long = 60,
	/** Задержки между повторами (экспоненциальный backoff 1с, 2с, 4с). */
	private val backoffMillis: LongArray = longArrayOf(1_000L, 2_000L, 4_000L),
) {
	init {
		require(baseUrl.isNotBlank()) { "baseUrl не должен быть пустым" }
		require(apiKey.isNotBlank()) { "apiKey не должен быть пустым" }
		require(model.isNotBlank()) { "model не должен быть пустым" }
		require(dim > 0) { "dim должен быть положительным: $dim" }
		require(timeoutSeconds > 0) { "timeoutSeconds должен быть положительным: $timeoutSeconds" }
	}

	/** Эндпоинт: хвостовые «/» базового URL обрезаны, добавлен путь /v1/embeddings. */
	val endpoint: String = baseUrl.trimEnd('/') + "/v1/embeddings"

	private val requestTimeout: Duration = Duration.ofSeconds(timeoutSeconds)

	private val http: HttpClient = HttpClient.newBuilder()
		.connectTimeout(requestTimeout)
		.build()

	private val jsonFormat: Json = Json { ignoreUnknownKeys = true }

	/** Удобная обёртка для одного текста. */
	fun embed(text: String): FloatArray = embedAll(listOf(text)).first()

	/**
	 * Возвращает векторы для [texts] в том же порядке, что и вход.
	 * Вход режется на батчи <= [MAX_BATCH_SIZE], по одному POST на батч;
	 * при 429/5xx и сетевых ошибках — до [MAX_ATTEMPTS] попыток с backoff.
	 */
	fun embedAll(texts: List<String>): List<FloatArray> {
		if (texts.isEmpty()) return emptyList()
		val result = arrayOfNulls<FloatArray>(texts.size)
		var offset = 0
		for (batchTexts in batch(texts, MAX_BATCH_SIZE)) {
			val requestJson = jsonFormat.encodeToString(
				EmbeddingRequestDto(model = model, input = batchTexts)
			)
			val vectors = parseResponse(postWithRetry(requestJson))
			if (vectors.size != batchTexts.size) {
				throw IllegalStateException(
					"API вернул ${vectors.size} векторов для батча из ${batchTexts.size} текстов"
				)
			}
			vectors.forEachIndexed { i, vector -> result[offset + i] = vector }
			offset += batchTexts.size
		}
		return List(texts.size) { requireNotNull(result[it]) }
	}

	/**
	 * Разбирает JSON-ответ OpenAI-формата: сортирует `data` по полю `index`
	 * и проверяет размерность каждого вектора ([dim]); при несовпадении —
	 * [IllegalStateException]. Внутренняя — тестируется без HTTP.
	 */
	internal fun parseResponse(json: String): List<FloatArray> =
		jsonFormat.decodeFromString(EmbeddingResponseDto.serializer(), json)
			.data
			.sortedBy { it.index }
			.map { item ->
				val vector = item.embedding.toFloatArray()
				if (vector.size != dim) {
					throw IllegalStateException(
						"Несовпадение размерности эмбеддинга: ожидалось $dim, получено ${vector.size} (index ${item.index})"
					)
				}
				vector
			}

	/** POST одного батча с повторами: до [MAX_ATTEMPTS] попыток, backoff [backoffMillis]. */
	private fun postWithRetry(bodyJson: String): String {
		var lastError: Exception? = null
		for (attempt in 1..MAX_ATTEMPTS) {
			if (attempt > 1) {
				val delayMillis = backoffMillis.getOrElse(attempt - 2) { backoffMillis.last() }
				try {
					Thread.sleep(delayMillis)
				} catch (interrupted: InterruptedException) {
					Thread.currentThread().interrupt()
					throw IOException("Прерваны во время ожидания повтора запроса эмбеддингов", interrupted)
				}
			}
			try {
				val response = http.send(buildRequest(bodyJson), HttpResponse.BodyHandlers.ofString())
				val status = response.statusCode()
				when {
					status in 200..299 -> return response.body()
					// 429 и 5xx — временные ошибки: повторить после паузы.
					status == 429 || status in 500..599 -> lastError = EmbeddingApiException(
						"Временная ошибка API эмбеддингов: HTTP $status " +
							"(попытка $attempt/$MAX_ATTEMPTS), body=${response.body().take(MAX_ERROR_BODY_CHARS)}",
						statusCode = status,
					)
					// Прочие 4xx — падаем сразу (в сообщении нет ключа API).
					else -> throw EmbeddingApiException(
						"Запрос к API эмбеддингов отклонён: HTTP $status, " +
							"body=${response.body().take(MAX_ERROR_BODY_CHARS)}",
						statusCode = status,
					)
				}
			} catch (e: HttpTimeoutException) {
				lastError = IOException("Таймаут запроса к API эмбеддингов (попытка $attempt/$MAX_ATTEMPTS)", e)
			} catch (e: IOException) {
				lastError = IOException(
					"Сетевая ошибка запроса к API эмбеддингов (попытка $attempt/$MAX_ATTEMPTS): ${e.message}",
					e,
				)
			}
		}
		throw lastError ?: IllegalStateException("Запрос к API эмбеддингов не удался за $MAX_ATTEMPTS попыток")
	}

	private fun buildRequest(bodyJson: String): HttpRequest =
		HttpRequest.newBuilder()
			.uri(URI.create(endpoint))
			.timeout(requestTimeout)
			.header("Authorization", "Bearer $apiKey")
			.header("Content-Type", "application/json")
			.POST(HttpRequest.BodyPublishers.ofString(bodyJson, StandardCharsets.UTF_8))
			.build()

	/** Без ключа API — только нечувствительные параметры клиента. */
	override fun toString(): String =
		"EmbeddingClient(model=$model, dim=$dim, endpoint=$endpoint, timeoutSeconds=$timeoutSeconds)"

	companion object {
		/** Всего попыток на батч (1 основная + 2 повтора). */
		const val MAX_ATTEMPTS = 3

		private const val MAX_ERROR_BODY_CHARS = 200
	}
}
