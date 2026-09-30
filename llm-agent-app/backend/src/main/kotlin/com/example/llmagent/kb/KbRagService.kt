package com.example.llmagent.kb

import com.example.llmagent.agent.LlmCallLog
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import java.nio.file.Paths
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.roundToLong
import kotlin.math.sqrt
import org.slf4j.LoggerFactory

/**
 * Один найденный RAG-поиском чанк — диагностическая запись для лог-трейса агента
 * (панель «Логи»): база, источник, секция, косинусная близость запроса и размер текста.
 */
data class KbChunkHit(
    val kbName: String,
    val source: String,
    val section: String,
    val score: Double,
    val contentChars: Int,
)

/**
 * Расширенный результат RAG-поиска: блок для промпта + диагностика.
 *
 * @param block           собранный блок «### База знаний»; null — блок не собран
 *                        (нет чанков / ни один не прошёл порог фильтра / не влез /
 *                        сбой — см. [error])
 * @param chunks          итоговые чанки воронки (sort → candidateK → порог → topK)
 *                        со score — даже если они не поместились в блок (полный трейс)
 * @param bases           имена АКТИВНЫХ ПРОИНДЕКСИРОВАННЫХ баз, по которым шёл поиск
 * @param candidateChunks всего чанков-кандидатов в активных базах (до воронки)
 * @param usedChunks      сколько чанков реально вошло в [block]
 * @param topK            лимит промпта из настроек на момент вызова ([KbRagSettings.topK])
 * @param maxBlockChars   лимит размера блока
 * @param embeddingModel  модель эмбеддингов
 * @param error           человекочитаемая причина сбоя (fail-open), null — сбоя не было
 */
data class KbRagResult(
    val block: String?,
    val chunks: List<KbChunkHit>,
    val bases: List<String>,
    val candidateChunks: Int,
    val usedChunks: Int,
    val topK: Int,
    val maxBlockChars: Int,
    val embeddingModel: String,
    val error: String? = null,
)

