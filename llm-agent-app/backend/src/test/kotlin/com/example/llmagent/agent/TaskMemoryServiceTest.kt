package com.example.llmagent.agent

import com.example.llmagent.config.LlmProperties
import com.example.llmagent.config.LlmSettings
import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * Юнит-тесты логики памяти задачи (Day-25, task_memory):
 * - строгий разбор JSON-ответа модели [TaskMemoryService.parseTaskMemory]
 *   (валидный ответ, отсутствующие поля, нестроковые элементы, не-JSON, массив);
 * - caps [TaskMemoryService.sanitize]: ≤10 пунктов на список, ≤300 символов на пункт,
 *   trim, пустые выбрасываются;
 * - промпт извлечения [TaskMemoryService.buildExtractionPrompt]: 4 user-сообщения,
 *   запрос — ПОСЛЕДНЕЕ, сообщение/ответ обрезаны до 1500 символов;
 * - end-to-end [TaskMemoryService.updateFromExchange] на FakeToolCallLlmClient:
 *   успех сохраняет и возвращает состояние; мусор от модели — один ретрай, затем
 *   LlmSummaryException наверх (fail-open делает вызывающий код в AgentImpl).
 */
class TaskMemoryServiceTest {

    @TempDir
    lateinit var tmpDir: Path

    private val om = ObjectMapper()

    private val runSettings = LlmSettings.from(LlmProperties())

    private fun newService(name: String): TaskMemoryService =
        TaskMemoryService(JdbcTaskMemoryStore(SqliteTestSupport.jdbc(tmpDir.resolve(name))), om)

    // ---- parseTaskMemory ----

    @Test
    fun `parse valid json with all fields`() {
        val service = newService("tms-parse.db")
        val parsed = service.parseTaskMemory(
            """{"goal":"Сделать X","clarifications":["Y"],"constraints":["Z","W"]}""",
        )
        assertEquals("Сделать X", parsed.goal)
        assertEquals(listOf("Y"), parsed.clarifications)
        assertEquals(listOf("Z", "W"), parsed.constraints)
    }

    @Test
    fun `parse missing fields defaults to empty`() {
        val service = newService("tms-parse-2.db")
        val partial = service.parseTaskMemory("""{"goal":"Цель"}""")
        assertEquals("Цель", partial.goal)
        assertTrue(partial.clarifications.isEmpty())
        assertTrue(partial.constraints.isEmpty())

        val empty = service.parseTaskMemory("{}")
        assertEquals("", empty.goal)
        assertTrue(empty.clarifications.isEmpty())
        assertTrue(empty.constraints.isEmpty())
    }

    @Test
    fun `parse skips non-string list items`() {
        val service = newService("tms-parse-nonstring.db")
        val parsed = service.parseTaskMemory(
            """{"goal":"г","clarifications":["текст",42,null],"constraints":[]}""",
        )
        assertEquals(listOf("текст"), parsed.clarifications)
    }

    @Test
    fun `parse non-json throws LlmSummaryException`() {
        val service = newService("tms-parse-nonjson.db")
        assertThrows(LlmSummaryException::class.java) { service.parseTaskMemory("это не json") }
    }

    @Test
    fun `parse json array instead of object throws LlmSummaryException`() {
        val service = newService("tms-parse-array.db")
        assertThrows(LlmSummaryException::class.java) { service.parseTaskMemory("""["goal"]""") }
    }

    // ---- sanitize ----

    @Test
    fun `sanitize trims goal and caps lists`() {
        val service = newService("tms-sanitize.db")
        val sanitized = service.sanitize(
            ExtractedTaskMemory(
                goal = "  цель с пробелами  ",
                clarifications = List(TaskMemoryService.MAX_ITEMS + 5) { "пункт $it" },
                constraints = listOf("   ", "", "нормальный"),
            ),
        )
        assertEquals("цель с пробелами", sanitized.goal)
        assertEquals(TaskMemoryService.MAX_ITEMS, sanitized.clarifications.size, "не более MAX_ITEMS пунктов")
        assertEquals("пункт 0", sanitized.clarifications.first())
        assertEquals(listOf("нормальный"), sanitized.constraints, "пустые и whitespace-пункты выбрасываются")
    }

    @Test
    fun `sanitize truncates long items`() {
        val service = newService("tms-sanitize-long.db")
        val long = "а".repeat(400)
        val sanitized = service.sanitize(
            ExtractedTaskMemory("г", listOf(long, "короткий"), emptyList()),
        )
        assertEquals(TaskMemoryService.ITEM_MAX_CHARS, sanitized.clarifications[0].length)
        assertEquals("короткий", sanitized.clarifications[1])
    }

    // ---- buildExtractionPrompt ----

    @Test
    fun `extraction prompt ends with request and truncates messages`() {
        val service = newService("tms-prompt.db")
        val previous = TaskMemory("s", "Цель", listOf("у"), emptyList())
        val longUser = "ю".repeat(2000)
        val prompt = service.buildExtractionPrompt(previous, longUser, "ответ ассистента")

        assertEquals(4, prompt.size, "прежнее состояние, сообщение пользователя, ответ, запрос")
        assertTrue(prompt.all { it.role == "user" }, "все сообщения промпта — user")
        assertTrue("Цель" in prompt[0].content!!, "в первом сообщении — прежнее состояние")
        assertEquals(
            "Сообщение пользователя:\n" + longUser.take(TaskMemoryService.MAX_MESSAGE_CHARS),
            prompt[1].content,
            "сообщение пользователя обрезано до MAX_MESSAGE_CHARS",
        )
        assertTrue("ответ ассистента" in prompt[2].content!!)
        assertEquals(TaskMemoryService.TASK_MEMORY_REQUEST_PROMPT, prompt.last().content, "запрос — последнее сообщение")
    }

    // ---- updateFromExchange (end-to-end на fake-LLM) ----

    @Test
    fun `updateFromExchange parses saves and returns state`() = runBlocking {
        val service = newService("tms-update.db")
        val llm = FakeToolCallLlmClient()
            .mark(
                TaskMemoryService.TASK_MEMORY_REQUEST_PROMPT,
                MockPlan.Text("""{"goal":"Новая цель","clarifications":["у1"],"constraints":["о1"]}"""),
            )

        val updated = service.updateFromExchange(
            "tms-1", service.load("tms-1"), "вопрос", "ответ", llm, runSettings,
        )

        assertNotNull(updated)
        assertEquals("Новая цель", updated!!.goal)
        assertEquals(listOf("у1"), updated.clarifications)
        assertEquals(listOf("о1"), updated.constraints)

        val stored = service.load("tms-1")
        assertEquals("Новая цель", stored.goal)
        assertTrue(!stored.isEmpty())
    }

    @Test
    fun `updateFromExchange retries once on garbage then throws`() {
        val service = newService("tms-retry.db")
        val llm = FakeToolCallLlmClient()
            .mark(TaskMemoryService.TASK_MEMORY_REQUEST_PROMPT, MockPlan.Text("совсем не json"))

        assertThrows(LlmSummaryException::class.java) {
            runBlocking {
                service.updateFromExchange(
                    "tms-2", service.load("tms-2"), "в", "о", llm, runSettings,
                )
            }
        }
        assertEquals(2, llm.callCount.get(), "один ретрай на не-JSON ответ")
        assertTrue(service.load("tms-2").isEmpty(), "при сбое состояние не сохраняется")
    }
}
