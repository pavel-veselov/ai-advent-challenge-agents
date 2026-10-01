package com.example.llmagent.kb

import com.example.llmagent.agent.LlmCallLog
import com.example.llmagent.agent.SqliteTestSupport
import com.example.llmagent.config.AppSettingsStore
import com.fasterxml.jackson.databind.ObjectMapper
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
 * [KbEmbedder] — без сети и без LLM. Настройки воронки (Day-23) — реальный
 * [KbRagSettingsService] над in-memory стором: перед каждым сценарием сохраняем
 * нужные kb.*-значения, проверяем воронку sort → candidateK → порог → topK.
 */
class KbRagServiceTest {

    private companion object {
        const val MODEL = "qwen3-vl-embedding-8b"
        val JSON = ObjectMapper()
    }

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

    /** In-memory стор: как в KbRagSettingsServiceTest — без сети и без БД. */
    private class InMemoryStore : AppSettingsStore {
        private val map = mutableMapOf<String, String>()
        override fun save(key: String, value: String) {
            map[key] = value
        }
        override fun get(key: String): String? = map[key]
        override fun all(): Map<String, String> = map.toMap()
    }

    /**
     * Реальный [KbRagSettingsService] над in-memory стором с сохранёнными kb.*-значениями
     * ДО сценария (порченые/сырые значения — сохраняем raw-парами, валидные — update).
     */
    private fun settingsService(vararg entries: Pair<String, String>): KbRagSettingsService {
        val store = InMemoryStore()
        entries.forEach { (key, value) -> store.save(key, value) }
        return KbRagSettingsService(store)
    }

