package com.druk.lmplayground.data

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface RagDao {

    @Insert
    suspend fun insertDocument(document: RagDocumentEntity)

    @Insert
    suspend fun insertChunks(chunks: List<RagChunkEntity>)

    @Query("SELECT * FROM rag_documents WHERE sessionId = :sessionId ORDER BY createdAt ASC")
    fun observeDocuments(sessionId: String): LiveData<List<RagDocumentEntity>>

    @Query("SELECT * FROM rag_documents WHERE id = :documentId")
    suspend fun getDocument(documentId: String): RagDocumentEntity?

    @Query(
        """UPDATE rag_documents SET
            status = 'READY', chunkCount = :chunkCount, embeddingDim = :embeddingDim
            WHERE id = :documentId"""
    )
    suspend fun markDocumentReady(documentId: String, chunkCount: Int, embeddingDim: Int)

    @Query(
        """SELECT rag_chunks.* FROM rag_chunks
            INNER JOIN rag_documents ON rag_chunks.documentId = rag_documents.id
            WHERE rag_documents.sessionId = :sessionId
            AND rag_documents.status = 'READY'
            ORDER BY rag_chunks.documentId, rag_chunks.ordinal"""
    )
    suspend fun getReadyChunksForSession(sessionId: String): List<RagChunkEntity>

    @Query(
        """SELECT COUNT(*) FROM rag_documents
            WHERE sessionId = :sessionId AND status = 'READY'"""
    )
    suspend fun countReadyDocuments(sessionId: String): Int

    @Query("SELECT displayName FROM rag_documents WHERE id = :documentId")
    suspend fun getDocumentName(documentId: String): String?

    @Query("DELETE FROM rag_documents WHERE id = :documentId")
    suspend fun deleteDocument(documentId: String)

    /**
     * Documents stuck INDEXING from a previous process death are dropped
     * (their chunks are only committed on success, so there is nothing to
     * keep — the user just re-attaches). Run once at app start.
     */
    @Query("DELETE FROM rag_documents WHERE status = 'INDEXING'")
    suspend fun deleteStaleIndexing()
}
