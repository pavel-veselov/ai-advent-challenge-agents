package com.example.rag.store

import com.example.rag.model.Chunk
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.time.Instant

/**
 * SQLite-хранилище индексации: чанки + эмбеддинги (+ брутфорс-поиск по косинусной близости).
 *
 * ПАРАЛЛЕЛИЗМ: класс НЕ потокобезопасен. Один переиспользуемый [Connection] на инстанс,
 * транзакции через setAutoCommit(false)/commit — это осознанный выбор для однопоточного
 * CLI-приложения (команды index/compare/export выполняются последовательно в одном процессе).
 * При необходимости многопоточного доступа каждый поток должен открывать свой ChunkStore
 * (SQLite это позволяет — отдельное соединение на файл БД).
 *
 * Формат BLOB эмбеддинга: сырые байты float в порядке байтов LITTLE_ENDIAN
 * (IEEE-754, ровно 4 байта на компоненту) — побитово точный roundtrip туда-обратно.
 * NULL в embedding соответствует chunk.embedding == null (dim при этом пишется 0).
 *
 * created_at пишется в ISO-8601 UTC через [Instant.toString]
 * (выбор зафиксирован: например "2026-09-28T12:34:56.789Z").
 *
 * @param dbPath путь к файлу БД (родительские каталоги создаются автоматически,
 *               например data/index.db создаст каталог data/ при первом открытии)
 */
class ChunkStore(dbPath: Path) : Closeable {

	/** Единственное JDBC-соединение на инстанс (см. KDoc класса про однопоточность). */
	private val connection: Connection

	init {
		val absolute = dbPath.toAbsolutePath()
		absolute.parent?.let { Files.createDirectories(it) }
		connection = DriverManager.getConnection("jdbc:sqlite:$absolute")
		createSchema()
	}

	private fun createSchema() {
		connection.createStatement().use { st ->
			st.executeUpdate(
				"""
				CREATE TABLE IF NOT EXISTS chunks (
					id INTEGER PRIMARY KEY AUTOINCREMENT,
					chunk_id TEXT UNIQUE,
					source TEXT,
					title TEXT,
					section TEXT,
					strategy TEXT,
					content TEXT,
					embedding BLOB,
					dim INTEGER,
					model TEXT,
					created_at TEXT
				)
				""".trimIndent()
			)
		}
	}

	/**
	 * Атомарно заменяет все чанки данной стратегии: DELETE по strategy + INSERT списка
	 * в ОДНОЙ транзакции (rollback при любой ошибке, старые данные не теряются).
	 *
	 * created_at каждой строки = [Instant.now].toString() (ISO-8601 UTC) в момент вставки.
	 */
	fun replaceByStrategy(strategy: String, chunks: List<Chunk>) {
		connection.autoCommit = false
		try {
			connection.prepareStatement("DELETE FROM chunks WHERE strategy = ?").use { del ->
				del.setString(1, strategy)
				del.executeUpdate()
			}
			connection.prepareStatement(INSERT_SQL).use { ins ->
				val now = Instant.now().toString()
				for (chunk in chunks) {
					bindChunk(ins, chunk, strategy, now)
					ins.addBatch()
				}
				ins.executeBatch()
			}
			connection.commit()
		} catch (e: Exception) {
			try {
				connection.rollback()
			} catch (rollbackError: SQLException) {
				e.addSuppressed(rollbackError)
			}
			throw e
		} finally {
			connection.autoCommit = true
		}
	}

	private fun bindChunk(ps: PreparedStatement, chunk: Chunk, strategy: String, createdAt: String) {
		val embedding = chunk.embedding
		ps.setString(1, chunk.chunkId)
		ps.setString(2, chunk.source)
		ps.setString(3, chunk.title)
		ps.setString(4, chunk.section)
		ps.setString(5, strategy)
		ps.setString(6, chunk.content)
		if (embedding == null) {
			ps.setNull(7, java.sql.Types.BLOB)
			ps.setInt(8, 0)
		} else {
			ps.setBytes(7, floatsToBlob(embedding))
			ps.setInt(8, embedding.size)
		}
		ps.setString(9, chunk.model)
		ps.setString(10, createdAt)
	}

	/**
	 * Загружает чанки: все (strategy == null) либо только данной стратегии,
	 * в порядке возрастания внутреннего id (порядок вставки).
	 * BLOB → FloatArray (LITTLE_ENDIAN, побитово точный roundtrip);
	 * embedding == null, если в БД хранился NULL. Метаданные сохраняются полностью.
	 */
	fun loadAll(strategy: String? = null): List<Chunk> {
		val sql = if (strategy == null) {
			SELECT_SQL
		} else {
			"$SELECT_SQL WHERE strategy = ?"
		}
		connection.prepareStatement(sql).use { ps ->
			if (strategy != null) ps.setString(1, strategy)
			ps.executeQuery().use { rs ->
				val result = ArrayList<Chunk>()
				while (rs.next()) result.add(readChunk(rs))
				return result
			}
		}
	}

