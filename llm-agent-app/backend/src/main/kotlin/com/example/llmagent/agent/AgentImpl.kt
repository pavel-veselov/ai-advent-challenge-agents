package com.example.llmagent.agent

import com.example.llmagent.agent.tools.TaskStateTool
import com.example.llmagent.config.AgentProperties
import com.example.llmagent.config.AppSettingsStore
import com.example.llmagent.config.LlmSettings
import com.example.llmagent.config.SessionLlmSettingsProvider
import com.example.llmagent.config.WorkflowSettings
import com.example.llmagent.kb.KbRagResult
import com.example.llmagent.kb.KbRagService
import com.example.llmagent.kb.KbRagSettingsService
import com.fasterxml.jackson.databind.ObjectMapper
import com.knuddels.jtokkit.Encodings
import com.knuddels.jtokkit.api.EncodingType
import com.knuddels.jtokkit.api.Encoding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.flux
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import reactor.core.publisher.Flux
import java.net.ConnectException
import java.net.UnknownHostException
import java.util.concurrent.TimeoutException

/**
 * Цикл агента: запрос -> LLM -> при tool_calls выполнить инструмент и вернуть наблюдение
 * в LLM -> повторять до финального ответа. Лимит итераций защищает от бесконечного цикла.
 * Контекст локально не оценивается: при переполнении (например, 400 от апстрима) ошибка
 * LLM API пробрасывается пользователю дословно через error-событие.
 */
