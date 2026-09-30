package com.example.llmagent.transport

import java.io.File
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.core.io.ByteArrayResource
import org.springframework.http.MediaType
import org.springframework.http.client.MultipartBodyBuilder
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.web.reactive.function.BodyInserters

/**
 * Интеграционные тесты REST баз знаний (Day-22): валидация POST, позитивный create
 * (проверяется только ответ 201 — сама индексация асинхронна и не ждётся) и 404/400 мутации.
 * Отдельный временный SQLite-файл (как в MemoryControllerTest). Валидационные тесты
 * НЕ вызывают LLM — реальные эмбеддинги не проверяются.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class KbControllerTest {

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") {
                "jdbc:sqlite:${File.createTempFile("llm-agent-kb-it-", ".db").absolutePath.replace('\\', '/')}"
            }
        }
    }

    @Autowired
    lateinit var client: WebTestClient

    /** Multipart POST /api/kb с заданными частями. */
    private fun post(parts: (MultipartBodyBuilder) -> Unit): WebTestClient.ResponseSpec {
        val builder = MultipartBodyBuilder()
        parts(builder)
        return client.post().uri("/api/kb")
            .contentType(MediaType.MULTIPART_FORM_DATA)
            .body(BodyInserters.fromMultipartData(builder.build()))
            .exchange()
    }

    @Test
    @Order(1)
    fun `GET kb on fresh database returns empty list`() {
        client.get().uri("/api/kb")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.knowledgeBases").isArray
            .jsonPath("$.knowledgeBases").isEmpty
    }

    @Test
    @Order(2)
    fun `GET models returns catalog with default embedding model`() {
        client.get().uri("/api/kb/models")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.models").isArray
            .jsonPath("$.models[0].id").isEqualTo("qwen3-vl-embedding-8b")
            .jsonPath("$.models[0].dimension").isEqualTo(4096)
    }

    @Test
    @Order(3)
    fun `POST without name returns 400`() {
        post { it.part("strategy", "fixed") }
            .expectStatus().isBadRequest
            .expectBody(String::class.java)
    }

    @Test
    @Order(4)
    fun `POST with blank name returns 400`() {
        post { builder ->
            builder.part("name", "   ")
            builder.part("strategy", "fixed")
        }.expectStatus().isBadRequest
            .expectBody(String::class.java)
    }

    @Test
    @Order(5)
    fun `POST with wrong strategy returns 400`() {
        post { builder ->
            builder.part("name", "Доки")
            builder.part("strategy", "wrong")
        }.expectStatus().isBadRequest
            .expectBody(String::class.java)
    }

    @Test
    @Order(6)
    fun `POST without files returns 400`() {
        post { builder ->
            builder.part("name", "Доки")
            builder.part("strategy", "fixed")
        }.expectStatus().isBadRequest
            .expectBody(String::class.java)
    }

    @Test
    @Order(7)
    fun `POST with unsupported extension returns 400`() {
        post { builder ->
            builder.part("name", "Доки")
            builder.part("strategy", "fixed")
            builder.part(
                "files",
                object : ByteArrayResource("MZ".toByteArray()) {
                    override fun getFilename(): String = "virus.exe"
                },
            )
        }.expectStatus().isBadRequest
            .expectBody(String::class.java)
    }

    @Test
    @Order(8)
    fun `PUT active unknown id returns 404 and empty body returns 400`() {
        client.put().uri("/api/kb/987654/active")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"active":true}""")
            .exchange()
            .expectStatus().isNotFound
        client.put().uri("/api/kb/987654/active")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("{}")
            .exchange()
            .expectStatus().isBadRequest
    }

    @Test
    @Order(9)
    fun `DELETE unknown id returns 404`() {
        client.delete().uri("/api/kb/987654")
            .exchange()
            .expectStatus().isNotFound
    }

    @Test
    @Order(10)
    fun `validation failures created nothing`() {
        client.get().uri("/api/kb")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.knowledgeBases").isEmpty
    }

    @Test
    @Order(11)
    fun `POST with valid multipart creates KB in indexing state`() {
        post { builder ->
            builder.part("name", "e2e")
            builder.part("strategy", "fixed")
            builder.part("chunkSize", "100")
            builder.part("overlap", "20")
            builder.part("embeddingModel", "qwen3-vl-embedding-8b")
            builder.part(
                "files",
                object : ByteArrayResource("# Дока\n\nКонтент для индексации.".toByteArray()) {
                    override fun getFilename(): String = "doc.md"
                },
            )
        }.expectStatus().isCreated
            .expectBody()
            .jsonPath("$.id").isNumber
            .jsonPath("$.name").isEqualTo("e2e")
            .jsonPath("$.status").isEqualTo("indexing")
    }

    @Test
    @Order(12)
    fun `GET settings on fresh store returns defaults`() {
        client.get().uri("/api/kb/settings")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.filterEnabled").isEqualTo(false)
            .jsonPath("$.minScore").isEqualTo(0.35)
            .jsonPath("$.candidateK").isEqualTo(8)
            .jsonPath("$.topK").isEqualTo(4)
            .jsonPath("$.rewriteEnabled").isEqualTo(false)
    }

    @Test
    @Order(13)
    fun `PUT settings roundtrip persists and echoes saved values`() {
        client.put().uri("/api/kb/settings")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"filterEnabled":true,"minScore":0.5,"candidateK":10,"topK":3,"rewriteEnabled":true}""")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.filterEnabled").isEqualTo(true)
            .jsonPath("$.minScore").isEqualTo(0.5)
            .jsonPath("$.candidateK").isEqualTo(10)
            .jsonPath("$.topK").isEqualTo(3)
            .jsonPath("$.rewriteEnabled").isEqualTo(true)
        // Персистентность: GET (service.load()) видит сохранённые значения.
        client.get().uri("/api/kb/settings")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.filterEnabled").isEqualTo(true)
            .jsonPath("$.minScore").isEqualTo(0.5)
            .jsonPath("$.candidateK").isEqualTo(10)
            .jsonPath("$.topK").isEqualTo(3)
            .jsonPath("$.rewriteEnabled").isEqualTo(true)
    }

    @Test
    @Order(14)
    fun `PUT settings with minScore outside 0 to 1 returns 400 with russian message`() {
        client.put().uri("/api/kb/settings")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"filterEnabled":false,"minScore":1.5,"candidateK":8,"topK":4,"rewriteEnabled":false}""")
            .exchange()
            .expectStatus().isBadRequest
            .expectBody()
            .jsonPath("$.message")
            .isEqualTo("minScore должен быть в диапазоне от 0 до 1: 1.5")
    }

    @Test
    @Order(15)
    fun `PUT settings with candidateK outside 1 to 100 returns 400 with russian message`() {
        client.put().uri("/api/kb/settings")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"filterEnabled":false,"minScore":0.35,"candidateK":0,"topK":4,"rewriteEnabled":false}""")
            .exchange()
            .expectStatus().isBadRequest
            .expectBody()
            .jsonPath("$.message")
            .isEqualTo("candidateK должен быть в диапазоне от 1 до 100: 0")
    }

    @Test
    @Order(16)
    fun `PUT settings with topK greater than candidateK returns 400 with russian message`() {
        client.put().uri("/api/kb/settings")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"filterEnabled":false,"minScore":0.35,"candidateK":8,"topK":9,"rewriteEnabled":false}""")
            .exchange()
            .expectStatus().isBadRequest
            .expectBody()
            .jsonPath("$.message")
            .isEqualTo("topK должен быть в диапазоне от 1 до candidateK=8: 9")
    }

    @Test
    @Order(17)
    fun `PUT settings with malformed JSON returns 400`() {
        client.put().uri("/api/kb/settings")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"filterEnabled": """)
            .exchange()
            .expectStatus().isBadRequest
    }

    @Test
    @Order(18)
    fun `failed PUT settings do not persist changes`() {
        // После невалидных PUT (Orders 14-17) в хранилище остаются значения Order 13.
        client.get().uri("/api/kb/settings")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.filterEnabled").isEqualTo(true)
            .jsonPath("$.minScore").isEqualTo(0.5)
            .jsonPath("$.candidateK").isEqualTo(10)
            .jsonPath("$.topK").isEqualTo(3)
            .jsonPath("$.rewriteEnabled").isEqualTo(true)
    }
}
