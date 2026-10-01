package com.example.llmagent.kb

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.OffsetDateTime
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.support.GeneratedKeyHolder
import org.springframework.stereotype.Component

/**
 * Персистентность баз знаний (Day-22): таблицы `knowledge_bases` / `kb_documents` /
 * `kb_chunks` (схема — в schema.sql; здесь — страховочное создание для старых файлов
 * БД, тот же приём, что McpServersStore).
 *
 * Каскадное удаление — ЯВНОЕ (delete KbRepository.delete: chunks → documents → kb):
 * SQLite enforcing FK (PRAGMA foreign_keys) ненадёжен через пул соединений, поэтому
 * поведение не завязано на FK CASCADE.
 *
 * Методы: сбои БД не роняют поток — возвращают null/false/пустой список + warn (fail-open,
 * как в остальных хранилищах); исключения форматирования эмбеддингов пробрасываются
 * (индексатор пометит базу failed).
 */
@Component
class KbRepository(private val jdbc: JdbcTemplate) {

    private val log = LoggerFactory.getLogger(KbRepository::class.java)

    init {
        createSchema()
        // Рестарт backend во время индексации: зависший статус indexing не имеет смысла
        // (индексер умер вместе с процессом) — сбрасываем в failed с осмысленной ошибкой.
        // Частично записанные чанки остаются — допустимо (см. план дня 22).
        resetInterruptedIndexing()
    }

    private fun createSchema() {
        jdbc.execute(
            """
            CREATE TABLE IF NOT EXISTS knowledge_bases (
                id              INTEGER PRIMARY KEY AUTOINCREMENT,
                name            TEXT NOT NULL,
                status          TEXT NOT NULL CHECK(status IN ('indexing','indexed','failed')),
                strategy        TEXT NOT NULL CHECK(strategy IN ('fixed','structural')),
                chunk_size      INTEGER,
                overlap         INTEGER,
                embedding_model TEXT NOT NULL,
                active          INTEGER NOT NULL DEFAULT 0,
                error           TEXT,
                processed_docs  INTEGER NOT NULL DEFAULT 0,
                total_docs      INTEGER NOT NULL DEFAULT 0,
                eta_seconds     INTEGER,
                created_at      TEXT NOT NULL DEFAULT (datetime('now'))
            )
            """.trimIndent()
        )
        jdbc.execute(
            """
            CREATE TABLE IF NOT EXISTS kb_documents (
                id         INTEGER PRIMARY KEY AUTOINCREMENT,
                kb_id      INTEGER NOT NULL REFERENCES knowledge_bases(id) ON DELETE CASCADE,
                filename   TEXT NOT NULL,
                size_bytes INTEGER NOT NULL,
                created_at TEXT NOT NULL DEFAULT (datetime('now'))
            )
            """.trimIndent()
        )
        jdbc.execute(
            """
            CREATE TABLE IF NOT EXISTS kb_chunks (
                id         INTEGER PRIMARY KEY AUTOINCREMENT,
                kb_id      INTEGER NOT NULL REFERENCES knowledge_bases(id) ON DELETE CASCADE,
                source     TEXT NOT NULL,
                title      TEXT NOT NULL,
                section    TEXT NOT NULL,
                strategy   TEXT NOT NULL,
                content    TEXT NOT NULL,
                embedding  BLOB,
                model      TEXT,
                created_at TEXT NOT NULL DEFAULT (datetime('now'))
            )
            """.trimIndent()
        )
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_kb_documents_kb_id ON kb_documents (kb_id)")
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_kb_chunks_kb_id ON kb_chunks (kb_id)")
    }

    /** Сброс зависших «indexing» → failed (вызывается при старте приложения). */
    fun resetInterruptedIndexing() {
        try {
            jdbc.update(
                "UPDATE knowledge_bases SET status = 'failed', error = ? WHERE status = 'indexing'",
                ERROR_INTERRUPTED,
            )
        } catch (e: Exception) {
            log.warn("KbRepository.resetInterruptedIndexing() не удался: {}", e.message)
        }
    }

