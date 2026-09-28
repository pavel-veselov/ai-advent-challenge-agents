package com.example.rag.chunk

import com.example.rag.model.Chunk
import com.example.rag.model.Document

/**
 * Стратегия чанкования: превращает документ в список чанков.
 * Реализации (fixed / structural и др.) подключаются на следующем шаге.
 */
interface Chunker {
	/** Имя стратегии (попадает в Chunk.strategy). */
	fun name(): String

	/** Разбить документ на чанки. */
	fun chunk(doc: Document): List<Chunk>
}
