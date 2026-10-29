package com.druk.llamacpp.tools

import org.json.JSONArray
import org.json.JSONObject

/**
 * The `agent` tool: lets the model run a multi-step task as a workflow.
 *
 * The model plans, works through the plan with its other tools (web search,
 * page fetch, JavaScript), checks steps off as it goes, saves findings, and
 * finishes with a summary. The tool does no work itself — it is the model's
 * checklist and scratchpad — but every result echoes the plan and says what to
 * do next, which is what keeps a small model on track across many rounds.
 *
 * Enabling this tool is also what switches a turn into *agent mode*: the
 * registry gives the turn a larger tool-round budget ([MAX_TOOL_ROUNDS]),
 * suppresses repeated identical calls, and the loop forces a final answer
 * rather than going silent if the budget runs out. See [ToolRegistry].
 *
 * The schema is deliberately flat (strings and one integer, no nested
 * arrays): small models fill flat schemas far more reliably. [execute] is
 * lenient about what it accepts — `steps` as a JSON array or a numbered /
 * newline list, `status` synonyms, a missing `action` inferred from the other
 * fields — and strict about what it returns.
 */
class AgentTool(private val state: AgentState) : Tool {

    override val name = NAME

    override val description =
        "Plan and track a multi-step task so you can work through it reliably. " +
            "Call with action=plan and a short list of steps, carry out each step with your other tools, " +
            "call action=update after each step, action=note to save a key fact, and action=finish " +
            "with a one-line summary before you write the final answer. " +
            "Skip it for simple questions that need at most one tool call."

    override val parametersSchema =
        """{"type":"object","properties":{""" +
            """"action":{"type":"string","enum":["plan","update","note","status","finish"],"description":"plan: set the steps. update: change a step's status. note: save a finding. status: show the plan. finish: the task is complete."},""" +
            """"steps":{"type":"string","description":"For plan: 2-8 short steps, one per line."},""" +
            """"step":{"type":"integer","description":"For update: the step number, starting at 1."},""" +
            """"status":{"type":"string","enum":["in_progress","done","skipped"],"description":"For update: the step's new status."},""" +
            """"text":{"type":"string","description":"For note: the fact to remember. For finish: a one-line summary."}""" +
            """},"required":["action"]}"""

    override fun execute(arguments: String): String {
        val args = try {
            JSONObject(arguments)
        } catch (_: Exception) {
            return error("Arguments must be a JSON object, e.g. {\"action\":\"plan\",\"steps\":\"1. ...\\n2. ...\"}.")
        }
        return when (val action = resolveAction(args)) {
            "plan" -> plan(args)
            "update" -> update(args)
            "note" -> note(args)
            "status" -> result()
            "finish" -> finish(args)
            null -> error("Missing action. Use one of: plan, update, note, status, finish.")
            else -> error("Unknown action '$action'. Use one of: plan, update, note, status, finish.")
        }
    }

    private fun plan(args: JSONObject): String {
        val steps = parseSteps(args.opt("steps"))
        if (steps.isEmpty()) {
            return error("plan needs steps: a short list of what to do, one step per line.")
        }
        val kept = state.setPlan(steps)
        val dropped = steps.size - kept
        return result { if (dropped > 0) put("warning", "Only the first $kept steps were kept; keep plans short.") }
    }

    private fun update(args: JSONObject): String {
        val index = parseStepNumber(args.opt("step"))
            ?: return error("update needs step: the step number, starting at 1.")
        val rawStatus = args.optString("status").trim()
        val status = if (rawStatus.isEmpty()) {
            AgentState.Status.DONE // "update step 2" most plausibly means "I finished it".
        } else {
            AgentState.Status.parse(rawStatus)
                ?: return error("Unknown status '$rawStatus'. Use in_progress, done or skipped.")
        }
        state.updateStep(index, status)?.let { return error(it) }
        return result()
    }

    private fun note(args: JSONObject): String {
        val text = args.optString("text").trim()
        if (text.isEmpty()) return error("note needs text: the fact to remember.")
        val added = state.addNote(text)
        return result { if (!added) put("info", "That note was already saved.") }
    }

    private fun finish(args: JSONObject): String {
        state.finish(args.optString("text"))
        return result()
    }

