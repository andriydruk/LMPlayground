package com.druk.lmplayground.conversation

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.druk.lmplayground.data.AppDatabase
import com.druk.lmplayground.data.ChatRepository
import com.druk.lmplayground.data.ConversationMetadata
import com.druk.lmplayground.data.SystemPromptRepository
import com.druk.lmplayground.models.ModelInfo
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class ChatSessionStoreTest {

    private lateinit var db: AppDatabase
    private lateinit var store: ChatSessionStore
    private lateinit var app: Application

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        store = ChatSessionStore(
            ChatRepository(db.chatDao()),
            SystemPromptRepository(db.systemPromptDao()),
            ChatImageStore(app),
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun model() = ModelInfo(
        name = "Test Model",
        filename = "test-model.gguf",
        description = "test"
    )

    private fun runCreateSession(
        firstMessage: String = "hello world",
        systemPrompt: String = "be brief",
    ): String = runBlocking {
        store.createSession(
            Message(author = "User", content = firstMessage),
            model(),
            GenerationParams(),
            systemPrompt,
        )
    }

    @Test
    fun createSessionPersistsRowWithTruncatedTitle() = runBlocking {
        val longMessage = "x".repeat(80)
        val id = runCreateSession(firstMessage = longMessage)

        val entity = store.getSession(id)
        assertNotNull(entity)
        assertEquals(50, entity!!.title.length)
        assertEquals("test-model.gguf", entity.modelFilename)
        assertEquals("Test Model", entity.modelName)
        assertEquals("be brief", entity.systemPrompt)
        assertEquals(GenerationParams().contextSize, entity.contextSize)
    }

    @Test
    fun persistAndLoadMessagesRoundTrip() = runBlocking {
        val id = runCreateSession()
        store.persistMessage(id, Message(author = "User", content = "q1", responseTokens = 0))
        store.persistMessage(
            id,
            Message(
                author = "Assistant",
                content = "a1",
                thinkingDurationSeconds = 3,
                thinkingTokens = 42,
                responseTokens = 100,
                responseDurationSeconds = 1.5f,
            )
        )

        val messages = store.loadSessionMessages(id)!!
        assertEquals(2, messages.size)
        assertEquals("q1", messages[0].content)
        val assistant = messages[1]
        assertEquals("a1", assistant.content)
        assertEquals(3, assistant.thinkingDurationSeconds)
        assertEquals(42, assistant.thinkingTokens)
        assertEquals(100, assistant.responseTokens)
        assertEquals(1.5f, assistant.responseDurationSeconds)
        assertNull(assistant.imageUri)
    }

    @Test
    fun loadDropsImageUriWhenFileIsGone() = runBlocking {
        val id = runCreateSession()
        store.persistMessage(
            id,
            Message(author = "User", content = "look"),
            imagePath = File(app.filesDir, "chat_images/gone.jpg").absolutePath,
        )
        val messages = store.loadSessionMessages(id)!!
        assertNull(messages[0].imageUri)
    }

    @Test
    fun deleteSessionRemovesRowsAndImageFiles() = runBlocking {
        val imagesDir = File(app.filesDir, "chat_images").apply { mkdirs() }
        val image = File(imagesDir, "todelete.jpg").apply { writeBytes(byteArrayOf(1)) }

        val id = runCreateSession()
        store.persistMessage(id, Message(author = "User", content = "q"), image.absolutePath)

        store.deleteSession(id)

        assertNull(store.getSession(id))
        assertTrue(store.loadSessionMessages(id)!!.isEmpty())
        assertFalse(image.exists())
    }

    @Test
    fun persistWebLinksWritesMetadataAndSkipsEmpty() = runBlocking {
        val id = runCreateSession()

        // Empty snapshot: no metadata written (entity keeps its "{}" default).
        store.persistWebLinks(id, emptyMap())
        assertEquals("{}", store.getSession(id)!!.metadata)

        store.persistWebLinks(id, mapOf("1" to "https://a", "2" to "https://b"))
        val links = ConversationMetadata.parse(store.getSession(id)!!.metadata)
            .getStringMap(ConversationMetadata.KEY_WEB_LINKS)
        assertEquals(mapOf("1" to "https://a", "2" to "https://b"), links)
    }

    @Test
    fun systemPromptCreateFindUpdate() = runBlocking {
        val created = store.createSystemPrompt("You are terse.")
        assertNotNull(created)

        assertEquals(created!!.id, store.findSystemPromptByText("You are terse.")?.id)

        assertTrue(store.updateSystemPromptText(created.id, "You are verbose."))
        assertEquals("You are verbose.", store.findSystemPromptByText("You are verbose.")?.text)
        assertNull(store.findSystemPromptByText("You are terse."))

        // Unknown id: no-op, reports false.
        assertFalse(store.updateSystemPromptText("missing-id", "whatever"))
    }

    @Test
    fun nullRepositoriesDegradeGracefully() = runBlocking {
        val nullStore = ChatSessionStore(null, null, ChatImageStore(app))
        assertNull(nullStore.loadSessionMessages("any"))
        assertNull(nullStore.getSession("any"))
        assertFalse(nullStore.systemPromptsAvailable)
        assertNull(nullStore.createSystemPrompt("text"))
        // Mutations are silent no-ops.
        nullStore.persistMessage("any", Message(author = "User", content = "q"))
        nullStore.deleteSession("any")
    }
}
