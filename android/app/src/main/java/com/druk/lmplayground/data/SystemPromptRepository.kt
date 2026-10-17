package com.druk.lmplayground.data

import androidx.lifecycle.LiveData

class SystemPromptRepository(private val dao: SystemPromptDao) {

    /** Library list — sorted by updatedAt DESC (with createdAt as tie-break). */
    fun getAll(): LiveData<List<SystemPromptEntity>> = dao.getAll()

    suspend fun getAllSnapshot(): List<SystemPromptEntity> = dao.getAllSnapshot()

    /** Per-model MRU list, reactive. */
    fun getRecentForModelLive(modelFilename: String): LiveData<List<SystemPromptEntity>> =
        dao.getRecentForModelLive(modelFilename)

    suspend fun getRecentForModel(modelFilename: String, limit: Int): List<SystemPromptEntity> =
        dao.getRecentForModel(modelFilename, limit)

    suspend fun getById(id: String): SystemPromptEntity? = dao.getById(id)

    suspend fun findByText(text: String): SystemPromptEntity? = dao.findByText(text)

    suspend fun insert(prompt: SystemPromptEntity) = dao.insert(prompt)

    suspend fun update(prompt: SystemPromptEntity) = dao.update(prompt)

    suspend fun delete(id: String) = dao.delete(id)

    /** Mark [promptId] as just-used for [modelFilename]. */
    suspend fun touchUsage(
        promptId: String,
        modelFilename: String,
        now: Long = System.currentTimeMillis()
    ) {
        if (modelFilename.isEmpty()) return
        dao.touchUsage(PromptUsage(promptId, modelFilename, now))
    }
}
