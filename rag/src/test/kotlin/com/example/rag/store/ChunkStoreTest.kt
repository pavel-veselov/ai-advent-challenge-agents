package com.example.rag.store

import com.example.rag.model.Chunk
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Тесты SQLite-хранилища чанков: roundtrip BLOB ↔ FloatArray (побитово точный),
 * семантика замены по стратегии, косинусная близость, брутфорс top-k.
 * Каждый тест живёт в собственном временном каталоге (Files.createTempDirectory).
 */
class ChunkStoreTest {

	private fun withStore(block: (ChunkStore) -> Unit) {
		val dir: Path = Files.createTempDirectory("rag-store-test")
		val store = ChunkStore(dir.resolve("index.db"))
		try {
			block(store)
		} finally {
			store.close()
			deleteRecursively(dir)
		}
	}

	private fun deleteRecursively(dir: Path) {
		Files.walk(dir).use { paths ->
			paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
		}
	}

	private fun chunk(
		chunkId: String,
		strategy: String,
		embedding: FloatArray? = null,
		model: String? = "qwen3-vl-embedding-8b",
	) = Chunk(
		chunkId = chunkId,
		source = "docs/$chunkId.md",
		title = "Заголовок $chunkId",
		section = "Раздел 1.$chunkId",
		strategy = strategy,
		content = "Текст чанка $chunkId: кириллица, спецсимволы \"'\\; эмбеддинг может быть null",
		embedding = embedding,
		model = model,
	)

	@Test
	fun `roundtrip preserves content, metadata and exact embedding values`() {
		withStore { store ->
			val embedding = floatArrayOf(-0.5f, 3.14159f, -1e-8f, Float.MIN_VALUE, 1234.5678f, 0.0f)
			val original = chunk("c1", "fixed", embedding)
			store.replaceByStrategy("fixed", listOf(original))

			val loaded = store.loadAll("fixed")
			assertEquals(1, loaded.size, "после roundtrip должен остаться 1 чанк")
			assertEquals(original, loaded[0], "чанк после roundtrip не идентичен (equals по содержимому)")
			// Побитовая точность float-компонент:
			val back = loaded[0].embedding!!
			assertEquals(embedding.size, back.size, "размерность вектора изменилась")
			for (i in embedding.indices) {
				assertEquals(
					java.lang.Float.floatToRawIntBits(embedding[i]),
					java.lang.Float.floatToRawIntBits(back[i]),
					"компонент #$i изменился побитово при roundtrip",
				)
			}
			assertEquals("qwen3-vl-embedding-8b", loaded[0].model, "метка модели потерялась")
		}
	}

	@Test
	fun `null embedding roundtrips to null`() {
		withStore { store ->
			val original = chunk("c-no-emb", "fixed", embedding = null, model = null)
			store.replaceByStrategy("fixed", listOf(original))

			val loaded = store.loadAll("fixed")
			assertEquals(1, loaded.size)
			assertEquals(original, loaded[0], "чанк без эмбеддинга не совпал после roundtrip")
			assertEquals(null, loaded[0].embedding, "null-эмбеддинг должен остаться null")
			assertEquals(null, loaded[0].model)
		}
	}

	@Test
	fun `replaceByStrategy deletes only target strategy rows`() {
		withStore { store ->
			store.replaceByStrategy("a", listOf(chunk("a1", "a"), chunk("a2", "a"), chunk("a3", "a")))
			store.replaceByStrategy("b", listOf(chunk("b1", "b"), chunk("b2", "b")))
			assertEquals(5, store.count(), "перед заменой должно быть 5 чанков")

			store.replaceByStrategy("a", listOf(chunk("a1", "a"), chunk("a4", "a")))

			assertEquals(2, store.count("a"), "стратегия a должна быть заменена новым списком")
			assertEquals(2, store.count("b"), "стратегия b не должна пострадать")
			assertEquals(4, store.count(), "суммарно 2 + 2 чанка")
			val idsA = store.loadAll("a").map { it.chunkId }.toSet()
			assertEquals(setOf("a1", "a4"), idsA, "в стратегии a должны остаться только новые чанки")
		}
	}

