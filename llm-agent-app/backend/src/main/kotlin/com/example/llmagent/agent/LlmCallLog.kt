package com.example.llmagent.agent

import com.example.llmagent.config.LlmSettings
import org.slf4j.LoggerFactory
import reactor.core.publisher.Flux

/**
 * Слушатель вызовов LLM (chat-модель и эмбеддинги): клиент вызывает [onLlmLog] на КАЖДЫЙ
 * HTTP-вызов — один раз при построении запроса (kind «Запрос в …», detail — фактическое
 * тело запроса) и один раз при получении ответа (kind «Ответ от …», detail — фактический
 * ответ). kind — подпись записи, detail — pretty JSON с фактическим содержимым; время
 * записи в текст НЕ вшивается — получатель рисует его сам из момента получения события
 * (в панели «Логи» это timestamp SSE-события).
 *
 * Внутри активного agent-run записи уходят в SSE (type="log", панель «Логи»); вне run —
 * фиксируются в серверный лог (slf4j INFO, см. [LlmCallLog.slf4jListener]).
 */
fun interface LlmCallListener {
    fun onLlmLog(kind: String, detail: String)
}

/** Словарь записей лога LLM-вызовов (kind) + предохранитель детализации (cap). */
object LlmCallLog {

    /**
     * Жёсткий предохранитель: JSON длиннее лимита режется с явной пометкой «…(+N симв.)».
     * Обычного капа НЕТ — полная детализация и есть суть требования.
     */
    const val MAX_CHARS = 200_000

    /** kind записей chat-модели (/v1/chat/completions). */
    const val KIND_REQUEST = "Запрос в llm"
    const val KIND_RESPONSE = "Ответ от llm"

    /** kind записей эмбеддинг-модели (/v1/embeddings, KbEmbeddingClient). */
    const val KIND_EMBEDDING_REQUEST = "Запрос в эмбеддинги"
    const val KIND_EMBEDDING_RESPONSE = "Ответ от эмбеддингов"

    /**
     * kind записей RAG-поиска по базам знаний (KbRagService.buildContextResult):
     * обращения к SQLite-хранилищу KB (KbRepository) и итог top-K поиска.
     * Ответ chunksOfBases (сотни чанков с векторами по 4000 чисел) НЕ логируется —
     * только запрос и метаданные отобранных топ-чанков.
     */
    const val KIND_DB_REQUEST = "Запрос в БД"
    const val KIND_DB_RESPONSE = "Ответ БД"
    const val KIND_SEARCH_RESULT = "Ответ поискового движка"

    /** Предохранитель: detail длиннее [MAX_CHARS] обрезается с явной пометкой. */
    fun cap(json: String): String =
        if (json.length <= MAX_CHARS) json
        else json.take(MAX_CHARS) + "…(+" + (json.length - MAX_CHARS) + " симв.)"

    private val log = LoggerFactory.getLogger(LlmCallLog::class.java)

    /**
     * Дефолтный слушатель: вызовы LLM ВНЕ активного agent-run (chat-модель и эмбеддинги)
     * фиксируются в серверный лог (slf4j INFO, kind + JSON). Ключи API не логируются —
     * передаётся только тело запроса, без заголовков.
     */
    fun slf4jListener(): LlmCallListener = LlmCallListener { kind, detail ->
        log.info("[LLM] {}\n{}", kind, detail)
    }
}

/**
 * Клиент-обёртка, логирующая ВСЕ вызовы chat-модели делегата — единая точка перехвата
 * фактических тел запроса/ответа. Запрос: делегат сам сериализует фактическое тело
 * HTTP-запроса в колбэке onRequestBody (pretty JSON) — он же уходит в слушатель c kind
 * «Запрос в llm». Ответ: делегат в конце стрима эмитит [LlmEvent.ResponseAssembled] с
 * собранным из стрима ответом в OpenAI-совместимом виде (choices/message/content/
 * tool_calls/finish_reason/usage — реальные значения, никакой реконструкции «по мотивам»)
 * c kind «Ответ от llm». Эмбеддинги (/v1/embeddings, KbEmbeddingClient) логируются
 * отдельно — своим слушателем (kind «Запрос в эмбеддинги» / «Ответ от эмбеддингов»).
 */
class LlmCallLoggingClient(
    private val delegate: LlmClient,
    private val listener: LlmCallListener,
) : LlmClient {

    override fun streamChat(
        messages: List<LlmMessage>,
        tools: List<ToolDefinition>,
        settings: LlmSettings,
    ): Flux<LlmEvent> = streamChat(messages, tools, settings) { }

    override fun streamChat(
        messages: List<LlmMessage>,
        tools: List<ToolDefinition>,
        settings: LlmSettings,
        onRequestBody: (String) -> Unit,
    ): Flux<LlmEvent> =
        delegate.streamChat(messages, tools, settings) { json ->
            listener.onLlmLog(LlmCallLog.KIND_REQUEST, LlmCallLog.cap(json))
            onRequestBody(json)
        }.map { e ->
            if (e is LlmEvent.ResponseAssembled) {
                listener.onLlmLog(LlmCallLog.KIND_RESPONSE, LlmCallLog.cap(e.body))
            }
            e
        }
}
