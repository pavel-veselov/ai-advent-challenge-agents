package com.example.llmagent.agent

import com.example.llmagent.agent.tools.CalculatorTool
import com.example.llmagent.agent.tools.GetCurrentDateTimeTool
import com.example.llmagent.config.AgentProperties
import com.example.llmagent.config.AppSettingsStore
import com.example.llmagent.config.JdbcSessionLlmSettingsStore
import com.example.llmagent.config.LlmProperties
import com.example.llmagent.config.LlmSettings
import com.example.llmagent.config.SessionLlmSettingsProvider
import com.example.llmagent.kb.KbChunkRow
import com.example.llmagent.kb.KbEmbedder
import com.example.llmagent.kb.KbRagService
import com.example.llmagent.kb.KbRagSettingsService
import com.example.llmagent.kb.KbRepository
import com.fasterxml.jackson.databind.ObjectMapper
import java.nio.file.Path
import java.time.Duration
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import reactor.core.publisher.Flux

/**
 * LLM для query rewrite (Day-23): вызов перезаписи различается по последнему сообщению
 * промпта (маркер [AgentImpl.REWRITE_REQUEST_PROMPT]) и возвращает [rewriteText] дословно
 * (или [rewriteError] при сбое); вызовы основного цикла отвечают фиксированным текстом.
 */
private class RewriteLlm(
    private val rewriteText: String? = null,
    private val rewriteError: Throwable? = null,
) : LlmClient {
    val rewriteCalls = AtomicInteger(0)
    val mainCalls = AtomicInteger(0)

    override fun streamChat(
        messages: List<LlmMessage>,
        tools: List<ToolDefinition>,
        settings: LlmSettings,
    ): Flux<LlmEvent> = if (messages.lastOrNull()?.content == AgentImpl.REWRITE_REQUEST_PROMPT) {
        rewriteCalls.incrementAndGet()
        rewriteError?.let { return@streamChat Flux.error(it) }
        Flux.just(LlmEvent.ContentDelta(rewriteText ?: ""), LlmEvent.Finished("stop"))
    } else {
        mainCalls.incrementAndGet()
        Flux.just(LlmEvent.ContentDelta("ответ агента"), LlmEvent.Finished("stop"))
    }
}

/** Фейк эмбеддера: детерминированные векторы из карты, счётчик вызовов (как в KbRagServiceTest). */
private class FakeEmbedder : KbEmbedder {
    val calls = mutableListOf<String>()
    val vectors = mutableMapOf<String, FloatArray>()

    /** Не-null → embedAll бросает исключение (симуляция сбоя ретривала, fail-open). */
    var throwOnCall: RuntimeException? = null

    override fun embedAll(texts: List<String>, model: String): List<FloatArray> {
        throwOnCall?.let { throw it }
        return texts.map { text ->
            calls.add(text)
            vectors[text] ?: floatArrayOf(0f, 0f, 0f, 1f)
        }
    }

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

/** In-memory стор настроек (как в KbRagServiceTest) — без сети и без БД. */
private class InMemoryStore : AppSettingsStore {
    private val map = mutableMapOf<String, String>()
    override fun save(key: String, value: String) {
        map[key] = value
    }
    override fun get(key: String): String? = map[key]
    override fun all(): Map<String, String> = map.toMap()
}

/**
 * Юнит-тесты query rewrite в агенте (Day-23): перезапись вопроса LLM перед RAG-поиском,
 * санитизация ответа, фолбэк на исходный запрос при любом сбое, гейты
 * (rewrite выключен / нет активных баз — ни одного лишнего LLM-вызова).
 * Плюс «пустой поиск» (Day-24, обновлено): при релевантности ниже порога LLM получает
 * system-заметку [KbRagService.EMPTY_SEARCH_NOTE] и отвечает сам — без отказа без LLM.
 */
class AgentImplTest {

    private companion object {
        const val MODEL = "qwen3-vl-embedding-8b"
        val JSON = ObjectMapper()
    }

    @TempDir
    lateinit var tmpDir: Path

    private val om = ObjectMapper()

    private val tools = ToolRegistry(listOf(CalculatorTool(), GetCurrentDateTimeTool()))

    /** Вектор с заданной первой компонентой (косинус с q=[1,0,0,0] растёт по ней). */
    private fun vec(first: Float): FloatArray = floatArrayOf(first, 1f - first, 0.1f, 0.05f)

    private fun newRepo(dbHint: String): KbRepository =
        KbRepository(SqliteTestSupport.jdbc(tmpDir.resolve("$dbHint-${UUID.randomUUID()}.db")))

    /** Настройки RAG с заданными kb.*-значениями (raw-парами, как в KbRagServiceTest). */
    private fun settingsService(vararg entries: Pair<String, String>): KbRagSettingsService {
        val store = InMemoryStore()
        entries.forEach { (key, value) -> store.save(key, value) }
        return KbRagSettingsService(store)
    }

