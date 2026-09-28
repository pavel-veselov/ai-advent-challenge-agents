package com.example.rag.store

import com.example.rag.model.Chunk
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/**
 * DTO для JSON-экспорта чанков. Отдельная сериализуемая модель — модель
 * [com.example.rag.model.Chunk] сознательно НЕ аннотируется @Serializable.
 */
@Serializable
data class ChunkDto(
	val chunkId: String,
	val source: String,
	val title: String,
	val section: String,
	val strategy: String,
	val content: String,
	/** Список компонент эмбеддинга; null — когда эмбеддинга нет или экспорт без векторов. */
	val embedding: List<Float>? = null,
	val model: String? = null,
)

/** Корневой объект JSON-файла экспорта индекса. */
@Serializable
data class ChunkExportDto(
	val count: Int,
	val withEmbeddings: Boolean,
	val chunks: List<ChunkDto>,
)

/**
 * JSON-экспорт индекса (команда export): список чанков → JSON-файл/строка.
 *
 * Формат файла: {"count": N, "withEmbeddings": bool, "chunks": [ ... ]}.
 * pretty print включён; null-поля (в т.ч. embedding при withEmbeddings = false)
 * не пишутся вовсе (explicitNulls = false) — «нет вектора» == «нет ключа».
 */
object ChunkJson {

	/** JSON-конфигурация экспорта: pretty print + пропуск null-полей. */
	val exportJson: Json = Json {
		prettyPrint = true
		explicitNulls = false
	}

	/**
	 * Маппинг [Chunk] → [ChunkDto]. При withEmbeddings = false вектор не попадает
	 * в DTO (embedding = null → ключ отсутствует в JSON); при true копируется как есть.
	 */
	fun toDto(chunk: Chunk, withEmbeddings: Boolean): ChunkDto = ChunkDto(
		chunkId = chunk.chunkId,
		source = chunk.source,
		title = chunk.title,
		section = chunk.section,
		strategy = chunk.strategy,
		content = chunk.content,
		embedding = if (withEmbeddings) chunk.embedding?.toList() else null,
		model = chunk.model,
	)

	/** Маппинг всего списка в корневой DTO экспорта. */
	fun toExportDto(chunks: List<Chunk>, withEmbeddings: Boolean): ChunkExportDto = ChunkExportDto(
		count = chunks.size,
		withEmbeddings = withEmbeddings,
		chunks = chunks.map { toDto(it, withEmbeddings) },
	)

	/** Сериализация списка чанков в JSON-строку (pretty print, UTF-8-совместимая). */
	fun toJson(chunks: List<Chunk>, withEmbeddings: Boolean = false): String =
		exportJson.encodeToString(ChunkExportDto.serializer(), toExportDto(chunks, withEmbeddings))

	/**
	 * Записывает JSON-экспорт в файл [target] (UTF-8, pretty print).
	 * Родительский каталог создаётся при необходимости (например rag/data/).
	 * @return путь к записанному файлу
	 */
	fun export(chunks: List<Chunk>, target: Path, withEmbeddings: Boolean = false): Path {
		val absolute = target.toAbsolutePath()
		absolute.parent?.let { Files.createDirectories(it) }
		Files.writeString(absolute, toJson(chunks, withEmbeddings), StandardCharsets.UTF_8)
		return absolute
	}
}
