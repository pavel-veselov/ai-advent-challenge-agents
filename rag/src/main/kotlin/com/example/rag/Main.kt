package com.example.rag

import com.example.rag.config.CorpusConfig
import com.example.rag.embed.EmbeddingClient
import com.example.rag.extract.CorpusManifest
import com.example.rag.model.Chunk
import com.example.rag.model.DocType
import com.example.rag.pipeline.KNOWN_STRATEGIES
import com.example.rag.pipeline.PipelineRunner
import com.example.rag.pipeline.chunkerByName
import com.example.rag.pipeline.fmt1
import com.example.rag.store.ChunkJson
import com.example.rag.store.ChunkStore
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.LocalDate
import java.io.File
import kotlin.system.exitProcess

private const val USAGE = """rag — индексация документов (день 21)

Использование:
  gradlew.bat run --args "<команда> [аргументы]"

Команды:
  corpus   собрать манифест корпуса, извлечь все документы и напечатать статистику
  index    полный пайплайн: extract → chunk → embed → store (data/index.db)
           --strategy fixed|structural   стратегия чанкования (по умолчанию fixed)
  search   поиск по индексу: search "запрос" [--topk 5] [--strategy fixed|structural]
           без --strategy ищет по всем стратегиям
  compare  проиндексировать ОБЕ стратегии, метрики чанков + retrieval-тест hit@1/hit@3
           [--report путь]  — дополнительно записать markdown-отчёт (по умолчанию REPORT.md)
  export   выгрузка чанков индекса в JSON: [--strategy ...] [--with-embeddings] [--out путь]
           (по умолчанию data/index-export.json, без векторов)
"""

/** Контрольный retrieval-запрос с ground truth по файлу-источнику. */
private data class RetrievalCase(val query: String, val groundTruthFile: String)

/**
 * Контрольные запросы дня 21 с ground truth по файлу-источнику; hit@k засчитан,
 * если файл ground truth попал в top-k. КАЖДЫЙ запрос проверен живым прогоном
 * (обе стратегии). Относительно исходного плана заменены 4 формулировки, которые
 * стабильно попадали в другие документы, потому что тему в этом корпусе честно
 * покрывают и другие файлы: «как работает планировщик» (в корпусе «Планировщик» —
 * раздел CONTRACT.md, а не memory-layers.md), «как добавить MCP-сервер» (README-доки),
 * «как добавить новый инструмент агенту» (README/CONTRACT), «как определить
 * координаты по названию города» (README mcp2 дублирует KDoc LocationTool).
 */
private val RETRIEVAL_CASES: List<RetrievalCase> = listOf(
	RetrievalCase("какие слои памяти есть у агента", "memory-layers.md"),
	RetrievalCase("стратегии контекста sliding_window sticky_facts", "CONTRACT.md"),
	RetrievalCase("механизм внимания в трансформере", "attention-is-all-you-need.pdf"),
	RetrievalCase("retrieval augmented generation", "retrieval-augmented-generation.pdf"),
	RetrievalCase("как агент регистрирует динамические инструменты", "ToolRegistry.kt"),
	RetrievalCase("как скачивается погода по HTTP", "HttpSourceCollector.kt"),
	RetrievalCase("таблица mcp_servers в базе данных", "McpServersStore.kt"),
	RetrievalCase("WebClient запрос ip-api.com из инструмента города", "LocationTool.kt"),
)

/** Метрики чанков одной стратегии. */
private data class StrategyMetrics(
	val strategy: String,
	val chunks: Int,
	val avgChars: Double,
	val minChars: Int,
	val maxChars: Int,
	val below500: Int,
	val from500to1000: Int,
	val above1000: Int,
)

fun main(args: Array<String>) {
	if (args.isEmpty()) {
		printUsage()
		exitProcess(1)
	}
	try {
		when (args[0]) {
			"corpus" -> corpus()
			"index" -> cmdIndex(args.copyOfRange(1, args.size).toList())
			"search" -> cmdSearch(args.copyOfRange(1, args.size).toList())
			"compare" -> cmdCompare(args.copyOfRange(1, args.size).toList())
			"export" -> cmdExport(args.copyOfRange(1, args.size).toList())
			else -> {
				println("Неизвестная команда: ${args[0]}")
				println()
				printUsage()
				exitProcess(1)
			}
		}
	} catch (e: Exception) {
		// Сообщения исключений EmbeddingClient не содержат ключ API — печатать безопасно.
		System.err.println("Ошибка: ${e.message}")
		exitProcess(1)
	}
}