    /** Активная проиндексированная база с двумя чанками убывающей релевантности. */
    private fun seedKb(repo: KbRepository, fake: FakeEmbedder) {
        val kbId = repo.create("Ноутбуки", "fixed", null, null, MODEL)!!.id
        fake.vectors["чанк1"] = vec(0.9f)
        fake.vectors["чанк2"] = vec(0.7f)
        repo.addChunks(
            kbId,
            listOf(
                KbChunkRow(
                    kbId = kbId, kbName = "Ноутбуки", source = "notes.md", title = "notes",
                    section = "сек1", strategy = "fixed", content = "чанк1",
                    embedding = fake.vectors["чанк1"]!!, model = MODEL,
                ),
                KbChunkRow(
                    kbId = kbId, kbName = "Ноутбуки", source = "notes.md", title = "notes",
                    section = "сек2", strategy = "fixed", content = "чанк2",
                    embedding = fake.vectors["чанк2"]!!, model = MODEL,
                ),
            ),
        )
        repo.setStatusIndexed(kbId)
        repo.setActive(kbId, true)
    }

    private fun agent(llm: LlmClient, kb: KbRagService, kbSettings: KbRagSettingsService): AgentImpl {
        val llmProps = LlmProperties()
        val agentProps = AgentProperties(8)
        val sessionLlmSettings = SessionLlmSettingsProvider(
            JdbcSessionLlmSettingsStore(SqliteTestSupport.jdbc(tmpDir.resolve("rw-llm-${UUID.randomUUID()}.db"))),
            LlmSettings.from(llmProps),
        )
        return AgentImpl(
            llm, tools, SqliteTestSupport.store(tmpDir.resolve("rw-ses-${UUID.randomUUID()}.db")),
            agentProps, LlmSettings.from(llmProps), sessionLlmSettings,
            SessionCompressionStore(SqliteTestSupport.jdbc(tmpDir.resolve("rw-cmp-${UUID.randomUUID()}.db"))),
            om,
            kbRagService = kb,
            kbRagSettingsService = kbSettings,
        )
    }

    private fun run(a: AgentImpl, sessionId: String, message: String): List<AgentEvent> =
        a.run(sessionId, message).collectList().block(Duration.ofSeconds(10))!!

    /** Агент с RAG: ОДИН сервис настроек делится между KbRagService и AgentImpl. */
    private fun rewriteAgent(llm: LlmClient, repo: KbRepository, fake: FakeEmbedder, vararg settings: Pair<String, String>): AgentImpl {
        val kbSettings = settingsService(*settings)
        return agent(llm, KbRagService(repo, fake, MODEL, kbSettings), kbSettings)
    }

    /** «Ответ поискового движка» из панели логов (или null, если поиска не было). */
    private fun searchLog(events: List<AgentEvent>): com.fasterxml.jackson.databind.JsonNode? =
        events.filterIsInstance<LogEvent>()
            .lastOrNull { it.text == LlmCallLog.KIND_SEARCH_RESULT }
            ?.let { JSON.readTree(it.detail) }

    @Test
    fun `rewrite enabled rewrites query and logs rewrite used`() {
        val repo = newRepo("rw-happy")
        val fake = FakeEmbedder()
        seedKb(repo, fake)
        fake.vectors["переписанный запрос"] = floatArrayOf(1f, 0f, 0f, 0f)
        // LLM отвечает В КАВЫЧКАХ — санитизация должна снять одну пару («…»).
        val llm = RewriteLlm(rewriteText = "«переписанный запрос»")

        val events = run(rewriteAgent(llm, repo, fake, "kb.rewriteEnabled" to "true"), "s1", "вопрос")

        // вызов перезаписи был ровно один, основной цикл — отдельно
        assertEquals(1, llm.rewriteCalls.get())
        assertEquals(1, llm.mainCalls.get())
        // эмбеддится ПЕРЕПИСАННЫЙ (очищенный от кавычек) запрос — и только он
        assertEquals(listOf("переписанный запрос"), fake.calls)

        // в логе поиска: rewrittenQuery + rewriteUsed=true
        val search = searchLog(events)
        assertTrue(search != null, "лог «Ответ поискового движка» должен быть")
        assertEquals("переписанный запрос", search!!.get("rewrittenQuery").asText())
        assertTrue(search.get("rewriteUsed").asBoolean())
        // KB-блок реально попал в контекст (чанк1 — ближайший к переписанному запросу)
        val prompt = events.filterIsInstance<LlmRequestStarted>().single().prompt
        assertTrue(prompt.any { it["content"]?.startsWith("### База знаний") == true })
        assertTrue(events.any { it is AgentFinished })
    }

