package com.example.llmagent.agent

import java.nio.file.Path
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * Юнит-тесты веток диалога: дерево parent_id в SessionStore.append (не-системные сообщения
 * цепляются к голове активной ветки, системные — нет), бэккафилл линейной истории
 * (идемпотентный, системные пропускаются), цепочки, fork из сообщения, независимость голов.
 */
class SessionBranchStoreTest {

    @TempDir
    lateinit var tmpDir: Path

    private fun branching(): Pair<SessionStore, SessionBranchStore> =
        SqliteTestSupport.branchingStore(tmpDir.resolve("branch-${UUID.randomUUID()}.db"))

    @Test
    fun `append maintains linear tree and advances default branch head`() {
        val (store, branches) = branching()
        val u1 = store.append("s", "user", "u1")
        val a1 = store.append("s", "assistant", "a1")
        val u2 = store.append("s", "user", "u2")
        val a2 = store.append("s", "assistant", "a2")

        val list = branches.list("s")
        assertEquals(1, list.size, "одна ветка по умолчанию")
        assertEquals("Основная", list[0].name)
        assertEquals(a2, list[0].headMessageId, "голова — последнее сообщение")

        val msgs = store.getStored("s")
        assertNull(msgs[0].parentId, "корень без родителя")
        assertEquals(u1, msgs[1].parentId)
        assertEquals(a1, msgs[2].parentId)
        assertEquals(u2, msgs[3].parentId)

        // цепочка от головы — корень → голова
        assertEquals(listOf(u1, a1, u2, a2), store.getBranchChain("s", a2).map { it.id })
    }

    @Test
    fun `system notice keeps parent null and does not advance head`() {
        val (store, branches) = branching()
        val u1 = store.append("s", "user", "u1")
        store.append("s", "system", "Сжатие контекста: служебная заметка")
        val u2 = store.append("s", "user", "u2")

        assertEquals(u2, branches.list("s")[0].headMessageId, "системная заметка голову не двигает")
        val msgs = store.getStored("s")
        assertEquals("system", msgs[1].role)
        assertNull(msgs[1].parentId, "системная заметка без родителя")
        assertEquals(u1, msgs[2].parentId, "u2 цепляется к предыдущему не-системному (u1), минуя заметку")
    }

    @Test
    fun `backfill links preexisting null-parent history skipping system and is idempotent`() {
        // История накоплена ДО подключения веток (обычный SessionStore — parent_id везде NULL).
        val dbFile = tmpDir.resolve("backfill-${UUID.randomUUID()}.db")
        val plain = SqliteTestSupport.store(dbFile)
        plain.append("s", "user", "u1")
        plain.append("s", "system", "заметка")
        plain.append("s", "assistant", "a1")
        plain.append("s", "user", "u2")
        plain.append("s", "assistant", "a2")

        val jdbc = SqliteTestSupport.jdbc(dbFile)
        val store = SessionStore(jdbc, branchStore = SessionBranchStore(jdbc))
        store.backfillLinearParents("s")

        val msgs = store.getStored("s")
        assertNull(msgs[0].parentId, "u1 — корень")
        assertNull(msgs[1].parentId, "системная заметка не связывается")
        assertEquals(msgs[0].id, msgs[2].parentId, "a1 ← u1")
        assertEquals(msgs[2].id, msgs[3].parentId, "u2 ← a1 (минуя системную заметку)")
        assertEquals(msgs[3].id, msgs[4].parentId, "a2 ← u2")

        // идемпотентность: повторный прогон ничего не меняет
        val before = store.getStored("s")
        store.backfillLinearParents("s")
        assertEquals(before, store.getStored("s"))
    }

    @Test
    fun `ensureDefault creates exactly one branch and is idempotent`() {
        val (store, branches) = branching()
        val id1 = store.append("s", "user", "u1")
        val def = branches.ensureDefault("s", id1)
        assertEquals("Основная", def.name)
        val again = branches.ensureDefault("s", id1)
        assertEquals(def.id, again.id, "повторный вызов не создаёт вторую ветку")
        assertEquals(1, branches.list("s").size)
    }

    @Test
    fun `fork from message gives branches with independent heads and shared ancestry`() {
        val (store, branches) = branching()
        val u1 = store.append("s", "user", "u1")
        val a1 = store.append("s", "assistant", "a1")
        val u2 = store.append("s", "user", "u2")
        val a2 = store.append("s", "assistant", "a2")

        // активная сейчас — «Основная» (голова a2); «форкаем» от a2
        val main = branches.list("s").first()
        val b = branches.create("s", "Ветка 2", a2)
        branches.setActive("s", b.id)

        // продолжение пишется в ВЕТКУ B (активна): parent = голова B
        val q1 = store.append("s", "user", "q1-b")
        val r1 = store.append("s", "assistant", "r1-b")
        assertEquals(listOf(u1, a1, u2, a2, q1, r1), store.getBranchChain("s", r1).map { it.id })

        // голова «Основной» не тронута
        assertEquals(a2, branches.get("s", main.id)!!.headMessageId)
        assertEquals(listOf(u1, a1, u2, a2), store.getBranchChain("s", a2).map { it.id })

        // переключаемся на «Основную» — append уходит туда и НЕ виден в B
        branches.setActive("s", main.id)
        val q2 = store.append("s", "user", "q2-main")
        assertEquals(listOf(u1, a1, u2, a2, q2), store.getBranchChain("s", q2).map { it.id })
        val bHead = branches.get("s", b.id)!!.headMessageId!!
        assertEquals(listOf(u1, a1, u2, a2, q1, r1), store.getBranchChain("s", bHead).map { it.id })
        assertTrue(store.getBranchChain("s", bHead).none { it.content == "q2-main" })
    }

    @Test
    fun `setActive and getActive roundtrip`() {
        val (_, branches) = branching()
        val b1 = branches.create("s", "Ветка 1", null)
        assertNull(branches.getActive("s"), "после create без setActive активной нет")
        branches.setActive("s", b1.id)
        assertEquals(b1.id, branches.getActive("s")!!.id)
        assertEquals(b1.id, branches.effectiveBranch("s")!!.id)
        // неизвестная ветка — активная не находится (getActive null, список без изменений)
        branches.setActive("s", 999L)
        assertNull(branches.getActive("s"))
    }

    @Test
    fun `remove deletes branches for session only`() {
        val (_, branches) = branching()
        branches.create("s1", "Ветка 1", null)
        branches.create("s2", "Ветка 1", null)
        branches.remove("s1")
        assertTrue(branches.list("s1").isEmpty())
        assertEquals(1, branches.list("s2").size)
    }
}
