package com.example.llmagent.kb

import com.example.llmagent.agent.LlmCallLog
import com.example.llmagent.agent.SqliteTestSupport
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * Юнит-тесты RAG-сборки блока «### База знаний»: фейк-эмбеддер на интерфейсе
 * [KbEmbedder] — без сети и без LLM.
 */
class KbRagServiceTest {

    @TempDir
    lateinit var tempDir: Path

    /** Фейк эмбеддера: детерминированные векторы из карты, счётчик вызовов, режим сбоя. */
    private class FakeEmbedder : KbEmbedder {
        val calls = mutableListOf<String>()
        val vectors = mutableMapOf<String, FloatArray>()
        var fail = false

        override fun embedAll(texts: List<String>, model: String): List<FloatArray> {
            if (fail) throw IllegalStateException("эмбеддер недоступен")
            return texts.map { text ->
                calls.add(text)
                vectors[text] ?: floatArrayOf(0f, 0f, 0f, 1f)
            }
        }

        /** Лог-обёртка: как в KbEmbeddingClient — запрос ДО вызова, ответ с маркером вектора. */
        override fun embedAll(
            texts: List<String>,
            model: String,
            onLlmLog: ((String, String) -> Unit)?,
        ): List<FloatArray> {
            onLlmLog?.invoke(LlmCallLog.KIND_EMBEDDING_REQUEST, """{"model":"$model"}""")
            val result = embedAll(texts, model)
            onLlmLog?.invoke(LlmCallLog.KIND_EMBEDDING_RESPONSE, """{"embedding":"[vector dim=${result.firstOrNull()?.size}]"}""")
            return result
        }
    }

    private fun newRepo(dbFile: String): KbRepository =
        KbRepository(SqliteTestSupport.jdbc(tempDir.resolve(dbFile)))

    private fun row(kbId: Long, content: String, vector: FloatArray, section: String = "(весь документ)") =
        KbChunkRow(
            kbId = kbId,
            kbName = "Ноутбуки",
            source = "notes.md",
            title = "notes",
            section = section,
            strategy = "fixed",
            content = content,
            embedding = vector,
            model = "qwen3-vl-embedding-8b",
        )

    /** Вектор с заданной первой компонентой (косинус с q=[1,0,0,0] растёт по ней). */
    private fun vec(first: Float): FloatArray = floatArrayOf(first, 1f - first, 0.1f, 0.05f)

    @Test
    fun `no active bases returns null without embedding call`() {
        val repo = newRepo("rag-empty.db")
        val fake = FakeEmbedder()
        val service = KbRagService(repo, fake, "qwen3-vl-embedding-8b")
        assertNull(service.buildContextBlock("вопрос"))
        assertTrue(fake.calls.isEmpty(), "без активных баз эмбеддер не вызывается вовсе")
    }

    @Test
    fun `block format with kb name source section and top4 selection`() {
        val repo = newRepo("rag-top4.db")
        val fake = FakeEmbedder()
        fake.vectors["вопрос"] = floatArrayOf(1f, 0f, 0f, 0f)
        // Шесть чанков с убывающей релевантностью (первая компонента)
        val contents = (1..6).map { i -> "чанк$i" }
        contents.forEachIndexed { i, content ->
            fake.vectors[content] = vec(0.95f - i * 0.15f)
        }
        val kbId = repo.create("Ноутбуки", "fixed", null, null, "qwen3-vl-embedding-8b")!!.id
        repo.addChunks(kbId, contents.mapIndexed { i, content ->
            row(kbId, content, fake.vectors[content]!!, section = "сек${i + 1}")
        })
        repo.setStatusIndexed(kbId)
        repo.setActive(kbId, true)

        val block = KbRagService(repo, fake, "qwen3-vl-embedding-8b").buildContextBlock("вопрос")!!
        assertTrue(block.startsWith("### База знаний"))
        assertTrue(block.contains("[КБ Ноутбуки | notes.md#сек1]"))
        assertTrue(block.contains("чанк1"))
        // top-4: чанки 5 и 6 не попали в блок
        assertFalse(block.contains("чанк5"))
        assertFalse(block.contains("чанк6"))
        // Ровно один вызов эмбеддера — запрос пользователя
        assertEquals(1, fake.calls.size)
        assertEquals("вопрос", fake.calls.first())
    }

