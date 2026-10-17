package com.druk.lmplayground.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One chunk of an attached document's extracted text plus its embedding
 * vector ([embedding] is an L2-normalized float32 array, little-endian —
 * see [EmbeddingCodec]).
 */
@Entity(
    tableName = "rag_chunks",
    foreignKeys = [
        ForeignKey(
            entity = RagDocumentEntity::class,
            parentColumns = ["id"],
            childColumns = ["documentId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("documentId")]
)
data class RagChunkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val documentId: String,
    /** Position of this chunk within the document (0-based). */
    val ordinal: Int,
    val text: String,
    val embedding: ByteArray,
) {
    // ByteArray field: identity by primary key is enough; avoids the
    // data-class array equals/hashCode lint trap.
    override fun equals(other: Any?): Boolean = other is RagChunkEntity && other.id == id
    override fun hashCode(): Int = id.hashCode()
}
