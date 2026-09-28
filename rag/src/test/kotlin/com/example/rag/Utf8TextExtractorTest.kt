package com.example.rag

import com.example.rag.extract.Utf8TextExtractor
import com.example.rag.model.DocType
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * UTF-8 извлечение: кириллица не должна искажаться, тип — по расширению.
 */
class Utf8TextExtractorTest {

	private val extractor = Utf8TextExtractor()

	@Test
	fun `cyrillic markdown extracted with utf-8 intact`() {
		val file = File.createTempFile("rag-test", ".md")
		try {
			file.writeText("# Привет, мир\n\nПроверка кириллицы: ё, Ж, Щ — и UTF-8.", Charsets.UTF_8)
			val doc = extractor.extract(file)
			assertEquals(DocType.MD, doc.type)
			// createTempFile добавляет случайный суффикс к префиксу имени.
			assertTrue(doc.title.startsWith("rag-test"), "title=${doc.title}")
			assertTrue(doc.rawText.contains("Привет, мир"), "кириллица искажена: ${doc.rawText}")
			assertTrue(doc.rawText.contains("ё, Ж, Щ"), "диакритика/кириллица искажены")
		} finally {
			file.delete()
		}
	}

	@Test
	fun `kotlin file maps to CODE type`() {
		val file = File.createTempFile("rag-code", ".kt")
		try {
			file.writeText("// Комментарий на русском: сервер запущен\nfun main() {}", Charsets.UTF_8)
			val doc = extractor.extract(file)
			assertEquals(DocType.CODE, doc.type)
			assertTrue(doc.title.startsWith("rag-code"), "title=${doc.title}")
			assertTrue(doc.rawText.contains("сервер запущен"), "кириллица в коде искажена")
		} finally {
			file.delete()
		}
	}
}