    @Test
    fun `block limited to 6000 chars cuts extra chunks`() {
        val repo = newRepo("rag-limit.db")
        val fake = FakeEmbedder()
        fake.vectors["вопрос"] = floatArrayOf(1f, 0f, 0f, 0f)
        val big1 = "А".repeat(3000)
        val big2 = "Б".repeat(3000)
        fake.vectors[big1] = vec(0.9f)
        fake.vectors[big2] = vec(0.8f)
        val kbId = repo.create("Ноутбуки", "fixed", null, null, "qwen3-vl-embedding-8b")!!.id
        repo.addChunks(kbId, listOf(
            row(kbId, big1, fake.vectors[big1]!!),
            row(kbId, big2, fake.vectors[big2]!!),
        ))
        repo.setStatusIndexed(kbId)
        repo.setActive(kbId, true)

        val block = KbRagService(repo, fake, "qwen3-vl-embedding-8b").buildContextBlock("вопрос")!!
        // Заголовок (15) + один вход (~3041) влезает; два входа (~6098) — уже за лимитом
        assertTrue(block.contains(big1))
        assertFalse(block.contains(big2), "второй чанк не помещается в лимит 6000")
        assertTrue(block.length < 6100)
    }

    @Test
    fun `inactive or not indexed bases are ignored`() {
        val repo = newRepo("rag-inactive.db")
        val fake = FakeEmbedder()
        val service = KbRagService(repo, fake, "qwen3-vl-embedding-8b")
        // indexed, но НЕ активная
        val kb = repo.create("База", "fixed", null, null, "qwen3-vl-embedding-8b")!!
        repo.setStatusIndexed(kb.id)
        assertNull(service.buildContextBlock("вопрос"))
        assertTrue(fake.calls.isEmpty())
        // активная, но ещё индексируется (не indexed)
        val kb2 = repo.create("База2", "fixed", null, null, "qwen3-vl-embedding-8b")!!
        repo.setActive(kb2.id, true)
        assertNull(service.buildContextBlock("вопрос"))
        assertTrue(fake.calls.isEmpty())
    }

    @Test
    fun `embedder failure returns null`() {
        val repo = newRepo("rag-fail.db")
        val fake = FakeEmbedder()
        fake.fail = true
        val kbId = repo.create("База", "fixed", null, null, "qwen3-vl-embedding-8b")!!.id
        repo.setStatusIndexed(kbId)
        repo.setActive(kbId, true)
        assertNull(KbRagService(repo, fake, "qwen3-vl-embedding-8b").buildContextBlock("вопрос"))
    }

    @Test
    fun `cosine identical orthogonal zero and size mismatch`() {
        val a = floatArrayOf(1f, 2f, 3f)
        assertEquals(1.0, KbRagService.cosine(a, a), 1e-9)
        assertEquals(0.0, KbRagService.cosine(floatArrayOf(1f, 0f), floatArrayOf(0f, 1f)), 1e-9)
        assertEquals(0.0, KbRagService.cosine(floatArrayOf(0f, 0f), floatArrayOf(1f, 1f)), 1e-9)
        assertEquals(0.0, KbRagService.cosine(floatArrayOf(1f), floatArrayOf(1f, 1f)), 1e-9)
    }

