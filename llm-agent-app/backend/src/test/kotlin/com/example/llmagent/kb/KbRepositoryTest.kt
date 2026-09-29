package com.example.llmagent.kb

import com.example.llmagent.agent.SqliteTestSupport
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * Юнит-тесты репозитория баз знаний (Day-22): временный SQLite-файл через
 * SqliteTestSupport.jdbc (тот же приём, что у SqliteSessionStoreTest).
 */
class KbRepositoryTest {

    @TempDir
    lateinit var tempDir: Path

    private fun newRepo(fileName: String): KbRepository =
        KbRepository(SqliteTestSupport.jdbc(tempDir.resolve(fileName)))

    private fun chunk(kbId: Long, kbName: String = "База", content: String = "текст") = KbChunkRow(
        kbId = kbId,
        kbName = kbName,
        source = "notes.md",
        title = "notes",
        section = "(весь документ)",
        strategy = "fixed",
        content = content,
        embedding = floatArrayOf(0.1f, -0.5f, 2.0f),
        model = "qwen3-vl-embedding-8b",
    )

    @Test
    fun `create stores indexing base with parameters`() {
        val repo = newRepo("create.db")
        val kb = repo.create("Доки", "fixed", 500, 100, "qwen3-vl-embedding-8b")!!
        assertEquals("Доки", kb.name)
        assertEquals("indexing", kb.status)
        assertEquals("fixed", kb.strategy)
        assertEquals(500, kb.chunkSize)
        assertEquals(100, kb.overlap)
        assertEquals("qwen3-vl-embedding-8b", kb.embeddingModel)
        assertFalse(kb.active)
        assertEquals(0, kb.documentsCount)
        assertNull(kb.chunksCount, "chunksCount у не-проиндексированной базы — null")
        assertNull(kb.error)
    }

    @Test
    fun `setActive toggles and unknown id returns null`() {
        val repo = newRepo("active.db")
        val kb = repo.create("Доки", "fixed", null, null, "qwen3-vl-embedding-8b")!!
        assertTrue(repo.setActive(kb.id, true)!!.active)
        assertFalse(repo.setActive(kb.id, false)!!.active)
        assertNull(repo.setActive(9999, true))
    }

    @Test
    fun `documents registered and listed in insertion order`() {
        val repo = newRepo("docs.db")
        val kb = repo.create("Доки", "fixed", null, null, "qwen3-vl-embedding-8b")!!
        repo.addDocument(kb.id, "a.md", 5)
        repo.addDocument(kb.id, "b.pdf", 2048)
        assertEquals(listOf("a.md" to 5L, "b.pdf" to 2048L), repo.documentsOf(kb.id))
        assertEquals(2, repo.findById(kb.id)!!.documentsCount)
    }

    @Test
    fun `chunks stored with embedding roundtrip and kb name join`() {
        val repo = newRepo("chunks.db")
        val kb = repo.create("База", "fixed", null, null, "qwen3-vl-embedding-8b")!!
        repo.addChunks(kb.id, listOf(chunk(kb.id, "База", "привет")))
        val rows = repo.chunksOfBases(listOf(kb.id))
        assertEquals(1, rows.size)
        val row = rows.first()
        assertEquals(kb.id, row.kbId)
        assertEquals("База", row.kbName, "имя базы подтягивается join'ом")
        assertEquals("notes.md", row.source)
        assertEquals("привет", row.content)
        assertEquals(floatArrayOf(0.1f, -0.5f, 2.0f).toList(), row.embedding.toList())
    }

    @Test
    fun `blob conversion roundtrip is bit-exact`() {
        val floats = floatArrayOf(0.25f, -1.5f, 3.125f, 0f, Float.MIN_VALUE)
        assertEquals(
            floats.toList(),
            KbRepository.blobToFloats(KbRepository.floatsToBlob(floats)).toList(),
        )
    }

    @Test
    fun `delete cascades documents chunks and base`() {
        val repo = newRepo("delete.db")
        val kb = repo.create("База", "fixed", null, null, "qwen3-vl-embedding-8b")!!
        repo.addDocument(kb.id, "a.md", 5)
        repo.addChunks(kb.id, listOf(chunk(kb.id)))
        assertTrue(repo.delete(kb.id))
        assertNull(repo.findById(kb.id))
        assertTrue(repo.chunksOfBases(listOf(kb.id)).isEmpty())
        assertTrue(repo.documentsOf(kb.id).isEmpty())
    }

    @Test
    fun `indexed status exposes chunksCount`() {
        val repo = newRepo("indexed.db")
        val kb = repo.create("База", "fixed", null, null, "qwen3-vl-embedding-8b")!!
        repo.addChunks(kb.id, listOf(chunk(kb.id), chunk(kb.id, content = "два")))
        assertNull(repo.findById(kb.id)!!.chunksCount)
        assertTrue(repo.setStatusIndexed(kb.id))
        val after = repo.findById(kb.id)!!
        assertEquals("indexed", after.status)
        assertEquals(2, after.chunksCount)
        assertNull(after.etaSeconds, "после индексации eta не нужен")
    }

    @Test
    fun `progress updates and failed status stores error`() {
        val repo = newRepo("failed.db")
        val kb = repo.create("База", "fixed", null, null, "qwen3-vl-embedding-8b")!!
        repo.updateProgress(kb.id, 3, 10, 42L)
        val progressing = repo.findById(kb.id)!!
        assertEquals(3, progressing.processedDocs)
        assertEquals(10, progressing.totalDocs)
        assertEquals(42L, progressing.etaSeconds)
        assertTrue(repo.setStatusFailed(kb.id, "упс"))
        val failed = repo.findById(kb.id)!!
        assertEquals("failed", failed.status)
        assertEquals("упс", failed.error)
    }

    @Test
    fun `interrupted indexing reset to failed on restart`() {
        val file = tempDir.resolve("restart.db")
        val first = KbRepository(SqliteTestSupport.jdbc(file))
        val kb = first.create("База", "fixed", null, null, "qwen3-vl-embedding-8b")!!
        assertEquals("indexing", first.findById(kb.id)!!.status)
        // «Рестарт»: новый экземпляр репозитория на той же БД сбрасывает indexing → failed.
        val second = KbRepository(SqliteTestSupport.jdbc(file))
        val after = second.findById(kb.id)!!
        assertEquals("failed", after.status)
        assertEquals(KbRepository.ERROR_INTERRUPTED, after.error)
    }

    @Test
    fun `listActiveIndexed filters by active flag`() {
        val repo = newRepo("ragfilter.db")
        val kb = repo.create("База", "fixed", null, null, "qwen3-vl-embedding-8b")!!
        repo.setStatusIndexed(kb.id)
        assertTrue(repo.listActiveIndexed().isEmpty(), "indexed, но active=0 — не входит")
        repo.setActive(kb.id, true)
        assertEquals(listOf(kb.id), repo.listActiveIndexed().map { it.id })
    }
}