    @Test
    fun `rewrite llm failure falls back to original query and run continues`() {
        val repo = newRepo("rw-fail")
        val fake = FakeEmbedder()
        seedKb(repo, fake)
        fake.vectors["вопрос"] = floatArrayOf(1f, 0f, 0f, 0f)
        val llm = RewriteLlm(rewriteError = LlmApiException(500, "boom"))

        val events = run(rewriteAgent(llm, repo, fake, "kb.rewriteEnabled" to "true"), "s1", "вопрос")

        assertEquals(1, llm.rewriteCalls.get())
        assertEquals(1, llm.mainCalls.get(), "сбой rewrite не должен ломать основной цикл")
        // фолбэк: эмбеддится ИСХОДНЫЙ запрос
        assertEquals(listOf("вопрос"), fake.calls)

        val search = searchLog(events)
        assertTrue(search != null)
        assertTrue(search!!.get("rewrittenQuery").isNull(), "при сбое rewrite в логе null")
        assertFalse(search.get("rewriteUsed").asBoolean())
        assertTrue(events.any { it is AgentFinished }, "run должен завершиться штатно")
        assertTrue(events.none { it is ErrorEvent }, "сбой rewrite НЕ должен показывать ошибку пользователю")
    }

    @Test
    fun `rewrite disabled makes no extra llm call`() {
        val repo = newRepo("rw-off")
        val fake = FakeEmbedder()
        seedKb(repo, fake)
        fake.vectors["вопрос"] = floatArrayOf(1f, 0f, 0f, 0f)
        // rewriteEnabled не задан — дефолт false (поведение дня 22)
        val llm = RewriteLlm(rewriteText = "переписанный запрос")

        val events = run(rewriteAgent(llm, repo, fake), "s1", "вопрос")

        assertEquals(0, llm.rewriteCalls.get(), "rewrite выключен — ни одного вызова перезаписи")
        assertEquals(1, llm.mainCalls.get())
        assertEquals(listOf("вопрос"), fake.calls, "эмбеддится исходный запрос")

        val search = searchLog(events)
        assertTrue(search != null)
        assertTrue(search!!.get("rewrittenQuery").isNull())
        assertFalse(search.get("rewriteUsed").asBoolean())
        assertTrue(events.any { it is AgentFinished })
    }

    @Test
    fun `rewrite enabled but no active kb bases skips rewrite call`() {
        val repo = newRepo("rw-empty") // баз нет вовсе
        val fake = FakeEmbedder()
        val llm = RewriteLlm(rewriteText = "переписанный запрос")

        val events = run(rewriteAgent(llm, repo, fake, "kb.rewriteEnabled" to "true"), "s1", "вопрос")

        assertEquals(0, llm.rewriteCalls.get(), "нет активных баз — rewrite не вызывается")
        assertEquals(1, llm.mainCalls.get())
        assertTrue(fake.calls.isEmpty(), "нет баз — эмбеддер не вызывается вовсе")
        assertTrue(searchLog(events) == null, "лог поиска не появляется без активных баз")
        assertTrue(events.any { it is AgentFinished })
    }

    @Test
    fun `rewrite blank response falls back to original query`() {
        val repo = newRepo("rw-blank")
        val fake = FakeEmbedder()
        seedKb(repo, fake)
        fake.vectors["вопрос"] = floatArrayOf(1f, 0f, 0f, 0f)
        // «мусорный» ответ: только пробелы — после очистки пусто → фолбэк
        val llm = RewriteLlm(rewriteText = "   ")

        val events = run(rewriteAgent(llm, repo, fake, "kb.rewriteEnabled" to "true"), "s1", "вопрос")

        assertEquals(1, llm.rewriteCalls.get())
        assertEquals(listOf("вопрос"), fake.calls, "пустой после очистки — эмбеддится исходный запрос")
        val search = searchLog(events)
        assertTrue(search != null)
        assertTrue(search!!.get("rewrittenQuery").isNull())
        assertFalse(search.get("rewriteUsed").asBoolean())
        assertTrue(events.any { it is AgentFinished })
    }

    @Test
    fun `rewrite long response truncated to 300 chars`() {
        val repo = newRepo("rw-long")
        val fake = FakeEmbedder()
        seedKb(repo, fake)
        val truncated = "А".repeat(300)
        fake.vectors[truncated] = floatArrayOf(1f, 0f, 0f, 0f)
        val llm = RewriteLlm(rewriteText = "А".repeat(400))

        val events = run(rewriteAgent(llm, repo, fake, "kb.rewriteEnabled" to "true"), "s1", "вопрос")

        assertEquals(1, llm.rewriteCalls.get())
        assertEquals(listOf(truncated), fake.calls, "переписанный запрос обрезан до 300 символов")
        val search = searchLog(events)
        assertTrue(search != null)
        assertEquals(truncated, search!!.get("rewrittenQuery").asText())
        assertTrue(search.get("rewriteUsed").asBoolean())
        assertTrue(events.any { it is AgentFinished })
    }

