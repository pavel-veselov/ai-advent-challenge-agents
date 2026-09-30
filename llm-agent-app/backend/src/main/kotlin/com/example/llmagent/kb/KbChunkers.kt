package com.example.llmagent.kb

import java.io.File
import java.nio.file.Paths

/**
 * Порт RAG-чанкеров дня 21 (rag/chunk) под пакет kb backend'а. Логика сохранена:
 * fixed — скользящее окно по абзацам с перекрытием; structural — рез по собственной
 * разметке документа (markdown-заголовки / объявления в коде / страницы PDF).
 * Субразбиение длинных секций в structural — по абзацам, с фолбэком на границы слов
 * для отдельного абзаца длиннее лимита.
 */

/** Тип документа (определяется по расширению; TEXT обрабатывается как markdown без заголовков). */
enum class KbDocType { MD, CODE, PDF }

/** Документ для чанкования: путь к файлу, тип, короткое имя и извлечённый текст. */
data class KbDoc(
    val path: String,
    val type: KbDocType,
    val title: String,
    val rawText: String,
)

/** Чанк — фрагмент документа для индексации (embedding заполняется этапом эмбеддингов). */
data class KbChunk(
    val chunkId: String,
    val source: String,
    val title: String,
    val section: String,
    val strategy: String,
    val content: String,
    val embedding: FloatArray? = null,
    val model: String? = null,
) {
    // FloatArray в data class: equals/hashCode по содержимому массива (сравнение в тестах).
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is KbChunk) return false
        return chunkId == other.chunkId && source == other.source && title == other.title &&
            section == other.section && strategy == other.strategy && content == other.content &&
            embedding.contentEquals(other.embedding) && model == other.model
    }

    override fun hashCode(): Int = content.hashCode()
}

/** Стратегия чанкования: превращает документ в список чанков. */
interface KbChunker {
    /** Имя стратегии (попадает в KbChunk.strategy и колонку strategy). */
    fun name(): String

    /** Разбить документ на чанки. */
    fun chunk(doc: KbDoc): List<KbChunk>
}

/**
 * Фиксированный чанкер: скользящее окно по абзацам с перекрытием. Абзацы накапливаются,
 * пока не превысят [maxChars]; длинный абзац жёстко режется, каждый следующий чанк
 * начинается с хвоста предыдущего (до [overlap] символов).
 */
class KbFixedSizeChunker(
    private val maxChars: Int = 1000,
    private val overlap: Int = 200,
) : KbChunker {

    override fun name(): String = "fixed"

    override fun chunk(doc: KbDoc): List<KbChunk> {
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
            KbChunk(
                chunkId = "fixed:$filename:$index",
                source = doc.path,
                title = doc.title,
                section = SECTION_ALL_DOCUMENT,
                strategy = name(),
                content = content,
            )
        }
    }

    /** Хвост предыдущего чанка до min(overlap, budget) символов на границе слова. */
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

    /** Жёсткое деление длинного текста на части <= maxChars (предпочтение — последний пробел в окне). */
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

/**
 * Структурный чанкер: режет документ по его собственной разметке — markdown-заголовкам,
 * объявлениям в коде и страницам PDF. Секция длиннее [maxChars] дожимается субразбиением
 * по абзацам (жадная упаковка целых абзацев без перекрытия); отдельный абзац длиннее
 * [maxChars] режется по границам слов.
 */
