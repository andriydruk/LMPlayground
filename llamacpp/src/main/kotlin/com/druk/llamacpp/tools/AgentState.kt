package com.druk.llamacpp.tools

import org.json.JSONArray
import org.json.JSONObject

/**
 * The working memory behind the `agent` tool: an ordered plan, a short list of
 * saved findings, and a finished flag.
 *
 * One instance is shared with [AgentTool] by a [ToolRegistry] (like
 * [WebLinkStore]) and lives for a conversation: the app snapshots it into the
 * conversation's metadata ([toJson]) and restores it when the chat is
 * reopened ([restore]).
 *
 * Everything is bounded so a runaway model can't grow the state (and the tool
 * results that echo it) without limit.
 */
class AgentState {

    enum class Status(val wire: String) {
        PENDING("pending"),
        IN_PROGRESS("in_progress"),
        DONE("done"),
        SKIPPED("skipped");

        val isOpen: Boolean get() = this == PENDING || this == IN_PROGRESS

        companion object {
            /** Accepts the wire names plus the synonyms small models tend to use. */
            fun parse(raw: String?): Status? {
                val key = raw?.trim()?.lowercase()?.replace('-', '_')?.replace(' ', '_') ?: return null
                return when (key) {
                    "pending", "todo", "not_started", "open" -> PENDING
                    "in_progress", "inprogress", "started", "doing", "active", "working", "current" -> IN_PROGRESS
                    "done", "complete", "completed", "finished", "ok", "success" -> DONE
                    "skipped", "skip", "cancelled", "canceled", "dropped", "n/a" -> SKIPPED
                    else -> null
                }
            }
        }
    }

    data class Step(val text: String, val status: Status)

    /** Immutable view handed to [AgentTool] for rendering. */
    data class View(val steps: List<Step>, val notes: List<String>, val finished: Boolean, val summary: String?) {
        val isEmpty: Boolean get() = steps.isEmpty() && notes.isEmpty() && !finished

        /** The step the model should be working on: in progress, else the first pending one. */
        fun currentStepIndex(): Int {
            val active = steps.indexOfFirst { it.status == Status.IN_PROGRESS }
            return if (active >= 0) active else steps.indexOfFirst { it.status == Status.PENDING }
        }
    }

    private var steps: List<Step> = emptyList()
    private var notes: List<String> = emptyList()
    private var finished = false
    private var summary: String? = null

    @Synchronized
    fun view(): View = View(steps, notes, finished, summary)

    /**
     * Replace the plan. Blank entries are dropped, long ones truncated, and
     * the list capped at [MAX_STEPS]; the first step starts in progress.
     * Notes survive a re-plan. Returns the number of steps kept.
     */
    @Synchronized
    fun setPlan(texts: List<String>): Int {
        val cleaned = texts.asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { it.take(MAX_STEP_CHARS) }
            .take(MAX_STEPS)
            .toList()
        steps = cleaned.mapIndexed { i, t -> Step(t, if (i == 0) Status.IN_PROGRESS else Status.PENDING) }
        finished = false
        summary = null
        return steps.size
    }

    /**
     * Set one step's status ([index] is 1-based). Returns an error message the
     * model can act on, or null on success.
     *
     * Only one step is in progress at a time. Closing the active step (done /
     * skipped) promotes the next pending one so the model always has a
     * "current" step to point at.
     */
    @Synchronized
    fun updateStep(index: Int, status: Status): String? {
        if (steps.isEmpty()) return "There is no plan yet. Call agent with action=plan first."
        if (index < 1 || index > steps.size) {
            return "There is no step $index. The plan has ${steps.size} step${if (steps.size == 1) "" else "s"} (1-${steps.size})."
        }
        val updated = steps.toMutableList()
        if (status == Status.IN_PROGRESS) {
            for (i in updated.indices) {
                if (updated[i].status == Status.IN_PROGRESS) updated[i] = updated[i].copy(status = Status.PENDING)
            }
        }
        updated[index - 1] = updated[index - 1].copy(status = status)
        if (updated.none { it.status == Status.IN_PROGRESS }) {
            val next = updated.indexOfFirst { it.status == Status.PENDING }
            if (next >= 0) updated[next] = updated[next].copy(status = Status.IN_PROGRESS)
        }
        steps = updated
        return null
    }

    /**
     * Save a finding. Exact duplicates are ignored; past [MAX_NOTES] the oldest
     * note is dropped. Returns false when [text] was blank or already saved.
     */
    @Synchronized
    fun addNote(text: String): Boolean {
        val clean = text.trim().take(MAX_NOTE_CHARS)
        if (clean.isEmpty() || clean in notes) return false
        notes = (notes + clean).takeLast(MAX_NOTES)
        return true
    }

    @Synchronized
    fun finish(text: String?) {
        finished = true
        summary = text?.trim()?.take(MAX_NOTE_CHARS)?.takeIf { it.isNotEmpty() }
    }

    /**
     * Called at the start of each user turn. A finished workflow belongs to the
     * previous request, so the next message starts clean; an unfinished one is
     * kept so the model can pick it back up.
     */
    @Synchronized
    fun onTurnStart() {
        if (finished) clear()
    }

    @Synchronized
    fun clear() {
        steps = emptyList()
        notes = emptyList()
        finished = false
        summary = null
    }

    /** Compact JSON for the conversation metadata, or null when there is nothing to save. */
    @Synchronized
    fun toJson(): String? {
        if (steps.isEmpty() && notes.isEmpty() && !finished) return null
        val obj = JSONObject()
        val stepArr = JSONArray()
        for (s in steps) stepArr.put(JSONObject().put("t", s.text).put("s", s.status.wire))
        obj.put("steps", stepArr)
        val noteArr = JSONArray()
        for (n in notes) noteArr.put(n)
        obj.put("notes", noteArr)
        obj.put("finished", finished)
        summary?.let { obj.put("summary", it) }
        return obj.toString()
    }

    /** Replace the contents from [toJson] output. Unreadable input leaves the state empty. */
    @Synchronized
    fun restore(json: String?) {
        clear()
        if (json.isNullOrBlank()) return
        try {
            val obj = JSONObject(json)
            val stepArr = obj.optJSONArray("steps")
            val restored = mutableListOf<Step>()
            if (stepArr != null) {
                for (i in 0 until minOf(stepArr.length(), MAX_STEPS)) {
                    val s = stepArr.optJSONObject(i) ?: continue
                    val text = s.optString("t").take(MAX_STEP_CHARS)
                    if (text.isEmpty()) continue
                    restored += Step(text, Status.parse(s.optString("s")) ?: Status.PENDING)
                }
            }
            val noteArr = obj.optJSONArray("notes")
            val restoredNotes = mutableListOf<String>()
            if (noteArr != null) {
                for (i in 0 until noteArr.length()) {
                    val n = noteArr.optString(i).take(MAX_NOTE_CHARS)
                    if (n.isNotEmpty()) restoredNotes += n
                }
            }
            steps = restored
            notes = restoredNotes.takeLast(MAX_NOTES)
            finished = obj.optBoolean("finished", false)
            summary = if (obj.has("summary")) obj.optString("summary").takeIf { it.isNotEmpty() } else null
        } catch (_: Exception) {
            clear()
        }
    }

    companion object {
        const val MAX_STEPS = 12
        const val MAX_STEP_CHARS = 200
        const val MAX_NOTES = 20
        const val MAX_NOTE_CHARS = 500
    }
}
