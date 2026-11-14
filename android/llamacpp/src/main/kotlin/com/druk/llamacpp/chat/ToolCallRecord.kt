package com.druk.llamacpp.chat

/**
 * One completed tool call: what the model asked for, and what came back.
 *
 * The engine's own type, deliberately free of UI concerns — the app maps it
 * onto its Compose `ToolCallInfo`, which carries presentation-only fields and
 * an @Immutable annotation this module can't provide.
 */
data class ToolCallRecord(
    val id: String,
    val name: String,
    val arguments: String,
    val result: String,
    val durationMs: Long = 0,
)