/**
 * RAG-инъекция знаний в промпт агента (Day-22): по последнему сообщению пользователя
 * ищем релевантные чанки по всем АКТИВНЫМ ПРОИНДЕКСИРОВАННЫМ базам и собираем
 * текстовый блок «### База знаний» — тот же механизм, что блоки памяти дня 11.
 *
 * Day-23: параметры отбора читаются из [KbRagSettingsService] при КАЖДОМ вызове
 * (изменения применяются без рестарта backend). Воронка: все чанки → sort desc →
 * top `candidateK` → порог `minScore` (только если `filterEnabled`) → top `topK`.
 * Если после порога не осталось ни одного чанка — блок не собирается
 * ([KbRagResult.block] = null, в логе поискового движка `passedFilter: 0`).
 * Дефолтные настройки = поведение дня 22 (фильтр выключен, topK=4).
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
    private val settingsService: KbRagSettingsService,
) {

    private val log = LoggerFactory.getLogger(KbRagService::class.java)

    /** Печатный JSON-сериализатор для detail-логов (тот же стиль, что KbEmbeddingClient). */
    private val mapper = ObjectMapper().registerKotlinModule()

    /**
     * Есть ли хотя бы одна АКТИВНАЯ ПРОИНДЕКСИРОВАННАЯ база знаний (Day-23 query rewrite):
     * гейт перед LLM-перезаписью запроса в AgentImpl — нет активных баз, поиск не
     * запустится вовсе, поэтому и вызывать LLM ради перезаписи не нужно.
     */
    fun hasActiveIndexed(): Boolean = repo.listActiveIndexed().isNotEmpty()

    /**
     * Блок контекста для промпта или null. Ровно 1 вызов эмбеддинга запроса + поиск
     * по чанкам активных баз (cosine, воронка candidateK → minScore → topK).
     */
    fun buildContextBlock(userMessage: String): String? = buildContextResult(userMessage)?.block

    /**
     * Расширенный вариант [buildContextBlock]: тот же поиск, но с диагностикой для
     * лог-трейса агента (панель «Логи»). null — ТОЛЬКО когда нет активных
     * проиндексированных баз (без сетевых вызовов); в остальных случаях возвращается
     * результат, где [KbRagResult.block] может быть null (нет чанков / всё отсечено
     * фильтром / не влезли / сбой — причина в [KbRagResult.error]), чтобы вызывавший
     * код отличал «нет баз» от «базы есть, но блок не собран».
     *
     * Настройки воронки ([KbRagSettings]) читаются из [KbRagSettingsService] при
     * КАЖДОМ вызове — изменения из меню настроек применяются без рестарта.
     *
     * Day-23 pre-wire query rewrite (Task 3): [rewrittenQuery] + [rewriteUsed].
     * При [rewriteUsed]=true и непустом [rewrittenQuery] эмбеддится именно он;
     * иначе (fallback / rewrite выключен) — исходное сообщение пользователя.
     * Оба значения всегда попадают в лог поиска (KIND_SEARCH_RESULT).
     *
     * [onLlmLog] — опциональный слушатель RAG-вызова: получает (kind, detail) —
     * («Запрос в эмбеддинги»/«Ответ от эмбеддингов», фактический JSON) + записи RAG-поиска:
     * «Запрос в БД» (каждое обращение к KbRepository, detail — {method, params, query}),
     * «Ответ БД» (только ответ listActiveIndexed — массив активных баз; ответ chunksOfBases
     * НЕ логируется — сотни чанков с векторами по 4000 чисел), «Ответ поискового движка»
     * (итог воронки: scored/candidates/passedFilter/usedChunks + настройки + отобранные
     * чанки с content, score до 4 знаков; вектора чанков в detail не попадают).
     * null (вызовы вне agent-run) — логирование на уровне клиента (slf4j/нет).
     */
    fun buildContextResult(
        userMessage: String,
        rewrittenQuery: String? = null,
        rewriteUsed: Boolean = false,
        onLlmLog: ((String, String) -> Unit)? = null,
    ): KbRagResult? {
        onLlmLog?.invoke(
            LlmCallLog.KIND_DB_REQUEST,
            LlmCallLog.cap(dbRequestJson("listActiveIndexed", emptyMap(), KbRepository.SELECT_ACTIVE_INDEXED_SQL)),
        )
        val bases = repo.listActiveIndexed()
        // Ответ БД логируем ВСЕГДА (даже пустой массив — до раннего return null):
        // в логе видно, что активных баз нет и поиск не запускался.
        onLlmLog?.invoke(
            LlmCallLog.KIND_DB_RESPONSE,
            LlmCallLog.cap(
                mapper.writerWithDefaultPrettyPrinter().writeValueAsString(
                    bases.map { base ->
                        mapOf(
                            "id" to base.id,
                            "name" to base.name,
                            "strategy" to base.strategy,
                            "status" to base.status,
                            "active" to base.active,
                            "embeddingModel" to base.embeddingModel,
                        )
                    },
                ),
            ),
        )
        if (bases.isEmpty()) return null
        val baseNames = bases.map { it.name }
        // Свежие настройки на каждый вызов ретривала: load() не бросает исключений
        // (порченые значения → дефолты поля), кросс-проверка topK<=candidateK на load
        // не выполняется — воронка ниже сама ограничивает итог размером кандидатов.
        val settings = settingsService.load()
        // Query rewrite (Day-23): переписанный запрос эмбеддится только если AgentImpl
        // подтвердил rewrite (rewriteUsed=true); иначе — исходное сообщение (день 22).
        val retrievalQuery = rewrittenQuery?.takeIf { rewriteUsed && it.isNotBlank() } ?: userMessage
        return try {
            val queryEmbedding = embedder.embed(retrievalQuery, embeddingModel, onLlmLog)
            onLlmLog?.invoke(
                LlmCallLog.KIND_DB_REQUEST,
                LlmCallLog.cap(
                    dbRequestJson(
                        method = "chunksOfBases",
                        params = mapOf("kbIds" to bases.map { it.id }),
                        query = KbRepository.CHUNKS_SQL.replace(
                            "{placeholders}",
                            bases.joinToString(",") { "?" },
                        ),
                    ),
                ),
            )
            val chunks = repo.chunksOfBases(bases.map { it.id })
            // Ответ chunksOfBases в лог НЕ пишется (см. KDoc) — только его запрос выше.
            if (chunks.isEmpty()) {
                return KbRagResult(
                    block = null, chunks = emptyList(), bases = baseNames,
                    candidateChunks = 0, usedChunks = 0, topK = settings.topK,
                    maxBlockChars = MAX_BLOCK_CHARS, embeddingModel = embeddingModel,
                )
            }

            // Воронка Day-23: все оценённые → sort desc → candidateK → порог → topK.
            val scored = chunks.asSequence()
                .map { it to cosine(queryEmbedding, it.embedding) }
                .sortedByDescending { it.second }
                .toList()
            val candidates = scored.take(settings.candidateK)
            val passed = if (settings.filterEnabled) {
                candidates.filter { it.second >= settings.minScore }
            } else {
                candidates
            }
            val top = passed.take(settings.topK)

            val hits = top.map { (chunk, score) ->
                KbChunkHit(
                    kbName = chunk.kbName,
                    source = fileNameOf(chunk.source),
                    section = chunk.section,
                    score = score,
                    contentChars = chunk.content.length,
                )
            }
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
            // Итог поиска логируем ПОСЛЕ воронки и подсчёта used: метаданные отобранных
            // чанков + текст целиком, без векторов (KbChunkHit-поля + content) + воронка
            // (scored → candidates → passedFilter → usedChunks) и параметры rewrite.
            onLlmLog?.invoke(
                LlmCallLog.KIND_SEARCH_RESULT,
                LlmCallLog.cap(
                    mapper.writerWithDefaultPrettyPrinter().writeValueAsString(
                        mapOf<String, Any?>(
                            "topK" to settings.topK,
                            "candidateChunks" to chunks.size,
                            "usedChunks" to used,
                            "embeddingModel" to embeddingModel,
                            "filterEnabled" to settings.filterEnabled,
                            "minScore" to settings.minScore,
                            "candidateK" to settings.candidateK,
                            "scored" to scored.size,
                            "candidates" to candidates.size,
                            "passedFilter" to passed.size,
                            "rewrittenQuery" to rewrittenQuery,
                            "rewriteUsed" to rewriteUsed,
                            "chunks" to top.map { (chunk, score) ->
                                mapOf(
                                    "kbName" to chunk.kbName,
                                    "source" to fileNameOf(chunk.source),
                                    "section" to chunk.section,
                                    "score" to round4(score),
                                    "contentChars" to chunk.content.length,
                                    "content" to chunk.content,
                                )
                            },
                        ),
                    ),
                ),
            )
            if (used == 0) {
                // Ни один чанк не прошёл порог (passedFilter=0) либо не влез в лимит
                // блока — блок не собирается, но результат возвращаем (с диагностикой).
                return KbRagResult(
                    block = null, chunks = hits, bases = baseNames,
                    candidateChunks = chunks.size, usedChunks = 0, topK = settings.topK,
                    maxBlockChars = MAX_BLOCK_CHARS, embeddingModel = embeddingModel,
                )
            }

            val block = sb.toString()
            log.info(
                "[KB RAG] инъекция: {} из {} чанков ({} баз), {} символов",
                used, chunks.size, bases.size, block.length,
            )
            KbRagResult(
                block = block, chunks = hits, bases = baseNames,
                candidateChunks = chunks.size, usedChunks = used, topK = settings.topK,
                maxBlockChars = MAX_BLOCK_CHARS, embeddingModel = embeddingModel,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // fail-open: сбой поиска (включая сбои логирования) не роняет агента.
            log.warn("[KB RAG] сбор блока базы знаний не удался: {}", e.message)
            KbRagResult(
                block = null, chunks = emptyList(), bases = baseNames,
                candidateChunks = 0, usedChunks = 0, topK = settings.topK,
                maxBlockChars = MAX_BLOCK_CHARS, embeddingModel = embeddingModel,
                error = e.message ?: e.javaClass.simpleName,
            )
        }
    }

    /** detail «Запрос в БД»: фактический запрос хранилища в виде {method, params, query}. */
    private fun dbRequestJson(method: String, params: Map<String, Any?>, query: String): String =
        mapper.writerWithDefaultPrettyPrinter().writeValueAsString(
            mapOf("method" to method, "params" to params, "query" to query),
        )

    companion object {
        const val HEADER = "### База знаний"
        const val MAX_BLOCK_CHARS = 6000

        /** Score в лог «Ответ поискового движка» округляем до 4 знаков. */
        private fun round4(score: Double): Double = (score * 10_000).roundToLong() / 10_000.0

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