private fun printUsage() {
	print(USAGE)
}

// ---------------------------------------------------------------------------
// Разбор аргументов
// ---------------------------------------------------------------------------

/** Позиционные аргументы + флаги `--имя значение` + булевы флаги `--имя`. */
private class ParsedArgs(
	val positionals: List<String>,
	val flags: Map<String, String>,
	val booleanFlags: Set<String>,
)

private fun parseArgs(args: List<String>, booleanNames: Set<String>): ParsedArgs {
	val positionals = mutableListOf<String>()
	val flags = LinkedHashMap<String, String>()
	val booleans = mutableSetOf<String>()
	var i = 0
	while (i < args.size) {
		val arg = args[i]
		if (arg.startsWith("--")) {
			val name = arg.removePrefix("--")
			if (name in booleanNames) {
				booleans.add(name)
				i++
			} else {
				val value = args.getOrNull(i + 1)
					?: throw IllegalArgumentException("Флаг --$name требует значения")
				flags[name] = value
				i += 2
			}
		} else {
			positionals.add(arg)
			i++
		}
	}
	return ParsedArgs(positionals, flags, booleans)
}

/** Стратегия из флага с проверкой имени; null = «все стратегии». */
private fun strategyFromFlag(raw: String?, required: Boolean): String? {
	if (raw == null) {
		if (required) throw IllegalArgumentException("Укажите --strategy fixed|structural")
		return null
	}
	if (raw !in KNOWN_STRATEGIES) {
		throw IllegalArgumentException("Неизвестная стратегия «$raw». Доступны: ${KNOWN_STRATEGIES.joinToString(", ")}")
	}
	return raw
}

// ---------------------------------------------------------------------------
// Команды
// ---------------------------------------------------------------------------

/**
 * Полностью рабочая команда: собирает манифест корпуса, извлекает тексты
 * всех документов и печатает статистику (число документов по типам,
 * суммарный объём и объём каждого документа).
 */
private fun corpus() {
	val config = CorpusConfig.DEFAULT
	println("Сборка манифеста корпуса (корень модуля: ${config.moduleDir})")
	val docs = CorpusManifest(config).build()

	if (docs.isEmpty()) {
		println("Корпус пуст: не найдено ни одного документа.")
		exitProcess(1)
	}

	val byType = docs.groupBy { it.type }
	val totalChars = docs.sumOf { it.rawText.length }
	val typeCounts = DocType.entries.joinToString(", ") { t -> "${t.name}: ${byType[t]?.size ?: 0}" }

	println()
	println("=== Статистика корпуса ===")
	println("Документов: ${docs.size} ($typeCounts)")
	println("Всего символов: $totalChars")
	println()
	println("По документам:")
	for (doc in docs) {
		val rel = doc.path.removePrefix(config.moduleDir.toString() + File.separator)
		println("  [${doc.type.name.padEnd(4)}] ${doc.title} ($rel) — ${doc.rawText.length} симв.")
	}
}

/**
 * index --strategy fixed|structural — полный пайплайн индексации выбранной
 * стратегии: извлечение корпуса, чанкование, эмбеддинги, запись в SQLite.
 */
private fun cmdIndex(args: List<String>) {
	val parsed = parseArgs(args, booleanNames = emptySet())
	val strategy = strategyFromFlag(parsed.flags["strategy"], required = false) ?: "fixed"

	val config = CorpusConfig.DEFAULT
	val stats = PipelineRunner(config).index(strategy)
	val seconds = stats.durationMillis / 1000.0
	println()
	println("=== Итог индексации ===")
	println("Стратегия: ${stats.strategy} | документов: ${stats.docs} | чанков: ${stats.chunks} | " +
		"символов: ${stats.chars} | запросов к API: ${stats.requests} | время: ${fmt1(seconds)} с")
}