    // --- Пустой поиск KB (Day-24, обновлено): заметка LLM вместо отказа без LLM ---

    /** Активная база + порог, отсекающий ВСЕ чанки → usedChunks=0, блок не собран.
     *  cosine(q=[1,0,0,0], vec(0.9)) ≈ 0.986, поэтому порог 0.99 выше любого чанка. */
    private fun emptySearchSetup(): Triple<KbRepository, FakeEmbedder, RewriteLlm> {
        val repo = newRepo("kb-empty")
        val fake = FakeEmbedder()
        seedKb(repo, fake)
        fake.vectors["вопрос"] = floatArrayOf(1f, 0f, 0f, 0f)
        return Triple(repo, fake, RewriteLlm(rewriteText = "переписанный запрос"))
    }

    @Test
    fun `empty kb search informs llm via system note and llm answers`() {
        val (repo, fake, llm) = emptySearchSetup()

        val events = run(
            rewriteAgent(llm, repo, fake, "kb.filterEnabled" to "true", "kb.minScore" to "0.99"),
            "s1", "вопрос",
        )

        // LLM-цикл запускается как обычно — никакого отказа без LLM
        assertEquals(1, llm.mainCalls.get(), "пустой поиск — LLM вызывается и отвечает сам")
        // финальный текст — от LLM-мока, а не фиксированная константа отказа
        val finished = events.filterIsInstance<AgentFinished>().single()
        assertEquals("ответ агента", finished.finalText)
        // заметка о пустом поиске есть среди system-сообщений промпта
        val prompt = events.filterIsInstance<LlmRequestStarted>().single().prompt
        assertTrue(
            prompt.any { it["role"] == "system" && it["content"] == KbRagService.EMPTY_SEARCH_NOTE },
            "system-заметка о пустом поиске должна попасть в контекст",
        )
        // KB-блока нет — релевантных чанков не осталось
        assertFalse(prompt.any { it["content"]?.startsWith("### База знаний") == true })
        // источников нет: usedChunks==0 → kbSources=null → поля sources в payload нет
        assertNull(finished.sources)
        assertFalse(finished.payload.containsKey("sources"), "пустой поиск — sources в payload нет")
        // лог поискового движка дошёл до панели ровно один раз (использованных чанков 0)
        val search = searchLog(events)
        assertTrue(search != null, "лог «Ответ поискового движка» должен быть")
        assertEquals(0, search!!.get("usedChunks").asInt())
        assertEquals(
            1,
            events.filterIsInstance<LogEvent>().count { it.text == LlmCallLog.KIND_SEARCH_RESULT },
            "лог поиска уходит в панель ровно один раз (без дублей)",
        )
        assertTrue(events.none { it is ErrorEvent })
    }

    @Test
    fun `refusal disabled answers without empty search note`() {
        val (repo, fake, llm) = emptySearchSetup()

        val events = run(
            rewriteAgent(
                llm, repo, fake,
                "kb.filterEnabled" to "true", "kb.minScore" to "0.99", "kb.refusalEnabled" to "false",
            ),
            "s1", "вопрос",
        )

        assertEquals(1, llm.mainCalls.get(), "refusalEnabled=false — модель отвечает вслепую, как в дне 22")
        val prompt = events.filterIsInstance<LlmRequestStarted>().single().prompt
        assertFalse(
            prompt.any { it["role"] == "system" && it["content"] == KbRagService.EMPTY_SEARCH_NOTE },
            "refusalEnabled=false — заметки о пустом поиске нет",
        )
        assertTrue(events.any { it is AgentFinished })
    }

    @Test
    fun `retrieval failure keeps agent behavior without empty search note`() {
        val repo = newRepo("kb-err")
        val fake = FakeEmbedder()
        seedKb(repo, fake)
        fake.throwOnCall = RuntimeException("эмбеддер недоступен")
        val llm = RewriteLlm()

        val events = run(rewriteAgent(llm, repo, fake), "s1", "вопрос")

        assertEquals(1, llm.mainCalls.get(), "fail-open: сбой ретривала не ломает run")
        val prompt = events.filterIsInstance<LlmRequestStarted>().single().prompt
        assertFalse(
            prompt.any { it["role"] == "system" && it["content"] == KbRagService.EMPTY_SEARCH_NOTE },
            "сбой ретривала — заметки о пустом поиске нет (fail-open, как раньше)",
        )
        assertTrue(events.any { it is AgentFinished })
        assertTrue(events.none { it is ErrorEvent })
    }
}
