package com.druk.lmplayground.data

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update

@Dao
interface ChatDao {

    @Query("SELECT * FROM chat_sessions ORDER BY pinned DESC, updatedAt DESC")
    fun getAllSessions(): LiveData<List<ChatSessionEntity>>

    @Query("SELECT * FROM chat_messages WHERE sessionId = :sessionId ORDER BY timestamp ASC")
    suspend fun getMessages(sessionId: String): List<ChatMessageEntity>

    @Insert
    suspend fun insertSession(session: ChatSessionEntity)

    @Insert
    suspend fun insertMessage(message: ChatMessageEntity)

    @Query("UPDATE chat_sessions SET updatedAt = :updatedAt WHERE id = :sessionId")
    suspend fun updateSessionTimestamp(sessionId: String, updatedAt: Long)

    @Query("UPDATE chat_sessions SET title = :title WHERE id = :sessionId")
    suspend fun updateSessionTitle(sessionId: String, title: String)

    @Query("UPDATE chat_sessions SET modelFilename = :filename, modelName = :name WHERE id = :sessionId")
    suspend fun updateSessionModel(sessionId: String, filename: String, name: String)

    @Query("UPDATE chat_sessions SET pinned = :pinned WHERE id = :sessionId")
    suspend fun updateSessionPinned(sessionId: String, pinned: Boolean)

    @Query("""UPDATE chat_sessions SET
        contextSize = :contextSize, temperature = :temperature, topP = :topP,
        repetitionPenalty = :repetitionPenalty, topK = :topK, minP = :minP, seed = :seed,
        thinkingBudget = :thinkingBudget
        WHERE id = :sessionId""")
    suspend fun updateSessionParams(
        sessionId: String,
        contextSize: Int, temperature: Float, topP: Float,
        repetitionPenalty: Float, topK: Int, minP: Float, seed: Int,
        thinkingBudget: Int
    )

    @Query("UPDATE chat_sessions SET systemPrompt = :systemPrompt WHERE id = :sessionId")
    suspend fun updateSessionSystemPrompt(sessionId: String, systemPrompt: String)

    @Query("UPDATE chat_sessions SET metadata = :metadata WHERE id = :sessionId")
    suspend fun updateSessionMetadata(sessionId: String, metadata: String)

    @Query("SELECT * FROM chat_sessions WHERE id = :sessionId")
    suspend fun getSession(sessionId: String): ChatSessionEntity?

    @Query("DELETE FROM chat_sessions WHERE id = :sessionId")
    suspend fun deleteSession(sessionId: String)
}