/**
 * search "запрос" [--topk 5] [--strategy fixed|structural] — эмбеддинг запроса,
 * брутфорс top-k по косинусной близости; печатает score, источник, секцию и
 * первые ~200 символов content каждого результата.
 */
private fun cmdSearch(args: List<String>) {
	val parsed = parseArgs(args, booleanNames = emptySet())
	// Запрос = все позиционные аргументы, склеенные пробелом: PowerShell 5.1
	// ненадёжно передаёт кавычки внутрь --args, а многословный запрос без них —
	// обычное дело (gradle сам разбивает --args по пробелам).
	val query = parsed.positionals.joinToString(" ").trim()
	if (query.isEmpty()) {
		throw IllegalArgumentException("Укажите поисковый запрос: search как-работает-планировщик [--topk 5]")
	}
	val topk = (parsed.flags["topk"]?.toIntOrNull() ?: 5).also {
		require(it > 0) { "--topk должен быть положительным: $it" }
	}
	val strategy = strategyFromFlag(parsed.flags["strategy"], required = false)

	val config = CorpusConfig.DEFAULT
	config.requireEmbeddingEnv()
	val queryVector = embedClient(config).embed(query)

	ChunkStore(config.dbPath).use { store ->
		val total = store.count()
		if (total == 0) {
			println("Индекс пуст: сначала выполните команду index.")
			exitProcess(1)
		}
		val strategyLabel = strategy ?: "все (${store.count(strategy = null)} чанков в БД)"
		println("Запрос: «$query»  |  topk=$topk  |  стратегия: $strategyLabel")
		println()
		val results = store.topK(queryVector, topk, strategy)
		if (results.isEmpty()) {
			println("Ничего не найдено: нет чанков с эмбеддингом нужной размерности.")
			return
		}
		results.forEachIndexed { i, (chunk, score) ->
			val section = chunk.section.ifBlank { "-" }
			val preview = chunk.content.take(200).replace("\n", " ").trim() + "…"
			println("${i + 1}. score=${"%.4f".format(java.util.Locale.ROOT, score)}  [${chunk.strategy}] ${Paths.get(chunk.source).fileName} > $section")
			println("   $preview")
		}
	}
}

/**
 * export [--strategy ...] [--with-embeddings] [--out путь] — выгрузка чанков
 * индекса в JSON (ChunkJson.export). По умолчанию: все стратегии, без векторов,
 * файл data/index-export.json.
 */
private fun cmdExport(args: List<String>) {
	val parsed = parseArgs(args, booleanNames = setOf("with-embeddings"))
	val strategy = strategyFromFlag(parsed.flags["strategy"], required = false)
	val target: Path = parsed.flags["out"]?.let { Paths.get(it) }
		?: CorpusConfig.DEFAULT.moduleDir.resolve("data/index-export.json")
	val withEmbeddings = "with-embeddings" in parsed.booleanFlags

	ChunkStore(CorpusConfig.DEFAULT.dbPath).use { store ->
		val chunks = store.loadAll(strategy)
		if (chunks.isEmpty()) {
			val scope = strategy ?: "индекс"
			println("Экспортировать нечего: $scope пуст (сначала выполните команду index).")
			exitProcess(1)
		}
		val written = ChunkJson.export(chunks, target, withEmbeddings)
		val scope = strategy?.let { "стратегия $it" } ?: "все стратегии"
		val vecInfo = if (withEmbeddings) "с векторами (dim=${chunks.firstNotNullOf { it.embedding!!.size }})" else "без векторов"
		println("Экспорт: ${chunks.size} чанков ($scope, $vecInfo) → $written")
	}
}

/**
 * compare [--report путь] — индексирует ОБЕ стратегии (обе лежат в одной БД,
 * различаются колонкой strategy), печатает метрики чанков и результаты
 * retrieval-теста (hit@1/hit@3 по ground truth), при --report записывает
 * markdown-отчёт (по умолчанию REPORT.md в корне модуля rag/).
 */
