package com.example.llmagent.kb

import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Асинхронный индексатор баз знаний (Day-22): daemon-потоки на ExecutorService,
 * по одной джобе на базу (ConcurrentHashMap). Ход работы: для каждого документа —
 * extract → chunk → эмбеддинги батчами → INSERT kb_chunks → updateProgress; после
 * ВСЕХ документов — status='indexed'. Любое исключение → status='failed' +
 * человекочитаемая ошибка (без ключей API).
 *
 * Отмена (DELETE базы): AtomicBoolean + interrupt; между документами джоба молча
 * завершается — DELETE сам вычищает БД и каталог файлов.
 */
@Component
class KbIndexer(
    private val repo: KbRepository,
    private val embedder: KbEmbedder,
) {

    private val log = LoggerFactory.getLogger(KbIndexer::class.java)

    private val pool: ExecutorService = Executors.newCachedThreadPool { runnable ->
        Thread(runnable).apply {
            isDaemon = true
            name = "kb-indexer-${sequenceNo.incrementAndGet()}"
        }
    }

    private val jobs = ConcurrentHashMap<Long, Job>()

    private class Job(val future: Future<*>, val cancelled: AtomicBoolean)

    /**
     * Запускает индексацию базы в фоне. [storageDir] — каталог с сохранёнными файлами
     * (data/kb/<kbId>/). Параметры чанкования берутся из строки БД (дефолты 100/50,
     * если пользователь не указал).
     */
    fun submit(kbId: Long, storageDir: Path) {
        val cancelled = AtomicBoolean(false)
        val future = pool.submit { run(kbId, storageDir, cancelled) }
        jobs[kbId] = Job(future, cancelled)
        log.info("[KB] индексация базы #{} запущена", kbId)
    }

    /** Отменяет идущую индексацию (если запущена): флаг + interrupt. Не бросает исключений. */
    fun cancel(kbId: Long) {
        jobs.remove(kbId)?.let { job ->
            job.cancelled.set(true)
            job.future.cancel(true)
            log.info("[KB] индексация базы #{} отменена", kbId)
        }
    }

    private fun run(kbId: Long, storageDir: Path, cancelled: AtomicBoolean) {
        try {
            val kb = repo.findById(kbId)
                ?: run { log.warn("[KB] база #{} исчезла до начала индексации", kbId); return }
            val documents = repo.documentsOf(kbId)
            if (documents.isEmpty()) {
                repo.setStatusFailed(kbId, "Нет документов для индексации")
                return
            }

            val chunker = when (kb.strategy) {
                "structural" -> KbStructuralChunker(maxChars = kb.chunkSize ?: DEFAULT_CHUNK_SIZE)
                else -> KbFixedSizeChunker(
                    maxChars = kb.chunkSize ?: DEFAULT_CHUNK_SIZE,
                    overlap = kb.overlap ?: DEFAULT_OVERLAP,
                )
            }

            repo.updateProgress(kbId, 0, documents.size, null)
            val startedAt = System.currentTimeMillis()
            var processed = 0

            for ((filename, _) in documents) {
                if (cancelled.get() || Thread.currentThread().isInterrupted) {
                    log.info("[KB] индексация базы #{} прервана (удаление базы)", kbId)
                    return
                }

                val file = storageDir.resolve(filename).toFile()
                if (!file.isFile) {
                    throw IllegalStateException("Файл не найден на диске: $filename")
                }

                val doc = KbExtractor.extract(file)
                val chunks = chunker.chunk(doc)
                if (chunks.isNotEmpty()) {
                    val embeddings = embedder.embedAll(chunks.map { it.content }, kb.embeddingModel)
                    val rows = chunks.zip(embeddings).map { (chunk, embedding) ->
                        KbChunkRow(
                            kbId = kbId,
                            kbName = kb.name,
                            source = filename,
                            title = chunk.title,
                            section = chunk.section,
                            strategy = chunk.strategy,
                            content = chunk.content,
                            embedding = embedding,
                            model = kb.embeddingModel,
                        )
                    }
                    repo.addChunks(kbId, rows)
                }

                processed++
                val elapsedSeconds = (System.currentTimeMillis() - startedAt) / 1000.0
                val remaining = documents.size - processed
                val etaSeconds = if (remaining > 0) {
                    Math.round(elapsedSeconds / processed * remaining)
                } else {
                    null
                }
                repo.updateProgress(kbId, processed, documents.size, etaSeconds)
            }

            repo.setStatusIndexed(kbId)
            log.info(
                "[KB] база #{} «{}» проиндексирована: {} документов, {} чанков",
                kbId, kb.name, documents.size, repo.chunksOfBases(listOf(kbId)).size,
            )
        } catch (e: Exception) {
            if (cancelled.get()) {
                log.info("[KB] индексация базы #{} отменена (исключение при остановке)", kbId)
                return
            }
            log.warn("[KB] индексация базы #{} упала: {}", kbId, e.message, e)
            repo.setStatusFailed(kbId, e.message ?: (e.javaClass.simpleName + " (см. логи backend)"))
        } finally {
            jobs.remove(kbId)
        }
    }

    companion object {
        /** Дефолты чанкования, если пользователь не указал (контракт POST /api/kb). */
        const val DEFAULT_CHUNK_SIZE = 100
        const val DEFAULT_OVERLAP = 50

        private val sequenceNo = java.util.concurrent.atomic.AtomicLong()
    }
}