	@Test
	fun `cosine of identical vectors is 1`() {
		withStore { store ->
			val v = floatArrayOf(0.3f, -1.7f, 2.5f, -0.001f)
			val score = store.cosine(v, v.copyOf())
			assertTrue(score >= 0.999, "cosine(v, v) = $score, ожидали >= 0.999")
		}
	}

	@Test
	fun `cosine of orthogonal vectors is 0`() {
		withStore { store ->
			val score = store.cosine(floatArrayOf(1f, 0f, 0f), floatArrayOf(0f, 1f, 0f))
			assertTrue(kotlin.math.abs(score) <= 1e-6, "cosine ортогональных = $score, ожидали |score| <= 1e-6")
		}
	}

	@Test
	fun `cosine with zero vector is 0 without NaN`() {
		withStore { store ->
			val zero = floatArrayOf(0f, 0f, 0f)
			val score = store.cosine(zero, floatArrayOf(1f, 2f, 3f))
			assertEquals(0.0, score, "cosine с нулевым вектором должен быть ровно 0.0")
			assertTrue(!score.isNaN(), "NaN недопустим")
			// и оба вектора нулевые:
			assertEquals(0.0, store.cosine(zero, zero.copyOf()))
		}
	}

	@Test
	fun `topK returns correct ranking`() {
		withStore { store ->
			val s = sqrt(2.0).toFloat()
			val e1 = floatArrayOf(1f, 0f, 0f)
			val e2 = floatArrayOf(0f, 1f, 0f)
			val e3 = floatArrayOf(0f, 0f, 1f)
			val e4 = floatArrayOf(0.9f, 0.1f, 0f)
			store.replaceByStrategy(
				"vecs",
				listOf(chunk("e1", "vecs", e1), chunk("e2", "vecs", e2), chunk("e3", "vecs", e3), chunk("e4", "vecs", e4)),
			)
			val query = floatArrayOf(0.9f, 0.2f, 0f) // ближе всего к e4, затем e1, e2, e3

			val top2 = store.topK(query, k = 2)
			assertEquals(2, top2.size)
			assertEquals("e4", top2[0].first.chunkId, "ближайший вектор — e4, получили ${top2[0].first.chunkId}")
			assertEquals("e1", top2[1].first.chunkId, "второй — e1, получили ${top2[1].first.chunkId}")
			assertTrue(top2[0].second >= top2[1].second, "похожесть должна убывать по списку")

			// k больше числа кандидатов → все 4, в порядке убывания
			val all = store.topK(query, k = 10)
			assertEquals(4, all.size)
			assertEquals(listOf("e4", "e1", "e2", "e3"), all.map { it.first.chunkId }, "порядок topK неверен")
		}
	}

	@Test
	fun `topK filters by strategy and skips mismatched dims`() {
		withStore { store ->
			store.replaceByStrategy(
				"s1",
				listOf(
					chunk("s1-a", "s1", floatArrayOf(1f, 0f, 0f)),
					// направление запроса (0.9; 0.3; 0) — косинус к s1-b = 1.0, к s1-a ≈ 0.949
					chunk("s1-b", "s1", floatArrayOf(0.9f, 0.3f, 0f)),
				),
			)
			store.replaceByStrategy(
				"s2",
				listOf(
					chunk("s2-a", "s2", floatArrayOf(0f, 0f, 1f)),
					// чужая размерность — должна быть пропущена при поиске 3-мерным запросом:
					chunk("s2-dim2", "s2", floatArrayOf(1f, 1f)),
					chunk("s2-noemb", "s2", null),
				),
			)
			val query = floatArrayOf(0.9f, 0.3f, 0f) // коллинеарен s1-b → он первый

			val onlyS2 = store.topK(query, k = 5, strategy = "s2")
			assertEquals(listOf("s2-a"), onlyS2.map { it.first.chunkId }, "в s2 только 3-мерный вектор s2-a подходит под запрос")

			val onlyS1 = store.topK(query, k = 5, strategy = "s1")
			assertEquals(listOf("s1-b", "s1-a"), onlyS1.map { it.first.chunkId })

			// без фильтра — 3 подходящих (одна 2-мерная и один без вектора пропущены)
			val everywhere = store.topK(query, k = 5)
			assertEquals(3, everywhere.size, "ожидали 3 кандидата с 3-мерными ненулевыми векторами")

			assertEquals(0, store.topK(query, k = 0).size, "k = 0 → пустой результат")
		}
	}
}
