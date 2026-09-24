package com.example.llmagent.transport

import com.example.llmagent.mcp.McpSchedulerClient
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * REST для скачивания файлов, сохранённых MCP-пайплайном papkin-helper (Day-19).
 *
 * Браузер не имеет доступа к файловой системе сервера, поэтому содержимое файла перекачивается
 * через этот эндпоинт: он вызывает MCP-тул `read_file` и отдаёт байты с заголовком
 * `Content-Disposition: attachment` — фронтенд показывает кнопку «Скачать», браузер кладёт файл.
 *
 * Fail-open: MCP недоступен → 502; тул вернул ошибку/файл не найден → 404.
 */
@RestController
@RequestMapping("/api/mcp")
class McpFileController(
    private val mcpClient: McpSchedulerClient,
    private val om: ObjectMapper,
) {

    companion object {
        private val log = LoggerFactory.getLogger(McpFileController::class.java)
    }

    @GetMapping("/file/download")
    suspend fun download(@RequestParam("filename") filename: String): ResponseEntity<ByteArray> {
        val safe = filename.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        val result = mcpClient.callTool("read_file", mapOf("filename" to safe))
        if (result == null) {
            log.warn("mcp file download '{}' -> 502: MCP-сервер недоступен", safe)
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .contentType(MediaType.TEXT_PLAIN)
                .body("MCP-сервер недоступен".toByteArray())
        }
        if (result.isError) {
            log.warn("mcp file download '{}' -> 404: {}", safe, result.text)
            return ResponseEntity.notFound().build()
        }
        val node = try {
            om.readTree(result.text)
        } catch (e: Exception) {
            null
        }
        val content = node?.path("content")?.asText()
        if (content.isNullOrEmpty()) {
            log.warn("mcp file download '{}' -> 404: нет содержимого", safe)
            return ResponseEntity.notFound().build()
        }
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"$safe\"")
            .contentType(MediaType.APPLICATION_OCTET_STREAM)
            .body(content.toByteArray(Charsets.UTF_8))
    }
}