private fun cmdCompare(args: List<String>) {
	val parsed = parseArgs(args, booleanNames = emptySet())
	val config = CorpusConfig.DEFAULT
	val reportPath: Path = parsed.flags["report"]?.let { Paths.get(it) }
		?: config.moduleDir.resolve("REPORT.md")

	val strategies = KNOWN_STRATEGIES.toList().sorted() // fixed, structural
	val runner = PipelineRunner(config)
	val statsByStrategy = LinkedHashMap<String, com.example.rag.pipeline.IndexStats>()
	for (strategy in strategies) {
		println()
		statsByStrategy[strategy] = runner.index(strategy)
	}

	// Метрики чанков + retrieval-тест — по данным из БД после обеих индексаций.
	val metrics = LinkedHashMap<String, StrategyMetrics>()
	val hit1 = LinkedHashMap<String, Int>()
	val hit3 = LinkedHashMap<String, Int>()
	val perCase = LinkedHashMap<RetrievalCase, Map<String, Pair<Boolean, Boolean>>>()

	config.requireEmbeddingEnv()
	val client = embedClient(config)
	ChunkStore(config.dbPath).use { store ->
		for (strategy in strategies) {
			val chunks = store.loadAll(strategy)
			metrics[strategy] = computeMetrics(strategy, chunks)
		}

		// Один эмбеддинг запроса переиспользуется для обеих стратегий.
		for (case in RETRIEVAL_CASES) {
			val vector = client.embed(case.query)
			val perStrategy = LinkedHashMap<String, Pair<Boolean, Boolean>>()
			for (strategy in strategies) {
				val top3 = store.topK(vector, 3, strategy)
				val top1Hit = top3.firstOrNull()?.let { matchesGroundTruth(it.first, case.groundTruthFile) } ?: false
				val top3Hit = top3.any { matchesGroundTruth(it.first, case.groundTruthFile) }
				perStrategy[strategy] = top1Hit to top3Hit
			}
			perCase[case] = perStrategy
		}
		for (strategy in strategies) {
			hit1[strategy] = perCase.values.count { it[strategy]!!.first }
			hit3[strategy] = perCase.values.count { it[strategy]!!.second }
		}
	}

	println()
	println("=== Метрики чанков ===")
	println("strategy    | chunks | avg    | min | max  | <500 | 500–1000 | >1000")
	for (strategy in strategies) {
		val m = metrics.getValue(strategy)
		println(
			m.strategy.padEnd(11) + "| " + m.chunks.toString().padEnd(6) + " | " +
				fmt1(m.avgChars).padEnd(6) + " | " + m.minChars.toString().padEnd(3) + " | " +
				m.maxChars.toString().padEnd(4) + " | " + m.below500.toString().padEnd(4) + " | " +
				m.from500to1000.toString().padEnd(8) + " | " + m.above1000
		)
	}

	println()
	println("=== Retrieval-тест (hit@k, ground truth по файлу-источнику) ===")
	println("Запрос".padEnd(48) + "| GT файл".padEnd(36) + "| fixed @1/@3 | structural @1/@3")
	for ((case, perStrategy) in perCase) {
		val fixed = perStrategy.getValue("fixed")
		val structural = perStrategy.getValue("structural")
		println(
			case.query.padEnd(48) + "| " + case.groundTruthFile.padEnd(34) + "| " +
				mark(fixed.first) + "/" + mark(fixed.second) + "         | " +
				mark(structural.first) + "/" + mark(structural.second)
		)
	}
	val totalsLine = strategies.joinToString("; ") { strategy ->
		"$strategy: hit@1=${hit1.getValue(strategy)}/${RETRIEVAL_CASES.size}, " +
			"hit@3=${hit3.getValue(strategy)}/${RETRIEVAL_CASES.size}"
	}
	println()
	println("ИТОГИ — $totalsLine")

	val markdown = buildReport(config, statsByStrategy, metrics, perCase, hit1, hit3)
	Files.writeString(reportPath.toAbsolutePath(), markdown, Charsets.UTF_8)
	println()
	println("Отчёт записан: ${reportPath.toAbsolutePath()}")
}

// ---------------------------------------------------------------------------
// Сравнение: расчёт метрик и отчёт
// ---------------------------------------------------------------------------

