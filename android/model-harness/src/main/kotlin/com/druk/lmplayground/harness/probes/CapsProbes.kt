package com.druk.lmplayground.harness.probes

import com.druk.lmplayground.harness.*
import com.druk.lmplayground.harness.Engine.newGreedySession

/**
 * Compares the template's declared capabilities against both what we expect
 * and what the catalog badges claim.
 *
 * Cheap (no decoding) and the most diagnostic probe in the set: it separates
 * "the model can't" from "our flags are wrong".
 */
object TemplateCapsProbe : Probe {
    override val name = "caps"
    override val cap = null

    override fun run(ctx: ProbeContext): List<ProbeResult> {
        val started = System.currentTimeMillis()
        val tools = ctx.model.supportsToolCalling()
        val thinking = ctx.model.supportsThinking()
        val vision = ctx.model.supportsVision()
        val out = mutableListOf<ProbeResult>()

        fun check(cap: Cap, actual: Boolean, label: String) {
            val expected = ctx.expect(cap)
            val wanted = expected != Expect.UNSUPPORTED
            val mismatch = wanted != actual
            out += ProbeResult(
                probe = name, cap = cap,
                status = if (!mismatch) Status.PASS else ctx.grade(cap, true),
                code = if (!mismatch) "OK" else if (actual) "UNEXPECTED_CAPABILITY" else "MISSING_CAPABILITY",
                reason = if (!mismatch) "$label=$actual as expected"
                         else "template reports $label=$actual, expected $wanted",
                durationMs = System.currentTimeMillis() - started,
                detail = mapOf("template" to actual.toString(), "expected" to wanted.toString()),
            )
        }
        check(Cap.TOOLS, tools, "supports_tools")
        check(Cap.THINKING, thinking, "supports_thinking")
        // Vision is deliberately NOT asserted here: supportsVision() only
        // turns true after loadMmprojModel(), so at this point it is false for
        // every model. VisionProbe owns that check, after loading the
        // projector. Reported as info so the matrix still shows the state.
        out += ProbeResult(
            probe = name, cap = null, status = Status.PASS, code = "INFO",
            reason = "supports_vision=$vision before the projector is loaded",
            detail = mapOf("mmproj" to (ctx.mmprojFile?.name ?: "none")),
        )

        // Catalog badge drift: a wrong badge doesn't break generation, but it
        // promises the user something the engine won't do (or hides one it will).
        fun drift(label: String, badge: Boolean, actual: Boolean) {
            if (badge != actual) out += ProbeResult(
                probe = name, cap = null, status = Status.WARN, code = "CATALOG_FLAG_DRIFT",
                reason = "ModelInfoProvider says $label=$badge but the template says $actual",
                detail = mapOf("badge" to badge.toString(), "template" to actual.toString()),
            )
        }
        drift("TOOL_CAPABLE", ctx.catalog.catalogSupportsTools, tools)
        drift("THINKING_CAPABLE", ctx.catalog.catalogSupportsThinking, thinking)
        return out
    }
}

/**
 * Renders the chat template without generating anything.
 *
 * Near-instant and fully deterministic, so it isolates template problems from
 * model behaviour: if the preamble is wrong, nothing downstream can be right.
 */
object PreambleProbe : Probe {
    override val name = "preamble"
    override val cap = null

    private const val SYSTEM = "You are a helpful assistant."

    override fun run(ctx: ProbeContext): List<ProbeResult> {
        val started = System.currentTimeMillis()
        val out = mutableListOf<ProbeResult>()
        val session = ctx.model.newGreedySession(systemPrompt = SYSTEM, nCtx = ctx.expectation.nCtx)
        try {
            session.setTools("[]")
            val bare = session.renderPreambleString(false)

            out += result(
                failed = bare.length < 20,
                code = "EMPTY_PREAMBLE",
                ok = "renders ${bare.length} bytes",
                bad = "rendered only ${bare.length} bytes",
                started = started,
            )
            out += result(
                failed = !bare.contains("helpful assistant"),
                code = "SYSTEM_PROMPT_DROPPED",
                ok = "system prompt present",
                bad = "system prompt missing from the rendered preamble",
                started = started,
            )

            if (ctx.model.supportsToolCalling()) {
                val registry = HarnessTools.registry()
                session.setTools(registry.toOpenAIToolsJson())
                val withTools = session.renderPreambleString(false)
                out += result(
                    failed = withTools.length <= bare.length,
                    code = "TOOLS_NOT_IN_PREAMBLE",
                    ok = "tools add ${withTools.length - bare.length} bytes",
                    bad = "declaring tools did not change the preamble " +
                        "(${bare.length} -> ${withTools.length} bytes)",
                    started = started,
                )
                out += result(
                    failed = !withTools.contains("web_search"),
                    code = "TOOL_NAME_ABSENT",
                    ok = "tool names reach the model",
                    bad = "tool name web_search absent from the preamble",
                    started = started,
                )
            }

            if (ctx.model.supportsThinking()) {
                // Informational only. The thinking toggle normally affects the
                // assistant generation prefix, not the system-prompt preamble,
                // so "no difference" is the common, correct case — a few
                // templates (SmolLM3) do vary the system message instead.
                val on = session.renderPreambleString(true)
                val off = session.renderPreambleString(false)
                out += ProbeResult(
                    probe = name, cap = null, status = Status.PASS, code = "INFO",
                    reason = if (on == off) "thinking toggle does not change the preamble"
                             else "thinking toggle changes the preamble (${off.length} -> ${on.length} bytes)",
                    durationMs = System.currentTimeMillis() - started,
                )
            }
        } finally {
            session.destroy()
        }
        return out
    }

    private fun result(failed: Boolean, code: String, ok: String, bad: String, started: Long) =
        ProbeResult(
            probe = name, cap = null,
            status = if (failed) Status.FAIL else Status.PASS,
            code = if (failed) code else "OK",
            reason = if (failed) bad else ok,
            durationMs = System.currentTimeMillis() - started,
        )
}
