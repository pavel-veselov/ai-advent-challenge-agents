package com.example.llmagent.kb

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.util.Collections
import java.util.concurrent.Executors

/**
 * Регрессия: GPUStack/OpenAI-совместимый сервер возвращает в элементах data[]
 * поле "object":"embedding" и дополнительные поля на верхнем уровне ("model",
 * "usage"). Раньше Jackson без FAIL_ON_UNKNOWN_PROPERTIES=false падал
 * UnrecognizedPropertyException — теперь DTO толерантны (@JsonIgnoreProperties).
 *
 * Стаб — встроенный JDK HttpServer (без сети/ключей GPUStack).
 */
class KbEmbeddingClientTest {

    private fun startServer(response: String, recorded: MutableList<String>): HttpServer {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/embeddings") { exchange ->
            recorded.add(exchange.requestBody.readBytes().toString(Charsets.UTF_8))
            val bytes = response.toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.executor = Executors.newCachedThreadPool()
        server.start()
        return server
    }

    @Test
    fun `embedAll parses OpenAI response with extra object-model-usage fields`() {
        // Реалистичный ответ OpenAI-совместимого сервера: "object" в data[i]
        // и "model"/"usage"/"object" на верхнем уровне.
        val response = """
            {
              "object": "list",
              "model": "qwen3-vl-embedding-8b",
              "usage": {"prompt_tokens": 12, "total_tokens": 12},
              "data": [
                {"object": "embedding", "index": 0, "embedding": [0.5, -0.25, 1.0, 0.0]},
                {"object": "embedding", "index": 1, "embedding": [1.5, 0.25, -1.0, 2.0]}
              ]
            }
        """.trimIndent()
        val requests = Collections.synchronizedList(mutableListOf<String>())
        val server = startServer(response, requests)
        try {
            val client = KbEmbeddingClient(
                baseUrl = "http://127.0.0.1:${server.address.port}",
                apiKey = "test-key",
            )

            val vectors = client.embedAll(
                listOf("первый текст", "второй текст"),
                "qwen3-vl-embedding-8b",
            )

            assertEquals(2, vectors.size, "должно вернуться по вектору на каждый текст")
            assertTrue(vectors.all { it.size == 4 }, "размерность векторов должна быть 4")
            assertEquals(0.5f, vectors[0][0])
            assertEquals(-0.25f, vectors[0][1])
            assertEquals(2.0f, vectors[1][3])
            assertEquals(1, requests.size, "2 текста <= MAX_BATCH_SIZE: один батч")
            assertTrue(requests[0].contains("\"model\":\"qwen3-vl-embedding-8b\""))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `embed returns single vector of correct dimension`() {
        val dim = 8
        val embedding = (1..dim).map { it / 8.0f }
        val response = """
            {
              "model": "qwen3-vl-embedding-8b",
              "usage": {"prompt_tokens": 5, "total_tokens": 5},
              "data": [{"object": "embedding", "index": 0, "embedding": $embedding}]
            }
        """.trimIndent()
        val requests = Collections.synchronizedList(mutableListOf<String>())
        val server = startServer(response, requests)
        try {
            val client = KbEmbeddingClient(
                baseUrl = "http://127.0.0.1:${server.address.port}",
                apiKey = "test-key",
            )

            val vector = client.embed("один запрос", "qwen3-vl-embedding-8b")

            assertEquals(dim, vector.size, "размерность эмбеддинга должна сохраниться")
            assertEquals(0.125f, vector[0])
            assertEquals(1.0f, vector[dim - 1])
        } finally {
            server.stop(0)
        }
    }
}
