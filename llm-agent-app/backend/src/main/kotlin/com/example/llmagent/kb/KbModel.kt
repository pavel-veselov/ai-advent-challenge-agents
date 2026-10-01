package com.example.llmagent.kb

/**
 * База знаний (Day-22, RAG в агенте) — строка таблицы `knowledge_bases`.
 *
 * @param status         жизненный цикл индексации: indexing | indexed | failed
 * @param chunkSize      размер чанка в символах (fixed/structural), null — берётся дефолт контроллера
 * @param overlap        перекрытие чанков в символах (только fixed)
 * @param documentsCount число загруженных документов (kb_documents)
 * @param chunksCount    число чанков (kb_chunks); заполняется ТОЛЬКО для indexed, иначе null
 * @param processedDocs  прогресс индексации: обработано документов
 * @param totalDocs      всего документов в базе
 * @param etaSeconds     оценка оставшегося времени (среднее время на документ × осталось); null — пока <1 документа
 * @param error          сообщение об ошибке индексации (status=failed)
 */
data class KnowledgeBase(
    val id: Long,
    val name: String,
    val status: String,
    val strategy: String,
    val chunkSize: Int?,
    val overlap: Int?,
    val embeddingModel: String,
    val active: Boolean,
    val documentsCount: Int,
    val chunksCount: Int?,
    val processedDocs: Int,
    val totalDocs: Int,
    val etaSeconds: Long?,
    val error: String?,
    val createdAt: String,
)

/** Чанк базы знаний с эмбеддингом (строка kb_chunks) + имя базы — для RAG-инъекции. */
data class KbChunkRow(
    val kbId: Long,
    /** Id строки kb_chunks (Day-24, источники ответов); 0 — до записи в БД (индексация). */
    val chunkId: Long = 0L,
    val kbName: String,
    val source: String,
    val title: String,
    val section: String,
    val strategy: String,
    val content: String,
    val embedding: FloatArray,
    val model: String?,
) {
    // FloatArray в data class: equals/hashCode по содержимому массива (сравнение в тестах).
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is KbChunkRow) return false
        return kbId == other.kbId && chunkId == other.chunkId && kbName == other.kbName && source == other.source &&
            title == other.title && section == other.section && strategy == other.strategy &&
            content == other.content && embedding.contentEquals(other.embedding) && model == other.model
    }

    override fun hashCode(): Int = content.hashCode()
}

/** Статический каталог эмбеддинг-моделей (расширяемый; сейчас одна модель GPUStack). */
object KbModelCatalog {

    data class EmbeddingModel(val id: String, val dimension: Int, val description: String)

    val models: List<EmbeddingModel> = listOf(
        EmbeddingModel(
            id = "qwen3-vl-embedding-8b",
            dimension = 4096,
            description = "Мультиязычная эмбеддинг-модель GPUStack",
        ),
    )

    /** Модель по идентификатору; null — неизвестная (POST → 400). */
    fun byId(id: String?): EmbeddingModel? = models.firstOrNull { it.id == id }

    /** Модель по умолчанию — первая в каталоге. */
    val default: EmbeddingModel get() = models.first()
}
