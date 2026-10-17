package com.druk.lmplayground.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A reusable system-prompt entry. Users curate these in Settings and pick
 * one on the conversation screen before sending the first message.
 *
 * Ordering in Settings is by [updatedAt] DESC (falling back to [createdAt])
 * so recently-edited or recently-created entries bubble to the top.
 *
 * Per-model "recently used for this model" ordering lives in the separate
 * `prompt_usage` table — see [PromptUsage].
 */
@Entity(tableName = "system_prompts")
data class SystemPromptEntity(
    @PrimaryKey val id: String,
    val text: String,
    val createdAt: Long,
    val updatedAt: Long = 0L
)
