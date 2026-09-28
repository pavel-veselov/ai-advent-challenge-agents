package com.example.rag.chunk

import com.example.rag.model.DocType
import com.example.rag.model.Document
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Тесты FixedSizeChunker: упаковка кириллических абзацев, перекрытие соседних чанков,
 * жёсткое деление длинных абзацев, пустые и короткие документы.
 */
class FixedSizeChunkerTest {

	private val chunker = FixedSizeChunker()

	/** Абзац ~317 символов с уникальными маркерами начала («словоN») и конца («конецN»). */
	private fun para(n: Int): String = (1..40).joinToString(" ") { "слово$n" } + " конец$n"

	private fun doc(rawText: String, path: String = "docs/notes.md") =
		Document(path = path, type = DocType.MD, title = "notes", rawText = rawText)

	@Test
	fun `name is fixed`() {
		assertEquals("fixed", chunker.name())
	}

	@Test
	fun `cyrillic paragraphs pack into single chunk within maxChars`() {
		val source = (1..3).joinToString("\n\n") { para(it) }
		val chunks = chunker.chunk(doc(source))

		assertEquals(1, chunks.size, "3 абзаца ~317 симв. должны уместиться в один чанк")
		val chunk = chunks.single()
		assertTrue(chunk.content.length <= 1000, "чанк ${chunk.content.length} симв. > maxChars")
		assertEquals("fixed", chunk.strategy)
		assertEquals("(весь документ)", chunk.section)
		assertEquals("fixed:notes.md:0", chunk.chunkId)
		assertEquals("docs/notes.md", chunk.source)
		assertEquals("notes", chunk.title)
		assertEquals(null, chunk.embedding)
		assertEquals(null, chunk.model)
		assertTrue("конец1" in chunk.content && "конец3" in chunk.content, "все абзацы должны попасть в чанк")
	}

	@Test
	fun `consecutive chunks share overlapping tail`() {
		val source = (1..6).joinToString("\n\n") { para(it) }
		val chunks = chunker.chunk(doc(source))

		assertEquals(3, chunks.size, "6 абзацев ~317 симв. должны дать 3 чанка, получили ${chunks.size}")
		chunks.forEach { assertTrue(it.content.length <= 1000, "чанк ${it.content.length} симв. > maxChars") }
		assertTrue(chunks[0].content.endsWith("конец3"), "первый чанк должен заканчиваться третьим абзацем")
		assertTrue(
			"конец3\n\nслово4" in chunks[1].content,
			"второй чанк должен начинаться с хвоста первого (перекрытие)",
		)
		assertTrue(
			"конец5\n\nслово6" in chunks[2].content,
			"третий чанк должен начинаться с хвоста второго (перекрытие)",
		)
	}

	@Test
	fun `long single paragraph is hard-split within maxChars`() {
		val source = (1..300).joinToString(" ") { "токен$it" }
		assertTrue(source.length > 2000, "подготовили абзац ${source.length} симв. без пустых строк")

		val chunks = chunker.chunk(doc(source))

		assertTrue(chunks.size >= 3, "абзац ${source.length} симв. должен дать >= 3 чанка, получили ${chunks.size}")
		chunks.forEach { assertTrue(it.content.length <= 1000, "часть ${it.content.length} симв. > maxChars") }
		assertTrue(chunks.first().content.startsWith("токен1"), "первая часть должна начинаться с начала абзаца")
		assertTrue(chunks.last().content.endsWith("токен300"), "последняя часть должна заканчиваться концом абзаца")
		val joined = chunks.joinToString(" ")
		assertTrue("токен1" in joined && "токен300" in joined, "жёсткое деление не должно терять текст")
	}

	@Test
	fun `blank document yields no chunks`() {
		assertTrue(chunker.chunk(doc("   \n\n  \n\n")).isEmpty(), "документ из пробелов не должен дать чанков")
		assertTrue(chunker.chunk(doc("")).isEmpty(), "пустой документ не должен давать чанков")
	}

	@Test
	fun `small document yields single chunk`() {
		val chunks = chunker.chunk(doc("Привет\n\nмир"))

		assertEquals(1, chunks.size)
		assertEquals("Привет\n\nмир", chunks.single().content, "короткий документ попадает в чанк целиком")
		assertEquals("fixed:notes.md:0", chunks.single().chunkId)
	}
}
