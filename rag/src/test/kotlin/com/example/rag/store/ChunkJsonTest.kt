package com.example.rag.store

import com.example.rag.model.Chunk
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Тесты JSON-экспорта: структура DTO, pretty-print, файл на диске,
 * режим withEmbeddings = false (векторы не попадают в JSON).
 */
class ChunkJsonTest {

	private val chunks = listOf(
		Chunk(
			chunkId = "j1",
			source = "docs/a.md",
			title = "Документ А",
			section = "Раздел 1",
			strategy = "fixed",
			content = "Текст первого чанка",
			embedding = floatArrayOf(0.25f, -0.75f, 1.5f),
			model = "qwen3-vl-embedding-8b",
		),
		Chunk(
			chunkId = "j2",
			source = "src/B.kt",
			title = "Документ B",
			section = "Класс B",
			strategy = "code",
			content = "Текст второго чанка",
			embedding = null,
			model = null,
		),
	)

	@Test
	fun `toDto maps all fields and honours withEmbeddings flag`() {
		val dto = ChunkJson.toDto(chunks[0], withEmbeddings = true)
		assertEquals("j1", dto.chunkId)
		assertEquals("docs/a.md", dto.source)
		assertEquals("Документ А", dto.title)
		assertEquals("Раздел 1", dto.section)
		assertEquals("fixed", dto.strategy)
		assertEquals("Текст первого чанка", dto.content)
		assertEquals(listOf(0.25f, -0.75f, 1.5f), dto.embedding, "вектор должен копироваться при withEmbeddings = true")
		assertEquals("qwen3-vl-embedding-8b", dto.model)

		val dtoNoEmb = ChunkJson.toDto(chunks[0], withEmbeddings = false)
		assertNull(dtoNoEmb.embedding, "при withEmbeddings = false вектор не должен попадать в DTO")
		assertEquals("j1", dtoNoEmb.chunkId, "остальные поля сохраняются")
	}

	@Test
	fun `toExportDto has correct count and flag`() {
		val export = ChunkJson.toExportDto(chunks, withEmbeddings = true)
		assertEquals(2, export.count)
		assertTrue(export.withEmbeddings)
		assertEquals(2, export.chunks.size)
	}

	@Test
	fun `export writes valid pretty JSON file with correct count`() {
		val dir: Path = Files.createTempDirectory("rag-json-test")
		try {
			val target = dir.resolve("export.json")
			ChunkJson.export(chunks, target, withEmbeddings = false)

			assertTrue(Files.exists(target), "файл экспорта не создан")
			val text = Files.readString(target, Charsets.UTF_8)
			assertTrue(text.contains("\n") || text.contains("\r\n"), "JSON должен быть pretty print (многострочным)")

			val root = ChunkJson.exportJson.parseToJsonElement(text).jsonObject
			assertEquals(2, root["count"]!!.jsonPrimitive.int, "count в JSON неверен")
			assertEquals(false, root["withEmbeddings"]!!.jsonPrimitive.boolean, "withEmbeddings в JSON неверен")
			val arr = root["chunks"]!!.jsonArray
			assertEquals(2, arr.size, "число чанков в JSON неверно")
		} finally {
			Files.walk(dir).use { paths ->
				paths.sorted(java.util.Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
			}
		}
	}

	@Test
	fun `export with withEmbeddings false omits embedding values`() {
		val text = ChunkJson.toJson(chunks, withEmbeddings = false)
		// Имя модели "qwen3-vl-embedding-8b" содержит подстроку "embedding" —
		// поэтому проверяем именно ключ JSON (в кавычках), а не подстроку по всему тексту.
		assertFalse(text.contains("\"embedding\""), "при withEmbeddings = false ключ embedding не должен появляться вовсе")
		assertFalse(text.contains("0.25"), "значения вектора не должны утекать в JSON")
		val root = ChunkJson.exportJson.parseToJsonElement(text).jsonObject
		val first = root["chunks"]!!.jsonArray[0].jsonObject
		assertFalse(first.containsKey("embedding"), "у чанка с вектором ключ embedding должен отсутствовать")
	}

	@Test
	fun `export with withEmbeddings true keeps vectors and handles null`() {
		val text = ChunkJson.toJson(chunks, withEmbeddings = true)
		val root = ChunkJson.exportJson.parseToJsonElement(text).jsonObject
		val arr = root["chunks"]!!.jsonArray

		val first = arr[0].jsonObject
		assertTrue(first.containsKey("embedding"), "при withEmbeddings = true у чанка с вектором должен быть ключ embedding")
		val emb = first["embedding"]!!.jsonArray.map { it.toString().toFloat() }
		assertEquals(listOf(0.25f, -0.75f, 1.5f), emb, "значения вектора искажены при экспорте")

		// второй чанк без вектора: ключ всё равно отсутствует (explicitNulls = false)
		val second = arr[1].jsonObject
		assertFalse(second.containsKey("embedding"), "null-эмбеддинг не должен сериализоваться как ключ")
	}
}
