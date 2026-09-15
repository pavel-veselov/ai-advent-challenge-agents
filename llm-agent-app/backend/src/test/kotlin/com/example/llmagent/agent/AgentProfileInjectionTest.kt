package com.example.llmagent.agent

import com.example.llmagent.agent.tools.CalculatorTool
import com.example.llmagent.agent.tools.GetCurrentDateTimeTool
import com.example.llmagent.config.AgentProperties
import com.example.llmagent.config.JdbcAppSettingsStore
import com.example.llmagent.config.JdbcSessionLlmSettingsStore
import com.example.llmagent.config.LlmProperties
import com.example.llmagent.config.LlmSettings
import com.example.llmagent.config.SessionLlmSettingsProvider
import com.fasterxml.jackson.databind.ObjectMapper
import java.nio.file.Path
import java.time.Duration
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * Юнит-тесты инъекции активного профиля пользователя в AgentImpl (персонализация агента):
 * системный блок «=== ПРОФИЛЬ ПОЛЬЗОВАТЕЛЯ ===» идёт СРАЗУ после SYSTEM_PROMPT, ДО блоков
 * памяти WM/LTM — для всех стратегий контекста. Активный профиль — ГЛОБАЛЬНАЯ настройка
 * (app_settings: ключ `profile.active` = id или "null"), читается агентом на каждом run.
 *
 * Fail-open: профиль не выбран / id указывает на удалённый профиль / store'ы не подключены
 * (старый 13-арг конструктор) — блок молча пропускается, run не ломается.
 *
 * Паттерн — AgentMemoryLayersTest: AgentImpl конструктором с позиционными (nullable)
 * store'ами, SqliteTestSupport @TempDir, фейковый LLM (FakeToolCallLlmClient).
 */
class AgentProfileInjectionTest {

    @TempDir
    lateinit var tmpDir: Path

    private val om = ObjectMapper()

    private data class ProfileStores(
        val dbFile: Path,
        val session: SessionStore,
        val projects: ProjectStore,
        val compression: SessionCompressionStore,
        val context: SessionContextStore,
        val facts: SessionFactsStore,
        val branches: SessionBranchStore,
        val workingMemory: WorkingMemoryStore,
        val longTerm: LongTermMemoryStore,
        val profiles: ProfileStore,
        val appSettings: JdbcAppSettingsStore,
    )

    /** Store'ы на уникальном SQLite-файле (каждый тест — своя БД), включая projects/chat_sessions. */
    private fun stores(dbHint: String): ProfileStores {
        val dbFile = tmpDir.resolve("$dbHint-${UUID.randomUUID()}.db")
        val jdbc = SqliteTestSupport.jdbc(dbFile)
        SqliteTestSupport.createChatMessagesTable(jdbc)
        // Порядок важен: ProjectStore создаёт chat_sessions, чтобы SessionStore-фикстура
        // ниже видела реестр сессий (миграция-очистка на пустой БД ничего не удаляет).
        val projects = ProjectStore(jdbc)
        val branches = SessionBranchStore(jdbc)
        return ProfileStores(
            dbFile = dbFile,
            session = SessionStore(jdbc, branchStore = branches),
            projects = projects,
            compression = SessionCompressionStore(jdbc),
            context = SessionContextStore(jdbc),
            facts = SessionFactsStore(jdbc),
            branches = branches,
            workingMemory = WorkingMemoryStore(jdbc),
            longTerm = LongTermMemoryStore(jdbc),
            profiles = ProfileStore(jdbc),
            appSettings = JdbcAppSettingsStore(jdbc),
        )
    }

    /** Создаёт проект и в нём сессию; возвращает (projectId, sessionId). */
    private fun projectAndSession(s: ProfileStores): Pair<Long, String> {
        val projectId = s.projects.create("тест-проект")
        val sessionId = s.session.createSession(projectId, "сессия")
        return projectId to sessionId
    }

