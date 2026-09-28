package com.example.rag.chunk

import com.example.rag.model.DocType
import com.example.rag.model.Document
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Тесты StructuralChunker: markdown-заголовки со стеком путей, рез кода по объявлениям,
 * страницы PDF, кириллица, пустые файлы и формат chunkId.
 */
class StructuralChunkerTest {

	private val chunker = StructuralChunker()

	@Test
	fun `name is structural`() {
		assertEquals("structural", chunker.name())
	}

	@Test
	fun `md headers build nested sections and override title`() {
		val doc = Document(
			path = "docs/guide.md",
			type = DocType.MD,
			title = "guide",
			rawText = """
				# Введение
				Общий текст введения.
				## Подходы
				Текст про подходы.
				### Детали
				Подробные детали подходов.
				## Итоги
				Всё хорошо.
			""".trimIndent(),
		)

		val chunks = chunker.chunk(doc)

		assertEquals(
			listOf("Введение", "Введение > Подходы", "Введение > Подходы > Детали", "Введение > Итоги"),
			chunks.map { it.section },
			"секции должны собираться стеком заголовков",
		)
		chunks.forEach {
			assertEquals("Введение", it.title, "title всех чанков = первый H1 документа")
			assertEquals("structural", it.strategy)
			assertEquals("docs/guide.md", it.source)
		}
		assertTrue(chunks.first().content.startsWith("# Введение"), "контент секции начинается со строки заголовка")
		assertTrue("Подробные детали подходов." in chunks[2].content, "кириллица тела секции сохраняется")
	}

	@Test
	fun `long md section is sub-split within maxChars`() {
		val body = (1..60).joinToString("\n\n") { "Абзац пояснения номер $it: подробный разбор шага." }
		val doc = Document(
			path = "docs/big.md",
			type = DocType.MD,
			title = "big",
			rawText = "# Большой раздел\n$body",
		)

		val chunks = chunker.chunk(doc)

		assertTrue(chunks.size >= 2, "секция > maxChars должна дожаться на несколько чанков, получили ${chunks.size}")
		chunks.forEach {
			assertEquals("Большой раздел", it.section)
			assertTrue(it.content.length <= 1000, "часть ${it.content.length} симв. > maxChars")
		}
		assertTrue(chunks.first().content.startsWith("# Большой раздел"), "первая часть сохраняет строку заголовка")
	}

	@Test
	fun `code over limit splits at declarations with name sections`() {
		val body = (1..30).joinToString("\n") {
			"    val значение$it = вычисление элемента номер $it из общего набора данных"
		}
		val doc = Document(
			path = "src/decls.kt",
			type = DocType.CODE,
			title = "decls",
			rawText = buildString {
				append("package com.example\n\nimport kx.annotations.Visible\n\n/** Общие утилиты */\n")
				append("fun загрузка(id: Int): String {\n").append(body).append("\n}\n\n")
				append("class КэшДанных {\n").append(body).append("\n}\n\n")
				append("object Реестр {\n    val размер = 42\n}\n")
			},
		)

		val chunks = chunker.chunk(doc)
		assertTrue(doc.rawText.length > 1500, "подготовили файл ${doc.rawText.length} симв. (> 1500)")
		assertTrue(chunks.size >= 4, "файл с тремя объявлениями > 1500 симв. должен дать >= 4 чанка, получили ${chunks.size}")
		assertEquals("загрузка", chunks.first().section, "первый блок — объявление fun")
		assertTrue(chunks.first().content.startsWith("package com.example"), "преамбула прилипает к первому блоку")
		assertEquals("КэшДанных", chunks.last { it.section == "КэшДанных" }.section)
		assertEquals("Реестр", chunks.last().section, "последний блок — object Реестр")
		chunks.forEach {
			assertTrue(it.content.length <= 1000, "часть ${it.content.length} симв. > maxChars")
			assertTrue(it.content.contains("значение") || it.content.contains("Реестр") || it.content.startsWith("package"), "кириллица сохраняется")
		}
	}

	@Test
	fun `code chunk ids are sequential over whole document`() {
		val body = (1..30).joinToString("\n") {
			"    val значение$it = вычисление элемента номер $it из общего набора"
		}
		val doc = Document(
			path = "src/decls.kt",
			type = DocType.CODE,
			title = "decls",
			rawText = buildString {
				append("fun первая(id: Int): String {\n").append(body).append("\n}\n\n")
				append("class КэшДанных {\n").append(body).append("\n}\n")
			},
		)

		val chunks = chunker.chunk(doc)
		assertTrue(chunks.size >= 3, "ожидали >= 3 чанков, получили ${chunks.size}")
		val ids = chunks.map { it.chunkId }
		assertEquals(
			ids.indices.map { "structural:decls.kt:$it" },
			ids,
			"chunkId должны быть structural:<файл>:<индекс> с нумерацией с нуля по всему документу",
		)
	}

	@Test
	fun `code under limit is one chunk with file section`() {
		val doc = Document(
			path = "src/compact.kt",
			type = DocType.CODE,
			title = "compact",
			rawText = buildString {
				append("package com.example\n\nfun первая(): Int = 1\n\n")
				repeat(25) { append("// строка комментария номер $it с пояснением\n") }
				append("fun вторая(): Int = 2\n")
			},
		)
		assertTrue(doc.rawText.length in 1001..1500, "подготовили файл ${doc.rawText.length} симв. в диапазоне (1000, 1500]")

		val chunks = chunker.chunk(doc)

		assertEquals(1, chunks.size, "файл <= 1500 симв. должен стать одним чанком")
		assertEquals("file", chunks.single().section)
		assertEquals("structural:compact.kt:0", chunks.single().chunkId)
	}

	@Test
	fun `pdf pages split by markers with page sections`() {
		val doc = Document(
			path = "corpus/article.pdf",
			type = DocType.PDF,
			title = "article",
			rawText = "Текст первой страницы про внимание.\n\n[PAGE 2]\n\nТекст второй страницы про Трансформеры.\n\n[PAGE 3]\n\nТекст третьей страницы про RAG.",
		)

		val chunks = chunker.chunk(doc)

		assertEquals(3, chunks.size, "три страницы => три чанка")
		assertEquals(listOf("страница 1", "страница 2", "страница 3"), chunks.map { it.section })
		assertEquals("Текст второй страницы про Трансформеры.", chunks[1].content.trim())
		assertEquals("Текст третьей страницы про RAG.", chunks[2].content.trim())
		chunks.forEach {
			assertEquals("article", it.title)
			assertEquals("structural", it.strategy)
		}
	}

	@Test
	fun `empty file yields no chunks`() {
		assertTrue(chunker.chunk(Document("x.md", DocType.MD, "x", "")).isEmpty(), "пустой файл не даёт чанков")
		assertTrue(chunker.chunk(Document("x.md", DocType.MD, "x", "   \n\n  ")).isEmpty(), "файл из пробелов не даёт чанков")
		assertTrue(chunker.chunk(Document("x.kt", DocType.CODE, "x", "")).isEmpty(), "пустой код не даёт чанков")
		assertTrue(chunker.chunk(Document("x.pdf", DocType.PDF, "x", "")).isEmpty(), "пустой PDF не даёт чанков")
	}
}