    /** Сервис с дефолтными настройками (= поведение дня 22): фильтр/rewrite выключены, topK=4. */
    private fun newService(
        repo: KbRepository,
        fake: FakeEmbedder,
        settings: KbRagSettingsService = settingsService(),
    ): KbRagService = KbRagService(repo, fake, MODEL, settings)

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
            model = MODEL,
        )

    /** Вектор с заданной первой компонентой (косинус с q=[1,0,0,0] растёт по ней). */
    private fun vec(first: Float): FloatArray = floatArrayOf(first, 1f - first, 0.1f, 0.05f)

    /**
     * Активная проиндексированная база «Ноутбуки» с чанками чанк1..чанк[N] убывающей
     * релевантности: cosine(q, чанкi) = 0.99, 0.96, 0.87, 0.70, 0.47, 0.24, ...
     */
    private fun seedDecayingChunks(repo: KbRepository, fake: FakeEmbedder, count: Int) {
        fake.vectors["вопрос"] = floatArrayOf(1f, 0f, 0f, 0f)
        val contents = (1..count).map { i -> "чанк$i" }
        contents.forEachIndexed { i, content ->
            fake.vectors[content] = vec(0.95f - i * 0.15f)
        }
        val kbId = repo.create("Ноутбуки", "fixed", null, null, MODEL)!!.id
        repo.addChunks(kbId, contents.mapIndexed { i, content ->
            row(kbId, content, fake.vectors[content]!!, section = "сек${i + 1}")
        })
        repo.setStatusIndexed(kbId)
        repo.setActive(kbId, true)
    }

    // --- Поведение дня 22 (дефолтные настройки) ---

    @Test
    fun `no active bases returns null without embedding call`() {
        val repo = newRepo("rag-empty.db")
        val fake = FakeEmbedder()
        val service = newService(repo, fake)
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
        val kbId = repo.create("Ноутбуки", "fixed", null, null, MODEL)!!.id
        repo.addChunks(kbId, contents.mapIndexed { i, content ->
            row(kbId, content, fake.vectors[content]!!, section = "сек${i + 1}")
        })
        repo.setStatusIndexed(kbId)
        repo.setActive(kbId, true)

        val block = newService(repo, fake).buildContextBlock("вопрос")!!
        assertTrue(block.startsWith("### База знаний"))
        // День-24: метка [n] + Файл/Раздел (старый формат «[КБ … | …#…]» заменён)
        assertTrue(block.contains("[1] Файл: notes.md — Раздел: сек1"))
        assertTrue(block.contains("[2] Файл: notes.md — Раздел: сек2"))
        // Правило цитирования в заголовке блока
        assertTrue(block.contains("Отвечай ТОЛЬКО на основе фрагментов ниже"))
        assertTrue(block.contains("«Не знаю»"))
        assertTrue(block.contains("чанк1"))
        // top-4 (дефолт настроек): чанки 5 и 6 не попали в блок
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
        val kbId = repo.create("Ноутбуки", "fixed", null, null, MODEL)!!.id
        repo.addChunks(kbId, listOf(
            row(kbId, big1, fake.vectors[big1]!!),
            row(kbId, big2, fake.vectors[big2]!!),
        ))
        repo.setStatusIndexed(kbId)
        repo.setActive(kbId, true)

        val block = newService(repo, fake).buildContextBlock("вопрос")!!
        // Заголовок с правилом цитирования (~257) + один вход (~3045) влезает;
        // два входа (~6350) — уже за лимитом 6000
        assertTrue(block.contains(big1))
        assertFalse(block.contains(big2), "второй чанк не помещается в лимит 6000")
        assertTrue(block.length < 6100)
    }

    @Test
    fun `inactive or not indexed bases are ignored`() {
        val repo = newRepo("rag-inactive.db")
        val fake = FakeEmbedder()
        val service = newService(repo, fake)
        // indexed, но НЕ активная
        val kb = repo.create("База", "fixed", null, null, MODEL)!!
        repo.setStatusIndexed(kb.id)
        assertNull(service.buildContextBlock("вопрос"))
        assertTrue(fake.calls.isEmpty())
        // активная, но ещё индексируется (не indexed)
        val kb2 = repo.create("База2", "fixed", null, null, MODEL)!!
        repo.setActive(kb2.id, true)
        assertNull(service.buildContextBlock("вопрос"))
        assertTrue(fake.calls.isEmpty())
    }

    @Test
    fun `embedder failure returns null`() {
        val repo = newRepo("rag-fail.db")
        val fake = FakeEmbedder()
        fake.fail = true
        val kbId = repo.create("База", "fixed", null, null, MODEL)!!.id
        repo.setStatusIndexed(kbId)
        repo.setActive(kbId, true)
        assertNull(newService(repo, fake).buildContextBlock("вопрос"))
    }

    @Test
    fun `cosine identical orthogonal zero and size mismatch`() {
        val a = floatArrayOf(1f, 2f, 3f)
        assertEquals(1.0, KbRagService.cosine(a, a), 1e-9)
        assertEquals(0.0, KbRagService.cosine(floatArrayOf(1f, 0f), floatArrayOf(0f, 1f)), 1e-9)
        assertEquals(0.0, KbRagService.cosine(floatArrayOf(0f, 0f), floatArrayOf(1f, 1f)), 1e-9)
        assertEquals(0.0, KbRagService.cosine(floatArrayOf(1f), floatArrayOf(1f, 1f)), 1e-9)
    }

    // --- Воронка Day-23: candidateK → порог minScore → topK ---

    @Test
    fun `filter threshold cuts low score chunks when enabled`() {
        val repo = newRepo("rag-threshold.db")
        val fake = FakeEmbedder()
        seedDecayingChunks(repo, fake, 6)
        // cosine: чанк1 0.99, чанк2 0.96, чанк3 0.87, чанк4 0.70, чанк5 0.47, чанк6 0.24
        val settings = settingsService()
        settings.update(KbRagSettings(filterEnabled = true, minScore = 0.8, candidateK = 8, topK = 4))
        val logs = mutableListOf<Pair<String, String>>()
        val result = newService(repo, fake, settings)
            .buildContextResult("вопрос") { kind, detail -> logs.add(kind to detail) }!!

        assertTrue(result.block!!.startsWith("### База знаний"))
        assertTrue(result.block!!.contains("чанк1"))
        assertTrue(result.block!!.contains("чанк3"))
        assertFalse(result.block!!.contains("чанк4"), "score 0.70 ниже порога 0.8 — отсечён")
        assertFalse(result.block!!.contains("чанк5"))
        assertFalse(result.block!!.contains("чанк6"))
        assertEquals(3, result.usedChunks)
        assertEquals(6, result.candidateChunks)
        assertTrue(result.chunks.all { it.score >= 0.8 }, "в воронку прошли только чанки с score >= minScore")

        // Воронка в логе: scored=6 → candidates=6 (candidateK=8 не режет) → passedFilter=3.
        val searchTree = JSON.readTree(logs.last().second)
        assertTrue(searchTree.get("filterEnabled").asBoolean())
        assertEquals(0.8, searchTree.get("minScore").asDouble(), 1e-9)
        assertEquals(6, searchTree.get("scored").asInt())
        assertEquals(6, searchTree.get("candidates").asInt())
        assertEquals(3, searchTree.get("passedFilter").asInt())
        assertEquals(3, searchTree.get("usedChunks").asInt())
    }

    @Test
    fun `candidateK caps pool before threshold and topK clamps to it`() {
        val repo = newRepo("rag-candidatek.db")
        val fake = FakeEmbedder()
        seedDecayingChunks(repo, fake, 6)
        // Сырые значения в обход update(): topK=4 > candidateK=2 (load не делает
        // кросс-проверку) — воронка сама ограничивает итог размером пула кандидатов.
        val settings = settingsService(
            "kb.filterEnabled" to "true",
            "kb.candidateK" to "2",
            "kb.topK" to "4",
            "kb.minScore" to "0.1", // порог почти ничего не срезает — срезает candidateK
        )
        val logs = mutableListOf<Pair<String, String>>()
        val result = newService(repo, fake, settings)
            .buildContextResult("вопрос") { kind, detail -> logs.add(kind to detail) }!!

        assertTrue(result.block!!.contains("чанк1"))
        assertTrue(result.block!!.contains("чанк2"))
        assertFalse(result.block!!.contains("чанк3"), "candidateK=2 обрезает пул ДО порога")
        assertEquals(2, result.usedChunks)
        assertEquals(4, result.topK, "topK взят из настроек, но пул короче — clamp")

        val searchTree = JSON.readTree(logs.last().second)
        assertEquals(2, searchTree.get("candidateK").asInt())
        assertEquals(4, searchTree.get("topK").asInt())
        assertEquals(6, searchTree.get("scored").asInt(), "оценены все чанки")
        assertEquals(2, searchTree.get("candidates").asInt(), "после candidateK осталось 2")
        assertEquals(2, searchTree.get("passedFilter").asInt(), "порог 0.1 ничего не срезал — срезал candidateK")
        assertEquals(2, searchTree.get("usedChunks").asInt())
    }

    @Test
    fun `topK limits final chunks below candidateK`() {
        val repo = newRepo("rag-topk-settings.db")
        val fake = FakeEmbedder()
        seedDecayingChunks(repo, fake, 6)
        val settings = settingsService()
        settings.update(KbRagSettings(filterEnabled = true, minScore = 0.0, candidateK = 6, topK = 2))
        val logs = mutableListOf<Pair<String, String>>()
        val result = newService(repo, fake, settings)
            .buildContextResult("вопрос") { kind, detail -> logs.add(kind to detail) }!!

        assertTrue(result.block!!.contains("чанк1"))
        assertTrue(result.block!!.contains("чанк2"))
        assertFalse(result.block!!.contains("чанк3"), "topK=2 — в блоке только два лучших")
        assertEquals(2, result.usedChunks)
        assertEquals(2, result.topK)

        val searchTree = JSON.readTree(logs.last().second)
        assertEquals(2, searchTree.get("topK").asInt())
        assertEquals(6, searchTree.get("passedFilter").asInt(), "порог 0.0 пропустил всех — срезал topK")
        assertEquals(6, searchTree.get("candidates").asInt())
        assertEquals(2, searchTree.get("usedChunks").asInt())
    }

    @Test
    fun `filter off skips threshold and keeps day22 top4`() {
        val repo = newRepo("rag-filter-off.db")
        val fake = FakeEmbedder()
        seedDecayingChunks(repo, fake, 6)
        // Порог 0.99 отсёк бы ВСЕ чанки — но фильтр выключен: поведение дня 22 (top4).
        val settings = settingsService(
            "kb.filterEnabled" to "false",
            "kb.minScore" to "0.99",
        )
        val logs = mutableListOf<Pair<String, String>>()
        val result = newService(repo, fake, settings)
            .buildContextResult("вопрос") { kind, detail -> logs.add(kind to detail) }!!

        assertTrue(result.block!!.contains("чанк4"), "порог не применяется при выключенном фильтре")
        assertEquals(4, result.usedChunks, "дефолт topK=4, как в дне 22")
        assertFalse(result.block!!.contains("чанк5"))

        val searchTree = JSON.readTree(logs.last().second)
        assertFalse(searchTree.get("filterEnabled").asBoolean())
        assertEquals(0.99, searchTree.get("minScore").asDouble(), 1e-9, "minScore залогирован, но НЕ применён")
        assertEquals(6, searchTree.get("passedFilter").asInt(), "все кандидаты прошли: порог пропущен")
        assertEquals(4, searchTree.get("usedChunks").asInt())
    }

    @Test
    fun `passedFilter zero returns null block without kb section`() {
        val repo = newRepo("rag-passed0.db")
        val fake = FakeEmbedder()
        seedDecayingChunks(repo, fake, 3)
        // Максимум (0.99) ниже порога 0.995 — после фильтра пусто → блок null.
        val settings = settingsService()
        settings.update(KbRagSettings(filterEnabled = true, minScore = 0.995, candidateK = 8, topK = 4))
        val logs = mutableListOf<Pair<String, String>>()
        val service = newService(repo, fake, settings)

        val result = service.buildContextResult("вопрос") { kind, detail -> logs.add(kind to detail) }
        assertNotNull(result, "базы есть — это НЕ «нет баз», а «всё отсечено фильтром»")
        assertNull(result!!.block, "passedFilter=0 — KB-блок не собирается")
        assertNull(result.error, "это не сбой, а штатное отсечение")
        assertEquals(0, result.usedChunks)
        assertTrue(result.chunks.isEmpty())
        assertNull(service.buildContextBlock("вопрос"), "и через buildContextBlock — null")

        val searchTree = JSON.readTree(logs.last().second)
        assertEquals(0, searchTree.get("passedFilter").asInt())
        assertEquals(0, searchTree.get("usedChunks").asInt())
        assertEquals(3, searchTree.get("scored").asInt())
        assertEquals(0, searchTree.get("chunks").size(), "в логе пустой список отобранных")
        assertTrue(searchTree.get("filterEnabled").asBoolean())
        assertEquals(0.995, searchTree.get("minScore").asDouble(), 1e-9)
    }

    // --- Лог «Ответ поискового движка»: расширенный JSON + query rewrite pre-wire ---

    @Test
    fun `rewritten query is used for retrieval and logged in search result`() {
        val repo = newRepo("rag-rewrite.db")
        val fake = FakeEmbedder()
        seedDecayingChunks(repo, fake, 2)
        val logs = mutableListOf<Pair<String, String>>()
        val result = newService(repo, fake)
            .buildContextResult(
                userMessage = "вопрос",
                rewrittenQuery = "пылесос LEGEE купить",
                rewriteUsed = true,
            ) { kind, detail -> logs.add(kind to detail) }!!

        assertEquals("пылесос LEGEE купить", fake.calls.first(), "эмбеддится переписанный запрос")
        assertTrue(result.block!!.contains("чанк1"))

        val searchTree = JSON.readTree(logs.last().second)
        assertEquals("пылесос LEGEE купить", searchTree.get("rewrittenQuery").asText())
        assertTrue(searchTree.get("rewriteUsed").asBoolean())
        // rewriteUsed=true, но rewrittenQuery=null/blank → фолбэк на исходный запрос
    }

    @Test
    fun `rewrite not used falls back to original user message`() {
        val repo = newRepo("rag-rewrite-fallback.db")
        val fake = FakeEmbedder()
        seedDecayingChunks(repo, fake, 2)
        val logs = mutableListOf<Pair<String, String>>()
        // rewriteUsed=false, но переписанный текст передан (fallback после ошибки) —
        // должен использоваться исходный запрос, а в логе rewriteUsed=false.
        val result = newService(repo, fake)
            .buildContextResult(
                userMessage = "вопрос",
                rewrittenQuery = "неудавшийся rewrite",
                rewriteUsed = false,
            ) { kind, detail -> logs.add(kind to detail) }!!

        assertEquals("вопрос", fake.calls.first(), "фолбэк: эмбеддится исходный запрос")
        assertTrue(result.block!!.contains("чанк1"))

        val searchTree = JSON.readTree(logs.last().second)
        assertEquals("неудавшийся rewrite", searchTree.get("rewrittenQuery").asText(), "в логе видно, что rewrite пытались сделать")
        assertFalse(searchTree.get("rewriteUsed").asBoolean())
    }

    @Test
    fun `onLlmLog emits db request response and search result in order`() {
        val repo = newRepo("rag-logs.db")
        val fake = FakeEmbedder()
        fake.vectors["вопрос"] = floatArrayOf(1f, 0f, 0f, 0f)
        fake.vectors["чанк1"] = vec(0.9f)
        fake.vectors["чанк2"] = vec(0.8f)
        val kbId = repo.create("Ноутбуки", "fixed", null, null, MODEL)!!.id
        repo.addChunks(kbId, listOf(
            row(kbId, "чанк1", fake.vectors["чанк1"]!!, section = "сек1"),
            row(kbId, "чанк2", fake.vectors["чанк2"]!!, section = "сек2"),
        ))
        repo.setStatusIndexed(kbId)
        repo.setActive(kbId, true)

        val logs = mutableListOf<Pair<String, String>>()
        val result = newService(repo, fake)
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
        val searchTree = JSON.readTree(logs[5].second)
        assertEquals(4, searchTree.get("topK").asInt())
        assertEquals(2, searchTree.get("candidateChunks").asInt())
        assertEquals(2, searchTree.get("usedChunks").asInt())
        assertEquals(MODEL, searchTree.get("embeddingModel").asText())
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

        // Day-23: расширенные поля воронки + rewrite (дефолты, rewrite не вызывался).
        assertFalse(searchTree.get("filterEnabled").asBoolean(), "дефолт: фильтр выключен")
        assertEquals(0.35, searchTree.get("minScore").asDouble(), 1e-9)
        assertEquals(8, searchTree.get("candidateK").asInt())
        assertEquals(2, searchTree.get("scored").asInt())
        assertEquals(2, searchTree.get("candidates").asInt())
        assertEquals(2, searchTree.get("passedFilter").asInt())
        assertTrue(searchTree.get("rewrittenQuery").isNull(), "rewrite не вызывался — null в логе")
        assertFalse(searchTree.get("rewriteUsed").asBoolean())
    }

    // --- День-24: метки [n], label/chunkId в логе, порядок источников блока ---

    @Test
    fun `hits carry label chunkId and content in block order`() {
        val repo = newRepo("rag-labels.db")
        val fake = FakeEmbedder()
        seedDecayingChunks(repo, fake, 3)
        val logs = mutableListOf<Pair<String, String>>()
        val result = newService(repo, fake)
            .buildContextResult("вопрос") { kind, detail -> logs.add(kind to detail) }!!

        // Все 3 чанка влезли: label = позиция в блоке 1..usedChunks, chunkId — id строки БД
        assertEquals(3, result.usedChunks)
        assertEquals(listOf(1, 2, 3), result.chunks.map { it.label })
        assertEquals(listOf(1L, 2L, 3L), result.chunks.map { it.chunkId }, "chunkId — id kb_chunks из БД")
        assertEquals(listOf("чанк1", "чанк2", "чанк3"), result.chunks.map { it.content })
        assertTrue(result.chunks.all { it.contentChars == it.content.length })
        // Свойство, на которое опираются источники agent_finished: первые usedChunks
        // чанков — ровно те, что в блоке, с метками 1..used.
        assertEquals(
            (1..result.usedChunks).toList(),
            result.chunks.take(result.usedChunks).map { it.label },
        )

        // Лог «Ответ поискового движка»: chunks[] + label + chunkId + content
        val searchTree = JSON.readTree(logs.last().second)
        val chunksNode = searchTree.get("chunks")
        assertEquals(3, chunksNode.size())
        assertEquals(listOf(1, 2, 3), (0 until chunksNode.size()).map { chunksNode[it].get("label").asInt() })
        assertEquals(listOf(1L, 2L, 3L), (0 until chunksNode.size()).map { chunksNode[it].get("chunkId").asLong() })
        assertEquals("чанк1", chunksNode[0].get("content").asText())
        assertEquals("сек1", chunksNode[0].get("section").asText())
        assertEquals("notes.md", chunksNode[0].get("source").asText())
    }

    @Test
    fun `6000 char cap applies after labels and rule header`() {
        val repo = newRepo("rag-cap24.db")
        val fake = FakeEmbedder()
        seedDecayingChunks(repo, fake, 4)
        val result = newService(repo, fake).buildContextResult("вопрос")!!
        // Заголовок с правилом (~257) + 4 коротких чанка — все влезли, лимит не сработал
        assertEquals(4, result.usedChunks)
        assertTrue(result.block!!.length <= KbRagService.MAX_BLOCK_CHARS, "блок не превышает лимит")
        // Теперь большой чанк: метка [n] и правило в заголовке тоже учитываются в лимите
        val repo2 = newRepo("rag-cap24-big.db")
        val fake2 = FakeEmbedder()
        fake2.vectors["вопрос"] = floatArrayOf(1f, 0f, 0f, 0f)
        val big1 = "А".repeat(5900)
        fake2.vectors[big1] = vec(0.9f)
        val kbId = repo2.create("Ноутбуки", "fixed", null, null, MODEL)!!.id
        repo2.addChunks(kbId, listOf(row(kbId, big1, fake2.vectors[big1]!!)))
        repo2.setStatusIndexed(kbId)
        repo2.setActive(kbId, true)
        val result2 = newService(repo2, fake2).buildContextResult("вопрос")!!
        assertNull(result2.block, "заголовок с правилом + чанк 5900 > 6000 — чанк не влез")
        assertEquals(0, result2.usedChunks)
        assertEquals(1, result2.chunks.size, "чанк оценён, но в блок не вошёл")
    }

    @Test
    fun `no active bases logs db response with empty array before null`() {
        val repo = newRepo("rag-logs-empty.db")
        val fake = FakeEmbedder()
        val logs = mutableListOf<Pair<String, String>>()
        val result = newService(repo, fake)
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
