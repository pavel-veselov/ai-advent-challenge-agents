package com.example.rag.chunk

import com.example.rag.config.CorpusConfig
import com.example.rag.model.Chunk
import com.example.rag.model.DocType
import com.example.rag.model.Document
import java.nio.file.Paths

/**
 * Структурный чанкер: режет документ по его собственной разметке —
 * markdown-заголовкам, объявлениям в коде и страницам PDF.
 * Секция длиннее [maxChars] дожимается фиксированным разбиением по границам слов.
 */
class StructuralChunker(
	private val maxChars: Int = CorpusConfig.DEFAULT.chunkMaxChars,
) : Chunker {

	override fun name(): String = "structural"

	override fun chunk(doc: Document): List<Chunk> {
		if (doc.rawText.isBlank()) return emptyList()

		val (title, sections) = when (doc.type) {
			DocType.MD -> mdSections(doc)
			DocType.CODE -> Pair(doc.title, codeSections(doc))
			DocType.PDF -> Pair(doc.title, pdfSections(doc))
		}

		val filename = Paths.get(doc.path).fileName.toString()
		val chunks = mutableListOf<Chunk>()
		for (section in sections) {
			val text = section.content.trim()
			if (text.isEmpty()) continue
			val parts = if (section.splittable) splitLong(text, maxChars) else listOf(text)
			for (part in parts) {
				chunks += Chunk(
					chunkId = "structural:$filename:${chunks.size}",
					source = doc.path,
					title = title,
					section = section.name,
					strategy = name(),
					content = part,
				)
			}
		}
		return chunks
	}

	/** Секция документа: имя-«адрес», текст и признак допустимости дожатия по maxChars. */
	private data class Section(val name: String, val content: String, val splittable: Boolean = true)

	/**
	 * Markdown: резка по ATX-заголовкам (1–6 «#» + пробел) со стеком путей заголовков.
	 * Заголовок первого уровня становится title всех чанков документа.
	 */
	private fun mdSections(doc: Document): Pair<String, List<Section>> {
		var docTitle: String? = null
		val sections = mutableListOf<Section>()
		val stack = ArrayDeque<Pair<Int, String>>() // (уровень, текст заголовка)
		val preamble = StringBuilder()
		var current = preamble
		var currentName = doc.title

		fun flush() {
			val text = current.toString().trim()
			if (text.isNotEmpty()) sections.add(Section(currentName, text))
		}

		for (line in doc.rawText.lines()) {
			val match = HEADER_REGEX.find(line)
			if (match == null) {
				current.append(line).append('\n')
				continue
			}
			val level = match.groupValues[1].length
			val headerText = match.groupValues[2].trim()
			if (level == 1 && docTitle == null) docTitle = headerText
			flush()
			while (stack.isNotEmpty() && stack.last().first >= level) stack.removeLast()
			stack.addLast(level to headerText)
			currentName = stack.joinToString(" > ") { it.second }
			current = StringBuilder(line.trimEnd()).append('\n')
		}
		flush()

		return Pair(docTitle ?: doc.title, sections)
	}

	/**
	 * Код: файл <= SINGLE_FILE_LIMIT символов — один чанк с секцией «file»;
	 * иначе рез по объявлениям в нулевой колонке, преамбула (package/import/комментарии)
	 * прилипает к первому блоку; секция — имя объявления или «file».
	 */
	private fun codeSections(doc: Document): List<Section> {
		if (doc.rawText.length <= SINGLE_FILE_LIMIT) {
			return listOf(Section(SECTION_FILE, doc.rawText, splittable = false))
		}
		val declarationRegex = if (isTypeScript(doc.path)) TS_DECLARATION else KT_DECLARATION
		val lines = doc.rawText.lines()
		val starts = lines.indices.filter { declarationRegex.containsMatchIn(lines[it]) }
		if (starts.isEmpty()) return listOf(Section(SECTION_FILE, doc.rawText))

		val sections = mutableListOf<Section>()
		val preamble = lines.subList(0, starts.first()).joinToString("\n")
		for (i in starts.indices) {
			val end = if (i + 1 < starts.size) starts[i + 1] else lines.size
			val block = lines.subList(starts[i], end).joinToString("\n")
			val content = if (i == 0 && preamble.isNotBlank()) preamble + "\n" + block else block
			val sectionName = declarationName(lines[starts[i]]) ?: SECTION_FILE
			sections.add(Section(sectionName, content))
		}
		return sections
	}

	/**
	 * PDF: рез по маркерам [PAGE n] (текст до первого маркера — страница 1);
	 * секция — «страница N», каждая страница при необходимости дожимается.
	 */
	private fun pdfSections(doc: Document): List<Section> {
		val matches = PAGE_MARKER_REGEX.findAll(doc.rawText).toList()
		if (matches.isEmpty()) return listOf(Section(pageSection(1), doc.rawText))

		val sections = mutableListOf(Section(pageSection(1), doc.rawText.substring(0, matches.first().range.first)))
		for (i in matches.indices) {
			val start = matches[i].range.last + 1
			val end = if (i + 1 < matches.size) matches[i + 1].range.first else doc.rawText.length
			sections.add(Section(pageSection(matches[i].groupValues[1].toInt()), doc.rawText.substring(start, end)))
		}
		return sections
	}

	/** Имя объявления: текст после ключевого слова до '('/'{'/':' (прагматично — ещё режем по '='). */
	private fun declarationName(line: String): String? {
		var rest = line.trim()
		if (rest.startsWith("export ")) rest = rest.removePrefix("export ").trim()
		if (rest.startsWith("default ")) rest = rest.removePrefix("default ").trim()
		val keyword = KEYWORDS.firstOrNull { rest.startsWith(it) } ?: return null
		rest = rest.removePrefix(keyword).trim()
		val cut = rest.indexOfAny(NAME_STOP_CHARS)
		var name = if (cut >= 0) rest.substring(0, cut) else rest
		name = name.substringBefore('=').trim()
		return name.ifEmpty { null }
	}

	/** Жёсткое деление длинного текста на части <= limit (предпочтение — последний пробельный символ в окне). */
	private fun splitLong(text: String, limit: Int): List<String> {
		if (text.length <= limit) return listOf(text)
		val parts = mutableListOf<String>()
		var rest = text
		while (rest.length > limit) {
			val lastWhitespace = rest.lastIndexOfAny(WHITESPACE, limit - 1)
			val cut = if (lastWhitespace > 0) lastWhitespace else limit
			val part = rest.substring(0, cut).trim()
			if (part.isNotEmpty()) parts.add(part)
			rest = rest.substring(cut).trim()
		}
		if (rest.isNotEmpty()) parts.add(rest)
		return parts
	}

	companion object {
		private const val SECTION_FILE = "file"
		private const val SINGLE_FILE_LIMIT = 1500
		private val WHITESPACE = charArrayOf(' ', '\n', '\t', '\r')
		private val NAME_STOP_CHARS = charArrayOf('(', '{', ':')
		private val KEYWORDS = listOf(
			"data class", "sealed class", "enum class", "abstract class",
			"function", "interface", "fun", "class", "object", "const",
		)
		private val HEADER_REGEX = Regex("^(#{1,6})\\s+(.+?)\\s*$")
		private val KT_DECLARATION = Regex("^(fun|class|object|interface|data class|sealed class|enum class|abstract class)\\b")
		private val TS_DECLARATION = Regex("^(export (default )?(function|class|const)|function |class |const [A-Za-z_$])")
		private val PAGE_MARKER_REGEX = Regex("\\[PAGE (\\d+)]")

		private fun pageSection(n: Int): String = "страница $n"
		private fun isTypeScript(path: String): Boolean {
			val filename = Paths.get(path).fileName.toString().lowercase()
			return filename.endsWith(".ts") || filename.endsWith(".tsx")
		}
	}
}
