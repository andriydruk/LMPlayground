package com.druk.lmplayground.harness

import com.druk.llamacpp.DirectGenerationSession
import com.druk.llamacpp.LlamaGenerationCallback
import com.druk.llamacpp.chat.ToolCallLoop
import com.druk.llamacpp.jni.NativeLlamaSession
import com.druk.lmplayground.harness.Engine.generateToEnd
import com.druk.lmplayground.harness.EngineFixture.countingPrompt
import com.druk.lmplayground.harness.EngineFixture.session
import com.druk.lmplayground.harness.EngineFixture.turn
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.io.File

/**
 * `rewindToTurn` — the engine half of edit, regenerate and branch.
 *
 * The oracle is a fresh session: rewinding to turn N and sending a message
 * must produce, token for token, what a session that never saw turns N.. would
 * produce (greedy sampling, fixed seed). Any KV state leaking across the
 * rewind — a stale suffix, a recurrent state that wasn't restored, a sliding
 * window missing cells — changes the logits and so the reply.
 *
 * Cache reuse is asserted separately through the prompt-token counter: a
 * message sent after a rewind must evaluate exactly what sending it at that
 * point originally evaluates — the rewind itself must never cost a rebuild of
 * the conversation. (How much a normal turn reuses is the template's business:
 * Qwen3.5 re-renders the previous reply, so its turns re-evaluate more.)
 *
 * Runs on every chat GGUF found (see [EngineFixture]); hybrid models (Qwen3.5,
 * LFM2) exercise the recurrent-state snapshots, SWA ones (Gemma) the window.
 */
@RunWith(Parameterized::class)
class ChatRewindTest(private val modelFile: File) {

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun models(): List<Array<Any>> {
            val models = EngineFixture.chatModels()
            // An empty list would make JUnit report a vacuous pass. One
            // placeholder lets each test skip visibly instead.
            return if (models.isEmpty()) listOf(arrayOf(File("<no chat GGUF in ${EngineFixture.modelsDir()}>")))
            else models.map { arrayOf<Any>(it) }
        }