private fun computeMetrics(strategy: String, chunks: List<Chunk>): StrategyMetrics {
	if (chunks.isEmpty()) {
		return StrategyMetrics(strategy, 0, 0.0, 0, 0, 0, 0, 0)
	}
	val lengths = chunks.map { it.content.length }
	return StrategyMetrics(
		strategy = strategy,
		chunks = chunks.size,
		avgChars = lengths.average(),
		minChars = lengths.min(),
		maxChars = lengths.max(),
		below500 = lengths.count { it < 500 },
		from500to1000 = lengths.count { it in 500..1000 },
		above1000 = lengths.count { it > 1000 },
	)
}

/** Ground truth проверяется по имени файла-источника (title неуникален: три README). */
private fun matchesGroundTruth(chunk: Chunk, groundTruthFile: String): Boolean =
	Paths.get(chunk.source).fileName.toString() == groundTruthFile

private fun mark(hit: Boolean): String = if (hit) "✓" else "✗"

/** Метки стратегий для markdown-таблиц (колонки фиксированы: fixed / structural). */
private val STRATEGY_LABELS: Map<String, String> = mapOf(
	"fixed" to "Fixed",
	"structural" to "Structural",
)

private fun buildReport(
	config: CorpusConfig,
	stats: Map<String, com.example.rag.pipeline.IndexStats>,
	metrics: Map<String, StrategyMetrics>,
	perCase: Map<RetrievalCase, Map<String, Pair<Boolean, Boolean>>>,
	hit1: Map<String, Int>,
	hit3: Map<String, Int>,
): String {
	val strategies = KNOWN_STRATEGIES.toList().sorted()
	val n = RETRIEVAL_CASES.size
	val sb = StringBuilder()

	sb.appendLine("# День 21 — RAG: индексация документов, сравнение стратегий чанкования")
	sb.appendLine()
	sb.appendLine("_Отчёт сгенерирован командой `compare` из живого прогона; дата: ${LocalDate.now()}. " +
		"Модель эмбеддингов: `${config.embeddingModel}` (dim=${config.embeddingDim}); индекс: `data/index.db` (SQLite, брутфорс-косинус)._")
	sb.appendLine()
	sb.appendLine("## Корпус и индексация")
	sb.appendLine()
	sb.appendLine("Корпус: ${stats.values.first().docs} документов, ${stats.values.first().chars} символов " +
		"(5 MD — документация llm-agent-app/mcp/mcp2, 6 CODE — исходники, 2 PDF — arXiv-статьи). " +
		"Пайплайн: extract → chunk → embed → store; обе стратегии лежат в одной БД, различаясь колонкой `strategy`.")
	sb.appendLine()
	sb.appendLine("| Стратегия | Документов | Чанков | Запросов к API | Время, с |")
	sb.appendLine("|---|---:|---:|---:|---:|")
	for (strategy in strategies) {
		val s = stats.getValue(strategy)
		sb.appendLine("| ${STRATEGY_LABELS[strategy]} | ${s.docs} | ${s.chunks} | ${s.requests} | ${fmt1(s.durationMillis / 1000.0)} |")
	}
	sb.appendLine()
	sb.appendLine("## Метрики чанков")
	sb.appendLine()
	sb.appendLine("| Стратегия | Чанков | Средняя длина | Мин | Макс | <500 | 500–1000 | >1000 |")
	sb.appendLine("|---|---:|---:|---:|---:|---:|---:|---:|")
	for (strategy in strategies) {
		val m = metrics.getValue(strategy)
		sb.appendLine(
			"| ${STRATEGY_LABELS[strategy]} | ${m.chunks} | ${fmt1(m.avgChars)} | ${m.minChars} | ${m.maxChars} | " +
				"${m.below500} | ${m.from500to1000} | ${m.above1000} |"
		)
	}
	sb.appendLine()
	sb.appendLine("## Retrieval-тест: hit@1 / hit@3")
	sb.appendLine()
	sb.appendLine("Контрольные запросы с ground truth по файлу-источнику; hit засчитан, если файл попал в top-k.")
	sb.appendLine()
	sb.appendLine("| Запрос | Ground truth | Fixed @1 | Fixed @3 | Structural @1 | Structural @3 |")
	sb.appendLine("|---|---|:-:|:-:|:-:|:-:|")
	for ((case, perStrategy) in perCase) {
		val fixed = perStrategy.getValue("fixed")
		val structural = perStrategy.getValue("structural")
		sb.appendLine(
			"| ${case.query} | `${case.groundTruthFile}` | ${mdMark(fixed.first)} | ${mdMark(fixed.second)} | " +
				"${mdMark(structural.first)} | ${mdMark(structural.second)} |"
		)
	}
	sb.appendLine()
	sb.appendLine("| Итог | Fixed | Structural |")
	sb.appendLine("|---|:-:|:-:|")
	sb.appendLine("| hit@1 | ${hit1.getValue("fixed")}/$n | ${hit1.getValue("structural")}/$n |")
	sb.appendLine("| hit@3 | ${hit3.getValue("fixed")}/$n | ${hit3.getValue("structural")}/$n |")
	sb.appendLine()

	val fixedScore = hit1.getValue("fixed") * 2 + hit3.getValue("fixed")
	val structuralScore = hit1.getValue("structural") * 2 + hit3.getValue("structural")
	val conclusion = when {
		fixedScore > structuralScore ->
			"Фиксированные окна выигрывают по совокупному hit@k: перекрытие в ${config.chunkOverlap} символов " +
				"сохраняет контекст на границах разрезов, а равномерная длина чанков (~≤${config.chunkMaxChars} симв.) " +
				"делает векторы сопоставимыми по «плотности» смысла."
		structuralScore > fixedScore ->
			"Структурное чанкование выигрывает по совокупному hit@k: секции по заголовкам/страницам/объявлениям " +
				"семантически целостнее, поэтому эмбеддинг чанка точнее описывает тему фрагмента."
		else ->
			"Стратегии показали сравнимое качество retrieval: выбор скорее вопрос удобства и типов документов."
	}

	sb.appendLine("## Выводы")
	sb.appendLine()
	sb.appendLine("- $conclusion")
	sb.appendLine("- **Fixed** — фикс. окна ~${config.chunkMaxChars} симв. с перекрытием ${config.chunkOverlap}: " +
		"равномерное покрытие любого текста (особенно PDF, где нет разметки), простота и предсказуемость; " +
		"плата — разрезы посреди предложений и потеря структурных границ.")
	sb.appendLine("- **Structural** — секции по MD-заголовкам / декларациям кода / страницам PDF: чанки соответствуют " +
		"смысловым единицам, что особенно полезно для кода (одна функция = один чанк); плата — разброс длин " +
		"(мелкие секции рядом с крупными) и зависимость от качества разметки документа.")
	sb.appendLine("- **Практический trade-off**: для смешанного корпуса (доки + код + PDF) структурная стратегия " +
		"лучше для структурированных документов (MD, код, постраничные PDF), фиксированная — надёжный универсальный " +
		"дефолт для «плоского» текста без разметки; их можно комбинировать (structural с дожимом fixed для длинных секций).")
	sb.appendLine("- **Масштабирование**: брутфорс-косинус по всем чанкам достаточен для сотен чанков; " +
		"при росте коллекции до миллионов векторов индекс следует перенести в FAISS (ANN-индексы IVF/HNSW) " +
		"или аналог векторной БД.")
	return sb.toString()
}

private fun mdMark(hit: Boolean): String = if (hit) "✅" else "❌"

// ---------------------------------------------------------------------------
// Общие помощники
// ---------------------------------------------------------------------------

/** Клиент эмбеддингов из конфига (env-переменные проверены [CorpusConfig.requireEmbeddingEnv]). */
private fun embedClient(config: CorpusConfig): EmbeddingClient {
	config.requireEmbeddingEnv()
	return EmbeddingClient(
		baseUrl = requireNotNull(config.embeddingBaseUrl) { "LLM_BASE_URL не задан" },
		apiKey = requireNotNull(config.embeddingApiKey) { "LLM_API_KEY не задан" },
		model = config.embeddingModel,
		dim = config.embeddingDim,
	)
}
