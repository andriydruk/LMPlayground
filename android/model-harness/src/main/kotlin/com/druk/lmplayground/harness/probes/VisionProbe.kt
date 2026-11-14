package com.druk.lmplayground.harness.probes

import com.druk.lmplayground.harness.*
import com.druk.lmplayground.harness.Engine.generateToEnd
import com.druk.lmplayground.harness.Engine.newGreedySession

/**
 * Image input: load the projector, attach a picture, describe it.
 *
 * Split deliberately into a structural half (the projector loads, vision turns
 * on, a non-empty answer comes back, no image placeholder tokens leak) which
 * is REQUIRED, and a content half (the answer mentions the subject) which only
 * warns — a 0.8B model calling a cat "an animal" is not an engine defect.
 */
object VisionProbe : Probe {
    override val name = "vision"
    override val cap = Cap.VISION

    private const val PROMPT = "Describe this image in one short sentence."

    override fun run(ctx: ProbeContext): List<ProbeResult> {
        val started = System.currentTimeMillis()
        val mmproj = ctx.mmprojFile
            ?: return listOf(ProbeResult(name, cap, Status.SKIP, "NO_MMPROJ",
                "vision projector not downloaded", 0))
        val fixture = System.getenv("LMP_VISION_IMAGE") ?: "test_cat.jpg"
        val image = Fixtures.image(fixture)
            ?: return listOf(ProbeResult(name, cap, Status.SKIP, "NO_FIXTURE",
                "test image not found in app/src/androidTest/assets", 0))

        val out = mutableListOf<ProbeResult>()
        try {
            ctx.model.loadMmprojModel(mmproj.absolutePath)
        } catch (t: Throwable) {
            return listOf(ProbeResult(name, cap, Status.ERROR, "MMPROJ_LOAD_FAILED",
                "${t::class.simpleName}: ${t.message}", System.currentTimeMillis() - started))
        }
        if (!ctx.model.supportsVision()) {
            return listOf(ProbeResult(name, cap, ctx.grade(cap, true), "VISION_OFF_AFTER_LOAD",
                "projector loaded but supportsVision() is still false — likely an " +
                    "mmproj/text-model mismatch",
                System.currentTimeMillis() - started))
        }

        val session = ctx.model.newGreedySession(nCtx = ctx.expectation.nCtx)
        try {
            session.setImageData(image)
            // Time the image turn separately: addMessage() is where the CLIP
            // encode happens, and that is the step users experience as a hang.
            val encodeStart = System.currentTimeMillis()
            session.addMessage(PROMPT, false)
            val encodeMs = System.currentTimeMillis() - encodeStart
            val gen = session.generateToEnd(ctx.expectation.maxTokens, ctx.expectation.timeoutMs)
            val ms = System.currentTimeMillis() - started
            out += ProbeResult(
                probe = "vision-encode", cap = cap, status = Status.PASS, code = "TIMING",
                reason = "image encode (addMessage) took ${encodeMs} ms",
                durationMs = encodeMs,
                detail = mapOf("image" to fixture, "bytes" to image.size.toString(),
                               "backend" to (System.getenv("LMP_MTMD_BACKEND") ?: "gpu"),
                               "decodeMs" to (ms - encodeMs).toString()),
            )

            val leak = Regex("""<\|vision_start\|>|<\|image_pad\|>|<image>|\[IMG]""").find(gen.text)
            out += when {
                gen.text.isBlank() -> ProbeResult(name, cap, ctx.grade(cap, true), "EMPTY_RESPONSE",
                    "no description produced for the attached image", ms)
                leak != null -> ProbeResult(name, cap, ctx.grade(cap, true), "IMAGE_TOKEN_LEAK",
                    "image placeholder '${leak.value}' leaked into the reply", ms,
                    rawArtifact = ctx.artifacts.write("vision", gen.text))
                else -> ProbeResult(name, cap, Status.PASS, "OK",
                    "described the image in ${gen.tokens} tokens", ms)
            }

            val kw = ctx.expectation.visionKeywords
            val hit = kw.any { gen.text.contains(it, ignoreCase = true) }
            out += ProbeResult(
                probe = "vision-content", cap = cap,
                status = if (hit) Status.PASS else Status.WARN,
                code = if (hit) "OK" else "SUBJECT_NOT_NAMED",
                reason = if (hit) "reply names the subject"
                         else "reply never mentions ${kw.joinToString("/")}",
                durationMs = ms,
                detail = mapOf("reply" to gen.text.take(120).replace("\n", "\\n")),
            )
        } catch (t: Throwable) {
            out += ProbeResult(name, cap, Status.ERROR, "ERROR", "${t::class.simpleName}: ${t.message}",
                System.currentTimeMillis() - started)
        } finally {
            session.destroy()
        }

        // Repeated image turns: the vision path clears the KV cache and
        // re-renders the whole conversation each time, so encode+prefill cost
        // grows with history. Users experience the third or fourth image in a
        // chat as a hang even though the first felt fine.
        val multi = ctx.model.newGreedySession(nCtx = ctx.expectation.nCtx)
        try {
            val timings = (1..3).map { turn ->
                multi.setImageData(image)
                val t0 = System.currentTimeMillis()
                multi.addMessage(PROMPT, false)
                val encode = System.currentTimeMillis() - t0
                multi.generateToEnd(128, ctx.expectation.timeoutMs)
                encode
            }
            val growth = if (timings.first() > 0) timings.last().toDouble() / timings.first() else 1.0
            out += ProbeResult(
                probe = "vision-repeat", cap = cap,
                status = if (growth > 3.0) Status.WARN else Status.PASS,
                code = if (growth > 3.0) "IMAGE_TURN_COST_GROWS" else "TIMING",
                reason = "image-turn encode across 3 turns: " +
                    timings.joinToString(" -> ") { it.toString() + "ms" } +
                    " (x" + String.format("%.1f", growth) + ")",
                durationMs = timings.sum().toLong(),
                detail = mapOf("turns" to timings.joinToString(","),
                               "backend" to (System.getenv("LMP_MTMD_BACKEND") ?: "gpu")),
            )
        } catch (t: Throwable) {
            out += ProbeResult("vision-repeat", cap, Status.ERROR, "ERROR",
                "${t::class.simpleName}: ${t.message}")
        } finally {
            multi.destroy()
        }

        // A vision model must still answer text-only turns on the same model.
        val textOnly = ctx.model.newGreedySession(nCtx = ctx.expectation.nCtx)
        try {
            textOnly.addMessage("What is the capital of France? One word.", false)
            val gen = textOnly.generateToEnd(256, ctx.expectation.timeoutMs)
            out += ProbeResult(
                probe = "vision-textonly", cap = cap,
                status = if (gen.text.isBlank()) ctx.grade(cap, true) else Status.PASS,
                code = if (gen.text.isBlank()) "EMPTY_RESPONSE" else "OK",
                reason = if (gen.text.isBlank()) "text-only turn returned nothing after loading the projector"
                         else "text-only turn still works with the projector loaded",
            )
        } catch (t: Throwable) {
            out += ProbeResult("vision-textonly", cap, Status.ERROR, "ERROR",
                "${t::class.simpleName}: ${t.message}")
        } finally {
            textOnly.destroy()
        }
        return out
    }
}