    /** Создаёт базу (status=indexing, active=0); сбой БД — null. Возвращает строку из БД. */
    fun create(
        name: String,
        strategy: String,
        chunkSize: Int?,
        overlap: Int?,
        embeddingModel: String,
    ): KnowledgeBase? {
        val now = OffsetDateTime.now().toString()
        return try {
            val keyHolder = GeneratedKeyHolder()
            jdbc.update({ connection ->
                val ps = connection.prepareStatement(
                    """
                    INSERT INTO knowledge_bases
                        (name, status, strategy, chunk_size, overlap, embedding_model, active,
                         processed_docs, total_docs, created_at)
                    VALUES (?, 'indexing', ?, ?, ?, ?, 0, 0, 0, ?)
                    """.trimIndent(),
                    arrayOf("id"),
                )
                ps.setString(1, name)
                ps.setString(2, strategy)
                if (chunkSize == null) ps.setNull(3, java.sql.Types.INTEGER) else ps.setInt(3, chunkSize)
                if (overlap == null) ps.setNull(4, java.sql.Types.INTEGER) else ps.setInt(4, overlap)
                ps.setString(5, embeddingModel)
                ps.setString(6, now)
                ps
            }, keyHolder)
            keyHolder.key?.toLong()?.let { findById(it) }
        } catch (e: Exception) {
            log.warn("KbRepository.create({}) не удался: {}", name, e.message)
            null
        }
    }

    /** Все базы (ORDER BY id); сбой БД — пустой список. */
    fun list(): List<KnowledgeBase> {
        return try {
            jdbc.query("$SELECT_SQL ORDER BY kb.id", ::mapRow)
        } catch (e: Exception) {
            log.warn("KbRepository.list() не удался: {}", e.message)
            emptyList()
        }
    }

    /** Активные ПРОИНДЕКСИРОВАННЫЕ базы (для RAG-инъекции); сбой БД — пустой список. */
    fun listActiveIndexed(): List<KnowledgeBase> {
        return try {
            jdbc.query(SELECT_ACTIVE_INDEXED_SQL, ::mapRow)
        } catch (e: Exception) {
            log.warn("KbRepository.listActiveIndexed() не удался: {}", e.message)
            emptyList()
        }
    }

    /** Строка базы по id; нет записи или сбой БД — null. */
    fun findById(id: Long): KnowledgeBase? {
        return try {
            jdbc.query("$SELECT_SQL WHERE kb.id = ?", ::mapRow, id).firstOrNull()
        } catch (e: Exception) {
            log.warn("KbRepository.findById({}) не удался: {}", id, e.message)
            null
        }
    }

    /** Включает/отключает базу; нет записи — null (fail-open). */
    fun setActive(id: Long, active: Boolean): KnowledgeBase? {
        return try {
            if (jdbc.update("UPDATE knowledge_bases SET active = ? WHERE id = ?", if (active) 1 else 0, id) > 0) {
                findById(id)
            } else {
                null
            }
        } catch (e: Exception) {
            log.warn("KbRepository.setActive({}, {}) не удался: {}", id, active, e.message)
            null
        }
    }

    /**
     * Удаляет базу с ДОКУМЕНТАМИ и ЧАНКАМИ (явный каскад в одной транзакции — см. KDoc
     * класса). true — удалена (или её не было: операция идемпотентна для каталога файлов).
     */
    fun delete(id: Long): Boolean {
        return try {
            jdbc.update("DELETE FROM kb_chunks WHERE kb_id = ?", id)
            jdbc.update("DELETE FROM kb_documents WHERE kb_id = ?", id)
            jdbc.update("DELETE FROM knowledge_bases WHERE id = ?", id) > 0
        } catch (e: Exception) {
            log.warn("KbRepository.delete({}) не удался: {}", id, e.message)
            false
        }
    }

