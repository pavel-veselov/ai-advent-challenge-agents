package com.example.llmagent.kb

import java.nio.file.Paths
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.sqrt
import org.slf4j.LoggerFactory

/**
 * RAG-инъекция знаний в промпт агента (Day-22): по последнему сообщению пользователя
 * ищем top-K релевантных чанков по всем АКТИВНЫМ ПРОИНДЕКСИРОВАННЫМ базам и собираем
 * текстовый блок «### База знаний» — тот же механизм, что блоки памяти дня 11.
 *
 * Нет активных баз → null без единого сетевого вызова (нулевой оверхед, поведение
 * агента не меняется — требование плана). Любой сбой → warn + null (fail-open),
 * CancellationException пробрасывается (кооперативная отмена корутины агента).
 *
 * НЕ @Component: в конструкторе строка embeddingModel — бин собирается в AppConfig
 * вместе с KbEmbeddingClient (ключи — только из env через LlmProperties).
 */
class KbRagService(
    private val repo: KbRepository,
    private val embedder: KbEmbedder,
    private val embeddingModel: String,
) {

    private val log = LoggerFactory.getLogger(KbRagService::class.java)

    /**
     * Блок контекста для промпта или null. Ровно 1 вызов эмбеддинга запроса + поиск
     * по чанкам активных баз (cosine, top-[TOP_K]).
     */
    fun buildContextBlock(userMessage: String): String? {
        val bases = repo.listActiveIndexed()
        if (bases.isEmpty()) return null
        return try {
            val queryEmbedding = embedder.embed(userMessage, embeddingModel)
            val chunks = repo.chunksOfBases(bases.map { it.id })
            if (chunks.isEmpty()) return null

            val top = chunks.asSequence()
                .map { it to cosine(queryEmbedding, it.embedding) }
                .sortedByDescending { it.second }
                .take(TOP_K)
                .toList()

            val sb = StringBuilder(HEADER)
            var total = HEADER.length
            var used = 0
            for ((chunk, score) in top) {
                val entry = "[КБ ${chunk.kbName} | ${fileNameOf(chunk.source)}#${chunk.section}]\n${chunk.content}"
                if (total + entry.length > MAX_BLOCK_CHARS) break
                sb.append("\n\n").append(entry)
                total += entry.length + 2
                used++
            }
            if (used == 0) return null

            val block = sb.toString()
            log.info(
                "[KB RAG] инъекция: {} из {} чанков ({} баз), {} символов",
                used, chunks.size, bases.size, block.length,
            )
            block
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("[KB RAG] сбор блока базы знаний не удался: {}", e.message)
            null
        }
    }

    companion object {
        const val HEADER = "### База знаний"
        const val TOP_K = 4
        const val MAX_BLOCK_CHARS = 6000

        /** Косинусная близость; нулевые/несовместимые векторы → 0.0. */
        internal fun cosine(a: FloatArray, b: FloatArray): Double {
            if (a.isEmpty() || a.size != b.size) return 0.0
            var dot = 0.0
            var normA = 0.0
            var normB = 0.0
            for (i in a.indices) {
                dot += a[i] * b[i]
                normA += a[i] * a[i]
                normB += b[i] * b[i]
            }
            if (normA == 0.0 || normB == 0.0) return 0.0
            return dot / (sqrt(normA) * sqrt(normB))
        }

        /** Только имя файла из source (source в БД хранится как filename). */
        private fun fileNameOf(source: String): String =
            Paths.get(source).fileName?.toString() ?: source
    }
}