    /**
     * AgentImpl с опциональными profileStore/appSettingsStore (null — блок отключён,
     * как в старых тестах). WM/LTM — реальные store'ы из [s].
     */
    private fun agent(
        llm: LlmClient,
        s: ProfileStores,
        profiles: ProfileStore? = s.profiles,
        appSettings: JdbcAppSettingsStore? = s.appSettings,
    ): AgentImpl {
        val llmProps = LlmProperties()
        val agentProps = AgentProperties(8)
        val sessionLlmSettings = SessionLlmSettingsProvider(
            JdbcSessionLlmSettingsStore(SqliteTestSupport.jdbc(tmpDir.resolve("prof-llm-${UUID.randomUUID()}.db"))),
            LlmSettings.from(llmProps),
        )
        val tools = ToolRegistry(listOf(CalculatorTool(), GetCurrentDateTimeTool()))
        return AgentImpl(
            llm, tools, s.session, agentProps, LlmSettings.from(llmProps), sessionLlmSettings,
            s.compression, om, s.context, s.facts, s.branches, s.workingMemory, s.longTerm,
            profiles, appSettings,
        )
    }

    private fun run(a: AgentImpl, sessionId: String, message: String): List<AgentEvent> =
        a.run(sessionId, message).collectList().block(Duration.ofSeconds(10))!!

    private fun promptOf(events: List<AgentEvent>): List<Map<String, String>> =
        events.filterIsInstance<LlmRequestStarted>().single().prompt

    private fun hasProfileBlock(events: List<AgentEvent>): Boolean =
        promptOf(events).any { it["content"]?.startsWith("=== ПРОФИЛЬ ПОЛЬЗОВАТЕЛЯ ===") == true }

    // ------------------------------------------------------------------
    // 1. Активный профиль: блок сразу после system, ДО блоков WM/LTM и истории.
    // ------------------------------------------------------------------
    @Test
    fun `active profile block injected right after system prompt`() {
        val s = stores("prof-inj")
        val (projectId, sessionId) = projectAndSession(s)
        // данные WM/LTM добавляет ПОЛЬЗОВАТЕЛЬ — проверяем порядок: профиль ДО блоков памяти
        s.workingMemory.setTask(projectId.toString(), "мой тест")
        s.longTerm.upsert("s0", "profile", "автор", "Аня")

        val profile = s.profiles.create(
            "Профиль 1",
            "Backend-разработчик",
            "кратко, списком",
            "код объяснять по шагам",
            "отвечай только по-русски",
        )
        s.appSettings.save("profile.active", profile.id.toString())

        val llm = FakeToolCallLlmClient()
        val events = run(agent(llm, s), sessionId, "привет")

        val prompt = promptOf(events)
        assertEquals("system", prompt[0]["role"])
        // блок профиля на позиции 1 — сразу после SYSTEM_PROMPT
        val profileContent = prompt[1]["content"]!!
        assertTrue(profileContent.startsWith("=== ПРОФИЛЬ ПОЛЬЗОВАТЕЛЯ ===\nПрофиль: Профиль 1"), profileContent)
        assertTrue(profileContent.contains("Стиль: Backend-разработчик"), profileContent)
        assertTrue(profileContent.contains("Формат ответа: кратко, списком"), profileContent)
        assertTrue(profileContent.contains("Предпочтения: код объяснять по шагам"), profileContent)
        assertTrue(profileContent.contains("Ограничения: отвечай только по-русски"), profileContent)
        // блоки памяти сдвинулись на позицию позже (после профиля)
        assertTrue(prompt[2]["content"]!!.startsWith("=== РАБОЧАЯ ПАМЯТЬ ==="))
        assertTrue(prompt[3]["content"]!!.startsWith("=== ДОЛГОВРЕМЕННАЯ ПАМЯТЬ ==="))
        // история — следом за блоками
        assertEquals("user", prompt[4]["role"])
        assertEquals("привет", prompt[4]["content"])
        assertTrue(events.any { it is AgentFinished })
    }