    /** Регистрирует документ базы (после сохранения файла на диск). */
    fun addDocument(kbId: Long, filename: String, sizeBytes: Long): Boolean {
        return try {
            jdbc.update(
                "INSERT INTO kb_documents (kb_id, filename, size_bytes, created_at) VALUES (?, ?, ?, ?)",
                kbId, filename, sizeBytes, OffsetDateTime.now().toString(),
            ) > 0
        } catch (e: Exception) {
            log.warn("KbRepository.addDocument({}, {}) не удался: {}", kbId, filename, e.message)
            false
        }
    }

    /** Документы базы (filename, size_bytes) в порядке вставки; сбой — пустой список. */
    fun documentsOf(kbId: Long): List<Pair<String, Long>> {
        return try {
            jdbc.query(
                "SELECT filename, size_bytes FROM kb_documents WHERE kb_id = ? ORDER BY id",
                { rs, _ -> rs.getString("filename") to rs.getLong("size_bytes") },
                kbId,
            )
        } catch (e: Exception) {
            log.warn("KbRepository.documentsOf({}) не удался: {}", kbId, e.message)
            emptyList()
        }
    }

    /** Добавляет чанки с эмбеддингами (bath INSERT). Бросает исключение при сбое —
     *  индексатор пометит базу failed. */
    fun addChunks(kbId: Long, chunks: List<KbChunkRow>) {
        jdbc.batchUpdate(
            "INSERT INTO kb_chunks (kb_id, source, title, section, strategy, content, embedding, model, created_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
            chunks.map { chunk ->
                arrayOf<Any?>(
                    kbId, chunk.source, chunk.title, chunk.section, chunk.strategy, chunk.content,
                    floatsToBlob(chunk.embedding), chunk.model, OffsetDateTime.now().toString(),
                )
            },
        )
    }

    /** Прогресс индексации (по ходу работы индексатора). */
    fun updateProgress(kbId: Long, processedDocs: Int, totalDocs: Int, etaSeconds: Long?) {
        try {
            jdbc.update(
                "UPDATE knowledge_bases SET processed_docs = ?, total_docs = ?, eta_seconds = ? WHERE id = ?",
                processedDocs, totalDocs, etaSeconds, kbId,
            )
        } catch (e: Exception) {
            log.warn("KbRepository.updateProgress({}) не удался: {}", kbId, e.message)
        }
    }

    /** Помечает базу проиндексированной (после успешного эмбеддинга ВСЕХ чанков). */
    fun setStatusIndexed(kbId: Long): Boolean {
        return try {
            jdbc.update(
                "UPDATE knowledge_bases SET status = 'indexed', error = NULL, eta_seconds = NULL WHERE id = ?",
                kbId,
            ) > 0
        } catch (e: Exception) {
            log.warn("KbRepository.setStatusIndexed({}) не удался: {}", kbId, e.message)
            false
        }
    }

    /** Помечает базу упавшей с человекочитаемой ошибкой (без ключей API). */
    fun setStatusFailed(kbId: Long, error: String): Boolean {
        return try {
            jdbc.update(
                "UPDATE knowledge_bases SET status = 'failed', error = ? WHERE id = ?",
                error.take(ERROR_MAX_LENGTH), kbId,
            ) > 0
        } catch (e: Exception) {
            log.warn("KbRepository.setStatusFailed({}) не удался: {}", kbId, e.message)
            false
        }
    }

    /** Чанки с эмбеддингами перечисленных баз (для поиска top-K); без эмбеддинга — пропускаются. */
    fun chunksOfBases(kbIds: List<Long>): List<KbChunkRow> {
        if (kbIds.isEmpty()) return emptyList()
        val sql = CHUNKS_SQL.replace("{placeholders}", kbIds.joinToString(",") { "?" })
        return try {
            jdbc.query(
                sql,
                { rs, _ ->
                    KbChunkRow(
                        kbId = rs.getLong("kb_id"),
                        chunkId = rs.getLong("chunk_id"),
                        kbName = rs.getString("kb_name"),
                        source = rs.getString("source"),
                        title = rs.getString("title"),
                        section = rs.getString("section"),
                        strategy = rs.getString("strategy"),
                        content = rs.getString("content"),
                        embedding = blobToFloats(rs.getBytes("embedding")),
                        model = rs.getString("model"),
                    )
                },
                *kbIds.toTypedArray(),
            )
        } catch (e: Exception) {
            log.warn("KbRepository.chunksOfBases({}) не удался: {}", kbIds, e.message)
            emptyList()
        }
    }

