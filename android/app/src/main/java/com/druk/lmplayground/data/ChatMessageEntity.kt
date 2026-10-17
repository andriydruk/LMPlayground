package com.druk.lmplayground.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "chat_messages",
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
data class ChatMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: String,
    val author: String,
    val content: String,
    val thinkingDurationSeconds: Int = 0,
    val thinkingTokens: Int = 0,
    val responseTokens: Int = 0,
    val responseDurationSeconds: Float = 0f,
    val timestamp: Long,
    // Absolute path of the attached image persisted under filesDir/chat_images,
    // null for text-only messages. Restored to a FileProvider URI on load.
    val imagePath: String? = null
)
