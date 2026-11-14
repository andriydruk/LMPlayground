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
import org.junit.Assert.assertNotEquals
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
            // Robolectric has no FileProvider registered; plain file URIs
            // stand in for the content URIs.
            object : ChatImageStore(app) {
                override fun imageContentUri(file: File): android.net.Uri = android.net.Uri.fromFile(file)
            },
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

    @Test
    fun truncateRemovesTrailingMessagesAndTheirImages() = runBlocking {
        val id = runCreateSession()
        val dir = File(app.filesDir, "chat_images").apply { mkdirs() }
        val dropped = File(dir, "dropped.jpg").apply { writeText("x") }
        val reused = File(dir, "reused.jpg").apply { writeText("y") }
        val messages = listOf(
            Message("User", "one", timestamp = 10),
            Message("Assistant", "r1", timestamp = 11),
            Message("User", "two", timestamp = 20, imagePath = reused.path),
            Message("Assistant", "r2", timestamp = 21),
            Message("User", "three", timestamp = 30, imagePath = dropped.path),
        )
        messages.forEach { store.persistMessage(id, it, it.imagePath) }

        store.truncateMessages(id, messages.subList(2, 5), keepImagePaths = setOf(reused.path))

        assertEquals(listOf("one", "r1"), store.loadSessionMessages(id)!!.map { it.content })
        assertFalse(dropped.exists())
        assertTrue("a re-sent message's image must survive", reused.exists())
    }

    @Test
    fun branchCopiesTheChatPrefixWithItsOwnImages() = runBlocking {
        val source = runCreateSession(systemPrompt = "terse")
        val dir = File(app.filesDir, "chat_images").apply { mkdirs() }
        val image = File(dir, "orig.jpg").apply { writeText("img") }
        val kept = listOf(
            Message("User", "look", timestamp = 10, imagePath = image.path),
            Message("Assistant", "a cat", timestamp = 11, responseTokens = 5),
        )
        kept.forEach { store.persistMessage(source, it, it.imagePath) }
        store.persistMessage(source, Message("User", "later", timestamp = 20))

        val (branchId, copies) = store.branchSession(source, kept, ::branchTitle)!!

        val entity = store.getSession(branchId)!!
        assertEquals("hello world (branch 1)", entity.title)
        assertEquals("terse", entity.systemPrompt)
        assertEquals(
            source,
            ConversationMetadata.parse(entity.metadata).getString(ConversationMetadata.KEY_BRANCHED_FROM),
        )
        val loaded = store.loadSessionMessages(branchId)!!
        assertEquals(listOf("look", "a cat"), loaded.map { it.content })
        assertEquals(5, loaded[1].responseTokens)
        val copiedPath = copies[0].imagePath!!
        assertNotEquals(image.path, copiedPath)
        assertEquals("img", File(copiedPath).readText())
        // The source chat is untouched.
        assertEquals(3, store.loadSessionMessages(source)!!.size)

        // Deleting the source must not take the branch's image with it.
        store.deleteSession(source)
        assertTrue(File(copiedPath).exists())
    }

    private fun branchTitle(rootTitle: String, number: Int) = "$rootTitle (branch $number)"

    @Test
    fun branchesAreNumberedPerOriginalChat() = runBlocking {
        val root = runCreateSession(firstMessage = "Root")
        val other = runCreateSession(firstMessage = "Other")
        val kept = listOf(Message("User", "q", timestamp = 1), Message("Assistant", "a", timestamp = 2))
        kept.forEach { store.persistMessage(root, it) }
        kept.forEach { store.persistMessage(other, it) }

        val (first, _) = store.branchSession(root, kept, ::branchTitle)!!
        val (second, _) = store.branchSession(root, kept, ::branchTitle)!!
        // A branch of a branch counts with its siblings and keeps the
        // original's title instead of nesting "(branch)".
        val (nested, _) = store.branchSession(second, kept, ::branchTitle)!!
        // Another chat's branches are numbered on their own.
        val (otherFirst, _) = store.branchSession(other, kept, ::branchTitle)!!

        assertEquals("Root (branch 1)", store.getSession(first)!!.title)
        assertEquals("Root (branch 2)", store.getSession(second)!!.title)
        assertEquals("Root (branch 3)", store.getSession(nested)!!.title)
        assertEquals("Other (branch 1)", store.getSession(otherFirst)!!.title)

        // Numbers keep growing after a deletion rather than being reused
        // while a later branch still holds a higher one.
        store.deleteSession(first)
        val (fourth, _) = store.branchSession(root, kept, ::branchTitle)!!
        assertEquals("Root (branch 4)", store.getSession(fourth)!!.title)
    }
}
