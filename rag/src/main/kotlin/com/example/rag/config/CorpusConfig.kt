package com.example.rag.config

import java.nio.file.Path
import java.nio.file.Paths

/**
 * Конфигурация корпуса и пайплайна индексации.
 *
 * Пути задаются относительно каталога модуля [moduleDir] (то есть `rag/`).
 *
 * @param moduleDir        корень модуля rag/ (по умолчанию — текущий рабочий каталог)
 * @param mdFiles          markdown-файлы корпуса (DocType.MD)
 * @param codeFiles        файлы исходников (DocType.CODE)
 * @param pdfDir           каталог с PDF-статьями (все *.pdf внутри)
 * @param embeddingBaseUrl базовый URL OpenAI-совместимого API эмбеддингов (env LLM_BASE_URL)
 * @param embeddingApiKey  ключ API (env LLM_API_KEY; никогда не печатать)
 * @param embeddingModel   модель эмбеддингов
 * @param embeddingDim     размерность вектора эмбеддинга
 * @param chunkMaxChars    максимальный размер чанка в символах (фиксированная стратегия)
 * @param chunkOverlap     перекрытие соседних чанков в символах
 * @param dbPath           путь к SQLite-индексу (data/index.db; каталог не создаётся до индексации)
 */
data class CorpusConfig(
	val moduleDir: Path,
	val mdFiles: List<String>,
	val codeFiles: List<String>,
	val pdfDir: Path,
	val embeddingBaseUrl: String?,
	val embeddingApiKey: String?,
	val embeddingModel: String,
	val embeddingDim: Int,
	val chunkMaxChars: Int,
	val chunkOverlap: Int,
	val dbPath: Path,
) {
	/**
	 * Проверяет наличие обязательных env-переменных для эмбеддингов.
	 * Вызывается на этапе индексации (команда index) — команда corpus их не требует.
	 */
	fun requireEmbeddingEnv() {
		val missing = buildList {
			if (embeddingBaseUrl.isNullOrBlank()) add("LLM_BASE_URL")
			if (embeddingApiKey.isNullOrBlank()) add("LLM_API_KEY")
		}
		require(missing.isEmpty()) {
			"Не заданы переменные окружения: ${missing.joinToString(" и ")}. " +
				"Они нужны для вычисления эмбеддингов (команда index). " +
				"Задайте их в среде запуска и повторите команду."
		}
	}

	companion object {
		/** Синглтон с дефолтным корпусом дня 21 «Индексация документов». */
		val DEFAULT: CorpusConfig by lazy {
			val moduleDir = Paths.get("").toAbsolutePath()
			CorpusConfig(
				moduleDir = moduleDir,
				mdFiles = listOf(
					"../llm-agent-app/README.md",
					"../llm-agent-app/CONTRACT.md",
					"../llm-agent-app/docs/memory-layers.md",
					"../mcp/README.md",
					"../mcp2/README.md",
				),
				codeFiles = listOf(
					"../llm-agent-app/backend/src/main/kotlin/com/example/llmagent/agent/AgentImpl.kt",
					"../llm-agent-app/backend/src/main/kotlin/com/example/llmagent/agent/McpServersStore.kt",
					"../llm-agent-app/backend/src/main/kotlin/com/example/llmagent/transport/McpServersController.kt",
					"../llm-agent-app/backend/src/main/kotlin/com/example/llmagent/agent/ToolRegistry.kt",
					"../mcp/src/main/kotlin/com/example/mcpserver/collector/HttpSourceCollector.kt",
					"../mcp2/src/main/kotlin/com/example/mcp2/tools/LocationTool.kt",
				),
				pdfDir = moduleDir.resolve("corpus/pdf"),
				embeddingBaseUrl = System.getenv("LLM_BASE_URL"),
				embeddingApiKey = System.getenv("LLM_API_KEY"),
				embeddingModel = "qwen3-vl-embedding-8b",
				embeddingDim = 4096,
				chunkMaxChars = 1000,
				chunkOverlap = 200,
				dbPath = moduleDir.resolve("data/index.db"),
			)
		}
	}
}