class KbStructuralChunker(
    private val maxChars: Int = 1000,
) : KbChunker {

    override fun name(): String = "structural"

    override fun chunk(doc: KbDoc): List<KbChunk> {
        if (doc.rawText.isBlank()) return emptyList()

        val (title, sections) = when (doc.type) {
            KbDocType.MD -> mdSections(doc)
            KbDocType.CODE -> Pair(doc.title, codeSections(doc))
            KbDocType.PDF -> Pair(doc.title, pdfSections(doc))
        }

        val filename = Paths.get(doc.path).fileName.toString()
        val chunks = mutableListOf<KbChunk>()
        for (section in sections) {
            val text = section.content.trim()
            if (text.isEmpty()) continue
            val parts = if (section.splittable) splitLong(text, maxChars) else listOf(text)
            for (part in parts) {
                chunks += KbChunk(
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
    private fun mdSections(doc: KbDoc): Pair<String, List<Section>> {
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
     * Код: файл <= SINGLE_FILE_LIMIT символов — один чанк с секцией «file»; иначе рез
     * по объявлениям в нулевой колонке (преамбула прилипает к первому блоку), секция —
     * имя объявления или «file».
     */
    private fun codeSections(doc: KbDoc): List<Section> {
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

    /** PDF: рез по маркерам [PAGE n] (текст до первого маркера — страница 1). */
    private fun pdfSections(doc: KbDoc): List<Section> {
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

    /**
     * Субразбиение длинной секции: жадно упаковывает целые абзацы («\n\n») в части <= [limit]
     * без перекрытия; отдельный абзац длиннее [limit] уходит в [hardSplit] (границы слов).
     */
    private fun splitLong(text: String, limit: Int): List<String> {
        if (text.length <= limit) return listOf(text)
        val paragraphs = text.split("\n\n").map { it.trim() }.filter { it.isNotEmpty() }
        val parts = mutableListOf<String>()
        var current = ""
        for (p in paragraphs) {
            when {
                p.length > limit -> {
                    if (current.isNotEmpty()) {
                        parts.add(current)
                        current = ""
                    }
                    parts.addAll(hardSplit(p, limit))
                }
                current.isEmpty() || current.length + PARAGRAPH_SEPARATOR.length + p.length <= limit ->
                    current = if (current.isEmpty()) p else current + PARAGRAPH_SEPARATOR + p
                else -> {
                    parts.add(current)
                    current = p
                }
            }
        }
        if (current.isNotEmpty()) parts.add(current)
        return parts
    }

    /** Жёсткое деление длинного текста на части <= limit (предпочтение — последний пробел в окне). */
    private fun hardSplit(text: String, limit: Int): List<String> {
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
        private const val PARAGRAPH_SEPARATOR = "\n\n"
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

/**
 * Экстракция загруженных файлов в текст (порт rag/extract): текстовые — как есть UTF-8,
 * PDF — постранично через PDFBox 3.x (`Loader.loadPDF`; в rag/ была ветка 2.0.x с
 * `PDDocument.load`). Страницы склеиваются маркерами `\n\n[PAGE n]\n\n` — структурный
 * чанкер режет PDF по страницам.
 */
object KbExtractor {

    /** Расширения, обрабатываемые как код (structural: рез по объявлениям). */
    private val CODE_EXTENSIONS = setOf("kt", "ts", "tsx", "js")

    /**
     * Извлечь документ из файла: .md/.txt/.json/.csv — как есть (MD-режим чанкера),
     * .kt/.ts/.tsx/.js — код, .pdf — PDFBox. Неподдерживаемое расширение — IllegalArgumentException.
     */
    fun extract(file: File): KbDoc {
        val ext = file.extension.lowercase()
        return when (ext) {
            "pdf" -> extractPdf(file)
            in CODE_EXTENSIONS -> textDoc(file, KbDocType.CODE)
            "md", "txt", "json", "csv" -> textDoc(file, KbDocType.MD)
            else -> throw IllegalArgumentException(
                "Неподдерживаемое расширение «.$ext» у файла ${file.name}: " +
                    "ожидаются .md, .txt, .pdf, .kt, .ts, .js, .json или .csv",
            )
        }
    }

    private fun textDoc(file: File, type: KbDocType): KbDoc = KbDoc(
        path = file.absolutePath,
        type = type,
        title = file.nameWithoutExtension,
        rawText = file.readText(Charsets.UTF_8),
    )

    private fun extractPdf(file: File): KbDoc {
        org.apache.pdfbox.Loader.loadPDF(file).use { pdf ->
            val sb = StringBuilder()
            val stripper = org.apache.pdfbox.text.PDFTextStripper()
            for (page in 1..pdf.numberOfPages) {
                if (page > 1) sb.append(pageMarker(page))
                stripper.startPage = page
                stripper.endPage = page
                sb.append(stripper.getText(pdf).trim())
            }
            return KbDoc(
                path = file.absolutePath,
                type = KbDocType.PDF,
                title = file.nameWithoutExtension,
                rawText = sb.toString(),
            )
        }
    }

    /** Маркер начала страницы n (n >= 2; первая страница маркера не получает). */
    fun pageMarker(n: Int): String = "\n\n[PAGE $n]\n\n"
}
