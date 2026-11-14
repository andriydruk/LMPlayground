package com.druk.lmplayground.harness

/** A capability a probe can assert. */
enum class Cap {
    TOOLS, THINKING, NO_THINKING, VISION, MULTI_TURN,
    TOOLS_WITH_THINKING, TOOLS_WITH_VISION,
}

enum class Expect {
    /** Probe must pass; a failure is a red cell. */
    REQUIRED,
    /** Run and report, but never red — for models too small to be reliable. */
    OPTIONAL,
    /** Skip the probe; if it passes anyway that is reported as a surprise. */
    UNSUPPORTED,
}

/**
 * What we claim each model can do.
 *
 * Kotlin rather than JSON/YAML so the enums are compile-checked and the diff
 * is reviewable. Every catalog model in the corpus needs an entry: one without
 * is reported as NO EXPECTATION DECLARED rather than skipped, which is what
 * stops a newly-added model from shipping unverified.
 */
data class ModelExpectation(
    val filename: String,
    val caps: Map<Cap, Expect>,
    /** Treat "the model never emitted a tool call" as a failure, not a warning. */
    val strictEmission: Boolean = false,
    val visionKeywords: List<String> = listOf("cat", "kitten", "feline", "animal"),
    val nCtx: Int = 4096,
    /** Thinking models need room to finish reasoning before the answer. */
    val maxTokens: Int = 1024,
    val timeoutMs: Long = 180_000,
    val notes: String = "",
)

object Expectations {

    private fun caps(
        tools: Expect, thinking: Expect, noThinking: Expect,
        vision: Expect, multiTurn: Expect = Expect.REQUIRED,
        toolsWithThinking: Expect = Expect.UNSUPPORTED,
        toolsWithVision: Expect = Expect.UNSUPPORTED,
    ) = mapOf(
        Cap.TOOLS to tools, Cap.THINKING to thinking, Cap.NO_THINKING to noThinking,
        Cap.VISION to vision, Cap.MULTI_TURN to multiTurn,
        Cap.TOOLS_WITH_THINKING to toolsWithThinking,
        Cap.TOOLS_WITH_VISION to toolsWithVision,
    )

    private val R = Expect.REQUIRED
    private val O = Expect.OPTIONAL
    private val N = Expect.UNSUPPORTED