    /** The current state, plus whatever [extra] adds, as the tool result. */
    private fun result(extra: JSONObject.() -> Unit = {}): String {
        val view = state.view()
        val out = JSONObject().put("ok", true)
        if (view.steps.isNotEmpty()) {
            val plan = JSONArray()
            view.steps.forEachIndexed { i, s -> plan.put("${i + 1}. [${s.status.wire}] ${s.text}") }
            out.put("plan", plan)
        }
        if (view.notes.isNotEmpty()) {
            val notes = JSONArray()
            view.notes.forEach { notes.put(it) }
            out.put("notes", notes)
        }
        out.extra()
        out.put("next", nextHint(view))
        return out.toString()
    }

    private fun nextHint(view: AgentState.View): String {
        if (view.finished) {
            return "The task is finished. Write your final answer to the user now; do not call more tools."
        }
        if (view.steps.isEmpty()) {
            return "There is no plan yet. For a multi-step task call agent with action=plan; otherwise just answer."
        }
        val current = view.currentStepIndex()
        if (current < 0) {
            return "Every step is closed. Call agent with action=finish, then write your final answer."
        }
        val n = current + 1
        return "Do step $n now (${view.steps[current].text}) using your other tools, " +
            "then call agent with action=update, step=$n, status=done."
    }

    /** The action, inferred from the other fields when the model leaves it out. */
    private fun resolveAction(args: JSONObject): String? {
        val raw = args.optString("action").trim().lowercase().replace('-', '_').replace(' ', '_')
        if (raw.isNotEmpty()) return ACTION_ALIASES[raw] ?: raw
        return when {
            args.has("steps") -> "plan"
            args.has("step") -> "update"
            args.has("text") -> "note"
            else -> null
        }
    }

    private fun parseStepNumber(value: Any?): Int? = when (value) {
        is Number -> value.toInt()
        // "2", "step 2", "#2", "2." — take the first run of digits.
        is String -> Regex("""\d+""").find(value)?.value?.toIntOrNull()
        else -> null
    }

    /** Steps as a JSON array of strings, or a numbered / bulleted / newline-separated string. */
    private fun parseSteps(value: Any?): List<String> {
        val lines = when (value) {
            is JSONArray -> (0 until value.length()).map { value.opt(it)?.toString().orEmpty() }
            is String -> splitStepList(value)
            else -> emptyList()
        }
        return lines.map { stripListMarker(it) }.filter { it.isNotBlank() }
    }

    private fun splitStepList(text: String): List<String> {
        val trimmed = text.trim()
        // A JSON array that arrived as a string: '["a","b"]'.
        if (trimmed.startsWith("[")) {
            try {
                val arr = JSONArray(trimmed)
                return (0 until arr.length()).map { arr.opt(it)?.toString().orEmpty() }
            } catch (_: Exception) {
                // fall through to line splitting
            }
        }
        val byLine = trimmed.split('\n', '\r').filter { it.isNotBlank() }
        if (byLine.size > 1) return byLine
        // One line holding "1. a 2. b 3. c". Only when it opens with a list marker,
        // so a step like "Find the top 10. Then compare" isn't split mid-sentence.
        if (Regex("""^\d{1,2}[.)]\s""").containsMatchIn(trimmed)) {
            val numbered = trimmed.split(Regex("""(?:^|\s)\d{1,2}[.)]\s+""")).filter { it.isNotBlank() }
            if (numbered.size > 1) return numbered
        }
        // "a; b; c"
        return trimmed.split(';')
    }

    private fun stripListMarker(line: String): String =
        line.trim().replace(Regex("""^(?:\d{1,2}[.):]|[-*•])\s*"""), "").trim()

    private fun error(message: String): String = JSONObject().put("error", message).toString()

    companion object {
        const val NAME = "agent"

        /**
         * Tool rounds allowed per turn in agent mode (the default is
         * [com.druk.llamacpp.chat.ToolCallLoop.DEFAULT_MAX_TOOL_ROUNDS]). A plan
         * of several steps needs a few rounds each: search, fetch, update.
         */
        const val MAX_TOOL_ROUNDS = 25

        private val ACTION_ALIASES = mapOf(
            "create" to "plan", "make_plan" to "plan", "set_plan" to "plan", "start" to "plan",
            "mark" to "update", "progress" to "update", "set_status" to "update",
            "remember" to "note", "save" to "note", "add_note" to "note",
            "show" to "status", "get" to "status", "list" to "status", "view" to "status",
            "finished" to "finish", "complete" to "finish", "done" to "finish", "end" to "finish",
            "final" to "finish", "submit" to "finish",
        )
    }
}