    // ------------------------------------------------------------------
    // 2. Пустые (null/blank) поля профиля в блок НЕ попадают — только непустые.
    // ------------------------------------------------------------------
    @Test
    fun `blank profile fields are omitted from the block`() {
        val s = stores("prof-blank")
        val (_, sessionId) = projectAndSession(s)
        val profile = s.profiles.create("Только должность", "аналитик", null, "   ", null)
        s.appSettings.save("profile.active", profile.id.toString())

        val events = run(agent(FakeToolCallLlmClient(), s), sessionId, "привет")

        val block = promptOf(events).first { it["content"]?.startsWith("=== ПРОФИЛЬ ПОЛЬЗОВАТЕЛЯ ===") == true }["content"]!!
        assertTrue(block.contains("Профиль: Только должность"), block)
        assertTrue(block.contains("Стиль: аналитик"), block)
        assertTrue(!block.contains("Формат ответа"), block)
        assertTrue(!block.contains("Предпочтения"), block)
        assertTrue(!block.contains("Ограничения"), block)
    }

    // ------------------------------------------------------------------
    // 3. «Без профиля» / ключ не задан — блока нет, run работает штатно.
    // ------------------------------------------------------------------
    @Test
    fun `no active profile means no profile block`() {
        val s = stores("prof-none")
        val (_, sessionId) = projectAndSession(s)
        s.profiles.create("неактивный профиль", null, null, null, null)
        // ключ profile.active НЕ задан

        val events = run(agent(FakeToolCallLlmClient(), s), sessionId, "привет")
        assertTrue(!hasProfileBlock(events), "без активного профиля блок не подаётся")
        assertTrue(events.any { it is AgentFinished })
    }

    @Test
    fun `explicit null active profile means no profile block`() {
        val s = stores("prof-null")
        val (_, sessionId) = projectAndSession(s)
        val profile = s.profiles.create("Профиль 1", null, null, null, null)
        s.appSettings.save("profile.active", profile.id.toString())
        // пользователь выбрал «Без профиля» → value = "null"
        s.appSettings.save("profile.active", "null")

        val events = run(agent(FakeToolCallLlmClient(), s), sessionId, "привет")
        assertTrue(!hasProfileBlock(events))
    }

    // ------------------------------------------------------------------
    // 4. Активный id указывает на удалённый профиль — fail-open: блока нет, run жив.
    // ------------------------------------------------------------------
    @Test
    fun `stale active id pointing to deleted profile is fail-open`() {
        val s = stores("prof-stale")
        val (_, sessionId) = projectAndSession(s)
        val profile = s.profiles.create("удаляемый", null, null, null, null)
        s.appSettings.save("profile.active", profile.id.toString())
        assertTrue(s.profiles.delete(profile.id))

        val events = run(agent(FakeToolCallLlmClient(), s), sessionId, "привет")
        assertTrue(!hasProfileBlock(events), "ссылка на удалённый профиль — блок молча пропускается")
        assertTrue(events.any { it is AgentFinished })
    }

    // ------------------------------------------------------------------
    // 5. Store'ы не подключены (старый 13-арг конструктор) — блока нет, run не ломается
    //    (тот же контракт, что у WM/LTM: null store = фича выключена).
    // ------------------------------------------------------------------
    @Test
    fun `missing profile stores keep run working without block`() {
        val s = stores("prof-off")
        val (_, sessionId) = projectAndSession(s)
        val profile = s.profiles.create("Профиль 1", null, null, null, null)
        s.appSettings.save("profile.active", profile.id.toString())

        // подключаем ТОЛЬКО память, store'ы профиля — null (как в старых тестах)
        val events = run(agent(FakeToolCallLlmClient(), s, profiles = null, appSettings = null), sessionId, "привет")
        assertTrue(!hasProfileBlock(events))
        assertTrue(events.any { it is AgentFinished })
    }
}