        private const val SYSTEM = "You are a terse assistant. Answer in one short sentence."
        private const val A = "My cat is called Biscuit. Say hello to her."
        private const val B = "What colour are most tomatoes?"
        private const val B_EDITED = "What colour is the sky on a clear day?"
        private const val C = "And what is my cat called?"
    }

    private fun model() = run {
        assumeTrue("no chat model on disk", modelFile.isFile)
        EngineFixture.model(modelFile)
    }

    private inline fun <T> withSession(
        systemPrompt: String = SYSTEM,
        nCtx: Int = 4096,
        block: (NativeLlamaSession) -> T,
    ): T {
        val s = model().session(systemPrompt, nCtx)
        try { return block(s) } finally { s.destroy() }
    }

    @Test
    fun regenerateReproducesTheReplyAndEvaluatesOnlyTheTurn() = withSession { s ->
        val (_, firstCost) = s.countingPrompt { s.turn(A) }
        val (original, originalCost) = s.countingPrompt { s.turn(B) }
        assertEquals(2, s.getTurnCount())

        val kept = s.rewindToTurn(1, B)
        // Turn B reused turn A's cache (it cost less than the first turn), so
        // the rewind has a snapshot to keep — dropping it would be a rebuild.
        if (originalCost < firstCost) assertTrue("regenerate dropped a reusable cache", kept > 0)
        assertTrue(kept >= 0)
        assertEquals(1, s.getTurnCount())

        val (again, cost) = s.countingPrompt { s.turn(B) }
        assertEquals("regenerated reply differs from the original", original, again)
        assertEquals("regenerate evaluated a different prompt than the original turn", originalCost, cost)
    }

    @Test
    fun editingAMiddleTurnMatchesAFreshConversation() {
        val (edited, editCost) = withSession { s ->
            s.turn(A)
            s.turn(B)
            s.turn(C)
            assertTrue(s.rewindToTurn(1, B) >= 0)
            s.countingPrompt { s.turn(B_EDITED) }
        }
        val (reference, referenceCost) = withSession { s ->
            s.turn(A)
            s.countingPrompt { s.turn(B_EDITED) }
        }
        assertEquals("edit leaked state from the dropped turns", reference, edited)
        assertEquals("edit evaluated more than sending the message there would", referenceCost, editCost)
    }

    @Test
    fun followUpAfterARewindMatchesAFreshConversation() {
        val rewound = withSession { s ->
            s.turn(A)
            s.turn(B)
            s.rewindToTurn(1, B)
            s.turn(B_EDITED)
            s.turn(C)
        }
        val reference = withSession { s ->
            s.turn(A)
            s.turn(B_EDITED)
            s.turn(C)
        }
        assertEquals(reference, rewound)
    }

    @Test
    fun rewindingTheFirstTurnStartsOver() {
        val rewound = withSession { s ->
            s.turn(A)
            s.turn(B)
            assertTrue(s.rewindToTurn(0, A) >= 0)
            assertEquals(0, s.getTurnCount())
            s.turn(B_EDITED)
        }
        val reference = withSession { s -> s.turn(B_EDITED) }
        assertEquals(reference, rewound)
    }

    @Test
    fun regenerateAfterStopDiscardsThePartialReply() {
        val regenerated = withSession { s ->
            s.turn(A)
            // Stop mid-reply: the turn is never finalized, the partial
            // tokens stay in the cache.
            check(s.addMessage(B, false) == 0)
            s.generateToEnd(maxTokens = 3)
            assertTrue(s.rewindToTurn(1, B) >= 0)
            s.turn(B)
        }
        val reference = withSession { s ->
            s.turn(A)
            s.turn(B)
        }
        assertEquals(reference, regenerated)
    }

    @Test
    fun aMismatchedTurnIsRefusedAndLeavesTheSessionUntouched() {
        val afterRefusals = withSession { s ->
            s.turn(A)
            s.turn(B)
            assertEquals(-1, s.rewindToTurn(2, ""))       // past the end
            assertEquals(-1, s.rewindToTurn(-1, ""))
            assertEquals(-1, s.rewindToTurn(1, C))        // numbering drifted
            assertEquals(2, s.getTurnCount())
            s.turn(C)
        }
        val reference = withSession { s ->
            s.turn(A)
            s.turn(B)
            s.turn(C)
        }
        assertEquals(reference, afterRefusals)
    }

    @Test
    fun replayedTurnsRewindWithoutACache() {
        withSession { s ->
            s.turn(A)
            s.turn(B)
        }
        val rewound = withSession { s ->
            // A reopened chat: history arrives without any KV behind it.
            s.replayHistory(arrayOf(A), arrayOf("Hello, Biscuit!"))
            assertEquals(1, s.getTurnCount())
            assertEquals("a replayed turn has no snapshot to keep", 0, s.rewindToTurn(0, A))
            assertEquals(0, s.getTurnCount())
            s.turn(B_EDITED)
        }
        val reference = withSession { s -> s.turn(B_EDITED) }
        assertEquals(reference, rewound)
    }

    @Test
    fun aTurnDroppedByCompactionIsRefused() = withSession(nCtx = 512) { s ->
        val filler = "Remember this list: " + (1..40).joinToString(", ") { "item number $it" } + "."
        var turns = 0
        // Fill the window until compaction drops the oldest turns.
        while (turns < 8) {
            s.turn("$filler Reply with OK.", maxTokens = 24)
            turns++
        }
        assertEquals(turns, s.getTurnCount())
        assertEquals("turn 0 was compacted away, rewind must refuse", -1, s.rewindToTurn(0, filler))
        assertTrue(s.rewindToTurn(turns - 1, filler) >= 0)
        assertEquals(turns - 1, s.getTurnCount())
    }

    @Test
    fun regenerateAfterAToolRoundMatchesTheOriginal() {
        val ask = "Use web_search to find the weather in Kyiv, then tell me."
        fun runToolTurn(native: NativeLlamaSession, text: String): String {
            val session = DirectGenerationSession(native, maxTokens = 160)
            var reply = ""
            val cb = object : LlamaGenerationCallback {
                override fun onFullResponse(response: String) { reply = response }
            }
            session.setTools(HarnessTools.registry().toOpenAIToolsJson())
            session.addMessage(text, false)
            runBlocking {
                ToolCallLoop(session, HarnessTools.executor(HarnessTools.registry()))
                    .run(cb, supportsThinking = false, enableThinking = false)
            }
            return reply
        }
        val (original, regenerated) = withSession { s ->
            s.turn(A)
            val first = runToolTurn(s, ask)
            assertTrue(s.rewindToTurn(1, ask) >= 0)
            first to runToolTurn(s, ask)
        }
        assertEquals(original, regenerated)
    }
}
