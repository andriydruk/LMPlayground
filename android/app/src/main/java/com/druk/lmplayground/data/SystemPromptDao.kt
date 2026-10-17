package com.druk.lmplayground.data

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

@Dao
interface SystemPromptDao {

    /** All prompts for the Settings library list, sorted by last edit. */
    @Query("SELECT * FROM system_prompts ORDER BY updatedAt DESC, createdAt DESC")
    fun getAll(): LiveData<List<SystemPromptEntity>>

    /** Snapshot variant of [getAll] — handy for tests/background reads. */
    @Query("SELECT * FROM system_prompts ORDER BY updatedAt DESC, createdAt DESC")
    suspend fun getAllSnapshot(): List<SystemPromptEntity>

    /**
     * Per-model MRU ranking. Prompts that were used on [modelFilename] are
     * ordered by their `prompt_usage.lastUsedAt` DESC; prompts never used on
     * this model fall back to creation-time order. The LEFT JOIN + COALESCE
     * keeps never-used entries visible at the tail.
     */
    @Query(
        """
        SELECT p.* FROM system_prompts p
        LEFT JOIN prompt_usage u
          ON p.id = u.promptId AND u.modelFilename = :modelFilename
        ORDER BY COALESCE(u.lastUsedAt, 0) DESC, p.createdAt DESC
        """
    )
    fun getRecentForModelLive(modelFilename: String): LiveData<List<SystemPromptEntity>>

    @Query(
        """
        SELECT p.* FROM system_prompts p
        LEFT JOIN prompt_usage u
          ON p.id = u.promptId AND u.modelFilename = :modelFilename
        ORDER BY COALESCE(u.lastUsedAt, 0) DESC, p.createdAt DESC
        LIMIT :limit
        """
    )
    suspend fun getRecentForModel(modelFilename: String, limit: Int): List<SystemPromptEntity>

    @Query("SELECT * FROM system_prompts WHERE id = :id")
    suspend fun getById(id: String): SystemPromptEntity?

    @Query("SELECT * FROM system_prompts WHERE text = :text LIMIT 1")
    suspend fun findByText(text: String): SystemPromptEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(prompt: SystemPromptEntity)

    @Update
    suspend fun update(prompt: SystemPromptEntity)

    @Query("DELETE FROM system_prompts WHERE id = :id")
    suspend fun delete(id: String)

    /** Upsert a per-model usage marker. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun touchUsage(usage: PromptUsage)
}
