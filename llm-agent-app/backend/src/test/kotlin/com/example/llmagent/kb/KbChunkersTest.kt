package com.example.llmagent.kb

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.writeText

/**
 * Юнит-тесты чанкеров и экстрактора (порт компактного набора rag-тестов дня 21).
 */
class KbChunkersTest {

    @TempDir
    lateinit var tempDir: Path

    /** Абзац ~400 символов с якорями «Абзац N» / «конецN». */
    private fun para(n: Int): String =
        "Абзац $n. " + (1..50).joinToString(" ") { "слово$it" } + " конец$n"

    // ---------- fixed ----------

    @Test
    fun `fixed chunker name and metadata`() {
        val chunker = KbFixedSizeChunker(maxChars = 1000, overlap = 200)
        assertEquals("fixed", chunker.name())
        val doc = KbDoc("dir/notes.md", KbDocType.MD, "notes", "Абзац один.\n\nАбзац два.")
        val chunks = chunker.chunk(doc)
        assertEquals(1, chunks.size)
        val chunk = chunks.first()
        assertEquals("fixed:notes.md:0", chunk.chunkId)
        assertEquals("(весь документ)", chunk.section)
        assertEquals("fixed", chunk.strategy)
        assertTrue(chunk.content.contains("Абзац один") && chunk.content.contains("Абзац два"))
    }

    @Test
    fun `fixed packs paragraphs and keeps overlap tail`() {
        val chunker = KbFixedSizeChunker(maxChars = 1000, overlap = 200)
        val raw = (1..3).joinToString("\n\n") { para(it) }
        val chunks = chunker.chunk(KbDoc("dir/notes.md", KbDocType.MD, "notes", raw))
        // Два абзаца влезают в 1000, третий уходит во второй чанк
        assertEquals(2, chunks.size)
        assertTrue(chunks[0].content.contains("Абзац 1") && chunks[0].content.contains("Абзац 2"))
        assertTrue(chunks[1].content.contains("Абзац 3"))
        // Перекрытие: хвост предыдущего чанка (конец абзаца 2) вошёл во второй чанк
        assertTrue(chunks[1].content.contains("конец2"))
        assertEquals("fixed:notes.md:1", chunks[1].chunkId)
    }

    @Test
    fun `fixed hard splits oversized single paragraph`() {
        val chunker = KbFixedSizeChunker(maxChars = 100, overlap = 20)
        val longLine = (1..60).joinToString(" ") { "слово$it" }
        val chunks = chunker.chunk(KbDoc("dir/long.md", KbDocType.MD, "long", longLine))
        assertTrue(chunks.size >= 4, "длинный абзац должен разбиться на несколько чанков")
        assertTrue(chunks.all { it.content.length <= 100 }, "все чанки <= maxChars")
        val joined = chunks.joinToString(" ") { it.content }
        assertTrue(joined.contains("слово1") && joined.contains("слово60"), "контент не потерян")
    }

    @Test
    fun `fixed returns nothing for blank document`() {
        assertTrue(KbFixedSizeChunker().chunk(KbDoc("dir/e.md", KbDocType.MD, "e", "   \n\n  ")).isEmpty())
    }

    // ---------- structural ----------

    @Test
    fun `structural md sections stack header paths`() {
        val raw = """
            # Введение
            Текст введения.
            ## Подходы
            Текст подходов.
            ### Детали
            Детальные детали.
            ## Итоги
            Итоговый текст.
        """.trimIndent()
        val chunks = KbStructuralChunker(maxChars = 1000)
            .chunk(KbDoc("dir/notes.md", KbDocType.MD, "notes", raw))
        val sections = chunks.map { it.section }
        assertEquals(
            listOf("Введение", "Введение > Подходы", "Введение > Подходы > Детали", "Введение > Итоги"),
            sections,
        )
        assertTrue(chunks.all { it.title == "Введение" }, "title — первый H1 документа")
        assertTrue(chunks.all { it.strategy == "structural" })
        assertTrue(chunks[0].content.contains("Текст введения"))
        assertTrue(chunks[2].content.contains("Детальные детали"))
    }

    @Test
    fun `structural long section is sub-split by words`() {
        val huge = (1..300).joinToString(" ") { "слово$it" } // ~2600 символов одной строкой
        val doc = KbDoc("dir/big.md", KbDocType.MD, "big", "## Заголовок\n$huge")
        val chunks = KbStructuralChunker(maxChars = 1000).chunk(doc)
        assertTrue(chunks.size >= 3, "длинная секция должна дожаться разбиением")
        assertTrue(chunks.all { it.content.length <= 1000 }, "все части <= maxChars")
        assertTrue(chunks.all { it.section == "Заголовок" })
    }

