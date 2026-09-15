package com.example.llmagent.transport

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.reactive.server.WebTestClient

/**
 * Интеграционный тест профилей пользователя (персонализация агента). CRUD через REST:
 * POST /api/profiles (валидация name: 400 — пустое, 409 — дубль имени), PUT /api/profiles/{id}
 * (404 — нет профиля), DELETE /api/profiles/{id} (200 {deleted:true} → 404 на повторном),
 * ГЛОБАЛЬНЫЙ активный профиль в app_settings: GET/PUT /api/profiles/active ({profileId: Long?};
 * null — «Без профиля»; 404 — профиль не найден). Удаление активного профиля сбрасывает
 * ключ `profile.active`.
 *
 * Отдельный временный SQLite-файл (рабочую БД ./data не трогаем); тесты упорядочены,
 * т.к. справочник профилей и ключ `profile.active` глобальны.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class ProfileControllerTest {

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") {
                "jdbc:sqlite:${File.createTempFile("llm-agent-profile-it-", ".db").absolutePath.replace('\\', '/')}"
            }
        }
    }

    @Autowired
    lateinit var client: WebTestClient

    private val om = ObjectMapper()

    private fun get(path: String): JsonNode {
        val body = client.get().uri(path)
            .exchange()
            .expectStatus().isOk
            .expectBody(String::class.java)
            .returnResult()
            .responseBody!!
        return om.readTree(body)
    }

    private fun postProfile(json: String): JsonNode {
        val body = client.post().uri("/api/profiles")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(json)
            .exchange()
            .expectStatus().isOk
            .expectBody(String::class.java)
            .returnResult()
            .responseBody!!
        return om.readTree(body)
    }

    private fun putActive(profileId: Long?): WebTestClient.ResponseSpec {
        val json = profileId?.let { """{"profileId":$it}""" } ?: """{"profileId":null}"""
        return client.put().uri("/api/profiles/active")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(json)
            .exchange()
    }

    @Test
    @Order(1)
    fun `GET fresh db return empty list and null`() {
        assertTrue(get("/api/profiles").isEmpty, "в чистой БД профилей нет")
        assertTrue(get("/api/profiles/active")["activeProfileId"].isNull, "активный профиль не выбран — null")
    }

    @Test
    @Order(2)
    fun `POST creates profile with all fields and GET lists it`() {
        val created = postProfile(
            """{"name":"Профиль 1","position":"Backend-разработчик",""" +
                """"responseFormat":"кратко, списком","preferences":"код объяснять по шагам",""" +
                """"constraints":"отвечай только по-русски"}""",
        )
        assertTrue(created["id"].asLong() > 0, "профиль получил реальный id из БД")
        assertEquals("Профиль 1", created["name"].asText())
        assertEquals("Backend-разработчик", created["position"].asText())
        assertEquals("кратко, списком", created["responseFormat"].asText())
        assertEquals("код объяснять по шагам", created["preferences"].asText())
        assertEquals("отвечай только по-русски", created["constraints"].asText())
        assertTrue(created.has("createdAt") && created["createdAt"].asText().isNotBlank())
        assertTrue(created.has("updatedAt") && created["updatedAt"].asText().isNotBlank())

        val list = get("/api/profiles")
        assertEquals(1, list.size(), "созданный профиль виден в справочнике")
        assertEquals(created["id"].asLong(), list[0]["id"].asLong())
        assertEquals("Профиль 1", list[0]["name"].asText())
    }

    @Test
    @Order(3)
    fun `POST duplicate name returns 409 and blank or missing name returns 400`() {
        postProfile("""{"name":"Дубль-тест"}""")

        // имя уже занято → 409
        client.post().uri("/api/profiles")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"name":"Дубль-тест"}""")
            .exchange()
            .expectStatus().isEqualTo(HttpStatus.CONFLICT)
            .expectBody(String::class.java)

        val badBodies = listOf(
            """{"name":""}""",              // пустое имя → 400
            """{"name":"   "}""",           // blank → 400
            """{"position":"без имени"}""", // name отсутствует → 400
        )
        for (json in badBodies) {
            client.post().uri("/api/profiles")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(json)
                .exchange()
                .expectStatus().isBadRequest
                .expectBody(String::class.java)
        }
    }

    @Test
    @Order(4)
    fun `PUT active sets and clears global active profile id`() {
        val id = get("/api/profiles")[0]["id"].asLong()

        // изначально активного профиля нет
        assertTrue(get("/api/profiles/active")["activeProfileId"].isNull)

        // выбор профиля: PUT {profileId} → эхо; GET подтверждает
        val echo = putActive(id)
            .expectStatus().isOk
            .expectBody(String::class.java)
            .returnResult()
            .responseBody!!
        assertEquals(id, om.readTree(echo)["activeProfileId"].asLong(), "PUT возвращает эхо activeProfileId")
        assertEquals(id, get("/api/profiles/active")["activeProfileId"].asLong())

        // «Без профиля»: PUT {profileId: null} → activeProfileId = null
        putActive(null).expectStatus().isOk
        assertTrue(get("/api/profiles/active")["activeProfileId"].isNull)
    }

    @Test
    @Order(5)
    fun `PUT active with unknown profile id returns 404`() {
        putActive(987654).expectStatus().isNotFound
    }

    @Test
    @Order(6)
    fun `PUT profile updates fields and returns 404 for missing id`() {
        val id = get("/api/profiles")[0]["id"].asLong()

        val body = client.put().uri("/api/profiles/$id")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"name":"Обновлённый","position":"Тимлид","responseFormat":null}""")
            .exchange()
            .expectStatus().isOk
            .expectBody(String::class.java)
            .returnResult()
            .responseBody!!
        val updated = om.readTree(body)
        assertEquals("Обновлённый", updated["name"].asText())
        assertEquals("Тимлид", updated["position"].asText())
        assertTrue(updated["responseFormat"].isNull, "null в PUT очищает поле")

        // 404 — несуществующий профиль
        client.put().uri("/api/profiles/987654")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"name":"никто"}""")
            .exchange()
            .expectStatus().isNotFound
    }

    @Test
    @Order(7)
    fun `DELETE returns 200 then 404 and resets active reference`() {
        val created = postProfile("""{"name":"удаляемый профиль"}""")
        val id = created["id"].asLong()

        // делаем профиль активным, затем удаляем: активная ссылка должна сброситься
        putActive(id).expectStatus().isOk

        client.delete().uri("/api/profiles/$id")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.deleted").isEqualTo(true)

        client.delete().uri("/api/profiles/$id")
            .exchange()
            .expectStatus().isNotFound

        // удалённый профиль больше не активен: activeProfileId стал null
        assertTrue(get("/api/profiles/active")["activeProfileId"].isNull)
        assertTrue(get("/api/profiles").none { it["id"].asLong() == id })
    }
}