	private fun readChunk(rs: ResultSet): Chunk {
		val blob = rs.getBytes("embedding")
		return Chunk(
			chunkId = rs.getString("chunk_id"),
			source = rs.getString("source"),
			title = rs.getString("title"),
			section = rs.getString("section"),
			strategy = rs.getString("strategy"),
			content = rs.getString("content"),
			embedding = if (blob == null) null else blobToFloats(blob),
			model = rs.getString("model"),
		)
	}

	/**
	 * Брутфорс-поиск top-k по косинусной близости. Перебираются только строки
	 * с НЕ-null эмбеддингом той же размерности, что и запрос (остальные пропускаются).
	 * Сортировка: по убыванию похожести, при равенстве — стабильна по внутреннему id.
	 * @return пары (чанк, похожесть из [cosine]), не более k; k <= 0 → пустой список
	 */
	fun topK(query: FloatArray, k: Int, strategy: String? = null): List<Pair<Chunk, Double>> {
		if (k <= 0) return emptyList()
		val sql = buildString {
			append(SELECT_SQL)
			append(" WHERE embedding IS NOT NULL AND dim = ?")
			if (strategy != null) append(" AND strategy = ?")
			append(" ORDER BY id")
		}
		connection.prepareStatement(sql).use { ps ->
			ps.setInt(1, query.size)
			if (strategy != null) ps.setString(2, strategy)
			ps.executeQuery().use { rs ->
				data class Scored(val id: Long, val chunk: Chunk, val score: Double)
				val scored = ArrayList<Scored>()
				while (rs.next()) {
					val id = rs.getLong("id")
					val chunk = readChunk(rs)
					scored.add(Scored(id, chunk, cosine(query, chunk.embedding!!)))
				}
				return scored
					.sortedWith(
						compareByDescending<Scored> { it.score }
							.thenBy { it.id }
					)
					.take(k)
					.map { it.chunk to it.score }
			}
		}
	}

	/** Число чанков: всех (strategy == null) либо данной стратегии. */
	fun count(strategy: String? = null): Int {
		val sql = if (strategy == null) "SELECT COUNT(*) FROM chunks" else "SELECT COUNT(*) FROM chunks WHERE strategy = ?"
		connection.prepareStatement(sql).use { ps ->
			if (strategy != null) ps.setString(1, strategy)
			ps.executeQuery().use { rs ->
				rs.next()
				return rs.getInt(1)
			}
		}
	}

	/** Косинусная близость векторной пары. Ноль-вектор (любой) → 0.0 — без NaN/Infinity. */
	internal fun cosine(a: FloatArray, b: FloatArray): Double {
		require(a.size == b.size) { "размерности векторов не совпадают: ${a.size} vs ${b.size}" }
		var dot = 0.0
		var normA = 0.0
		var normB = 0.0
		for (i in a.indices) {
			dot += a[i].toDouble() * b[i].toDouble()
			normA += a[i].toDouble() * a[i].toDouble()
			normB += b[i].toDouble() * b[i].toDouble()
		}
		if (normA == 0.0 || normB == 0.0) return 0.0
		return dot / kotlin.math.sqrt(normA) / kotlin.math.sqrt(normB)
	}

	override fun close() {
		connection.close()
	}

	companion object {
		private const val INSERT_SQL =
			"INSERT INTO chunks (chunk_id, source, title, section, strategy, content, embedding, dim, model, created_at) " +
				"VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
		private const val SELECT_SQL =
			"SELECT id, chunk_id, source, title, section, strategy, content, embedding, dim, model, created_at FROM chunks"

		/** FloatArray → BLOB: байты IEEE-754 в LITTLE_ENDIAN, побитово точный roundtrip. */
		private fun floatsToBlob(floats: FloatArray): ByteArray {
			val buffer = ByteBuffer.allocate(floats.size * 4).order(ByteOrder.LITTLE_ENDIAN)
			buffer.asFloatBuffer().put(floats)
			return buffer.array()
		}

		/** BLOB → FloatArray: обратное преобразование к [floatsToBlob]. */
		private fun blobToFloats(bytes: ByteArray): FloatArray {
			val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
			val floats = FloatArray(bytes.size / 4)
			buffer.asFloatBuffer().get(floats)
			return floats
		}
	}
}