    /** The default corpus: <=4B parameters, latest generation per family. */
    val ALL: List<ModelExpectation> = listOf(
        // ── Alibaba: Qwen 3.5 (vision, thinking, tools) ──────────────────
        ModelExpectation("Qwen_Qwen3.5-0.8B-IQ4_XS.gguf",
            caps(tools = O, thinking = R, noThinking = R, vision = R, toolsWithThinking = O, toolsWithVision = O),
            maxTokens = 2048, notes = "0.8B: emission unreliable, parsing must still work"),
        ModelExpectation("Qwen_Qwen3.5-2B-IQ4_XS.gguf",
            caps(tools = R, thinking = R, noThinking = R, vision = R, toolsWithThinking = R, toolsWithVision = O),
            strictEmission = true, maxTokens = 2048),
        ModelExpectation("Qwen_Qwen3.5-4B-IQ4_XS.gguf",
            caps(tools = R, thinking = R, noThinking = R, vision = R, toolsWithThinking = R, toolsWithVision = O),
            strictEmission = true, maxTokens = 2048),

        // ── Google: Gemma 4 QAT (vision, thinking, tools) ────────────────
        ModelExpectation("gemma-4-E2B_q4_0-it.gguf",
            caps(tools = R, thinking = R, noThinking = R, vision = R, toolsWithThinking = R, toolsWithVision = O),
            notes = "post-tool reply goes blank if thinking isn't forced on — see ToolCallLoop"),
        ModelExpectation("gemma-4-E4B_q4_0-it.gguf",
            caps(tools = R, thinking = R, noThinking = R, vision = R, toolsWithThinking = R, toolsWithVision = O),
            strictEmission = true),

        // ── Meta: Llama 3.2 ──────────────────────────────────────────────
        ModelExpectation("Llama-3.2-1B-Instruct-Q4_K_M.gguf",
            caps(tools = N, thinking = N, noThinking = R, vision = N),
            notes = "template reports supports_tools=false; de-badged 2026-08"),
        ModelExpectation("Llama-3.2-3B-Instruct-Q4_K_M.gguf",
            caps(tools = O, thinking = N, noThinking = R, vision = N)),

        // ── Microsoft ────────────────────────────────────────────────────
        ModelExpectation("Phi-4-mini-instruct-Q4_K_M.gguf",
            caps(tools = N, thinking = N, noThinking = R, vision = N),
            notes = "template reports supports_tools=false; de-badged 2026-08"),

        // ── DeepSeek ─────────────────────────────────────────────────────
        ModelExpectation("DeepSeek-R1-Distill-Qwen-1.5B-Q4_K_M.gguf",
            caps(tools = N, thinking = R, noThinking = O, vision = N),
            notes = "distill: always reasons, non-thinking mode is best-effort"),

        // ── Liquid: LFM2.5 ───────────────────────────────────────────────
        ModelExpectation("LFM2.5-350M-Q4_K_M.gguf",
            caps(tools = O, thinking = O, noThinking = R, vision = N),
            notes = "350M; template can't render tool_calls but the specialized " +
                "LFM2.5 parser handles emission — verified 2026-08, no override needed"),
        ModelExpectation("LFM2.5-1.2B-Instruct-Q4_K_M.gguf",
            caps(tools = O, thinking = O, noThinking = R, vision = N, toolsWithThinking = O),
            notes = "replaced the Thinking build as the compact default; its template " +
                "still exposes a thinking mode, it just doesn't reason by default"),
        ModelExpectation("LFM2.5-1.2B-Thinking-Q4_K_M.gguf",
            caps(tools = O, thinking = R, noThinking = O, vision = N, toolsWithThinking = O),
            maxTokens = 2048, notes = "reasons at length; needs a bigger token budget"),
        ModelExpectation("LFM2.5-2.6B-Q4_K_M.gguf",
            caps(tools = R, thinking = R, noThinking = O, vision = N, toolsWithThinking = O),
            maxTokens = 2048,
            notes = "reasoning-tuned: still emits <think> with thinking disabled, so the " +
                "app's toggle cannot suppress it"),

        // ── Mistral: Ministral 3 (vision) ────────────────────────────────
        ModelExpectation("Ministral-3-3B-Instruct-2512-Q4_K_M.gguf",
            caps(tools = R, thinking = O, noThinking = R, vision = R, toolsWithVision = O),
            notes = "template advertises thinking but the model never emits a <think> " +
                "block, so the app shows a toggle that does nothing"),
        ModelExpectation("Ministral-3-3B-Reasoning-2512-Q4_K_M.gguf",
            caps(tools = R, thinking = R, noThinking = O, vision = R, toolsWithThinking = R, toolsWithVision = O),
            maxTokens = 2048,
            notes = "reasoning-tuned: still emits <think> with thinking disabled"),

        // ── IBM ──────────────────────────────────────────────────────────
        ModelExpectation("granite-4.1-3b-Q4_K_M.gguf",
            caps(tools = R, thinking = N, noThinking = R, vision = N),
            notes = "no thinking mode: the template reports supports_thinking=false and the " +
                "model emits no <think> block. The catalog badge already agrees"),
        ModelExpectation("granite-4.2-3b-Q4_K_M.gguf",
            caps(tools = R, thinking = R, noThinking = R, vision = N, toolsWithThinking = R),
            maxTokens = 2048),

        // ── Liquid AI: vision ────────────────────────────────────────────
        ModelExpectation("LFM2.5-VL-450M-Q4_K_M.gguf",
            caps(tools = O, thinking = O, noThinking = R, vision = R, toolsWithVision = O),
            notes = "runs on the LFM2.5 350M text template: its own template renders tool " +
                "calls in a form llama.cpp derives an unparseable grammar from. Thinking is " +
                "advertised by that template but, like the 350M, never used"),

        ModelExpectation("LFM2.5-VL-1.6B-Q4_K_M.gguf",
            caps(tools = R, thinking = O, noThinking = R, vision = R, toolsWithVision = O),
            notes = "own template derives a working tool grammar, like the 3B. Thinking is " +
                "advertised by the template but never used"),
        ModelExpectation("LFM2.5-VL-3B-Q4_K_M.gguf",
            caps(tools = R, thinking = O, noThinking = R, vision = R, toolsWithVision = O),
            notes = "unlike the 450M its own template derives a working tool grammar, so no " +
                "override. Thinking is advertised by the template but never used"),

        // ── XHToken ──────────────────────────────────────────────────────
        ModelExpectation("Spark-X2.5-4B-Q4_K_M.gguf",
            caps(tools = R, thinking = R, noThinking = R, vision = N, toolsWithThinking = R),
            maxTokens = 2048,
            notes = "spark2_5 architecture: needs llama.cpp b10828 or later"),

        // ── NVIDIA ───────────────────────────────────────────────────────
        ModelExpectation("NVIDIA-Nemotron3-Nano-4B-Q4_K_M.gguf",
            caps(tools = R, thinking = R, noThinking = R, vision = N, toolsWithThinking = R),
            maxTokens = 2048),

        // ── Hugging Face ─────────────────────────────────────────────────
        ModelExpectation("HuggingFaceTB_SmolLM3-3B-Q4_K_M.gguf",
            caps(tools = R, thinking = R, noThinking = R, vision = N, toolsWithThinking = R),
            strictEmission = true, maxTokens = 2048,
            notes = "shipped broken until 2026-08: template never rendered tool_calls, " +
                "so tool calls leaked as raw XML. Fixed by a template override — this " +
                "row failing again means the override stopped being applied"),

        // ── OpenBMB ──────────────────────────────────────────────────────
        ModelExpectation("MiniCPM5-1B-Q4_K_M.gguf",
            caps(tools = R, thinking = R, noThinking = R, vision = N, toolsWithThinking = O)),
        ModelExpectation("MiniCPM5-2B-Q4_K_M.gguf",
            caps(tools = R, thinking = O, noThinking = R, vision = N, toolsWithThinking = R),
            maxTokens = 2048,
            notes = "greedy decoding loops (\"Thus answer: 9.\" x400) and never closes " +
                "</think>; the app's sampler closes it in 900-1650 tokens on every seed " +
                "tried, so thinking is optional here rather than broken"),
    )

    fun byFilename(): Map<String, ModelExpectation> = ALL.associateBy { it.filename }

    fun expect(e: ModelExpectation, cap: Cap): Expect = e.caps[cap] ?: Expect.UNSUPPORTED
}