    private fun mapRow(rs: java.sql.ResultSet, rowNum: Int): KnowledgeBase {
        val status = rs.getString("status")
        return KnowledgeBase(
            id = rs.getLong("id"),
            name = rs.getString("name"),
            status = status,
            strategy = rs.getString("strategy"),
            chunkSize = rs.getObject("chunk_size")?.let { (it as Number).toInt() },
            overlap = rs.getObject("overlap")?.let { (it as Number).toInt() },
            embeddingModel = rs.getString("embedding_model"),
            active = rs.getInt("active") != 0,
            documentsCount = rs.getInt("documents_count"),
            chunksCount = if (status == "indexed") rs.getInt("chunks_count") else null,
            processedDocs = rs.getInt("processed_docs"),
            totalDocs = rs.getInt("total_docs"),
            etaSeconds = rs.getObject("eta_seconds")?.let { (it as Number).toLong() },
            error = rs.getString("error"),
            createdAt = rs.getString("created_at"),
        )
    }

    companion object {
        /** Ошибка зависших при рестарте индексаций (см. план дня 22). */
        const val ERROR_INTERRUPTED = "Прервано рестартом backend"

        private const val ERROR_MAX_LENGTH = 1000

        /** Базовый SELECT по `knowledge_bases` со счётчиками документов/чанков. */
        internal val SELECT_SQL = """
            SELECT kb.*,
                   (SELECT COUNT(*) FROM kb_documents d WHERE d.kb_id = kb.id) AS documents_count,
                   (SELECT COUNT(*) FROM kb_chunks c WHERE c.kb_id = kb.id) AS chunks_count
            FROM knowledge_bases kb
        """.trimIndent()

        /**
         * Фактический SQL [listActiveIndexed] — уходит в лог RAG-файла (kind «Запрос в БД»).
         */
        internal val SELECT_ACTIVE_INDEXED_SQL =
            SELECT_SQL + " WHERE kb.active = 1 AND kb.status = 'indexed' ORDER BY kb.id"

        /**
         * Шаблон фактического SQL [chunksOfBases]: `{placeholders}` заменяется на столько
         * `?`, сколько id баз в вызове. В лог RAG-файла уходит именно этот SQL (с подставленными
         * `?`) — сам ответ (~сотни чанков с векторами) в лог НЕ пишется.
         */
        internal const val CHUNKS_SQL = """
            SELECT c.id AS chunk_id, c.kb_id, b.name AS kb_name, c.source, c.title, c.section, c.strategy,
                   c.content, c.embedding, c.model
            FROM kb_chunks c JOIN knowledge_bases b ON b.id = c.kb_id
            WHERE c.kb_id IN ({placeholders}) AND c.embedding IS NOT NULL
            ORDER BY c.id
        """

        /** FloatArray → BLOB: байты IEEE-754 LITTLE_ENDIAN (формат дня 21, побитово точный roundtrip). */
        fun floatsToBlob(floats: FloatArray): ByteArray {
            val buffer = ByteBuffer.allocate(floats.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            buffer.asFloatBuffer().put(floats)
            return buffer.array()
        }

        /** BLOB → FloatArray: обратное преобразование к [floatsToBlob]. */
        fun blobToFloats(bytes: ByteArray): FloatArray {
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val floats = FloatArray(bytes.size / 4)
            buffer.asFloatBuffer().get(floats)
            return floats
        }
    }
}
