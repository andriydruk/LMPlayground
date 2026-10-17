package com.druk.lmplayground.rag

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.lifecycle.LiveData
import com.druk.lmplayground.data.EmbeddingCodec
import com.druk.lmplayground.data.RagChunkEntity
import com.druk.lmplayground.data.RagDao
import com.druk.lmplayground.data.RagDocumentEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Document (RAG) orchestration: indexing attached documents (extract →
 * chunk → embed → store) and retrieving the chunks most relevant to a
 * query via cosine similarity.
 *
 * Indexing runs on [applicationScope] so it survives UI navigation. Only
 * INDEXING and READY documents exist as rows (the chips the user sees);
 * a failed document is deleted and the failure reported once through
 * [indexingFailures] — reason codes are [DocumentExtractionException.Reason]
 * names plus [ERROR_EMBEDDING], mapped to localized text by the UI.
 */
class RagRepository(
    private val appContext: Context,
    private val ragDao: RagDao,
    private val embeddingManager: TextEmbedder,
    private val applicationScope: CoroutineScope,
) {

    data class RetrievedChunk(
        val documentName: String,
        val text: String,
        val score: Float,
    )

    data class IndexingFailure(val displayName: String, val reasonCode: String)

    /**
     * One event per failed indexing attempt (the document row is already
     * gone by then). Buffered so an emit without an active collector
     * isn't lost to a momentary resubscribe gap.
     */
    private val _indexingFailures = MutableSharedFlow<IndexingFailure>(extraBufferCapacity = 4)
    val indexingFailures: SharedFlow<IndexingFailure> = _indexingFailures

    fun observeDocuments(sessionId: String): LiveData<List<RagDocumentEntity>> =
        ragDao.observeDocuments(sessionId)

    /** Whether the embedding model's GGUF is on disk (SAF scan — call on IO). */
    fun isEmbeddingModelAvailable(): Boolean = embeddingManager.isModelOnDisk()

    suspend fun hasReadyDocuments(sessionId: String): Boolean =
        ragDao.countReadyDocuments(sessionId) > 0

    suspend fun deleteDocument(documentId: String) =
        ragDao.deleteDocument(documentId)

    /** Drop documents orphaned INDEXING by a process death. App-start hook. */
    fun resetStaleIndexing() {
        applicationScope.launch { ragDao.deleteStaleIndexing() }
    }

    /**
     * Insert the document row (status INDEXING — the chip's spinner) and
     * kick off the indexing job. The [sessionId] row must already exist
     * (FK). Returns the new document id immediately; success flips the row
     * to READY, failure deletes it and emits on [indexingFailures].
     */
    fun attachDocument(
        sessionId: String,
        uri: Uri,
        displayName: String,
        mimeType: String?,
        sizeBytes: Long,
    ): String {
        val documentId = UUID.randomUUID().toString()
        applicationScope.launch {
            ragDao.insertDocument(
                RagDocumentEntity(
                    id = documentId,
                    sessionId = sessionId,
                    displayName = displayName,
                    mimeType = mimeType,
                    sourceUri = uri.toString(),
                    sizeBytes = sizeBytes,
                    status = RagDocumentEntity.STATUS_INDEXING,
                    embeddingModel = embeddingManager.modelInfo.filename,
                    createdAt = System.currentTimeMillis(),
                )
            )
            try {
                indexDocument(documentId, uri, displayName, mimeType, sizeBytes)
            } catch (e: DocumentExtractionException) {
                Log.w(TAG, "Indexing failed for $displayName: ${e.reason}", e)
                dropFailedDocument(documentId, displayName, e.reason.name)
            } catch (t: Throwable) {
                Log.e(TAG, "Indexing failed for $displayName", t)
                dropFailedDocument(documentId, displayName, ERROR_EMBEDDING)
            }
        }
        return documentId
    }

    private suspend fun indexDocument(
        documentId: String,
        uri: Uri,
        displayName: String,
        mimeType: String?,
        sizeBytes: Long,
    ) {
        if (sizeBytes > DocumentTextExtractor.MAX_FILE_BYTES) {
            throw DocumentExtractionException(
                DocumentExtractionException.Reason.TOO_LARGE,
                "File is $sizeBytes bytes",
            )
        }
        val extractor = DocumentTextExtractors.forDocument(mimeType, displayName)
            ?: throw DocumentExtractionException(
                DocumentExtractionException.Reason.UNSUPPORTED,
                "No extractor for $mimeType / $displayName",
            )

        val text = withContext(Dispatchers.IO) { extractor.extract(appContext, uri) }
        val chunks = TextChunker.chunk(text)
        if (chunks.isEmpty()) {
            throw DocumentExtractionException(
                DocumentExtractionException.Reason.NO_TEXT,
                "Document produced no text",
            )
        }
        Log.i(TAG, "Indexing $displayName: ${text.length} chars → ${chunks.size} chunks")

        // Embed in small batches (each an AIDL round-trip) instead of one
        // manager call over the whole list, so a concurrent query embed in
        // another chat only waits one batch, not the whole document.
        var dim = 0
        val rows = ArrayList<RagChunkEntity>(chunks.size)
        for (batch in chunks.chunked(EMBED_BATCH_SIZE)) {
            val vectors = withContext(Dispatchers.Default) {
                embeddingManager.embedDocumentChunks(batch.map { it.text })
            } ?: throw IllegalStateException("Embedding failed")
            dim = vectors.firstOrNull()?.size ?: 0
            for ((chunk, vector) in batch.zip(vectors)) {
                rows.add(
                    RagChunkEntity(
                        documentId = documentId,
                        ordinal = chunk.ordinal,
                        text = chunk.text,
                        embedding = EmbeddingCodec.encode(vector),
                    )
                )
            }
        }

        ragDao.insertChunks(rows)
        ragDao.markDocumentReady(documentId, chunkCount = rows.size, embeddingDim = dim)
        Log.i(TAG, "Indexed $displayName: ${rows.size} chunks, dim=$dim")
    }

    /**
     * Top-[topK] chunks of this session's READY documents by cosine
     * similarity to [query] (vectors are pre-normalized, so a dot product
     * suffices). Brute force is fine at this scale: a few thousand
     * 768-dim vectors ≈ milliseconds. Empty when nothing clears
     * [minScore] or the embedding model is unavailable.
     */
    suspend fun retrieve(
        sessionId: String,
        query: String,
        topK: Int = DEFAULT_TOP_K,
        minScore: Float = DEFAULT_MIN_SCORE,
    ): List<RetrievedChunk> {
        val rows = ragDao.getReadyChunksForSession(sessionId)
        if (rows.isEmpty()) return emptyList()
        val queryVector = withContext(Dispatchers.Default) {
            embeddingManager.embedQuery(query)
        } ?: return emptyList()

        val top = withContext(Dispatchers.Default) {
            val scored = rows.mapNotNull { row ->
                val vector = EmbeddingCodec.decode(row.embedding)
                if (vector.size != queryVector.size) return@mapNotNull null
                row to dot(queryVector, vector)
            }.sortedByDescending { (_, score) -> score }
            Log.d(
                TAG,
                "retrieve: ${scored.size} chunks scored, top=" +
                    scored.take(topK).joinToString { (_, s) -> "%.3f".format(java.util.Locale.US, s) } +
                    ", minScore=$minScore",
            )
            scored.filter { (_, score) -> score >= minScore }.take(topK)
        }
        if (top.isEmpty()) return emptyList()

        val nameById = top.map { (row, _) -> row.documentId }.distinct()
            .associateWith { id -> ragDao.getDocumentName(id) ?: "document" }
        return top.map { (row, score) ->
            RetrievedChunk(
                documentName = nameById.getValue(row.documentId),
                text = row.text,
                score = score,
            )
        }
    }

    /**
     * A failed document leaves no trace in the chat — remove the row (the
     * chip's spinner vanishes) and report the reason once so the UI can
     * toast it.
     */
    private suspend fun dropFailedDocument(
        documentId: String,
        displayName: String,
        reasonCode: String,
    ) {
        ragDao.deleteDocument(documentId)
        _indexingFailures.tryEmit(IndexingFailure(displayName, reasonCode))
    }

    private fun dot(a: FloatArray, b: FloatArray): Float {
        var sum = 0f
        for (i in a.indices) sum += a[i] * b[i]
        return sum
    }

    companion object {
        private const val TAG = "RagRepository"

        /** errorMessage code: embedding model failed or unavailable. */
        const val ERROR_EMBEDDING = "EMBEDDING_FAILED"

        const val DEFAULT_TOP_K = 6
        const val DEFAULT_MIN_SCORE = 0.35f

        /** Chunks per embedding call — see indexDocument. */
        private const val EMBED_BATCH_SIZE = 16
    }
}
