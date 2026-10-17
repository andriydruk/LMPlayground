package com.druk.lmplayground

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.druk.lmplayground.data.AppDatabase
import com.druk.lmplayground.data.SystemPromptEntity
import com.druk.lmplayground.data.SystemPromptRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/**
 * Verifies the system-prompt library persists, orders by last-update, ranks
 * per-model MRU through the prompt_usage table, and round-trips the CRUD
 * APIs correctly.
 */
@RunWith(AndroidJUnit4::class)
class SystemPromptRepositoryTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: SystemPromptRepository

    @Before
    fun setUp() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repo = SystemPromptRepository(db.systemPromptDao())
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun prompt(
        text: String,
        createdAt: Long = 0L,
        updatedAt: Long = createdAt
    ) = SystemPromptEntity(
        id = UUID.randomUUID().toString(),
        text = text,
        createdAt = createdAt,
        updatedAt = updatedAt
    )

    @Test
    fun insertAndReadBack() = runBlocking {
        val p = prompt("Answer in French.", createdAt = 1L)
        repo.insert(p)
        val loaded = repo.getById(p.id)
        assertNotNull(loaded)
        assertEquals("Answer in French.", loaded!!.text)
    }

    @Test
    fun libraryOrdersByUpdatedAtThenCreatedAt() = runBlocking {
        val a = prompt("A", createdAt = 100, updatedAt = 100)
        val b = prompt("B", createdAt = 200, updatedAt = 200)
        val c = prompt("C", createdAt = 300, updatedAt = 300)
        repo.insert(a); repo.insert(b); repo.insert(c)

        // No updates yet → library sorted by updatedAt (== createdAt) DESC.
        val initialOrder = repo.getAllSnapshot().map { it.text }
        assertEquals(listOf("C", "B", "A"), initialOrder)

        // Bumping `updatedAt` on A moves it to the top of the library listing.
        repo.update(a.copy(updatedAt = 500))
        val afterUpdate = repo.getAllSnapshot().map { it.text }
        assertEquals(listOf("A", "C", "B"), afterUpdate)
    }

    @Test
    fun perModelMruIsIndependentAcrossModels() = runBlocking {
        val a = prompt("A", createdAt = 100)
        val b = prompt("B", createdAt = 200)
        val c = prompt("C", createdAt = 300)
        repo.insert(a); repo.insert(b); repo.insert(c)

        val dao = db.systemPromptDao()

        // Model X: user picks B, then A.
        repo.touchUsage(b.id, "modelX.gguf", now = 1000)
        repo.touchUsage(a.id, "modelX.gguf", now = 2000)
        val orderX = dao.getRecentForModel("modelX.gguf", 10).map { it.text }
        // A is most recent, then B, then C (never used on X → createdAt fallback).
        assertEquals(listOf("A", "B", "C"), orderX)

        // Model Y: user picks only C.
        repo.touchUsage(c.id, "modelY.gguf", now = 500)
        val orderY = dao.getRecentForModel("modelY.gguf", 10).map { it.text }
        // C is most recent on Y; A and B fall back to createdAt DESC.
        assertEquals(listOf("C", "B", "A"), orderY)

        // Model X is unaffected by Y's activity.
        val orderXAgain = dao.getRecentForModel("modelX.gguf", 10).map { it.text }
        assertEquals(listOf("A", "B", "C"), orderXAgain)
    }

    @Test
    fun deletingPromptCascadesUsage() = runBlocking {
        val p = prompt("temp", createdAt = 100)
        repo.insert(p)
        repo.touchUsage(p.id, "modelX.gguf", now = 500)
        repo.delete(p.id)
        // After delete, MRU for model X no longer knows about p.
        val orderX = db.systemPromptDao().getRecentForModel("modelX.gguf", 10)
        assertEquals(emptyList<SystemPromptEntity>(), orderX)
        assertNull(repo.getById(p.id))
    }

    @Test
    fun findByTextMatchesExact() = runBlocking {
        val p = prompt("Always use JSON.", createdAt = 100)
        repo.insert(p)
        val found = repo.findByText("Always use JSON.")
        assertEquals(p.id, found?.id)
        val miss = repo.findByText("always use json.")
        assertNull(miss)
    }
}
