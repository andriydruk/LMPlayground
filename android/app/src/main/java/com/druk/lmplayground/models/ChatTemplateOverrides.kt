package com.druk.lmplayground.models

import android.content.Context
import android.util.Log

/**
 * Replacement chat templates for models whose published template is broken.
 *
 * The chat template lives inside the GGUF (`tokenizer.chat_template`) and is
 * normally authoritative. A handful of models ship one that cannot express
 * something the model was trained to do, and the symptom is invisible until a
 * user hits it. Overriding is a last resort, reserved for defects verified
 * against the real model — see the per-entry notes.
 *
 * Keyed by GGUF filename, so an override never applies to a model it wasn't
 * validated against. If a publisher fixes their template in a re-quant under
 * the same filename, this override would silently outlive the bug — the
 * :model-harness capability sweep re-checks each entry against the shipped
 * GGUF and flags overrides that are no longer needed.
 */
object ChatTemplateOverrides {

    private const val TAG = "ChatTemplateOverrides"
    private const val ASSET_DIR = "chat_templates"

    /**
     * filename -> asset name.
     *
     * SmolLM3 3B: its template describes the `<tool_call>` format in the
     * system prompt but never renders `message.tool_calls`. llama.cpp derives
     * tool-call grammars differentially (render a tool call, diff the output),
     * so it derived none — `tool_mode: NONE`. Every tool call the model
     * emitted was therefore unparseable and leaked into the chat as raw XML.
     * The override adds the missing assistant branch, which flips the derived
     * format to JSON_NATIVE with `<tool_call>` delimiters.
     *
     * LFM2.5-VL 450M: the opposite problem. Its template does render
     * `message.tool_calls`, in a pythonic `[name(arg="v")]` form that
     * llama.cpp's differential analysis mis-derives into a grammar with empty
     * rules. The grammar fails to parse, so tool calls run unconstrained and
     * the model answers in prose instead. The override is the LFM2.5 350M text
     * template — the VL model's own backbone — which renders no tool calls and
     * so reaches llama.cpp's dedicated LFM2 parser, like the text models.
     */
    private val OVERRIDES = mapOf(
        "HuggingFaceTB_SmolLM3-3B-Q4_K_M.gguf" to "HuggingFaceTB_SmolLM3-3B-Q4_K_M.jinja",
        "LFM2.5-VL-450M-Q4_K_M.gguf" to "LFM2.5-VL-450M-Q4_K_M.jinja",
    )

    /** Cached per filename; templates are a few KB and read once per load. */
    private val cache = mutableMapOf<String, String?>()

    /**
     * Returns the replacement template for [filename], or null to use the
     * GGUF's own. A missing or unreadable asset degrades to null (the model
     * still loads, just with its original template).
     */
    @Synchronized
    fun forModel(context: Context, filename: String): String? {
        val asset = OVERRIDES[filename] ?: return null
        return cache.getOrPut(filename) {
            try {
                context.assets.open("$ASSET_DIR/$asset").bufferedReader().use { it.readText() }
                    .also { Log.i(TAG, "loaded override for $filename (${it.length} chars)") }
            } catch (e: Exception) {
                Log.e(TAG, "failed to read override asset $asset for $filename", e)
                null
            }
        }
    }

    /** Filenames with an override, for diagnostics and harness cross-checks. */
    fun overriddenFilenames(): Set<String> = OVERRIDES.keys
}