    @Test
    fun `structural long section splits at paragraph boundaries`() {
        // Три абзаца ~350 символов: поодиночке и парой влезают в 1000, вместе — нет
        val raw = "## Раздел\n" + (1..3).joinToString("\n\n") { para(it) }
        val chunks = KbStructuralChunker(maxChars = 1000)
            .chunk(KbDoc("dir/paras.md", KbDocType.MD, "paras", raw))
        assertEquals(2, chunks.size, "пара абзацев в первый чанк, третий — во второй")
        assertTrue(chunks.all { it.content.length <= 1000 }, "все чанки <= maxChars")
        assertTrue(chunks.all { it.section == "Раздел" })
        assertTrue(chunks[0].content.startsWith("## Раздел"), "строка заголовка остаётся в первом чанке")
        assertTrue(chunks[0].content.contains("Абзац 1") && chunks[0].content.contains("конец1"))
        assertTrue(chunks[0].content.contains("Абзац 2") && chunks[0].content.contains("конец2"))
        assertTrue(chunks[1].content.contains("Абзац 3") && chunks[1].content.contains("конец3"))
    }

    @Test
    fun `structural oversize paragraph falls back to word-boundary split`() {
        val hugePara = (1..200).joinToString(" ") { "слово$it" } // > 1000 символов одним абзацем
        val raw = "## Раздел\nКороткий вводный абзац.\n\n$hugePara"
        val chunks = KbStructuralChunker(maxChars = 1000)
            .chunk(KbDoc("dir/huge.md", KbDocType.MD, "huge", raw))
        assertTrue(chunks.size >= 2, "гигантский абзац должен разбиться на части")
        assertTrue(chunks.all { it.content.length <= 1000 }, "все части <= maxChars")
        assertTrue(chunks.all { it.section == "Раздел" })
        val joined = chunks.joinToString(" ") { it.content }
        assertTrue(joined.contains("слово1") && joined.contains("слово200"), "контент не потерян")
    }

    @Test
    fun `structural section within limit stays single part`() {
        val raw = "## Раздел\nПервый абзац.\n\nВторой абзац."
        val chunks = KbStructuralChunker(maxChars = 1000)
            .chunk(KbDoc("dir/small.md", KbDocType.MD, "small", raw))
        assertEquals(1, chunks.size, "секция <= maxChars не должна делиться")
        assertTrue(chunks[0].content.contains("Первый абзац") && chunks[0].content.contains("Второй абзац"))
    }

    @Test
    fun `structural pdf splits by page markers`() {
        val raw = "Текст первой\n\n[PAGE 2]\n\nВторая\n\n[PAGE 3]\n\nТретья"
        val chunks = KbStructuralChunker().chunk(KbDoc("dir/book.pdf", KbDocType.PDF, "book", raw))
        assertEquals(3, chunks.size)
        assertEquals(listOf("страница 1", "страница 2", "страница 3"), chunks.map { it.section })
        assertTrue(chunks[0].content.contains("Текст первой"))
        assertTrue(chunks[1].content.contains("Вторая"))
        assertTrue(chunks[2].content.contains("Третья"))
    }

    @Test
    fun `structural code splits kt file by declarations`() {
        val code = buildString {
            appendLine("package demo")
            appendLine("// преамбула")
            repeat(40) { appendLine("val filler$it = $it // выравнивание длины файла padding") }
            appendLine("fun alpha() {")
            repeat(30) { appendLine("    val x$it = $it // тело alpha padding padding") }
            appendLine("}")
            appendLine("fun beta() {")
            repeat(30) { appendLine("    val y$it = $it // тело beta padding padding") }
            appendLine("}")
        }
        val doc = KbDoc("dir/app.kt", KbDocType.CODE, "app", code)
        val chunks = KbStructuralChunker().chunk(doc)
        // Длинные функции дожимаются подсеканием с той же секцией — проверяем
        // уникальные секции (порядок: alpha раньше beta) и содержимое.
        assertEquals(listOf("alpha", "beta"), chunks.map { it.section }.distinct())
        assertTrue(chunks.size >= 2, "должно быть несколько чанков")
        assertTrue(chunks[0].content.contains("преамбула"), "преамбула прилипает к первому блоку")
        assertTrue(chunks.last().content.contains("тело beta"))
    }

    // ---------- extractor ----------

    @Test
    fun `extractor maps extensions to doc types`() {
        val md = tempDir.resolve("doc.md").apply { writeText("# T\nтекст") }
        val txt = tempDir.resolve("note.txt").apply { writeText("просто текст") }
        val ts = tempDir.resolve("app.ts").apply { writeText("export const a = 1") }
        val json = tempDir.resolve("data.json").apply { writeText("{}") }
        assertEquals(KbDocType.MD, KbExtractor.extract(md.toFile()).type)
        assertEquals(KbDocType.MD, KbExtractor.extract(txt.toFile()).type)
        assertEquals(KbDocType.CODE, KbExtractor.extract(ts.toFile()).type)
        assertEquals(KbDocType.MD, KbExtractor.extract(json.toFile()).type)
    }

    @Test
    fun `extractor rejects unsupported extension`() {
        val exe = tempDir.resolve("virus.exe").apply { writeText("MZ") }
        val ex = assertThrows(IllegalArgumentException::class.java) { KbExtractor.extract(exe.toFile()) }
        assertTrue(ex.message!!.contains("Неподдерживаемое расширение"))
    }

    @Test
    fun `pdf page marker format matches structural chunker regex`() {
        assertEquals("\n\n[PAGE 2]\n\n", KbExtractor.pageMarker(2))
    }
}
