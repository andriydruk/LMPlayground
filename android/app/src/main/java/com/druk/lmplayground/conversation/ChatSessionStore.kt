package com.druk.lmplayground.conversation

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.druk.lmplayground.data.ChatMessageEntity
import com.druk.lmplayground.data.ChatRepository
import com.druk.lmplayground.data.ChatSessionEntity
import com.druk.lmplayground.data.ConversationMetadata
import com.druk.lmplayground.data.SystemPromptEntity
import com.druk.lmplayground.data.SystemPromptRepository
import com.druk.lmplayground.models.ModelInfo
import java.io.File
import java.util.UUID

/**
 * Persistence facade for chat sessions: wraps [ChatRepository] and
 * [SystemPromptRepository] and owns the entity ↔ UI-model mapping.
 * Holds no UI state — the ViewModel keeps the current-session id and
 * passes it in. Both repositories are nullable because the `:llama`
 * process runs the same Application class without Room initialized.
 */
class ChatSessionStore(
    private val chatRepository: ChatRepository?,
    private val systemPromptRepository: SystemPromptRepository?,
    private val imageStore: ChatImageStore,
) {

    fun allSessions(): LiveData<List<ChatSessionEntity>> =
        chatRepository?.getAllSessions() ?: MutableLiveData(emptyList())

    /** Per-model MRU system prompts, most recently used first. */
    fun recentSystemPromptsForModel(modelFilename: String?): LiveData<List<SystemPromptEntity>> {
        val repo = systemPromptRepository
        return if (repo == null || modelFilename.isNullOrEmpty()) {
            MutableLiveData(emptyList())
        } else {
            repo.getRecentForModelLive(modelFilename)
        }
    }

    /**
     * Create and persist a new session row titled after [firstUserMessage],
     * carrying the loaded model's identity, the active generation params and
     * system prompt. Returns the new session id.
     */
    suspend fun createSession(
        firstUserMessage: Message,
        modelInfo: ModelInfo?,
        params: GenerationParams,
        systemPrompt: String,
    ): String = createSession(firstUserMessage.content.take(50), modelInfo, params, systemPrompt)

    /**
     * [createSession] variant with an explicit [title] — used when a session
     * row must exist before the first message (e.g. attaching a document to
     * a brand-new chat, where the rag_documents FK needs the session).
     */
    suspend fun createSession(
        title: String,
        modelInfo: ModelInfo?,
        params: GenerationParams,
        systemPrompt: String,
    ): String {
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        chatRepository?.insertSession(
            ChatSessionEntity(
                id = id,
                title = title,
                modelFilename = modelInfo?.filename ?: "",
                modelName = modelInfo?.name ?: "Unknown",
                createdAt = now,
                updatedAt = now,
                contextSize = params.contextSize,
                temperature = params.temperature,
                topP = params.topP,
                repetitionPenalty = params.repetitionPenalty,
                topK = params.topK,
                minP = params.minP,
                seed = params.seed,
                thinkingBudget = params.thinkingBudget,
                systemPrompt = systemPrompt
            )
        )
        return id
    }

    suspend fun persistMessage(
        sessionId: String,
        message: Message,
        imagePath: String? = null,
    ) {
        chatRepository?.insertMessage(
            ChatMessageEntity(
                sessionId = sessionId,
                author = message.author,
                content = message.content,
                thinkingDurationSeconds = message.thinkingDurationSeconds,
                thinkingTokens = message.thinkingTokens,
                responseTokens = message.responseTokens,
                responseDurationSeconds = message.responseDurationSeconds,
                timestamp = message.timestamp,
                imagePath = imagePath
            )
        )
    }

    /**
     * Snapshot the web_search link references into the conversation's metadata
     * so a returned ref still resolves after the app is restarted and the
     * conversation reopened. Read-modify-write to preserve any other metadata
     * keys. No-op when there are no references to save.
     */
    suspend fun persistWebLinks(sessionId: String, links: Map<String, String>) {
        val repo = chatRepository ?: return
        if (links.isEmpty()) return
        val existing = repo.getSession(sessionId)?.metadata
        val updated = ConversationMetadata.parse(existing)
            .putStringMap(ConversationMetadata.KEY_WEB_LINKS, links)
            .toJson()
        repo.updateSessionMetadata(sessionId, updated)
    }

    /**
     * Load a session's messages mapped to UI models. The display URI is
     * re-derived from the persisted image file; dropped if the file is gone
     * so the bubble just shows text. Returns null when persistence is
     * unavailable (no Room in this process).
     */
    suspend fun loadSessionMessages(sessionId: String): List<Message>? {
        val entities = chatRepository?.getMessages(sessionId) ?: return null
        return entities.map { entity ->
            val imageUri = entity.imagePath?.let { path ->
                val file = File(path)
                if (file.exists()) imageStore.imageContentUri(file) else null
            }
            Message(
                author = entity.author,
                content = entity.content,
                thinkingDurationSeconds = entity.thinkingDurationSeconds,
                thinkingTokens = entity.thinkingTokens,
                responseTokens = entity.responseTokens,
                responseDurationSeconds = entity.responseDurationSeconds,
                timestamp = entity.timestamp,
                imageUri = imageUri
            )
        }
    }

    suspend fun getSession(sessionId: String): ChatSessionEntity? =
        chatRepository?.getSession(sessionId)

    suspend fun touchSessionTimestamp(sessionId: String) {
        chatRepository?.updateSessionTimestamp(sessionId, System.currentTimeMillis())
    }

    suspend fun updateSessionModel(sessionId: String, modelFilename: String, modelName: String) {
        chatRepository?.updateSessionModel(sessionId, modelFilename, modelName)
    }

    suspend fun updateSessionParams(sessionId: String, params: GenerationParams) {
        chatRepository?.updateSessionParams(
            sessionId,
            params.contextSize, params.temperature, params.topP,
            params.repetitionPenalty, params.topK, params.minP, params.seed,
            params.thinkingBudget
        )
    }

    suspend fun updateSessionSystemPrompt(sessionId: String, text: String) {
        chatRepository?.updateSessionSystemPrompt(sessionId, text)
    }

    suspend fun findSystemPromptByText(text: String): SystemPromptEntity? =
        systemPromptRepository?.findByText(text)

    suspend fun touchSystemPromptUsage(promptId: String, modelFilename: String) {
        systemPromptRepository?.touchUsage(promptId, modelFilename)
    }

    /** True when the system-prompt library is available in this process. */
    val systemPromptsAvailable: Boolean
        get() = systemPromptRepository != null

    /**
     * Overwrite the text of an existing library entry. Returns false when
     * the library is unavailable or the entry no longer exists.
     */
    suspend fun updateSystemPromptText(promptId: String, text: String): Boolean {
        val repo = systemPromptRepository ?: return false
        val existing = repo.getById(promptId) ?: return false
        repo.update(existing.copy(text = text))
        return true
    }

    /**
     * Persist a brand-new system prompt to the library. Returns null when
     * the library is unavailable in this process.
     */
    suspend fun createSystemPrompt(text: String): SystemPromptEntity? {
        val repo = systemPromptRepository ?: return null
        val now = System.currentTimeMillis()
        val entity = SystemPromptEntity(
            id = UUID.randomUUID().toString(),
            text = text,
            createdAt = now,
            updatedAt = now
        )
        repo.insert(entity)
        return entity
    }

    suspend fun renameSession(sessionId: String, newTitle: String) {
        chatRepository?.updateSessionTitle(sessionId, newTitle)
    }

    suspend fun pinSession(sessionId: String, pinned: Boolean) {
        chatRepository?.updateSessionPinned(sessionId, pinned)
    }

    /**
     * Delete a session row and its messages. On-disk image copies are
     * deleted first: the DB CASCADE removes the message rows but not the
     * files they point at.
     */
    suspend fun deleteSession(sessionId: String) {
        chatRepository?.getMessages(sessionId)?.forEach { entity ->
            entity.imagePath?.let { path ->
                try { File(path).delete() } catch (_: Exception) {}
            }
        }
        chatRepository?.deleteSession(sessionId)
    }
}
