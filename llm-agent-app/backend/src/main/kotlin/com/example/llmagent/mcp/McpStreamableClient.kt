package com.example.llmagent.mcp

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.time.Duration
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.reactive.function.client.WebClient
import reactor.core.publisher.Mono

/** Инструмент MCP-сервера: имя, описание и JSON-схема аргументов (inputSchema). */
data class McpToolInfo(
    val name: String,
    val description: String,
    val inputSchema: JsonNode,
)

/** Результат вызова инструмента MCP: текст (соединённые text-контента) + признак ошибки. */
data class McpCallResult(val text: String, val isError: Boolean)

/**
 * Упрощённый JSON-RPC 2.0 клиент MCP Streamable-HTTP (протокол 2024-11-05). Без MCP SDK —
 * hand-rolled поверх Spring WebClient (блокирующий WebClient уже есть в зависимостях).
 * Работает в корутинах через kotlinx-coroutines-reactor (awaitSingle / awaitSingleOrNull).
 *
 * Протокол: POST JSON-RPC на `url` (Content-Type: application/json, Accept: application/json,
 * text/event-stream); после мягкого рукопожатия (initialize + notifications/initialized)
 * сервер может вернуть заголовок `Mcp-Session-Id` — он отправляется в каждом следующем запросе.
 * Тело ответа может быть одиночным application/json ЛИБО text/event-stream с строкой `data:`.
 *
 * Все методы fail-open: ошибка возвращает false / пустой список / McpCallResult(isError=true)
 * и пишется в лог — сбой MCP-сервера не роняет агента.
 */
