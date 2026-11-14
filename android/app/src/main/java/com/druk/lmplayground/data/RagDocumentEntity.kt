package com.druk.lmplayground.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A document attached to a chat session for RAG (document Q&A). The
 * extracted text lives in [RagChunkEntity] rows; the original file is NOT
 * copied — only its chunked text and embeddings, plus [sourceUri] as a
 * link back to the original (openable for as long as the persisted SAF
 * grant and the file itself survive).
 */
@Entity(
    tableName = "rag_documents",
    foreignKeys = [
        ForeignKey(
            entity = ChatSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("sessionId")]
)
data class RagDocumentEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val displayName: String,
    val mimeType: String?,
    /**
     * The picked document's content URI, held with a persisted read grant
     * so tapping the chip can reopen the original across app restarts.
     * Null for legacy rows; opening degrades to a toast when the file or
     * grant is gone.
     */
    val sourceUri: String? = null,
    val sizeBytes: Long,
    /**
     * [STATUS_INDEXING] or [STATUS_READY]. Failed documents are deleted,
     * not persisted — the UI surfaces the failure as a one-shot toast.
     */
    val status: String,
    val chunkCount: Int = 0,
    /** Dimension of the stored chunk vectors (retrieval sanity check). */
    val embeddingDim: Int = 0,
    /** Filename of the embedding model used, for reproducibility. */
    val embeddingModel: String = "",
    val createdAt: Long,
) {
    companion object {
        const val STATUS_INDEXING = "INDEXING"
        const val STATUS_READY = "READY"
    }
}
