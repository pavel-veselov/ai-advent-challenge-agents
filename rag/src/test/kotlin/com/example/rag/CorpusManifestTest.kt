package com.example.rag

import com.example.rag.config.CorpusConfig
import com.example.rag.extract.CorpusManifest
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Тест полного извлечения корпуса: 5 markdown + 6 кода + 2 PDF,
 * суммарный объём текста >= 100 000 символов.
 */
class CorpusManifestTest {

	private val docs = CorpusManifest(CorpusConfig.DEFAULT).build()

	@Test
	fun `manifest contains at least 5 md docs`() {
		val md = docs.count { it.type.name == "MD" }
		assertTrue(md >= 5, "ожидали >= 5 MD-документов, получили $md")
	}

	@Test
	fun `manifest contains at least 5 code docs`() {
		val code = docs.count { it.type.name == "CODE" }
		assertTrue(code >= 5, "ожидали >= 5 CODE-документов, получили $code")
	}

	@Test
	fun `manifest contains exactly 2 pdf docs`() {
		val pdf = docs.count { it.type.name == "PDF" }
		assertEquals(2, pdf, "ожидали ровно 2 PDF-документа (обе arXiv-статьи)")
	}

	@Test
	fun `total extracted text is at least 100k chars`() {
		val total = docs.sumOf { it.rawText.length }
		assertTrue(total >= 100_000, "суммарный объём корпуса $total симв. < 100 000 (PDF не извлеклись?)")
	}

	@Test
	fun `pdf corpus files exist on disk`() {
		val pdfDir = CorpusConfig.DEFAULT.pdfDir.toFile()
		val names = pdfDir.listFiles()?.map { it.name } ?: emptyList()
		assertTrue("attention-is-all-you-need.pdf" in names, "нет attention-is-all-you-need.pdf в ${File(pdfDir.path)}")
		assertTrue("retrieval-augmented-generation.pdf" in names, "нет retrieval-augmented-generation.pdf")
	}
}