class McpStreamableClient(
    private val url: String,
    private val webClient: WebClient = WebClient.create(),
    private val om: ObjectMapper = ObjectMapper(),
) {

    private val log = LoggerFactory.getLogger(McpStreamableClient::class.java)

    @Volatile
    private var sessionId: String? = null

    @Volatile
    private var initialized = false

    /**
     * Мягкое рукопожатие: initialize -> (заголовок Mcp-Session-Id) -> notifications/initialized.
     * Fail-open: при любой ошибке — false + warn. Повторные вызовы безопасности ради
     * выполняются заново.
     */
    suspend fun initialize(): Boolean {
        return try {
            val req = om.createObjectNode()
                .put("jsonrpc", "2.0")
                .put("id", 1)
                .put("method", "initialize")
            val params = req.putObject("params")
            params.put("protocolVersion", "2024-11-05")
            params.set<JsonNode>("capabilities", om.createObjectNode())
            params.set<JsonNode>("clientInfo", om.createObjectNode().put("name", "llm-agent").put("version", "0.1.0"))

            val resp = post(req).awaitSingle()
            resp.headers.getFirst(MCP_SESSION_HEADER)?.let { sessionId = it }
            parseResponseBody(bodyString(resp.body)) // разбираем ответ (может быть JSON или SSE)

            // notifications/initialized ожидает 202/пустое тело; ошибка игнорируется.
            try {
                val notif = om.createObjectNode().put("jsonrpc", "2.0").put("method", "notifications/initialized")
                post(notif).awaitSingleOrNull()
            } catch (e: Exception) {
                log.warn("MCP {} notifications/initialized не удался (игнорирую): {}", url, e.message)
            }
            initialized = true
            true
        } catch (e: Exception) {
            log.warn("MCP {} initialize не удался: {}", url, e.message)
            false
        }
    }

    /** tools/list -> [McpToolInfo]; при ошибке сессии — одно пере-рукопожатие и повтор. */
    suspend fun listTools(): List<McpToolInfo> {
        if (!ensureInitialized()) return emptyList()
        var tools = tryListTools()
        if (tools == null) {
            sessionId = null
            initialized = false
            if (ensureInitialized()) tools = tryListTools()
        }
        return tools ?: emptyList()
    }

    /** tools/call -> McpCallResult; при ошибке сессии — одно пере-рукопожатие и повтор. */
    suspend fun callTool(name: String, args: Map<String, Any?>): McpCallResult {
        if (!ensureInitialized()) return McpCallResult("MCP-сервер не проинициализирован", true)
        var result = tryCallTool(name, args)
        if (result == null) {
            sessionId = null
            initialized = false
            if (ensureInitialized()) result = tryCallTool(name, args)
        }
        return result ?: McpCallResult("Не удалось вызвать инструмент MCP: $name", true)
    }

    private suspend fun ensureInitialized(): Boolean {
        if (initialized) return true
        return initialize()
    }

    private suspend fun tryListTools(): List<McpToolInfo>? {
        return try {
            val req = om.createObjectNode()
                .put("jsonrpc", "2.0")
                .put("id", 2)
                .put("method", "tools/list")
            req.set<JsonNode>("params", om.createObjectNode())
            val resp = post(req).awaitSingle()
            val root = parseResponseBody(bodyString(resp.body)) ?: return null
            if (root.path("error").isObject) return null // JSON-RPC ошибка — сигнал к пере-рукопожатию
            val toolsArr = root.path("result").path("tools")
            if (!toolsArr.isArray) return null
            toolsArr.mapNotNull { t ->
                val n = t.path("name").asText()
                if (n.isBlank()) {
                    null
                } else {
                    McpToolInfo(
                        name = n,
                        description = t.path("description").asText(""),
                        inputSchema = t.path("inputSchema"),
                    )
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun tryCallTool(name: String, args: Map<String, Any?>): McpCallResult? {
        return try {
            val req = om.createObjectNode()
                .put("jsonrpc", "2.0")
                .put("id", 3)
                .put("method", "tools/call")
            val params = req.putObject("params")
            params.put("name", name)
            params.set<JsonNode>("arguments", om.valueToTree(args))
            val resp = post(req).awaitSingle()
            val root = parseResponseBody(bodyString(resp.body)) ?: return null

            if (root.path("error").isObject) {
                val code = root.path("error").path("code").asInt(0)
                val message = root.path("error").path("message").asText("MCP error")
                // Сессионная ошибка (код -32001 «session not found») — пере-рукопожатие и повтор.
                if (code == SESSION_NOT_FOUND_CODE || message.contains("session", ignoreCase = true)) return null
                return McpCallResult(message, true)
            }

            val result = root.path("result")
            val isErr = result.path("isError").asBoolean(false)
            val text = extractText(result.path("content"))
            if (isErr) return McpCallResult(text.ifEmpty { "Ошибка инструмента MCP: $name" }, true)
            McpCallResult(text, false)
        } catch (e: Exception) {
            null
        }
    }

    /** Инструмент JSON-RPC запрос: POST с нужными заголовками, таймаут 30с, тело как байты. */
    private fun post(node: JsonNode): Mono<ResponseEntity<ByteArray>> {
        var spec = webClient.post()
            .uri(url)
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
        sessionId?.let { spec = spec.header(MCP_SESSION_HEADER, it) }
        return spec.bodyValue(node)
            .retrieve()
            .toEntity(ByteArray::class.java)
            .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
    }

    /**
     * Разбирает тело ответа: либо одиночный JSON, либо text/event-stream (строки
     * `event:`/`data:` — берём JSON из первой `data:`-строки). Сбой разбора — null.
     */
    private fun parseResponseBody(body: String): JsonNode? {
        if (body.isBlank()) return null
        val trimmed = body.trim()
        return try {
            if (trimmed.startsWith("event:") || trimmed.startsWith("data:")) {
                val dataLine = trimmed.lineSequence()
                    .map { it.trim() }
                    .firstOrNull { it.startsWith("data:") }
                    ?.removePrefix("data:")
                    ?.trim()
                if (dataLine.isNullOrBlank()) null else om.readTree(dataLine)
            } else {
                om.readTree(trimmed)
            }
        } catch (e: Exception) {
            null
        }
    }

    /** Соединяет text-контента результата tools/call в одну строку. */
    private fun extractText(content: JsonNode): String =
        if (content.isArray) {
            content.filter { it.path("type").asText() == "text" }
                .joinToString("") { it.path("text").asText("") }
        } else {
            content.path("text").asText("")
        }

    private fun bodyString(body: ByteArray?): String = body?.let { String(it, Charsets.UTF_8) } ?: ""

    private companion object {
        const val MCP_SESSION_HEADER = "Mcp-Session-Id"
        const val TIMEOUT_SECONDS = 30L
        const val SESSION_NOT_FOUND_CODE = -32001
    }
}