class AgentImpl(
    private val llmClient: LlmClient,
    private val toolRegistry: ToolRegistry,
    private val sessionStore: SessionStore,
    private val agentProperties: AgentProperties,
    /** Глобальные применённые настройки LLM (тарифы стоимости, таймаут, fallback-контекст). */
    private val settings: LlmSettings,
    /** Per-session настройки LLM — разрешение по sessionId для каждого run (см. SessionLlmSettingsProvider). */
    private val sessionLlmSettings: SessionLlmSettingsProvider,
    /** Per-session настройки сжатия истории и свёрнутые резюме (см. SessionCompressionStore). */
    private val compressionStore: SessionCompressionStore,
    private val om: ObjectMapper,
    /**
     * Per-session стратегия контекста (none/sliding_window/sticky_facts/summary/branching,
     * см. SessionContextStore). null — в юнит-тестах, где стратегия не подключена: поведение
     * ровно как раньше (fallback по сжатию, см. [resolveStrategy]).
     */
    private val contextStore: SessionContextStore? = null,
    /** Хранилище «липких фактов» сессии (sticky_facts); null — извлечение фактов отключено. */
    private val factsStore: SessionFactsStore? = null,
    /** Хранилище веток диалога (branching); null — ветки не подключены (контекст = вся история). */
    private val branchStore: SessionBranchStore? = null,
    /** Рабочая память ПРОЕКТА (memory layers): заметки, добавляемые ТОЛЬКО пользователем
     *  (REST/UI); null — память не подключена (юнит-тесты/старая обвязка): контекстный
     *  блок молча пропускается (fail-open). Агент память не пишет. */
    private val workingMemoryStore: WorkingMemoryStore? = null,
    /** Глобальная долговременная память (memory layers, все сессии); null — не подключена
     *  (тот же fail-open: блок контекста и снапшоты пропускаются). Пишется ТОЛЬКО
     *  пользователем (REST/UI). */
    private val longTermMemoryStore: LongTermMemoryStore? = null,
    /** Справочник профилей пользователя (персонализация); null — блок профиля отключён
     *  (юнит-тесты/старая обвязка): контекстный блок молча пропускается (fail-open). */
    private val profileStore: ProfileStore? = null,
    /** Глобальные настройки приложения (app_settings): активный профиль — ключ
     *  `profile.active`; null — активный профиль не подключён (тот же fail-open). */
    private val appSettingsStore: AppSettingsStore? = null,
    /** Per-session состояние задачи (Day-13, FSM task_state); null — инструмент не
     *  подключён (юнит-тесты/старая обвязка): вызов инструмента возвращает ошибку,
     *  контекстный блок «=== СОСТОЯНИЕ ЗАДАЧИ ===» молча пропускается (fail-open). */
    private val taskStateStore: TaskStateStore? = null,
    /** Настройки воркфлоу Day-14 (app_settings: workflow.enabled / workflow.mode);
     *  null — воркфлоу выключен (юнит-тесты/старая обвязка): агент ведёт себя как
     *  сегодня, без гейтинга этапов (fail-open). */
    private val workflowSettings: WorkflowSettings? = null,
    /** Инварианты проекта (Day-14): обязательные ограничения, которые ассистент не
     *  имеет права нарушать; null — инварианты не подключены (юнит-тесты/старая
     *  обвязка): блок «=== ИНВАРИАНТЫ ===» молча пропускается (fail-open). */
    private val invariantsStore: InvariantsStore? = null,
    /** Базы знаний (Day-22, RAG): null — RAG-инъекция отключена (юнит-тесты/старая
     *  обвязка): блок «### База знаний» молча пропускается (fail-open). */
    private val kbRagService: KbRagService? = null,
    /** Настройки RAG (Day-23: фильтр релевантности, query rewrite); null — настройки
     *  не подключены (юнит-тесты/старая обвязка): перезапись запроса не выполняется
     *  (fail-open), поиск идёт по исходному сообщению пользователя. */
    private val kbRagSettingsService: KbRagSettingsService? = null,
) : Agent {

    private val log = LoggerFactory.getLogger(AgentImpl::class.java)

    override fun run(sessionId: String, userMessage: String): Flux<AgentEvent> =
        runCore(sessionId, userMessage, appendUser = true)

    override fun continueRun(sessionId: String): Flux<AgentEvent> =
        runCore(sessionId, WORKFLOW_CONTINUE_MESSAGE, appendUser = false)

    /**
     * Ядро агентского цикла. [appendUser]=true — обычный run пользователя (сообщение
     * сохраняется в историю); [appendUser]=false — продолжение воркфлоу Day-14
     * (/continue): новое user-сообщение НЕ добавляется, агент читает сохранённую
     * историю и состояние задачи (текущий этап) — контекст продолжается без
     * повторного объяснения.
     */
    private fun runCore(
        sessionId: String,
        userMessage: String,
        appendUser: Boolean,
    ): Flux<AgentEvent> = flux {
        if (appendUser) {
            sessionStore.append(sessionId, "user", userMessage)
        }
        // Day-12: рабочая память (WM) живёт НА ПРОЕКТЕ (общая для сессий проекта).
        // projectId берём из реестра сессий (chat_sessions); для сессии без строки
        // (легаси/осиротевшая) — fallback на sessionId: память ключуется по wmKey,
        // поведение run'ов без проектов сохраняется.
        val projectId: Long? = sessionStore.getProjectId(sessionId)
        val wmKey: String = projectId?.toString() ?: sessionId
        val toolCounters = mutableMapOf<String, Int>()
        var iteration = 0
        var errorIdx = 0
        var logIdx = 0
        // Панель «Логи»: ЕДИНСТВЕННЫЙ источник записей — обращения к chat-модели и
        // к эмбеддинг-модели (RAG-поиск баз знаний). Каждый вызов перехватывается НА
        // УРОВНЕ КЛИЕНТА (LlmCallLoggingClient — «Запрос в llm»/«Ответ от llm»,
        // KbEmbeddingClient — «Запрос в эмбеддинги»/«Ответ от эмбеддингов») и фиксируется
        // парой (kind, detail): text события = kind, detail = фактический JSON (время
        // записи не вшивается — фронт рисует его из timestamp события). Колбэки вызываются
        // на потоках reactor-стрима/IO, поэтому пары складываются в неблокирующую очередь
        // (UNLIMITED — потеря записи недопустима) и вычитываются корутиной run'а через
        // suspend send в SSE.
        val llmLogQueue = Channel<Pair<String, String>>(Channel.UNLIMITED)
        val llm = LlmCallLoggingClient(llmClient) { kind, json -> llmLogQueue.trySend(kind to json) }

        /** Вычитывает накопившиеся записи LLM-лога и отправляет их в SSE (type="log"). */
        suspend fun drainLlmLogs() {
            while (true) {
                val (kind, detail) = llmLogQueue.tryReceive().getOrNull() ?: break
                send(LogEvent(logIdx++, kind, detail))
            }
        }

        // Память агента (memory layers) пишется ТОЛЬКО пользователем (REST/UI): агент НЕ
        // захватывает задачу на старте run и НЕ добавляет заметки после tool-результатов.
        // Рабочая память (WM) проекта и глобальная долговременная память (LTM) лишь
        // ПОДАЮТСЯ модели контекстными блоками ниже (см. сборку контекста) — fail-open:
        // нет/битое хранилище — warn в лог и блок молча пропускается, run не ломается.

        // Per-session настройки LLM — ЭФФЕКТИВНЫЙ набор по всем редактируемым полям (model,
        // temperature, topP, topK, maxTokens, timeoutSeconds, тарифы, reasoningEnabled)
        // для каждого run. У сессии без сохранённой строки — текущие ГЛОБАЛЬНЫЕ значения
        // (поведение как сегодня). Билдер запроса берёт параметры отсюда на каждый вызов.
        val runSettings = sessionLlmSettings.resolve(sessionId)

        // Разрешение эффективной стратегии контекста сессии для этого run. Правило
        // (см. SessionContextStore.resolve): сохранённая стратегия; при 'none' — fallback
        // на 'summary', если включено legacy-сжатие (compression.enabled==true). Так
        // старая функциональность сжатия работает неизменно для сессий, не выбиравших
        // стратегию. Без хранилища (юнит-тесты) — то же падение только по сжатию.
        val compressionEnabled = compressionStore.getSettings(sessionId).enabled
        val contextStrategy = contextStore?.resolve(sessionId, compressionEnabled)
            ?: if (compressionEnabled) SessionContextStore.STRATEGY_SUMMARY else SessionContextStore.STRATEGY_NONE
        val strategyWindowSize = contextStore?.get(sessionId)?.windowSize
            ?: ContextStrategySettings(sessionId).windowSize
        send(AgentStarted(userMessage, runSettings.settings() + mapOf(
            "maxToolCallIterations" to agentProperties.maxToolCallIterations,
            "tools" to visibleToolNames(),
            "contextStrategy" to contextStrategy,
        )))

        // Сжатие истории (per-session): старые сообщения сворачиваются в резюме, чтобы контекст
        // LLM не рос бесконечно. Происходит ДО основного цикла, поэтому события context_summary_*
        // НЕ входят в нумерацию итераций; stepId фиксирован. При сбое вызова резюмирования —
        // error-событие и fail-open: run продолжается со всей историей (поведение без сжатия).
        val compression = compressionStore.getSettings(sessionId)
        val previousSummary = compressionStore.getSummary(sessionId)
        // Итоговое резюме для контекста (если сжатие применяем): либо только что свёрнутое, либо прежнее.
        var contextSummary: String? = previousSummary?.summary
        // Сжимаем контекст (резюме + последние keepLast + вопрос); false — выключено/сбой/резюме
        // ещё не создано → вся история. Сжатие работает ТОЛЬКО для стратегии summary
        // (в неё разрешается и legacy-включённое сжатие, см. resolveStrategy выше).
        var compressionActive = false
        if (contextStrategy == SessionContextStore.STRATEGY_SUMMARY && compression.enabled) {
            // Системные заметки (информация о сжатии) — служебные: в резюме и в контекст не попадают.
            val stored = sessionStore.getStored(sessionId).filter { it.role != "system" }
            val keep = compression.keepLast
            val summaryUpto = previousSummary?.uptoOrder ?: 0L
            // «Складываемые» сообщения: ещё не покрыты резюме И вне хвоста, который остаётся
            // в контексте дословно. Хвост и сам новый вопрос в резюмирование не уходят:
            // последнее сообщение промпта-резюме — не вопрос пользователя, иначе модель
            // резюмирования отвечает на вопрос вместо сжатия; и без дубля хвостового
            // сообщения (оно и так остаётся в контексте «как есть»).
            val foldable = stored
                .filterIndexed { index, m -> index < stored.size - 1 - keep && m.id > summaryUpto }
            if (foldable.size >= compression.summaryEvery) {
                // Промпт резюмирования собирается заранее, чтобы уйти в событие
                // context_summary_started: в логе шагов видно, что именно отправлено в LLM.
                val summaryPrompt = buildSummaryPrompt(foldable, previousSummary?.summary)
                send(ContextSummaryStarted(foldable.size, promptSnapshot(summaryPrompt)))
                try {
                    val result = summarize(llm, summaryPrompt, runSettings)
                    compressionStore.saveSummary(sessionId, result.text, foldable.last().id)
                    // Оценка размера контекста (в токенах, эвристика — см. estimateTokens):
                    // «до» — контекст без сжатия (system + вся история с новым вопросом),
                    // «после» — сжатый контекст этого run (system + резюме + хвост + вопрос).
                    val contextBefore = estimateTokens(
                        listOf(LlmMessage("system", SYSTEM_PROMPT)) +
                            stored.map { LlmMessage(it.role, it.content) },
                    )
                    val contextAfter = estimateTokens(
                        listOf(
                            LlmMessage("system", SYSTEM_PROMPT),
                            LlmMessage("system", "$SUMMARY_CONTEXT_PREFIX${result.text}"),
                        ) +
                            stored.dropLast(1).takeLast(keep).map { LlmMessage(it.role, it.content) } +
                            listOf(LlmMessage("user", userMessage)),
                    )
                    send(
                        ContextSummaryFinished(
                            foldable.size,
                            result.usage?.inputTokens ?: 0,
                            result.usage?.outputTokens ?: 0,
                            result.text,
                            contextBefore,
                            contextAfter,
                        )
                    )
                    // Информация о сжатии — в самом чате: короткая системная заметка,
                    // переживает перезагрузку. В LLM-контекст она не попадает:
                    // role "system" отфильтровывается при сборке контекста ниже.
                    sessionStore.append(
                        sessionId,
                        "system",
                        "Сжатие контекста: ${foldable.size} старых сообщений свернуто в резюме. " +
                            "Контекст: $contextBefore → $contextAfter токенов.",
                    )
                    contextSummary = result.text
                    compressionActive = true
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Сбой вызова резюмирования — не валим run: error-событие и продолжаем БЕЗ сжатия.
                    log.warn("session={} compression LLM call failed, run continues without it: {}", sessionId, e.message)
                    send(ErrorEvent(errorIdx++, "Ошибка сжатия истории: ${compressionFailureMessage(e)}. Продолжаю без сжатия."))
                    compressionActive = false
                }
            } else {
                // Порог не достигнут — применяем прежнее резюме, если оно уже есть;
                // если резюме ещё нет, шлём полную историю: иначе старые сообщения
                // выпадали бы из контекста без суммаризации (молчаливая потеря данных).
                compressionActive = contextSummary != null
            }
        }

        // «Липкие факты» (strategy=sticky_facts): извлечение фактов о пользователе и диалоге
        // LLM-вызовом — синхронно и ДО сборки контекста (как блок сжатия выше). stepId
        // события facts_updated фиксирован ("facts") и в нумерацию итераций не входит.
        // Fail-open: при ЛЮБОМ сбое (LLM API/таймаут/связь/не-JSON/пустой ответ) — error-событие
        // «Не удалось обновить факты: ...» и run продолжается с ПРЕЖНИМИ фактами; событие
        // facts_updated при сбое не шлётся, факты не меняются.
        val factsStoreInstance = factsStore
        var facts: Map<String, String> = factsStoreInstance?.getAll(sessionId) ?: emptyMap()
        if (contextStrategy == SessionContextStore.STRATEGY_STICKY_FACTS && factsStoreInstance != null) {
            try {
                val window = sessionStore.getStored(sessionId)
                    .filter { it.role != "system" }
                    .takeLast(strategyWindowSize)
                val factsPrompt = buildFactsPrompt(window, facts)
                val updated = extractFacts(llm, factsPrompt, runSettings)
                factsStoreInstance.replaceAll(sessionId, updated)
                facts = updated
                send(FactsUpdated(updated))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Сбой извлечения фактов — не валим run: error-событие и прежние факты.
                log.warn("session={} facts LLM call failed, run continues with previous facts: {}", sessionId, e.message)
                send(ErrorEvent(errorIdx++, "Не удалось обновить факты: ${factsFailureMessage(e)}. Продолжаю с предыдущими фактами."))
            }
        }

        val messages = mutableListOf<LlmMessage>()
        messages += LlmMessage("system", SYSTEM_PROMPT)

        // День-24: результат RAG-поиска нужен ВНЕ блока памяти — для детерминированного
        // отказа «не знаю» (после try/catch) и для списка источников в agent_finished.
        var kbRagResult: KbRagResult? = null

        // Память агента — контекстные блоки (memory layers): рабочие блоки WM (ПО ПРОЕКТУ)
        // и LTM между SYSTEM_PROMPT и блоком стратегии, для ВСЕХ стратегий (включая branching)).
        // В историю чата НЕ пишутся — только в контекст текущего запроса. Fail-open:
        // нет store / сбой чтения — блок молча пропускается, run не ломается.
        try {
            // Персонализация (профиль пользователя): активный профиль — ГЛОБАЛЬНАЯ настройка
            // (app_settings: `profile.active` = id или "null"). Системный блок «=== ПРОФИЛЬ
            // ПОЛЬЗОВАТЕЛЯ ===» идёт сразу после SYSTEM_PROMPT, ДО блоков памяти — для ВСЕХ
            // стратегий контекста. Нет профиля («Без профиля»)/нет store/сбой чтения — блок
            // молча пропускается (fail-open), run не ломается.
            val activeProfileId = appSettingsStore?.get(PROFILE_ACTIVE_KEY)
                ?.trim()?.takeIf { it != PROFILE_ACTIVE_NONE }?.toLongOrNull()
            if (activeProfileId != null) {
                val profile = profileStore?.findById(activeProfileId)
                if (profile != null) {
                    messages += LlmMessage("system", buildProfileSystem(profile))
                }
            }

            val wm = workingMemoryStore?.get(wmKey) ?: WorkingMemory(task = null, notes = emptyList())
            val wmSections = mutableListOf<String>()
            if (!wm.task.isNullOrBlank()) wmSections += "Текущая задача: ${wm.task}"
            if (wm.notes.isNotEmpty()) {
                wmSections += "Промежуточные результаты:\n" +
                    wm.notes.mapIndexed { index, note -> "${index + 1}. $note" }.joinToString("\n")
            }
            val wmBlock = if (wmSections.isNotEmpty()) "=== РАБОЧАЯ ПАМЯТЬ ===\n" + wmSections.joinToString("\n\n") else null
            if (wmBlock != null) {
                messages += LlmMessage("system", wmBlock)
            }

            val longTerm = longTermMemoryStore?.listAll() ?: emptyList()
            val ltmBlock = if (longTerm.isNotEmpty()) {
                val shown = longTerm.take(20)
                val lines = shown.map { "${it.type} | ${it.key}: ${it.value}" }.toMutableList()
                if (longTerm.size > 20) {
                    lines += "…и ещё ${longTerm.size - 20} записей в долговременной памяти"
                }
                "=== ДОЛГОВРЕМЕННАЯ ПАМЯТЬ ===\n" + lines.joinToString("\n")
            } else null
            if (ltmBlock != null) {
                messages += LlmMessage("system", ltmBlock)
            }

            // Инварианты проекта (Day-14): обязательные ограничения (архитектура, технические
            // решения, стек, бизнес-правила), которые ассистент НЕ имеет права нарушать.
            // Хранятся ОТДЕЛЬНО от диалога, скоупятся ПО ПРОЕКТУ (wmKey) — общая для всех
            // сессий проекта. Системный блок «=== ИНВАРИАНТЫ ===» подаётся модели сразу после
            // памяти, для ВСЕХ стратегий контекста. Нет store / нет инвариантов / сбой чтения —
            // блок молча пропускается (fail-open), run не ломается.
            val invariants = invariantsStore?.list(wmKey) ?: emptyList()
            if (invariants.isNotEmpty()) {
                val invariantsBlock = buildInvariantsSystem(invariants)
                messages += LlmMessage("system", invariantsBlock)
            }

            // Базы знаний (Day-22, RAG): по вопросу пользователя ищем top-K релевантных
            // чанков по всем АКТИВНЫМ ПРОИНДЕКСИРОВАННЫМ базам — системный блок
            // «### База знаний» рядом с блоками памяти, для ВСЕХ стратегий контекста.
            // Эмбеддинг-вызов — блокирующий HTTP + чтение БД: уводим в Dispatchers.IO.
            // Нет активных баз → buildContextResult вернёт null БЕЗ сетевых вызовов
            // (нулевой оверхед, поведение агента не меняется — требование плана).
            val ragQuery = if (appendUser) userMessage
            else sessionStore.getStored(sessionId)
                .lastOrNull { it.role == "user" }?.content ?: userMessage
            // Query rewrite (Day-23): при включённой настройке kb.rewriteEnabled И наличии
            // активной проиндексированной базы вопрос пользователя переписывается LLM-вызовом
            // под векторный поиск (по паттерну extractFacts выше — тот же обёрнутый клиент,
            // записи уходят в панель «Логи»). Ответ очищается (кавычки/лимит 300 символов);
            // при ЛЮБОМ сбое (LLM API/таймаут/связь/пустой после очистки) — warn и фолбэк
            // на исходный запрос: rewrite НИКОГДА не ломает ответ. Выключенная настройка
            // или отсутствие активных баз — ни одного дополнительного LLM-вызова.
            var rewrittenQuery: String? = null
            var rewriteUsed = false
            val ragSettingsService = kbRagSettingsService
            if (kbRagService != null && ragSettingsService != null &&
                ragSettingsService.load().rewriteEnabled && kbRagService.hasActiveIndexed()
            ) {
                try {
                    val raw = rewriteQuery(llm, buildRewritePrompt(ragQuery), runSettings)
                    val sanitized = sanitizeRewrittenQuery(raw)
                    if (sanitized != null) {
                        rewrittenQuery = sanitized
                        rewriteUsed = true
                    } else {
                        log.warn(
                            "session={} rewrite вернул пустой запрос после очистки — использую исходный",
                            sessionId,
                        )
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.warn(
                        "session={} rewrite LLM call failed, run continues with original query: {}",
                        sessionId, e.message,
                    )
                }
            }
            val kbRag = kbRagService?.let { service ->
                withContext(Dispatchers.IO) {
                    // Эмбеддинг-вызов RAG-поиска логируется в панель «Логи» наравне с
                    // chat-моделью: колбэк складывает («Запрос в эмбеддинги»/«Ответ от
                    // эмбеддингов», JSON) в ту же очередь, что и записи chat-модели.
                    service.buildContextResult(ragQuery, rewrittenQuery, rewriteUsed) { kind, json -> llmLogQueue.trySend(kind to json) }
                }
            }
            kbRagResult = kbRag
            // block != null ⟺ сбоя не было, найдены чанки и блок собран (см. KbRagResult) —
            // блок «### База знаний» добавляется в контекст после памяти, как раньше.
            val kbBlock = kbRag?.block
            if (kbBlock != null) {
                messages += LlmMessage("system", kbBlock)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("session={} memory context blocks failed, run continues without them: {}", sessionId, e.message)
        }

        // День-24: детерминированный отказ «не знаю» ДО цикла tool-calling. Активные
        // проиндексированные базы есть (kbRagResult != null), поиск завершился без сбоя,
        // но релевантность ниже порога (usedChunks==0 или лучший score < minScore) →
        // LLM-цикл не запускается вовсе: фиксированный отказ, без риска галлюцинаций.
        // Лог «Ответ поискового движка» (KIND_SEARCH_RESULT) ещё в очереди llmLogQueue —
        // вычитываем ОДИН раз, чтобы запись ушла в панель, и НЕ дублируем её.
        // Компромисс (задокументирован в плане дня 24): при активной KB разговорное
        // сообщение ниже порога тоже получит отказ — это требование задания; отключается
        // настройкой kb.refusalEnabled (дефолт true). Сбоя в сборке памяти выше нет —
        // если был, kbRagResult = null и отказ не сработает (fail-open, как раньше).
        val kbRagSettings = kbRagSettingsService?.load()
        if (kbRagSettings != null && KbRagService.shouldRefuse(kbRagResult, kbRagSettings)) {
            drainLlmLogs()
            log.info("session={} релевантность KB ниже порога — отказ «{}»", sessionId, KbRagService.REFUSAL_TEXT)
            send(AgentFinished(KbRagService.REFUSAL_TEXT))
            return@flux
        }
        // Источники финального ответа (Day-24): usedChunks в порядке KB-блока
        // (label = позиция в блоке); нет баз / блок не собран — null (поле в payload
        // отсутствует — обратная совместимость).
        val kbSources = kbRagResult?.let { r ->
            r.chunks.take(r.usedChunks)
                .map { hit -> KbSourceRef(hit.chunkId, hit.label, hit.kbName, hit.source, hit.section, hit.score) }
                .ifEmpty { null }
        }

        // Состояние задачи (Day-13, FSM): системный блок «=== СОСТОЯНИЕ ЗАДАЧИ ===» —
        // этап/шаг/ожидаемое действие из per-session task_state + строка паузы. Нет строки
        // (задача не начата)/нет store — блок молча пропускается (fail-open). Блок идёт
        // после памяти, перед сообщениями истории — для ВСЕХ стратегий контекста: модель
        // продолжает с текущего шага без повторного объяснения задачи.
        val taskState = taskStateStore?.get(sessionId)
        if (taskState != null) {
            val taskStateBlock = buildTaskStateSystem(taskState)
            messages += LlmMessage("system", taskStateBlock)
        }

        // Воркфлоу Day-14: глобальная настройка app_settings (workflow.enabled /
        // workflow.mode). Текущий этап — из состояния задачи (или planning по умолчанию
        // для только что поставленной задачи). Директива в контексте: ручной режим —
        // выполнить ТОЛЬКО текущий этап (агент отдаёт результат и ждёт подтверждения),
        // авто — пройти все этапы подряд без паузы.
        val workflowEnabled = workflowSettings?.isEnabled() ?: false
        val workflowMode = workflowSettings?.mode() ?: WorkflowSettings.MODE_MANUAL
        val workflowStage = if (workflowEnabled) (taskState?.stage ?: TaskStateStore.STAGE_PLANNING) else null
        if (workflowEnabled && workflowStage != null) {
            val workflowBlock = buildWorkflowDirective(workflowStage, workflowMode)
            messages += LlmMessage("system", workflowBlock)
        }

        // AUTO-воркфлоу: отслеживаем этап, на котором модель находится, чтобы при смене
        // этапа инструментом task_state сохранить повествование модели как результат этапа
        // (отдельное сообщение ассистента — пользователь видит ответы на каждом этапе).
        // Текст последней реплики держим отдельно — для корректного закрытия пузыря при
        // внешней паузе (см. проверку paused в цикле).
        var lastWorkflowStage: String? = workflowStage
        var lastAssistantText = ""

        when {
            // Сжатый контекст (strategy=summary): [резюме как сообщение] + последние keepLast
            // сообщений «как есть» + новый вопрос (поведение сжатия не изменилось).
            compressionActive -> {
                if (contextSummary != null) {
                    messages += LlmMessage("system", "$SUMMARY_CONTEXT_PREFIX$contextSummary")
                }
                // Системные заметки (информация о сжатии) в контекст не идут; фильтр ДО
                // dropLast/takeLast: новая реплика — последнее не-системное сообщение.
                sessionStore.getStored(sessionId)
                    .filter { it.role != "system" }
                    .dropLast(1) // новый вопрос уже в истории — добавляем его отдельно, без дубля
                    .takeLast(compression.keepLast)
                    .forEach { m -> messages.add(LlmMessage(m.role, m.content)) }
                messages += LlmMessage("user", userMessage)
            }
            // Окно последних сообщений (strategy=sliding_window / sticky_facts): новый вопрос
            // уже последнее не-системное сообщение в истории — входит в окно как есть.
            contextStrategy == SessionContextStore.STRATEGY_SLIDING_WINDOW ||
                contextStrategy == SessionContextStore.STRATEGY_STICKY_FACTS -> {
                if (contextStrategy == SessionContextStore.STRATEGY_STICKY_FACTS && facts.isNotEmpty()) {
                    // Текущие известные факты — системное сообщение перед окном диалога.
                    messages += LlmMessage(
                        "system",
                        "Известные факты:\n" + facts.entries.joinToString("\n") { "- ${it.key}: ${it.value}" },
                    )
                }
                val window = sessionStore.getStored(sessionId)
                    .filter { it.role != "system" }
                    .takeLast(strategyWindowSize)
                window.forEach { m -> messages.add(LlmMessage(m.role, m.content)) }
            }
            // Цепочка активной ветки (strategy=branching): корень → … → вопрос (после append
            // вопрос — голова активной ветки); системные заметки фильтруются, порядок
            // хронологический (root→head уже в getBranchChain).
            contextStrategy == SessionContextStore.STRATEGY_BRANCHING && branchStore != null -> {
                val branch = branchStore.effectiveBranch(sessionId)
                val branchMessages = branch?.headMessageId
                    ?.let { sessionStore.getBranchChain(sessionId, it) }
                    ?: emptyList()
                val branchChain = branchMessages
                    .filter { it.role != "system" }
                branchChain.forEach { m -> messages.add(LlmMessage(m.role, m.content)) }
            }
            // Вся история целиком (strategy=none и любой непокрытый выше случай).
            else -> {
                val storedAll = sessionStore.getStored(sessionId)
                    .filter { it.role != "system" }
                storedAll.forEach { m -> messages.add(LlmMessage(m.role, m.content)) }
            }
        }

        try {
            while (true) {
                iteration++
                if (iteration > agentProperties.maxToolCallIterations) {
                    val msg = "Превышен лимит итераций агента (${agentProperties.maxToolCallIterations})"
                    log.warn("session={} {}", sessionId, msg)
                    send(ErrorEvent(errorIdx++, msg))
                    return@flux
                }

                // Воркфлоу: пользователь мог поставить паузу (REST PUT task-state {paused:true})
                // в момент выполнения. Уважаем внешнюю паузу — останавливаем выполнение,
                // НЕ отдавая финальный ответ, и сообщаем панели состояния (paused=true).
                // Задача и текущий этап сохраняются: после снятия паузы выполнение продолжится.
                // Проверка выполняется только для workflow-режима (вне воркфлоу поведение
                // сохраняем прежним — юнит-тест `tool update preserves pause flag`).
                if (workflowEnabled && taskStateStore != null) {
                    val current = taskStateStore.get(sessionId)
                    if (current != null && current.paused) {
                        send(
                            TaskStateChanged(
                                current.stage, current.currentStep, current.expectedAction, paused = true,
                                current.plan, current.implementation, current.validation, current.awaitConfirmation,
                            ),
                        )
                        // Закрываем пузырь ассистента финальным событием (иначе фронтенд
                        // оставил бы его в состоянии streaming навсегда).
                        send(AgentFinished(lastAssistantText))
                        return@flux
                    }
                }

                // Локальных оценок до отправки в LLM нет: переполнение контекста выявляет сам
                // апстрим (обычно 400), его ответ пробрасывается дословно через error-событие.
                val text = StringBuilder()
                var toolCalls: List<LlmToolCall> = emptyList()
                var finishReason = "stop"
                var usage: LlmUsage? = null
                var requestBody: String? = null
                var responseBody: String? = null
                // Тело запроса приходит из колбэка синхронно при построении streamChat(),
                // поэтому строка шага появляется ДО HTTP-вызова — в т.ч. при ошибке API.
                val flux = llm.streamChat(messages, visibleToolDefinitions(), runSettings) { requestBody = it }
                // «HH:mm:ss  Запрос в llm» — текст построен клиентом при сборке запроса;
                // вычитываем очередь, чтобы запись ушла в SSE до llm_request_started.
                drainLlmLogs()
                send(LlmRequestStarted(iteration, promptSnapshot(messages), requestBody = requestBody))
                val events = flux.collectList().awaitSingle()
                for (e in events) {
                    when (e) {
                        is LlmEvent.ContentDelta -> {
                            text.append(e.delta)
                            send(LlmToken(iteration, e.delta))
                        }
                        is LlmEvent.ToolCallsComplete -> toolCalls = e.toolCalls
                        is LlmEvent.Finished -> {
                            finishReason = e.finishReason
                            usage = e.usage
                        }
                        is LlmEvent.ResponseAssembled -> responseBody = e.body
                    }
                }
                // «HH:mm:ss  Ответ от llm» — записан в очередь при получении ResponseAssembled.
                drainLlmLogs()
                lastAssistantText = text.toString()
                val costUsd = usage?.let {
                    (it.inputTokens * settings.priceInputPer1M() + it.outputTokens * settings.priceOutputPer1M()) /
                        1_000_000.0
                }
                send(LlmResponseFinished(iteration, finishReason, usage, costUsd = costUsd, responseBody = responseBody))

                // Воркфлоу: пользователь мог поставить паузу, ПОКА модель генерировала ответ
                // (REST PUT task-state {paused:true} легло в БД после проверки в топе цикла).
                // Уважаем её: останавливаемся ДО обработки переходов инструмента (task_state →
                // смена этапа), чтобы агент не завершил текущий этап и не переключился на
                // следующий. Результат текущего этапа НЕ сохраняется (этап остаётся прежним),
                // панели сообщаем paused=true. Проверка только для workflow-режима.
                if (workflowEnabled && taskStateStore != null) {
                    val cur = taskStateStore.get(sessionId)
                    if (cur != null && cur.paused) {
                        send(
                            TaskStateChanged(
                                cur.stage, cur.currentStep, cur.expectedAction, paused = true,
                                cur.plan, cur.implementation, cur.validation, cur.awaitConfirmation,
                            ),
                        )
                        send(AgentFinished(lastAssistantText))
                        return@flux
                    }
                }

                if (finishReason == "tool_calls" && toolCalls.isNotEmpty()) {
                    val assistantReply = text.toString().trim()
                    messages.add(LlmMessage("assistant", assistantReply.ifEmpty { null }, toolCalls = toolCalls))
                    // Day-19: сохраняем в историю КАЖДЫЙ ответ модели (не только финальный). Иначе
                    // фронтенд после финализации перечитывает историю (сокращённую до одного ответа)
                    // и «схлопывает» все промежуточные сообщения модели. Пустой tool-calling ответ
                    // (модель вызвала инструмент без повествования) в историю НЕ пишем — на живом
                    // виде пузырь такого шага не открывается (cur.content.trim() !== ''), и в истории
                    // он тоже не нужен: иначе после перечитывания истории появился бы пустой пузырь,
                    // которого не было на стриме. Fail-open: сбой БД не прерывает цикл инструментов.
                    if (assistantReply.isNotEmpty()) {
                        try {
                            sessionStore.append(sessionId, "assistant", assistantReply, usage?.inputTokens, usage?.outputTokens)
                        } catch (e: Exception) {
                            log.warn("session={} не удалось сохранить промежуточный ответ модели: {}", sessionId, e.message)
                        }
                    }
                    for (tc in toolCalls) {
                        val name = tc.name ?: "unknown"
                        val idx = toolCounters[name] ?: 0
                        toolCounters[name] = idx + 1
                        val args = parseArgs(tc.arguments)
                        send(ToolCallStarted(name, idx, args))

                        val tool = toolRegistry.get(name)
                        val result = if (name == TaskStateTool.TOOL_NAME) {
                            // task_state исполняется агентом, а не Tool.execute: хранилищу
                            // нужен sessionId сессии, которого у Tool его нет. Схема
                            // аргументов для LLM объявлена в TaskStateTool.
                            try {
                                handleTaskState(sessionId, args)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                log.warn("session={} tool {} failed", sessionId, name, e)
                                ToolResult("Ошибка инструмента: ${e.message}", true)
                            }
                        } else if (tool == null) {
                            ToolResult("Неизвестный инструмент: $name", true)
                        } else {
                            try {
                                tool.execute(args)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                log.warn("session={} tool {} failed", sessionId, name, e)
                                ToolResult("Ошибка инструмента: ${e.message}", true)
                            }
                        }
                        send(ToolCallFinished(name, idx, if (result.isError) "error" else "success", result.result))
                        // Day-13: после успешного task_state — событие task_state_changed
                        // (панель состояния на фронтенде обновляется в реальном времени)
                        // и строка лога с новым состоянием. При ошибке валидации FSM
                        // модель получает ToolResult-ошибку и скорректирует аргументы сама.
                        if (name == TaskStateTool.TOOL_NAME && !result.isError) {
                            val saved = taskStateStore?.get(sessionId)
                            if (saved != null) {
                                send(
                                    TaskStateChanged(
                                        saved.stage, saved.currentStep, saved.expectedAction, saved.paused,
                                        saved.plan, saved.implementation, saved.validation, saved.awaitConfirmation,
                                    )
                                )
                            }
                        }
                        // Память агент НЕ пишет: рабочая память и долговременная память —
                        // ТОЛЬКО пользователь (REST/UI); tool-результаты просто уходят модели.
                        // День-15: если task_state вернул ОШИБКУ (недопустимый переход) — кладём в
                        // контекст не голый текст ошибки, а блокирующую директиву: переход НЕ
                        // выполнен, состояние не изменилось, заявлять успех нельзя. Так модель не
                        // «перепрыгивает» строгий линейный FSM и честно передаёт пользователю ошибку.
                        val llmToolMessage = if (name == TaskStateTool.TOOL_NAME && result.isError) {
                            "[ОШИБКА ИНСТРУМЕНТА task_state — переход НЕ выполнен, состояние задачи не изменилось]\n" +
                                result.result + "\n\n" +
                                "Передай пользователю текст ошибки выше ДОСЛОВНО. НЕ утверждай, что переход " +
                                "выполнен, этап изменён или задача закрыта. Объясни, какой переход допустим " +
                                "для текущего этапа и что нужно сделать, чтобы его выполнить."
                        } else {
                            result.result
                        }
                        messages.add(LlmMessage("tool", llmToolMessage, toolCallId = tc.id))
                    }

                    // AUTO-воркфлоу: модель завершила этап и инструментом task_state перешла
                    // на следующий — сохраняем повествование этапа как отдельное сообщение
                    // ассистента (в истории чата — пользователь видит ответы на каждом этапе,
                    // а не только финальный) и как результат этапа (колонка plan/implementation/
                    // validation, зеркально ручному режиму, но без ожидания подтверждения).
                    if (workflowEnabled && workflowMode == WorkflowSettings.MODE_AUTO && taskStateStore != null) {
                        val after = taskStateStore.get(sessionId)
                        if (after != null && after.stage != lastWorkflowStage) {
                            val completed = lastWorkflowStage
                            val stageText = text.toString().trim()
                            if (completed != null && completed != TaskStateStore.STAGE_DONE && stageText.isNotEmpty()) {
                                // Повествование этапа уже сохранено в историю (append промежуточного
                                // ответа выше) — здесь только фиксируем результат этапа и событие UI.
                                taskStateStore.setStageOutputKeepStage(sessionId, completed, stageText)
                                // Надёжная граница этапа: повествование только что закоммичено
                                // и в историю, и в результат этапа — сообщаем фронту, чтобы он
                                // закрыл пузырь этапа и открыл новый для следующего (live-вид
                                // видит каждую стадию отдельным сообщением ассистента).
                                send(WorkflowStageFinished(completed, stageText))
                            }
                            lastWorkflowStage = after.stage
                        }
                    }
                } else if (finishReason == "error" || finishReason == "length") {
                    val msg = "Ошибка LLM (finishReason=$finishReason)"
                    send(ErrorEvent(errorIdx++, msg))
                    return@flux
                } else {
                    val finalText = text.toString()
                    sessionStore.append(sessionId, "assistant", finalText, usage?.inputTokens, usage?.outputTokens)
                    // Кумулятивная статистика «за всё время» — переживает удаление сессии.
                    sessionStore.addLifetimeTokens(usage?.inputTokens ?: 0, usage?.outputTokens ?: 0, costUsd ?: 0.0)
                    // День-24: sources — использованные чанки KB (null — поле отсутствует).
                    send(AgentFinished(finalText, kbSources))

                    // AUTO-воркфлоу: модель могла дать финальный ответ, не дойдя до этапа
                    // «готово» (например, завершилась на проверке) — сохраняем его как
                    // результат последнего этапа (та же колонка, что в ручном режиме).
                    if (workflowEnabled && workflowMode == WorkflowSettings.MODE_AUTO && taskStateStore != null) {
                        val current = taskStateStore.get(sessionId)
                        if (current != null && current.stage != TaskStateStore.STAGE_DONE &&
                            lastWorkflowStage != null && lastWorkflowStage != TaskStateStore.STAGE_DONE
                        ) {
                            taskStateStore.setStageOutput(sessionId, current.stage, finalText, await = false)
                        }
                    }

                    // Воркфлоу Day-14 (ручной режим): этап завершён, но не «готово» — сохраняем
                    // результат этапа в состояние задачи (plan/implementation/validation),
                    // поднимаем флаг ожидания подтверждения и сообщаем фронтенду — под последним
                    // сообщением ассистента появятся кнопки «Продолжить»/«Отмена». Авто-режим:
                    // агент сам прошёл все этапы — пауза не нужна.
                    if (workflowEnabled && workflowMode == WorkflowSettings.MODE_MANUAL &&
                        workflowStage != null && workflowStage != TaskStateStore.STAGE_DONE
                    ) {
                        val saved = taskStateStore?.setStageOutput(sessionId, workflowStage, finalText, await = true)
                        if (saved != null) {
                            send(
                                TaskStateChanged(
                                    saved.stage, saved.currentStep, saved.expectedAction, saved.paused,
                                    saved.plan, saved.implementation, saved.validation, saved.awaitConfirmation,
                                )
                            )
                            send(WorkflowPaused(workflowStage, finalText, true))
                        } else {
                            log.warn(
                                "session={} не удалось сохранить результат этапа «{}» — продолжаю без паузы",
                                sessionId, workflowStage,
                            )
                        }
                    }
                    return@flux
                }
            }
        } catch (e: CancellationException) {
            log.info("Run cancelled for session {}", sessionId)
            throw e
        } catch (e: LlmApiException) {
            // 4xx/5xx от LLM API (неверный ключ/модель/тело, сбой апстрима) — понятное
            // событие со статусом и сообщением апстрима, если оно было в теле ответа.
            // Запись запроса уже в очереди лога (запрос построен до HTTP-вызова) —
            // вычитываем, чтобы «Запрос в llm» ушёл в панель и при сбое API.
            drainLlmLogs()
            log.error("LLM API error (HTTP {}) for session {}: {}", e.status, sessionId, e.message)
            send(ErrorEvent(errorIdx++, "Ошибка LLM API (HTTP ${e.status}): ${e.message}"))
        } catch (e: TimeoutException) {
            // Таймаут ожидания ответа от LLM — понятная формулировка вместо внутреннего
            // Reactor-сообщения («Did not observe any item or terminal signal…»).
            drainLlmLogs()
            val msg = "Превышен таймаут ожидания ответа от LLM (${settings.timeoutSeconds()} с). Попробуйте ещё раз или увеличьте «Таймаут» в настройках."
            log.error("LLM timeout for session {}: {}", sessionId, msg)
            send(ErrorEvent(errorIdx++, msg))
        } catch (e: Exception) {
            // Сбой вызова LLM (в т.ч. на построении запроса) — запись «Запрос в llm»
            // могла остаться в очереди: вычитываем, чтобы она не потерялась.
            drainLlmLogs()
            log.error("Agent run failed for session {}: {}", sessionId, e.message)
            try {
                // Ошибка соединения с сервером LLM — явное «нет связи» с сутью ошибки;
                // любые прочие ошибки тоже упаковываются в error-событие, а НЕ в молчаливый
                // обрыв потока (иначе фронтенд видит сетевую ошибку «Failed to fetch»).
                send(ErrorEvent(errorIdx++, upstreamConnectionMessage(e) ?: "Ошибка агента: ${e.message}"))
            } catch (ex: Exception) {
                log.debug("Downstream closed while sending error event")
            }
        }
    }

    /**
     * Человекочитаемое описание сбоя связи с сервером LLM (соединение отклонено,
     * нерезолвится адрес, обрыв соединения), найденное в цепочке причин исключения.
     * null — ошибка НЕ похожа на сетевую (оставляем общую формулировку «Ошибка агента»).
     */
    private fun upstreamConnectionMessage(e: Throwable): String? {
        var cur: Throwable? = e
        while (cur != null) {
            when (cur) {
                is ConnectException -> return "Нет связи с сервером LLM (соединение отклонено): ${cur.message}"
                is UnknownHostException -> return "Нет связи с сервером LLM (адрес не резолвится): ${cur.message}"
            }
            val msg = cur.message.orEmpty()
            val lower = msg.lowercase()
            if (lower.contains("connection reset") ||
                lower.contains("timeout while retrieving") ||
                lower.contains("connect timed out") ||
                lower.contains("no route to host")
            ) {
                return "Нет связи с сервером LLM: $msg"
            }
            cur = cur.cause
        }
        return null
    }

    /**
     * Системный блок активного профиля пользователя («=== ПРОФИЛЬ ПОЛЬЗОВАТЕЛЯ ===»):
     * имя профиля + только НЕпустые поля (стиль, формат ответа, предпочтения,
     * ограничения), каждое с русской подписью. Блок подаётся моделью как system-сообщение
     * (после SYSTEM_PROMPT, до блоков памяти) — действует для всех стратегий контекста.
     */
    private fun buildProfileSystem(profile: Profile): String {
        val lines = mutableListOf("Профиль: ${profile.name}")
        if (!profile.position.isNullOrBlank()) lines += "Стиль: ${profile.position}"
        if (!profile.responseFormat.isNullOrBlank()) lines += "Формат ответа: ${profile.responseFormat}"
        if (!profile.preferences.isNullOrBlank()) lines += "Предпочтения: ${profile.preferences}"
        if (!profile.constraints.isNullOrBlank()) lines += "Ограничения: ${profile.constraints}"
        return "=== ПРОФИЛЬ ПОЛЬЗОВАТЕЛЯ ===\n" + lines.joinToString("\n")
    }

    /**
     * Системный блок инвариантов («=== ИНВАРИАНТЫ ===»): перечисление обязательных
     * ограничений проекта (категория — текст, если категория задана) и явная инструкция
     * модели предлагать только решения, которые их соблюдают, а при конфликте запроса
     * с инвариантом — отказываться и объяснять, какой именно инвариант нарушается.
     * Блок подаётся как system-сообщение (после памяти, до истории) — для всех стратегий.
     */
    private fun buildInvariantsSystem(invariants: List<Invariant>): String {
        val lines = invariants.mapIndexed { index, inv ->
            if (inv.category.isNullOrBlank()) "${index + 1}. ${inv.text}"
            else "${index + 1}. ${inv.category} — ${inv.text}"
        }
        return "=== ИНВАРИАНТЫ ===\n" +
            lines.joinToString("\n") + "\n\n" +
            "Эти инварианты обязательны. Предлагай только решения, которые их соблюдают. " +
            "Если запрос пользователя конфликтует с инвариантом — откажись и объясни, какой именно " +
            "инвариант нарушается и почему ты не можешь предложить такое решение."
    }

    /**
     * Исполнение инструмента task_state (Day-13): валидация этапа и перехода FSM,
     * upsert в TaskStateStore с реальным sessionId сессии. Инструмент НЕ меняет флаг
     * паузы: paused сохраняется прежним (первая запись создаётся с paused=false) —
     * паузу/продолжение делает только пользователь через REST/UI (PUT task-state).
     * Ошибки валидации — ToolResult(isError=true) с подсказкой допустимых значений,
     * чтобы модель сама скорректировала аргументы на следующей итерации.
     */
    /** Инструменты, видимые модели: при выключенном воркфлоу (workflow.enabled=false) инструмент
     *  состояния задачи (TaskStateTool) убирается, чтобы модель не предлагала workflow-действия,
     *  когда следование воркфлоу отключено пользователем. */
    private fun visibleToolNames(): List<String> {
        val workflowEnabled = workflowSettings?.isEnabled() ?: false
        return toolRegistry.names().sorted().filter { it != TaskStateTool.TOOL_NAME || workflowEnabled }
    }

    private fun visibleToolDefinitions(): List<ToolDefinition> {
        val workflowEnabled = workflowSettings?.isEnabled() ?: false
        return toolRegistry.definitions().filter { it.name != TaskStateTool.TOOL_NAME || workflowEnabled }
    }

    private fun handleTaskState(sessionId: String, args: Map<String, Any?>): ToolResult {
        val store = taskStateStore
            ?: return ToolResult("Хранилище состояния задачи не подключено (task_state недоступен)", true)
        val stage = (args["stage"] as? String)?.trim().orEmpty()
        if (stage.isEmpty()) {
            return ToolResult(
                "Аргумент 'stage' (string) обязателен: ${TaskStateStore.STAGES.sorted().joinToString(" | ")}",
                true,
            )
        }
        if (!TaskStateStore.isValidStage(stage)) {
            return ToolResult(
                "Неизвестный этап \"$stage\". Допустимые этапы: ${TaskStateStore.STAGES.sorted().joinToString(" | ")}",
                true,
            )
        }
        val existing = store.get(sessionId)
        // День-15: задача ВСЕГДА начинается с этапа planning — нельзя стартовать с
        // execution/validation/done (первое состояние жёстко фиксировано). Даже если
        // строка ещё нет (existing == null), недопустимый первый этап — ошибка.
        if (existing == null && stage != TaskStateStore.STAGE_PLANNING) {
            return ToolResult(TaskStateStore.firstStageErrorMessage(stage), true)
        }
        if (existing != null && existing.stage != stage && !TaskStateStore.canTransition(existing.stage, stage)) {
            return ToolResult(TaskStateStore.transitionErrorMessage(existing.stage, stage), true)
        }
        val currentStep = (args["current_step"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
        val expectedAction = (args["expected_action"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
        val saved = store.upsert(
            sessionId, stage, currentStep, expectedAction, existing?.paused ?: false,
            // Воркфлоу Day-14: при смене этапа инструментом поля результата предыдущих
            // этапов и флаг ожидания сохраняются (не обнуляются).
            existing?.plan, existing?.implementation, existing?.validation,
            existing?.awaitConfirmation ?: false,
        )
            ?: return ToolResult("Не удалось сохранить состояние задачи (сбой БД)", true)
        return ToolResult(
            "Состояние задачи сохранено: этап=${saved.stage}" +
                (saved.currentStep?.let { ", текущий шаг=$it" } ?: "") +
                (saved.expectedAction?.let { ", ожидаемое действие=$it" } ?: "") +
                ", пауза=${if (saved.paused) "да" else "нет"}.",
            false,
        )
    }

    /**
     * Системный блок состояния задачи («=== СОСТОЯНИЕ ЗАДАЧИ ===»): этап FSM, текущий
     * шаг и ожидаемое действие (только непустые); при паузе — явная команда не выполнять
     * шаги (кратко подтвердить паузу и ждать продолжения). Воркфлоу Day-14: результаты
     * пройденных этапов (plan/implementation/validation) подаются модели, чтобы она
     * продолжала с места без повторного объяснения. Инструкция обновлять состояние
     * инструментом task_state присутствует всегда.
     */
    private fun buildTaskStateSystem(state: TaskState): String {
        val lines = mutableListOf("Этап: ${state.stage}")
        if (!state.currentStep.isNullOrBlank()) lines += "Текущий шаг: ${state.currentStep}"
        if (!state.expectedAction.isNullOrBlank()) lines += "Ожидаемое действие: ${state.expectedAction}"
        if (state.paused) {
            lines +=
                "ЗАДАЧА НА ПАУЗЕ: не выполняй шаги задачи. Кратко подтверди паузу; когда пользователь попросит продолжить — продолжай с текущего шага без повторного объяснения задачи."
        }
        if (state.awaitConfirmation) {
            lines += "Воркфлоу: пользователь ещё не подтвердил переход к следующему этапу — заверши текущий результат и не начинай следующий этап."
        }
        if (!state.plan.isNullOrBlank()) lines += "План (готов):\n${state.plan}"
        if (!state.implementation.isNullOrBlank()) lines += "Выполнение (готово):\n${state.implementation}"
        if (!state.validation.isNullOrBlank()) lines += "Проверка (готово):\n${state.validation}"
        // Требование текущего этапа: на планировании — конкретика (шаги/файлы/порядок/крайние случаи),
        // на выполнении — следовать плану, на проверке — сверка с планом.
        val stageReq = workflowStageRequirement(state.stage)
        if (stageReq.isNotEmpty()) lines += stageReq
        lines += "После значимых продвижений обновляй состояние инструментом task_state (этап, текущий шаг, ожидаемое действие)."
        lines +=
            "ВАЖНО: если инструмент task_state вернул ошибку (переход недопустим / состояние не сохранено) — " +
                "переход НЕ выполнен, состояние задачи осталось прежним. Не утверждай, что этап изменён, " +
                "задача выполнена или закрыта. Передай пользователю текст " +
                "ошибки ДОСЛОВНО и объясни, какой переход допустим для текущего этапа и что нужно сделать."
        return "=== СОСТОЯНИЕ ЗАДАЧИ ===\n" + lines.joinToString("\n")
    }

    /**
     * Директива воркфлоу Day-14 («=== ВОРКФЛОУ ===»): ручной режим — агент выполняет
     * ТОЛЬКО текущий этап [stage] и отдаёт результат как финальный ответ, не переходя
     * к следующему без подтверждения пользователя; авто — проходит все этапы подряд
     * (обновляя состояние инструментом task_state) без паузы.
     */
    private fun buildWorkflowDirective(stage: String, mode: String): String {
        val label = workflowStageLabel(stage)
        // Общее правило для обоих режимов: строго линейный конвейер и честность при ошибке.
        val strictFlowRule =
            "Переходы СТРОГО ЛИНЕЙНЫЕ, перепрыгивание этапов и откат назад запрещены: " +
                "планирование → выполнение → проверка → готово; к этапу «готово» можно перейти " +
                "только после пройденной проверки (validation). " +
                "Если инструмент task_state вернул ошибку (переход недопустим) — переход НЕ выполнен, " +
                "задача не изменена: НЕ заявляй успех/закрытие/смену этапа, а сообщи пользователю текст " +
                "ошибки ДОСЛОВНО и объясни, какой переход допустим."
        return if (mode == WorkflowSettings.MODE_AUTO) {
            "=== ВОРКФЛОУ (авто) ===\n" +
                "Проходи все этапы подряд: планирование → выполнение → проверка → готово. " +
                "Каждый этап завершай, обновляя состояние задачи инструментом task_state, и переходи к следующему. " +
                "Пользователь подтверждения не ждёт — работай на результат.\n\n" +
                "$strictFlowRule\n\n" +
                workflowStageRequirement(stage)
        } else {
            "=== ВОРКФЛОУ (вручную) ===\n" +
                "Ты работаешь по шагам. Сейчас этап «$label» ($stage). " +
                "Выполни ТОЛЬКО этот этап и дай результат как финальный ответ. " +
                "Не переходи к следующему этапу — дождись подтверждения пользователя.\n\n" +
                "$strictFlowRule\n\n" +
                workflowStageRequirement(stage)
        }
    }

    /**
     * Этап-специфичные требования воркфлоу: план должен быть КОНКРЕТНЫМ рабочим планом
     * (шаги, файлы/функции, подход, порядок, крайние случаи), а не общей фразой; выполнение —
     * строго по плану; проверка — сверка результата с планом. Подставляется в директиву
     * воркфлоу (авто и ручной) и в системный блок состояния задачи.
     */
    private fun workflowStageRequirement(stage: String): String = when (stage) {
        TaskStateStore.STAGE_PLANNING ->
            "ПЛАНИРОВАНИЕ: составь КОНКРЕТНЫЙ рабочий план реализации, а НЕ общую фразу. " +
                "Перечисли шаги в порядке выполнения; какие файлы/модули/функции/фрагменты кода создашь и зачем; " +
                "какой подход и инструменты используешь; как обработаешь крайние случаи и ошибки " +
                "(пустые/невалидные входы, отсутствие файла, граничные значения). " +
                "План — это ТЗ для следующего этапа: по нему можно реализовать без переспрашивания."
        TaskStateStore.STAGE_EXECUTION ->
            "ВЫПОЛНЕНИЕ: реализуй строго по плану и в том порядке, что указан. " +
                "Создай/дополни нужные файлы и функции; результат — готовый рабочий код/структура."
        TaskStateStore.STAGE_VALIDATION ->
            "ПРОВЕРКА: проверь результат по плану. Пройди по каждому пункту плана: что работает, " +
                "что нет и что требует доработки; прогони крайние случаи и ошибки из плана."
        else -> ""
    }

    /** Человекочитаемая подпись этапа для директивы воркфлоу. */
    private fun workflowStageLabel(stage: String): String = when (stage) {
        TaskStateStore.STAGE_PLANNING -> "планирование"
        TaskStateStore.STAGE_EXECUTION -> "выполнение"
        TaskStateStore.STAGE_VALIDATION -> "проверка"
        TaskStateStore.STAGE_DONE -> "готово"
        else -> stage
    }

    /** Снимок промпта для события llm_request_started: точный список сообщений, ушедший в LLM. */
    private fun promptSnapshot(messages: List<LlmMessage>): List<Map<String, String>> =
        messages.map { m ->
            val content: String = m.content
                ?: m.toolCalls
                    ?.joinToString("; ") { tc -> tc.name ?: "unknown" }
                    ?.let { names -> "[вызов инструмента: $names]" }
                ?: ""
            mapOf("role" to m.role, "content" to content)
        }

    /**
     * Сборка промпта вызова резюмирования: при наличии прежнее резюме («Предыдущее
     * резюме»), сами сообщения с их настоящими ролями (user/assistant) и простой
     * запрос на сжатие ПОСЛЕДНИМ сообщением — так модель отвечает на запрос,
     * а не на последнее сообщение истории. Вынесена отдельно, чтобы тот же
     * промпт ушёл в снимок события context_summary_started.
     */
    private fun buildSummaryPrompt(foldable: List<StoredMessage>, previousSummary: String?): List<LlmMessage> {
        val prompt = mutableListOf<LlmMessage>()
        if (previousSummary != null) {
            prompt += LlmMessage("user", "Предыдущее резюме:\n$previousSummary")
        }
        foldable.forEach { prompt += LlmMessage(it.role, it.content) }
        prompt += LlmMessage("user", SUMMARY_REQUEST_PROMPT)
        return prompt
    }

    /**
     * Вызов LLM для резюмирования по готовому промпту (см. [buildSummaryPrompt]).
     * Инструменты не передаются: резюме — чисто текстовый ответ. Возвращает текст
     * резюме и usage (если провайдер прислал). Бросает [LlmSummaryException] при
     * ответе с ошибкой, вызовом инструмента или пустом тексте — вызывающий код
     * переводит это в fail-open. Вызов идёт через обёрнутый клиент [client]
     * (агентский run передаёт LlmCallLoggingClient — запись уходит в панель «Логи»).
     */
    private suspend fun summarize(
        client: LlmClient,
        prompt: List<LlmMessage>,
        settings: LlmSettings,
    ): SummaryResult {
        val text = StringBuilder()
        var usage: LlmUsage? = null
        var finishReason = "stop"
        val events = client.streamChat(prompt, emptyList(), settings).collectList().awaitSingle()
        for (e in events) {
            when (e) {
                is LlmEvent.ContentDelta -> text.append(e.delta)
                is LlmEvent.Finished -> {
                    finishReason = e.finishReason
                    usage = e.usage
                }
                is LlmEvent.ToolCallsComplete -> Unit
                is LlmEvent.ResponseAssembled -> Unit
            }
        }
        if (finishReason == "error" || finishReason == "tool_calls") {
            throw LlmSummaryException("LLM вернул finishReason=$finishReason вместо текстового резюме")
        }
        val summary = text.toString()
        if (summary.isBlank()) {
            throw LlmSummaryException("LLM вернул пустой ответ вместо резюме")
        }
        return SummaryResult(summary, usage)
    }

    /** Агрегированный результат вызова LLM-резюмирования. */
    private data class SummaryResult(val text: String, val usage: LlmUsage?)

    /**
     * Человекочитаемая причина сбоя вызова резюмирования — в той же стилистике, что
     * формулировки существующих error-событий основного цикла (LLM API / таймаут / связь).
     */
    private fun compressionFailureMessage(e: Exception): String = when (e) {
        is LlmApiException -> "LLM API (HTTP ${e.status}): ${e.message}"
        is TimeoutException -> "превышен таймаут ожидания ответа от LLM (${settings.timeoutSeconds()} с)"
        is LlmSummaryException -> e.message ?: "внутренняя ошибка"
        else -> upstreamConnectionMessage(e) ?: e.message ?: e.javaClass.simpleName
    }

    /**
     * Сборка промпта извлечения «липких фактов»: прежние известные факты (если есть,
     * user-сообщение «Текущие известные факты: …»), последние windowSize не-системных
     * сообщений с их реальными ролями и ПОСЛЕДНИМ user-сообщением — запрос выдачи
     * JSON-фактов (см. [FACTS_REQUEST_PROMPT]). Так модель отвечает на запрос фактов,
     * а не продолжает диалог по последнему сообщению окна.
     */
    private fun buildFactsPrompt(window: List<StoredMessage>, facts: Map<String, String>): List<LlmMessage> {
        val prompt = mutableListOf<LlmMessage>()
        if (facts.isNotEmpty()) {
            prompt += LlmMessage(
                "user",
                "Текущие известные факты:\n" + facts.entries.joinToString("\n") { "- ${it.key}: ${it.value}" } +
                    "\n(обнови этот список с учётом нового сообщения)",
            )
        }
        window.forEach { prompt += LlmMessage(it.role, it.content) }
        prompt += LlmMessage("user", FACTS_REQUEST_PROMPT)
        return prompt
    }

    /**
     * Вызов LLM для извлечения фактов по готовому промпту (см. [buildFactsPrompt]).
     * Инструменты не передаются. Возвращает LinkedHashMap в порядке, который вернула
     * модель (сохраняется при replaceAll). Принимаются ТОЛЬКО строковые значения; прочие
     * молча пропускаются. Бросает [LlmSummaryException] при finishReason error/tool_calls,
     * пустом ответе или не-JSON-ответе — вызывающий код переводит это в fail-open.
     */
    private suspend fun extractFacts(
        client: LlmClient,
        prompt: List<LlmMessage>,
        runSettings: LlmSettings,
    ): LinkedHashMap<String, String> {
        // Один ретрай на пустой/сбойный ответ: модель изредка отдаёт пустоту на короткие
        // служебные промпты — повторная попытка обычно успешна (fail-open остаётся крайним случаем).
        return try {
            extractFactsOnce(client, prompt, runSettings)
        } catch (e: LlmSummaryException) {
            extractFactsOnce(client, prompt, runSettings)
        }
    }

    private suspend fun extractFactsOnce(
        client: LlmClient,
        prompt: List<LlmMessage>,
        runSettings: LlmSettings,
    ): LinkedHashMap<String, String> {
        val text = StringBuilder()
        var finishReason = "stop"
        val events = client.streamChat(prompt, emptyList(), runSettings).collectList().awaitSingle()
        for (e in events) {
            when (e) {
                is LlmEvent.ContentDelta -> text.append(e.delta)
                is LlmEvent.Finished -> finishReason = e.finishReason
                is LlmEvent.ToolCallsComplete -> Unit
                is LlmEvent.ResponseAssembled -> Unit
            }
        }
        if (finishReason == "error" || finishReason == "tool_calls") {
            throw LlmSummaryException("LLM вернул finishReason=$finishReason вместо фактов")
        }
        if (text.isBlank()) {
            throw LlmSummaryException("LLM вернул пустой ответ вместо фактов")
        }
        val node = om.readTree(text.toString())
        if (node == null || !node.isObject) {
            throw LlmSummaryException("LLM вернул не-JSON-объект вместо фактов")
        }
        val parsed = LinkedHashMap<String, String>()
        node.fields().forEach { (key, value) ->
            // Принимаем только строковые значения — остальное молча пропускаем.
            if (value.isTextual) parsed[key] = value.asText()
        }
        return parsed
    }

    /**
     * Человекочитаемая причина сбоя вызова извлечения фактов — та же стилистика, что
     * [compressionFailureMessage] (LLM API / таймаут / связь / не-JSON / пустой ответ).
     */
    private fun factsFailureMessage(e: Exception): String = when (e) {
        is LlmApiException -> "LLM API (HTTP ${e.status}): ${e.message}"
        is TimeoutException -> "превышен таймаут ожидания ответа от LLM (${settings.timeoutSeconds()} с)"
        is LlmSummaryException -> e.message ?: "внутренняя ошибка"
        else -> upstreamConnectionMessage(e) ?: e.message ?: e.javaClass.simpleName
    }

    /**
     * Сборка промпта перезаписи вопроса под векторный поиск (Day-23 query rewrite):
     * вопрос пользователя и ПОСЛЕДНИМ user-сообщением — запрос поисковой формулировки
     * (см. [REWRITE_REQUEST_PROMPT]). Так модель отвечает на запрос перезаписи,
     * а не продолжает диалог по вопросу.
     */
    private fun buildRewritePrompt(ragQuery: String): List<LlmMessage> = listOf(
        LlmMessage("user", ragQuery),
        LlmMessage("user", REWRITE_REQUEST_PROMPT),
    )

    /**
     * Вызов LLM для перезаписи вопроса (по паттерну [extractFacts]: тот же обёрнутый
     * клиент, инструменты не передаются). Возвращает СЫРОЙ текст ответа модели;
     * очистка — в [sanitizeRewrittenQuery]. Бросает [LlmSummaryException] при
     * finishReason error/tool_calls или пустом ответе — вызывающий код переводит
     * это в фолбэк на исходный запрос (rewrite не ломает ответ).
     */
    private suspend fun rewriteQuery(
        client: LlmClient,
        prompt: List<LlmMessage>,
        runSettings: LlmSettings,
    ): String {
        val text = StringBuilder()
        var finishReason = "stop"
        val events = client.streamChat(prompt, emptyList(), runSettings).collectList().awaitSingle()
        for (e in events) {
            when (e) {
                is LlmEvent.ContentDelta -> text.append(e.delta)
                is LlmEvent.Finished -> finishReason = e.finishReason
                is LlmEvent.ToolCallsComplete -> Unit
                is LlmEvent.ResponseAssembled -> Unit
            }
        }
        if (finishReason == "error" || finishReason == "tool_calls") {
            throw LlmSummaryException("LLM вернул finishReason=$finishReason вместо поискового запроса")
        }
        if (text.isBlank()) {
            throw LlmSummaryException("LLM вернул пустой ответ вместо поискового запроса")
        }
        return text.toString()
    }

    /**
     * Очистка ответа перезаписи (Day-23): trim, снятие ОДНОЙ пары окружающих кавычек
     * («…» либо прямых), обрезка до [MAX_REWRITTEN_QUERY_CHARS] символов. null — после
     * очистки пусто: вызывающий код делает фолбэк на исходный запрос.
     */
    private fun sanitizeRewrittenQuery(raw: String): String? {
        var s = raw.trim()
        if (s.length >= 2) {
            val quotePairs = listOf('«' to '»', '"' to '"', '\'' to '\'')
            val pair = quotePairs.firstOrNull { it.first == s.first() && it.second == s.last() }
            if (pair != null) s = s.substring(1, s.length - 1).trim()
        }
        if (s.length > MAX_REWRITTEN_QUERY_CHARS) s = s.take(MAX_REWRITTEN_QUERY_CHARS)
        return s.takeIf { it.isNotBlank() }
    }

    private fun parseArgs(arguments: String?): Map<String, Any?> {
        if (arguments.isNullOrBlank()) return emptyMap()
        return try {
            val node = om.readTree(arguments)
            if (node == null || !node.isObject) {
                emptyMap()
            } else {
                node.fields().asSequence().associate { (k, v) -> k to om.convertValue(v, Any::class.java) }
            }
        } catch (e: Exception) {
            emptyMap()
        }
    }

    companion object {
        /** Системный промпт агентского цикла. */
        const val SYSTEM_PROMPT =
            "Ты — полезный ассистент. Отвечай кратко и по делу. " +
                "Используй доступные инструменты, когда это нужно для точного ответа " +
                "(арифметические вычисления, текущие дата и время)."

        /**
         * Простой запрос на сжатие истории — обычное user-сообщение, замыкающее промпт
         * резюмирования (без системного промпта). Ставится последним, чтобы модель
         * отвечала именно на него, а не продолжала диалог по последнему сообщению.
         */
        const val SUMMARY_REQUEST_PROMPT =
        "Сожми историю нашего диалога. Это служебное сообщение о сжатии — его запоминать и включать в резюме не нужно."

        /**
         * Простой запрос на извлечение «липких фактов» (strategy=sticky_facts) — обычное
         * user-сообщение, замыкающее промпт извлечения (без системного промпта). Ставится
         * последним, чтобы модель отвечала именно на него, а не продолжала диалог по
         * последнему сообщению окна. Ответ — ТОЛЬКО JSON-объект вида {"ключ": "значение"}.
         */
        const val FACTS_REQUEST_PROMPT =
        "Обнови список фактов о пользователе и диалоге (цель, ограничения, предпочтения, решения, договорённости). " +
            "Верни ТОЛЬКО JSON-объект вида {\"ключ\": \"значение\"} без пояснений и без markdown. " +
            "Это служебное сообщение — не отвечай на него как на часть диалога."

        /**
         * Запрос на перезапись вопроса под векторный поиск (Day-23 query rewrite) —
         * обычное user-сообщение, замыкающее промпт перезаписи (без системного промпта).
         * Ставится последним, чтобы модель отвечала именно на него, а не продолжала
         * диалог по вопросу пользователя. Ответ — ТОЛЬКО поисковый запрос одной строкой.
         */
        const val REWRITE_REQUEST_PROMPT =
        "Переформулируй вопрос пользователя для векторного поиска по базе знаний: " +
            "сохрани конкретные термины, названия моделей и числа, убери вежливость и лишние слова. " +
            "Верни ТОЛЬКО поисковый запрос одной строкой, без кавычек и пояснений. " +
            "Это служебное сообщение — не отвечай на него как на часть диалога."

        /** Лимит длины перезаписанного запроса после очистки (символы). */
        const val MAX_REWRITTEN_QUERY_CHARS = 300

        /** Префикс сообщения-резюме в сжатом контексте (system-роль). */
        const val SUMMARY_CONTEXT_PREFIX = "Резюме ранее: "

        /** Ключ активного профиля пользователя в app_settings (значение — id или "null"). */
        const val PROFILE_ACTIVE_KEY = "profile.active"

        /**
         * Метка продолжения воркфлоу Day-14: передаётся в runCore как `userMessage`,
         * когда user-сообщение НЕ добавляется в историю (appendUser=false), поэтому
         * служит только для логов и сборки промпта (стратегии с окном/резюме).
         */
        const val WORKFLOW_CONTINUE_MESSAGE = "Продолжение воркфлоу"

        /** Значение app_settings для «Без профиля» (активный профиль не выбран). */
        const val PROFILE_ACTIVE_NONE = "null"

        /**
         * Фактический подсчёт токенов в списке сообщений BPE-токенизатором o200k_base
         * (jtokkit) — тот же класс разметки, что у современных OpenAI-моделей, для других
         * провайдеров даёт значения, близкие к их usage. Сверх содержимого добавляется
         * ~4 токена на сообщение — роль и служебная разметка чат-формата.
         */
        private val contextEncoding: Encoding = Encodings.newDefaultEncodingRegistry()
            .getEncoding(EncodingType.O200K_BASE)

        fun estimateTokens(messages: List<LlmMessage>): Int =
            messages.sumOf { 4 + contextEncoding.countTokens(it.content ?: "") }
    }
}

/** Сбой вызова LLM-резюмирования (не текст/ошибка модели) — переводится в fail-open. */
class LlmSummaryException(message: String) : RuntimeException(message)
