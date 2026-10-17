package com.druk.lmplayground.rag

import android.app.Application
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.druk.lmplayground.data.AppDatabase
import com.druk.lmplayground.data.ChatSessionEntity
import com.druk.lmplayground.data.RagDocumentEntity
import com.druk.lmplayground.models.ModelInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import kotlin.math.sqrt

/**
 * Deterministic embedder: maps text to a 4-dim L2-normalized vector from
 * simple lexical features, so "similar" strings (sharing marker words)
 * score higher than unrelated ones.
 */
private class FakeEmbedder(var failEmbeds: Boolean = false) : TextEmbedder {
    override val modelInfo = ModelInfo(
        name = "Fake Embedder",
        filename = "fake-embedder.gguf",
        description = "test",
    )

    override fun isModelOnDisk(): Boolean = true

    override suspend fun embedDocumentChunks(chunks: List<String>): List<FloatArray>? =
        if (failEmbeds) null else chunks.map { embed(it) }

    override suspend fun embedQuery(query: String): FloatArray? =
        if (failEmbeds) null else embed(query)

    private fun embed(text: String): FloatArray {
        val lower = text.lowercase()
        val vector = floatArrayOf(
            1f, // shared component: any two texts have some similarity
            if ("cat" in lower || "kitten" in lower) 3f else 0f,
            if ("engine" in lower || "carburetor" in lower) 3f else 0f,
            if ("revenue" in lower || "profit" in lower) 3f else 0f,
        )
        val norm = sqrt(vector.sumOf { (it * it).toDouble() }).toFloat()
        return FloatArray(vector.size) { vector[it] / norm }
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class RagRepositoryTest {

    private lateinit var app: Application
    private lateinit var db: AppDatabase
    private lateinit var embedder: FakeEmbedder
    private lateinit var scope: CoroutineScope
    private lateinit var collectorScope: CoroutineScope
    private lateinit var repository: RagRepository
    private val failures = mutableListOf<RagRepository.IndexingFailure>()

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        embedder = FakeEmbedder()
        // Unconfined: indexing jobs run to completion inside attachDocument.
        scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher())
        repository = RagRepository(app, db.ragDao(), embedder, scope)
        // Failure events land here. Separate scope: the collector never
        // completes, so it must not be one of the children attachAndAwait
        // joins.
        failures.clear()
        collectorScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher())
        collectorScope.launch { repository.indexingFailures.collect { failures.add(it) } }
    }

    @After
    fun tearDown() {
        collectorScope.cancel()
        db.close()
    }

    private fun createSession(id: String = "session-1"): String = runBlocking {
        db.chatDao().insertSession(
            ChatSessionEntity(
                id = id,
                title = "test",
                modelFilename = "m.gguf",
                modelName = "M",
                createdAt = 1L,
                updatedAt = 1L,
            )
        )
        id
    }

    private fun writeTextFile(name: String, content: String): Uri {
        val file = File(app.cacheDir, name)
        file.writeText(content)
        return Uri.fromFile(file)
    }

    private fun attachAndAwait(
        sessionId: String,
        uri: Uri,
        name: String,
        mime: String? = "text/plain",
        size: Long = 100L,
    ): String = runBlocking {
        val docId = repository.attachDocument(sessionId, uri, name, mime, size)
        scope.coroutineContext.job.children.forEach { it.join() }
        docId
    }

    @Test
    fun `attach indexes a text document to READY with chunks`() = runBlocking {
        val sessionId = createSession()
        val uri = writeTextFile("cats.txt", "The cat sat on the mat.\n\nA kitten purrs loudly.")

        val docId = attachAndAwait(sessionId, uri, "cats.txt")

        val doc = db.ragDao().getDocument(docId)!!
        assertEquals(RagDocumentEntity.STATUS_READY, doc.status)
        assertTrue(doc.chunkCount > 0)
        assertEquals(4, doc.embeddingDim)
        // Source link kept so the chip can reopen the original.
        assertEquals(uri.toString(), doc.sourceUri)
        assertTrue(repository.hasReadyDocuments(sessionId))
        assertEquals(doc.chunkCount, db.ragDao().getReadyChunksForSession(sessionId).size)
    }

    @Test
    fun `retrieve ranks matching content first and applies minScore`() = runBlocking {
        val sessionId = createSession()
        val text = "The cat chased a kitten around the house.\n\n" +
            "The engine needs a new carburetor this year.\n\n" +
            "Quarterly revenue and profit both grew."
        attachAndAwait(sessionId, writeTextFile("mixed.txt", text), "mixed.txt")

        val results = repository.retrieve(sessionId, "why does my cat like kittens", topK = 2, minScore = 0.5f)

        assertTrue(results.isNotEmpty())
        assertTrue(results[0].text.contains("cat"))
        assertEquals("mixed.txt", results[0].documentName)
        // Scores are descending.
        for (i in 1 until results.size) {
            assertTrue(results[i - 1].score >= results[i].score)
        }
        // An impossible threshold yields nothing.
        assertEquals(
            emptyList<RagRepository.RetrievedChunk>(),
            repository.retrieve(sessionId, "cat", minScore = 0.999f),
        )
    }

    @Test
    fun `retrieval is scoped to the session`() = runBlocking {
        val sessionA = createSession("session-a")
        val sessionB = createSession("session-b")
        attachAndAwait(sessionA, writeTextFile("a.txt", "The cat document."), "a.txt")

        assertFalse(repository.hasReadyDocuments(sessionB))
        assertEquals(
            emptyList<RagRepository.RetrievedChunk>(),
            repository.retrieve(sessionB, "cat"),
        )
    }

    @Test
    fun `unsupported format deletes the document and reports the reason`() = runBlocking {
        val sessionId = createSession()
        val uri = writeTextFile("data.bin", "binary-ish")

        val docId = attachAndAwait(sessionId, uri, "data.bin", mime = "application/octet-stream")

        // No chip left behind — the failure arrives as a one-shot event.
        assertEquals(null, db.ragDao().getDocument(docId))
        assertEquals(
            listOf(RagRepository.IndexingFailure("data.bin", "UNSUPPORTED")),
            failures,
        )
        assertFalse(repository.hasReadyDocuments(sessionId))
    }

    @Test
    fun `embedding failure deletes the document and reports the reason`() = runBlocking {
        val sessionId = createSession()
        embedder.failEmbeds = true

        val docId = attachAndAwait(sessionId, writeTextFile("t.txt", "some text"), "t.txt")

        assertEquals(null, db.ragDao().getDocument(docId))
        assertEquals(RagRepository.ERROR_EMBEDDING, failures.single().reasonCode)
    }

    @Test
    fun `oversized file is rejected as TOO_LARGE`() = runBlocking {
        val sessionId = createSession()
        val docId = attachAndAwait(
            sessionId,
            writeTextFile("big.txt", "x"),
            "big.txt",
            size = DocumentTextExtractor.MAX_FILE_BYTES + 1,
        )

        assertEquals(null, db.ragDao().getDocument(docId))
        assertEquals("TOO_LARGE", failures.single().reasonCode)
    }

    @Test
    fun `deleteDocument cascades to chunks`() = runBlocking {
        val sessionId = createSession()
        val docId = attachAndAwait(sessionId, writeTextFile("d.txt", "The cat."), "d.txt")
        assertTrue(db.ragDao().getReadyChunksForSession(sessionId).isNotEmpty())

        repository.deleteDocument(docId)

        assertEquals(0, db.ragDao().getReadyChunksForSession(sessionId).size)
        assertFalse(repository.hasReadyDocuments(sessionId))
    }

    @Test
    fun `deleting the chat session cascades to documents and chunks`() = runBlocking {
        val sessionId = createSession()
        val docId = attachAndAwait(sessionId, writeTextFile("e.txt", "The cat."), "e.txt")

        db.chatDao().deleteSession(sessionId)

        assertEquals(null, db.ragDao().getDocument(docId))
    }

    @Test
    fun `stale INDEXING documents are dropped at startup`() = runBlocking {
        val sessionId = createSession()
        db.ragDao().insertDocument(
            RagDocumentEntity(
                id = "stuck",
                sessionId = sessionId,
                displayName = "stuck.pdf",
                mimeType = "application/pdf",
                sizeBytes = 10L,
                status = RagDocumentEntity.STATUS_INDEXING,
                createdAt = 1L,
            )
        )
        // A READY sibling must survive the sweep.
        val readyId = attachAndAwait(sessionId, writeTextFile("ok.txt", "The cat."), "ok.txt")

        db.ragDao().deleteStaleIndexing()

        assertEquals(null, db.ragDao().getDocument("stuck"))
        assertEquals(RagDocumentEntity.STATUS_READY, db.ragDao().getDocument(readyId)!!.status)
    }
}
