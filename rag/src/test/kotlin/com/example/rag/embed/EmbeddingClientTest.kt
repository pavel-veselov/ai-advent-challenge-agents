package com.example.rag.embed

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Юнит-тесты EmbeddingClient без сети: разбор ответа (parseResponse),
 * проверка размерности, разбиение на батчи (batch) и гигиена ключа API.
 */
class EmbeddingClientTest {

	private fun client(dim: Int = 3): EmbeddingClient = EmbeddingClient(
		baseUrl = "http://localhost:9999/",
		apiKey = "test-key-never-log",
		model = "test-embedding-model",
		dim = dim,
	)

	// ---------- parseResponse: разбор ответа ----------

	@Test
	fun `parseResponse returns vectors in index order with correct values`() {
		val json = """
			{
			  "object": "list",
			  "model": "test-embedding-model",
			  "data": [
			    {"object": "embedding", "index": 1, "embedding": [4.5, 5.25, 6.0]},
			    {"object": "embedding", "index": 0, "embedding": [1.0, -2.5, 3.75]}
			  ],
			  "usage": {"prompt_tokens": 10, "total_tokens": 10}
			}
		""".trimIndent()

		val vectors = client(dim = 3).parseResponse(json)

		assertEquals(2, vectors.size)
		assertEquals(listOf(1.0f, -2.5f, 3.75f), vectors[0].toList())
		assertEquals(listOf(4.5f, 5.25f, 6.0f), vectors[1].toList())
	}

	@Test
	fun `parseResponse tolerates unknown fields and single item`() {
		val json = """
			{"object":"list","data":[{"object":"embedding","index":0,"embedding":[0.5,1.5,2.5],"extra":"ignored"}]}
		""".trimIndent()

		val vectors = client(dim = 3).parseResponse(json)

		assertEquals(listOf(0.5f, 1.5f, 2.5f), vectors.single().toList())
	}

	@Test
	fun `parseResponse returns empty list for empty data`() {
		val vectors = client(dim = 3).parseResponse("""{"data":[]}""")

		assertTrue(vectors.isEmpty())
	}

	@Test
	fun `parseResponse throws IllegalStateException on dimension mismatch`() {
		val json = """
			{"data":[{"index":0,"embedding":[1.0,2.0]}]}
		""".trimIndent()

		val error = assertFailsWith<IllegalStateException> {
			client(dim = 3).parseResponse(json)
		}
		assertEquals(false, error.message?.contains("test-key-never-log"))
	}

	// ---------- batch: разбиение на батчи ----------

	@Test
	fun `batch splits 70 texts into 32 32 6 preserving order`() {
		val texts = (1..70).map { "text-$it" }

		val batches = batch(texts, MAX_BATCH_SIZE)

		assertEquals(listOf(32, 32, 6), batches.map { it.size })
		assertEquals(texts, batches.flatten())
	}

	@Test
	fun `batch handles empty input and exact multiples`() {
		assertTrue(batch(emptyList(), MAX_BATCH_SIZE).isEmpty())

		val exactMultiple = (1..64).map { "text-$it" }
		assertEquals(listOf(32, 32), batch(exactMultiple, MAX_BATCH_SIZE).map { it.size })

		val single = listOf("solo")
		assertEquals(listOf(listOf("solo")), batch(single, MAX_BATCH_SIZE))
	}

	@Test
	fun `batch rejects non-positive size`() {
		assertFailsWith<IllegalArgumentException> { batch(listOf("a"), 0) }
		assertFailsWith<IllegalArgumentException> { batch(listOf("a"), -1) }
	}

	// ---------- embedAll без сети и гигиена ключа ----------

	@Test
	fun `embedAll of empty list returns empty without any HTTP call`() {
		assertTrue(client().embedAll(emptyList()).isEmpty())
	}

	@Test
	fun `toString never contains api key`() {
		val rendered = client().toString()

		assertFalse(rendered.contains("test-key-never-log"))
		assertTrue(rendered.contains("test-embedding-model"))
	}
}
