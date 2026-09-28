package com.example.rag.model

import java.util.Arrays

/**
 * Чанк — фрагмент документа для индексации.
 *
 * @param chunkId   уникальный идентификатор чанка (заполняется на этапе индексации)
 * @param source    путь к исходному документу
 * @param title     заголовок документа (имя файла без расширения)
 * @param section   «адрес» фрагмента внутри документа (заголовок секции, номер страницы и т.п.)
 * @param strategy  имя стратегии чанкования (какой Chunker построил фрагмент)
 * @param content   текст фрагмента
 * @param embedding эмбеддинг фрагмента (заполняется позже, этап embeddings)
 * @param model     модель эмбеддингов, которой получен вектор
 */
data class Chunk(
	val chunkId: String,
	val source: String,
	val title: String,
	val section: String,
	val strategy: String,
	val content: String,
	val embedding: FloatArray? = null,
	val model: String? = null,
) {
	// FloatArray в data class: equals/hashCode по содержимому массива,
	// а не по ссылке (чанки сравниваются при дедупликации и в тестах).
	override fun equals(other: Any?): Boolean {
		if (this === other) return true
		if (other !is Chunk) return false
		return chunkId == other.chunkId &&
			source == other.source &&
			title == other.title &&
			section == other.section &&
			strategy == other.strategy &&
			content == other.content &&
			Arrays.equals(embedding, other.embedding) &&
			model == other.model
	}

	override fun hashCode(): Int {
		var result = chunkId.hashCode()
		result = 31 * result + source.hashCode()
		result = 31 * result + title.hashCode()
		result = 31 * result + section.hashCode()
		result = 31 * result + strategy.hashCode()
		result = 31 * result + content.hashCode()
		result = 31 * result + Arrays.hashCode(embedding)
		result = 31 * result + (model?.hashCode() ?: 0)
		return result
	}
}
