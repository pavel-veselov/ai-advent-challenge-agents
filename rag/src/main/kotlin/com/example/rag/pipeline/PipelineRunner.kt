package com.example.rag.pipeline

import com.example.rag.chunk.Chunker
import com.example.rag.chunk.FixedSizeChunker
import com.example.rag.chunk.StructuralChunker
import com.example.rag.config.CorpusConfig
import com.example.rag.embed.MAX_BATCH_SIZE
import com.example.rag.embed.EmbeddingClient
import com.example.rag.extract.CorpusManifest
import com.example.rag.model.Chunk
import com.example.rag.model.Document
import com.example.rag.store.ChunkStore
import java.nio.file.Paths
import java.util.Locale
import kotlin.math.ceil

/** Формат числа с одной дробной цифрой, локале-независимый (точка, не запятая). */
internal fun fmt1(value: Double): String = String.format(Locale.ROOT, "%.1f", value)

/** Имена поддерживаемых стратегий чанкования. */
val KNOWN_STRATEGIES: Set<String> = setOf("fixed", "structural")

/**
 * Фабрика чанкеров по имени стратегии. Бросает [IllegalArgumentException]
 * с человекочитаемым сообщением для неизвестной стратегии.
 */
fun chunkerByName(strategy: String, config: CorpusConfig): Chunker = when (strategy) {
	"fixed" -> FixedSizeChunker(maxChars = config.chunkMaxChars, overlap = config.chunkOverlap)
	"structural" -> StructuralChunker(maxChars = config.chunkMaxChars)
	else -> throw IllegalArgumentException(
		"Неизвестная стратегия «$strategy». Доступны: ${KNOWN_STRATEGIES.joinToString(", ")}"
	)
}

/**
 * Итоговая статистика одного прогона индексации.
 *
 * @param strategy       имя стратегии чанкования
 * @param docs           число проиндексированных документов корпуса
 * @param chunks         число записанных в БД чанков
 * @param chars          суммарный объём исходных текстов документов (символов)
 * @param requests       число HTTP-запросов к API эмбеддингов (по батчам по 32)
 * @param durationMillis полное время пайплайна (извлечение + чанкование + эмбеддинги + запись)
 */
data class IndexStats(
	val strategy: String,
	val docs: Int,
	val chunks: Int,
	val chars: Int,
	val requests: Int,
	val durationMillis: Long,
)

/**
 * Полный пайплайн индексации: extract → chunk → embed → store.
 *
 * - корпус собирается [CorpusManifest] (Utf8TextExtractor / PdfExtractor);
 * - документ чанкуется выбранной стратегией ([chunkerByName]);
 * - содержимое чанков эмбеддится [EmbeddingClient] (батчи по 32 внутри);
 * - на чанк вешаются embedding + model, результат пишется в [ChunkStore]
 *   через [ChunkStore.replaceByStrategy] (старые чанки стратегии заменяются).
 *
 * Однопоточный CLI-пайплайн (ограничение ChunkStore — см. его KDoc).
 */
class PipelineRunner(private val config: CorpusConfig) {

	/**
	 * Проиндексировать корпус стратегией [strategy] ("fixed" | "structural").
	 * Прогресс печатается построчно (документ + эмбеддинг-батчи), чтобы живой
	 * прогон не выглядел зависшим.
	 */
	fun index(strategy: String): IndexStats {
		config.requireEmbeddingEnv()
		val chunker = chunkerByName(strategy, config)
		val startedAt = System.nanoTime()

		println("Стратегия: ${chunker.name()}  |  модель: ${config.embeddingModel} (dim=${config.embeddingDim})")
		val docs = CorpusManifest(config).build()
		if (docs.isEmpty()) {
			throw IllegalStateException("Корпус пуст: индексировать нечего.")
		}
		val totalChars = docs.sumOf { it.rawText.length }
		println("Документов: ${docs.size}, суммарно $totalChars символов. Чанкование...")

		val client = EmbeddingClient(
			baseUrl = requireNotNull(config.embeddingBaseUrl) { "LLM_BASE_URL не задан" },
			apiKey = requireNotNull(config.embeddingApiKey) { "LLM_API_KEY не задан" },
			model = config.embeddingModel,
			dim = config.embeddingDim,
		)

		val allChunks = mutableListOf<Chunk>()
		var requests = 0
		for ((docIndex, doc) in docs.withIndex()) {
			val chunks = chunker.chunk(doc)
			val label = shortSource(doc)
			if (chunks.isEmpty()) {
				println("  [${docIndex + 1}/${docs.size}] $label — 0 чанков (пустой документ)")
				continue
			}
			val docStartedAt = System.nanoTime()
			// Эмбеддим по документу: и прогресс виден, и естественный размер батчей.
			val vectors = client.embedAll(chunks.map { it.content })
			requests += ceil(chunks.size / MAX_BATCH_SIZE.toDouble()).toInt()
			val embedded = chunks.mapIndexed { i, chunk ->
				// ФИКС (Wave E): chunkId чанкеров построен только из имени файла,
				// а в корпусе три документа называются README.md → коллизия UNIQUE
				// в БД. Переквалифицируем id на уровне пайплайна (docIndex делает
				// его уникальным), сами чанкеры не трогаем.
				chunk.copy(
					chunkId = "${chunker.name()}:doc$docIndex:$i",
					embedding = vectors[i],
					model = config.embeddingModel,
				)
			}
			allChunks += embedded
			val docSeconds = (System.nanoTime() - docStartedAt) / 1_000_000_000.0
			println(
				"  [${docIndex + 1}/${docs.size}] $label — ${chunks.size} чанков, " +
					"эмбеддинги ok за ${fmt1(docSeconds)} с"
			)
		}

		println("Запись в БД: ${config.dbPath} (replaceByStrategy=${chunker.name()}, чанков ${allChunks.size})")
		ChunkStore(config.dbPath).use { store ->
			store.replaceByStrategy(chunker.name(), allChunks)
		}

		val durationMillis = (System.nanoTime() - startedAt) / 1_000_000
		println("Готово: стратегия «${chunker.name()}», чанков ${allChunks.size}, время ${fmt1(durationMillis / 1000.0)} с")
		return IndexStats(
			strategy = chunker.name(),
			docs = docs.size,
			chunks = allChunks.size,
			chars = totalChars,
			requests = requests,
			durationMillis = durationMillis,
		)
	}

	/** Короткая метка источника для прогресса: тип + имя файла. */
	private fun shortSource(doc: Document): String = "[${doc.type.name}] ${Paths.get(doc.path).fileName}"
}