    @Test
    fun `onLlmLog emits db request response and search result in order`() {
        val repo = newRepo("rag-logs.db")
        val fake = FakeEmbedder()
        fake.vectors["вопрос"] = floatArrayOf(1f, 0f, 0f, 0f)
        fake.vectors["чанк1"] = vec(0.9f)
        fake.vectors["чанк2"] = vec(0.8f)
        val kbId = repo.create("Ноутбуки", "fixed", null, null, "qwen3-vl-embedding-8b")!!.id
        repo.addChunks(kbId, listOf(
            row(kbId, "чанк1", fake.vectors["чанк1"]!!, section = "сек1"),
            row(kbId, "чанк2", fake.vectors["чанк2"]!!, section = "сек2"),
        ))
        repo.setStatusIndexed(kbId)
        repo.setActive(kbId, true)

        val logs = mutableListOf<Pair<String, String>>()
        val result = KbRagService(repo, fake, "qwen3-vl-embedding-8b")
            .buildContextResult("вопрос") { kind, detail -> logs.add(kind to detail) }!!
        assertNotNull(result.block)

        // Полный порядок RAG-вызова: БД(базы) → ответ БД → эмбеддинги → БД(чанки) → поиск.
        // Ответ chunksOfBases в лог НЕ уходит (векторы чанков не логируются).
        assertEquals(
            listOf(
                LlmCallLog.KIND_DB_REQUEST,
                LlmCallLog.KIND_DB_RESPONSE,
                LlmCallLog.KIND_EMBEDDING_REQUEST,
                LlmCallLog.KIND_EMBEDDING_RESPONSE,
                LlmCallLog.KIND_DB_REQUEST,
                LlmCallLog.KIND_SEARCH_RESULT,
            ),
            logs.map { it.first },
        )

        val dbRequest1 = logs[0].second
        assertTrue(dbRequest1.contains("\"listActiveIndexed\""), "первый запрос БД — listActiveIndexed")
        assertTrue(dbRequest1.contains("kb.active = 1"), "в query — фактический SQL метода")
        val dbResponse = logs[1].second
        assertTrue(dbResponse.contains("\"Ноутбуки\""), "ответ БД — массив активных баз")
        assertTrue(dbResponse.contains("\"embeddingModel\""))
        val dbRequest2 = logs[4].second
        assertTrue(dbRequest2.contains("\"chunksOfBases\""), "второй запрос БД — chunksOfBases")
        assertTrue(dbRequest2.contains("\"kbIds\""), "params — id запрашиваемых баз")
        assertTrue(dbRequest2.contains("c.kb_id IN"), "в query — фактический SQL запроса чанков")

        // «Ответ поискового движка» — pretty JSON: парсим и проверяем поля,
        // включая контент отобранных чанков (векторов в detail быть не должно).
        val searchTree = com.fasterxml.jackson.databind.ObjectMapper().readTree(logs[5].second)
        assertEquals(4, searchTree.get("topK").asInt())
        assertEquals(2, searchTree.get("candidateChunks").asInt())
        assertEquals(2, searchTree.get("usedChunks").asInt())
        assertEquals("qwen3-vl-embedding-8b", searchTree.get("embeddingModel").asText())
        val chunksNode = searchTree.get("chunks")
        assertEquals(2, chunksNode.size())
        assertEquals("чанк1", chunksNode[0].get("content").asText(), "content чанка целиком")
        assertEquals("чанк2", chunksNode[1].get("content").asText(), "content чанка целиком")
        assertEquals("Ноутбуки", chunksNode[0].get("kbName").asText())
        assertEquals("notes.md", chunksNode[0].get("source").asText(), "source — имя файла")
        assertEquals("сек1", chunksNode[0].get("section").asText())
        assertTrue(chunksNode[0].get("score").isNumber, "score — число")
        assertTrue(chunksNode[0].get("contentChars").asInt() == "чанк1".length)
        assertFalse(chunksNode[0].has("embedding"), "вектор чанка в detail не попадает")
    }

    @Test
    fun `no active bases logs db response with empty array before null`() {
        val repo = newRepo("rag-logs-empty.db")
        val fake = FakeEmbedder()
        val logs = mutableListOf<Pair<String, String>>()
        val result = KbRagService(repo, fake, "qwen3-vl-embedding-8b")
            .buildContextResult("вопрос") { kind, detail -> logs.add(kind to detail) }
        assertNull(result, "без активных баз — null (без сетевых вызовов)")
        assertEquals(
            listOf(LlmCallLog.KIND_DB_REQUEST, LlmCallLog.KIND_DB_RESPONSE),
            logs.map { it.first },
        )
        // Печатный Jackson-принтер пустой массив печатает как «[ ]».
        assertEquals("[ ]", logs[1].second.trim(), "пустой ответ БД логируется пустым массивом")
        assertTrue(fake.calls.isEmpty())
    }
}
