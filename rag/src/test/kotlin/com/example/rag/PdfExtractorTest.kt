package com.example.rag

import com.example.rag.extract.PdfExtractor
import com.example.rag.extract.Utf8TextExtractor
import com.example.rag.model.DocType
import org.apache.pdfbox.pdmodel.PDDocument
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * PDFBox-извлечение обеих arXiv-статей корпуса: страницы > 5, ключевые
 * слова на месте, маркеры страниц [PAGE n] присутствуют.
 */
class PdfExtractorTest {

	private val extractor = PdfExtractor()

	private fun corpusPdf(name: String): File {
		val f = File("corpus/pdf/$name")
		check(f.isFile) { "PDF не найден: ${f.absolutePath}" }
		return f
	}

	@Test
	fun `attention paper extracted with pages and marker`() {
		val file = corpusPdf("attention-is-all-you-need.pdf")
		PDDocument.load(file).use { assertEquals(15, it.numberOfPages, "у статьи Attention Is All You Need 15 страниц") }

		val doc = extractor.extract(file)
		assertEquals(DocType.PDF, doc.type)
		assertEquals("attention-is-all-you-need", doc.title)
		assertTrue(doc.rawText.contains("Transformer"), "текст статьи не содержит «Transformer»")
		assertTrue("[PAGE 2]" in doc.rawText, "маркер [PAGE 2] отсутствует в извлечённом тексте")
	}

	@Test
	fun `rag paper extracted with pages and marker`() {
		val file = corpusPdf("retrieval-augmented-generation.pdf")
		val pages = PDDocument.load(file).use { it.numberOfPages }
		assertTrue(pages > 5, "ожидали > 5 страниц, получили $pages")

		val doc = extractor.extract(file)
		assertEquals(DocType.PDF, doc.type)
		assertTrue(doc.rawText.contains("Retrieval-Augmented"), "текст статьи не содержит «Retrieval-Augmented»")
		assertTrue("[PAGE 2]" in doc.rawText, "маркер [PAGE 2] отсутствует в извлечённом тексте")
	}

	@Test
	fun `utf8 extractor still maps kt extension`() {
		// Страховка расширений: .tsx тоже считается кодом (пригодится корпусу позже).
		val file = File.createTempFile("rag-tsx", ".tsx")
		try {
			file.writeText("const x: string = 'привет';", Charsets.UTF_8)
			assertEquals(DocType.CODE, Utf8TextExtractor().extract(file).type)
		} finally {
			file.delete()
		}
	}
}
