package com.example.llmagent.agent

import com.example.llmagent.config.AgentProperties
import com.example.llmagent.config.LlmSettings
import com.example.llmagent.config.SessionLlmSettingsProvider
import com.fasterxml.jackson.databind.ObjectMapper
import com.knuddels.jtokkit.Encodings
import com.knuddels.jtokkit.api.EncodingType
import com.knuddels.jtokkit.api.Encoding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.flux
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
) : Agent {

    private val log = LoggerFactory.getLogger(AgentImpl::class.java)

    override fun run(sessionId: String, userMessage: String): Flux<AgentEvent> = flux {
        sessionStore.append(sessionId, "user", userMessage)
        val toolCounters = mutableMapOf<String, Int>()
        var iteration = 0
        var errorIdx = 0

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
            "tools" to toolRegistry.names().sorted(),
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
                    val result = summarize(summaryPrompt, runSettings)
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
                val updated = extractFacts(factsPrompt, runSettings)
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
                sessionStore.getStored(sessionId)
                    .filter { it.role != "system" }
                    .takeLast(strategyWindowSize)
                    .forEach { m -> messages.add(LlmMessage(m.role, m.content)) }
            }
            // Цепочка активной ветки (strategy=branching): корень → … → вопрос (после append
            // вопрос — голова активной ветки); системные заметки фильтруются, порядок
            // хронологический (root→head уже в getBranchChain).
            contextStrategy == SessionContextStore.STRATEGY_BRANCHING && branchStore != null -> {
                val branch = branchStore.effectiveBranch(sessionId)
                val branchMessages = branch?.headMessageId
                    ?.let { sessionStore.getBranchChain(sessionId, it) }
                    ?: emptyList()
                branchMessages
                    .filter { it.role != "system" }
                    .forEach { m -> messages.add(LlmMessage(m.role, m.content)) }
            }
            // Вся история целиком (strategy=none и любой непокрытый выше случай).
            else -> {
                sessionStore.getStored(sessionId)
                    .filter { it.role != "system" }
                    .forEach { m -> messages.add(LlmMessage(m.role, m.content)) }
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

                // Локальных оценок до отправки в LLM нет: переполнение контекста выявляет сам
                // апстрим (обычно 400), его ответ пробрасывается дословно через error-событие.
                send(LlmRequestStarted(iteration, promptSnapshot(messages)))
                val text = StringBuilder()
                var toolCalls: List<LlmToolCall> = emptyList()
                var finishReason = "stop"
                var usage: LlmUsage? = null

                val events = llmClient.streamChat(messages, toolRegistry.definitions(), runSettings)
                    .collectList()
                    .awaitSingle()
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
                    }
                }
                val costUsd = usage?.let {
                    (it.inputTokens * settings.priceInputPer1M() + it.outputTokens * settings.priceOutputPer1M()) /
                        1_000_000.0
                }
                send(LlmResponseFinished(iteration, finishReason, usage, costUsd = costUsd))

                if (finishReason == "tool_calls" && toolCalls.isNotEmpty()) {
                    messages.add(LlmMessage("assistant", text.toString().ifEmpty { null }, toolCalls = toolCalls))
                    for (tc in toolCalls) {
                        val name = tc.name ?: "unknown"
                        val idx = toolCounters[name] ?: 0
                        toolCounters[name] = idx + 1
                        val args = parseArgs(tc.arguments)
                        send(ToolCallStarted(name, idx, args))

                        val tool = toolRegistry.get(name)
                        val result = if (tool == null) {
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
                        messages.add(LlmMessage("tool", result.result, toolCallId = tc.id))
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
                    send(AgentFinished(finalText))
                    return@flux
                }
            }
        } catch (e: CancellationException) {
            log.info("Run cancelled for session {}", sessionId)
            throw e
        } catch (e: LlmApiException) {
            // 4xx/5xx от LLM API (неверный ключ/модель/тело, сбой апстрима) — понятное
            // событие со статусом и сообщением апстрима, если оно было в теле ответа.
            log.error("LLM API error (HTTP {}) for session {}: {}", e.status, sessionId, e.message)
            send(ErrorEvent(errorIdx++, "Ошибка LLM API (HTTP ${e.status}): ${e.message}"))
        } catch (e: TimeoutException) {
            // Таймаут ожидания ответа от LLM — понятная формулировка вместо внутреннего
            // Reactor-сообщения («Did not observe any item or terminal signal…»).
            val msg = "Превышен таймаут ожидания ответа от LLM (${settings.timeoutSeconds()} с). Попробуйте ещё раз или увеличьте «Таймаут» в настройках."
            log.error("LLM timeout for session {}: {}", sessionId, msg)
            send(ErrorEvent(errorIdx++, msg))
        } catch (e: Exception) {
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
     * переводит это в fail-open.
     */
    private suspend fun summarize(
        prompt: List<LlmMessage>,
        settings: LlmSettings,
    ): SummaryResult {
        val text = StringBuilder()
        var usage: LlmUsage? = null
        var finishReason = "stop"
        val events = llmClient.streamChat(prompt, emptyList(), settings).collectList().awaitSingle()
        for (e in events) {
            when (e) {
                is LlmEvent.ContentDelta -> text.append(e.delta)
                is LlmEvent.Finished -> {
                    finishReason = e.finishReason
                    usage = e.usage
                }
                is LlmEvent.ToolCallsComplete -> Unit
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
    private suspend fun extractFacts(prompt: List<LlmMessage>, runSettings: LlmSettings): LinkedHashMap<String, String> {
        // Один ретрай на пустой/сбойный ответ: модель изредка отдаёт пустоту на короткие
        // служебные промпты — повторная попытка обычно успешна (fail-open остаётся крайним случаем).
        return try {
            extractFactsOnce(prompt, runSettings)
        } catch (e: LlmSummaryException) {
            extractFactsOnce(prompt, runSettings)
        }
    }

    private suspend fun extractFactsOnce(prompt: List<LlmMessage>, runSettings: LlmSettings): LinkedHashMap<String, String> {
        val text = StringBuilder()
        var finishReason = "stop"
        val events = llmClient.streamChat(prompt, emptyList(), runSettings).collectList().awaitSingle()
        for (e in events) {
            when (e) {
                is LlmEvent.ContentDelta -> text.append(e.delta)
                is LlmEvent.Finished -> finishReason = e.finishReason
                is LlmEvent.ToolCallsComplete -> Unit
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

        /** Префикс сообщения-резюме в сжатом контексте (system-роль). */
        const val SUMMARY_CONTEXT_PREFIX = "Резюме ранее: "

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
