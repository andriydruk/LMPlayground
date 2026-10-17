package com.druk.lmplayground.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/**
 * Per-model MRU marker for a system prompt. One row per (prompt, model)
 * pair; [lastUsedAt] advances each time the user applies that prompt while
 * that model is loaded.
 *
 * The ON DELETE CASCADE on [promptId] keeps the table clean when a prompt
 * is deleted from the library.
 */
@Entity(
    tableName = "prompt_usage",
    primaryKeys = ["promptId", "modelFilename"],
    foreignKeys = [
        ForeignKey(
            entity = SystemPromptEntity::class,
            parentColumns = ["id"],
            childColumns = ["promptId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("modelFilename")]
)
data class PromptUsage(
    val promptId: String,
    val modelFilename: String,
    val lastUsedAt: Long
)
