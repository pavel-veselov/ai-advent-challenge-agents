package com.example.rag.chunk

import com.example.rag.config.CorpusConfig
import com.example.rag.model.Chunk
import com.example.rag.model.Document
import java.nio.file.Paths

/**
 * Фиксированный чанкер: скользящее окно по абзацам с перекрытием.
 * Абзацы накапливаются, пока не превысят [maxChars]; длинный абзац жёстко режется,
 * каждый следующий чанк начинается с хвоста предыдущего (до [overlap] символов).
 */
class FixedSizeChunker(
	private val maxChars: Int = CorpusConfig.DEFAULT.chunkMaxChars,
	private val overlap: Int = CorpusConfig.DEFAULT.chunkOverlap,
) : Chunker {

	override fun name(): String = "fixed"

	override fun chunk(doc: Document): List<Chunk> {
		val paragraphs = doc.rawText.split("\n\n")
			.map { it.trim() }
			.filter { it.isNotEmpty() }
		if (paragraphs.isEmpty()) return emptyList()

		val pieces = paragraphs.flatMap { hardSplit(it) }
		val texts = mutableListOf<String>()
		var current = ""
		for (piece in pieces) {
			if (current.isEmpty()) {
				current = piece
				continue
			}
			if (current.length + SEPARATOR.length + piece.length <= maxChars) {
				current = current + SEPARATOR + piece
			} else {
				texts.add(current)
				val tail = tailOf(current, budget = maxChars - piece.length - SEPARATOR.length)
				current = if (tail.isEmpty()) piece else tail + SEPARATOR + piece
			}
		}
		if (current.isNotEmpty()) texts.add(current)

		val filename = Paths.get(doc.path).fileName.toString()
		return texts.mapIndexed { index, content ->
			Chunk(
				chunkId = "fixed:$filename:$index",
				source = doc.path,
				title = doc.title,
				section = SECTION_ALL_DOCUMENT,
				strategy = name(),
				content = content,
			)
		}
	}

	/** Хвост предыдущего чанка до min(overlap, budget) символов на границе слова (жёсткий рез — крайний случай). */
	private fun tailOf(text: String, budget: Int): String {
		if (overlap <= 0 || budget <= 0) return ""
		val limit = minOf(overlap, budget)
		if (text.length <= limit) return text
		var start = text.length - limit
		while (start < text.length && !text[start].isWhitespace()) start++
		return if (start >= text.length) {
			text.substring(text.length - limit).trim()
		} else {
			text.substring(start).trim()
		}
	}

	/** Жёсткое деление длинного текста на части <= maxChars (предпочтение — последний пробел/перевод строки в окне). */
	private fun hardSplit(text: String): List<String> {
		if (text.length <= maxChars) return listOf(text)
		val parts = mutableListOf<String>()
		var rest = text
		while (rest.length > maxChars) {
			val lastWhitespace = rest.lastIndexOfAny(WHITESPACE, maxChars - 1)
			val cut = if (lastWhitespace > 0) lastWhitespace else maxChars
			val part = rest.substring(0, cut).trim()
			if (part.isNotEmpty()) parts.add(part)
			rest = rest.substring(cut).trim()
		}
		if (rest.isNotEmpty()) parts.add(rest)
		return parts
	}

	companion object {
		private const val SEPARATOR = "\n\n"
		private const val SECTION_ALL_DOCUMENT = "(весь документ)"
		private val WHITESPACE = charArrayOf(' ', '\n', '\t', '\r')
	}
}
