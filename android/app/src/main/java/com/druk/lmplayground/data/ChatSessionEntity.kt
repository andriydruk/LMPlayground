package com.druk.lmplayground.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "chat_sessions")
data class ChatSessionEntity(
    @PrimaryKey val id: String,
    val title: String,
    val modelFilename: String,
    val modelName: String,
    val createdAt: Long,
    val updatedAt: Long,
    val pinned: Boolean = false,
    val contextSize: Int = 4096,
    val temperature: Float = 0.8f,
    val topP: Float = 0.95f,
    val repetitionPenalty: Float = 1.0f,
    val topK: Int = 40,
    val minP: Float = 0.05f,
    val seed: Int = -1,
    val thinkingBudget: Int = 1024,
    val systemPrompt: String = "",
    /**
     * Generic per-conversation metadata as a JSON object string. Currently holds
     * the web_search link map; future features can add keys without a schema
     * change. See [ConversationMetadata].
     */
    val metadata: String = "{}"
)
