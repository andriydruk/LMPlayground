package com.druk.lmplayground.harness

import com.druk.llamacpp.jni.NativeLlamaSession
import com.druk.lmplayground.harness.EngineFixture.countingPrompt
import com.druk.lmplayground.harness.EngineFixture.session
import com.druk.lmplayground.harness.EngineFixture.turn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.io.File

/**
 * Per-turn KV-cache reuse in an ordinary conversation.
 *
 * Each turn should evaluate only what is new since the cache — not the whole
 * conversation again. Qwen3/3.5 templates make that non-trivial: the latest
 * reply renders with an empty think block that disappears once a new user
 * turn follows, so the string prefix breaks every turn and reuse has to fall
 * back to matching tokens (and, on hybrid memories, to a checkpoint taken
 * where the reply started).
 *
 * The oracle for correctness is a fresh session given the same history by
 * replay: reusing the cache must not change what the model sees.
 */
@RunWith(Parameterized::class)
class KvReuseTest(private val modelFile: File) {

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun models(): List<Array<Any>> {
            val models = EngineFixture.chatModels()
            return if (models.isEmpty()) listOf(arrayOf(File("<no chat GGUF in ${EngineFixture.modelsDir()}>")))
            else models.map { arrayOf<Any>(it) }
        }

        private const val SYSTEM = "You are a terse assistant. Answer in one short sentence."
        private val TURNS = listOf(
            "My cat is called Biscuit. Say hello to her.",
            "What colour are most tomatoes?",
            "Name one planet in the solar system.",
            "And what is my cat called?",
        )
    }

    private fun model() = run {
        assumeTrue("no chat model on disk", modelFile.isFile)
        EngineFixture.model(modelFile)
    }

    private inline fun <T> withSession(block: (NativeLlamaSession) -> T): T {
        val s = model().session(SYSTEM)
        try { return block(s) } finally { s.destroy() }
    }

    @Test
    fun laterTurnsEvaluateOnlyWhatIsNewAndMatchAFreshReplay() {
        val replies = mutableListOf<String>()
        val lastCost = withSession { s ->
            var cost = 0
            for (text in TURNS) {
                val (reply, c) = s.countingPrompt { s.turn(text) }
                replies += reply
                cost = c
            }
            cost
        }
        val history = TURNS.size - 1
        val (fresh, fullCost) = withSession { s ->
            s.replayHistory(
                TURNS.take(history).toTypedArray(),
                replies.take(history).toTypedArray(),
            )
            s.countingPrompt { s.turn(TURNS.last()) }
        }
        assertEquals("cache reuse changed what the model saw", fresh, replies.last())
        assertTrue(
            "turn ${TURNS.size} evaluated $lastCost prompt tokens, a full replay $fullCost",
            lastCost * 2 < fullCost,
        )
    }
}
