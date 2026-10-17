package com.druk.lmplayground

import android.util.Log
import com.druk.lmplayground.conversation.ConversationUiState
import com.druk.lmplayground.conversation.Message
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4

@RunWith(AndroidJUnit4::class)
class ThinkingDurationTest {

    companion object {
        private const val TAG = "ThinkingDurationTest"
    }

    @Test
    fun testDurationComputedFromStartTime() {
        val uiState = ConversationUiState(emptyList())
        val startTime = System.currentTimeMillis()
        uiState.addMessage(Message("Assistant", "", thinkingStartTimeMs = startTime))

        Thread.sleep(2500)
        uiState.updateLastMessage("<think>reasoning tokens here")

        val msg = uiState.messages.last()
        Log.d(TAG, "Duration after 2.5s: ${msg.thinkingDurationSeconds}s")
        assertTrue(
            "Duration should be >= 2 after ~2.5s sleep, was ${msg.thinkingDurationSeconds}",
            msg.thinkingDurationSeconds >= 2
        )
    }

    @Test
    fun testDurationFreezesAfterThinkingEnds() {
        val uiState = ConversationUiState(emptyList())
        val startTime = System.currentTimeMillis()
        uiState.addMessage(Message("Assistant", "", thinkingStartTimeMs = startTime))

        Thread.sleep(2000)
        uiState.updateLastMessage("<think>reasoning</think>\nResponse text")
        val frozenDuration = uiState.messages.last().thinkingDurationSeconds
        Log.d(TAG, "Frozen duration: ${frozenDuration}s")
        assertTrue("Duration should be >= 2, was $frozenDuration", frozenDuration >= 2)

        Thread.sleep(2000)
        uiState.updateLastMessage("<think>reasoning</think>\nResponse text with more tokens")
        val laterDuration = uiState.messages.last().thinkingDurationSeconds
        Log.d(TAG, "Later duration: ${laterDuration}s")
        assertEquals(
            "Duration should freeze after </think>",
            frozenDuration,
            laterDuration
        )
    }

    @Test
    fun testDurationPreservedAcrossRapidContentUpdates() {
        val uiState = ConversationUiState(emptyList())
        val startTime = System.currentTimeMillis()
        uiState.addMessage(Message("Assistant", "", thinkingStartTimeMs = startTime))

        Thread.sleep(3000)

        // Simulate rapid token arrivals (like the real callback)
        for (i in 1..50) {
            uiState.updateLastMessage("<think>thinking token $i")
        }

        val msg = uiState.messages.last()
        Log.d(TAG, "Duration after 3s + 50 updates: ${msg.thinkingDurationSeconds}s")
        assertTrue(
            "Duration should be >= 3 after 3s, was ${msg.thinkingDurationSeconds}",
            msg.thinkingDurationSeconds >= 3
        )
    }

    @Test
    fun testNoDurationWithoutThinking() {
        val uiState = ConversationUiState(emptyList())
        uiState.addMessage(Message("Assistant", ""))

        Thread.sleep(1000)
        uiState.updateLastMessage("Response without thinking")

        val msg = uiState.messages.last()
        Log.d(TAG, "Duration without thinking: ${msg.thinkingDurationSeconds}s")
        assertEquals(
            "Duration should be 0 when thinking not active",
            0,
            msg.thinkingDurationSeconds
        )
    }
}
