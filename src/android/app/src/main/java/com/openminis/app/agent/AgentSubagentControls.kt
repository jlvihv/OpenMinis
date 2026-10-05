package com.openminis.app.agent

import com.openminis.app.agent.jobs.*
import com.openminis.app.tools.ToolExecutionResult
import org.json.JSONArray
import org.json.JSONObject

/** Shared model/card/queue policy. Input blocks are immutable display snapshots, not UI objects. */
internal class AgentSubagentControls(private val runtime: AgentSubagentRuntime) {
    data class Block(val id: String, val title: String, val args: String, val content: String)
    enum class Resume { STARTED, QUEUED, REFUSED }

    private fun anchor(block: Block): AgentSubagentRuntime.ResumeAnchor? {
        val json = runCatching { JSONObject(block.content) }.getOrNull() ?: return null
        val child = json.optString("child_session_id", "").takeIf { it.isNotEmpty() } ?: return null
        return AgentSubagentRuntime.ResumeAnchor(child, block.id, block.title.ifEmpty { json.optString("title", "") },
            json.optString("agent", "").ifEmpty { null })
    }

    fun interrupted(blocks: List<Block>): List<AgentSubagentRuntime.ResumeAnchor> = blocks.mapNotNull { block ->
        val json = runCatching { JSONObject(block.content) }.getOrNull()
        anchor(block)?.takeIf { json?.optString("status") == "running" && AgentJobRegistry.jobForSession(it.childId) == null }
    }

    fun resumeCard(childId: String, parent: AgentSubagentRuntime.Parent, blocks: List<Block>,
        effects: (String) -> AgentSubagentRuntime.Effects): Resume {
        parent.checkBranch()
        if (parent.helper || parent.sessionId.isEmpty()) return Resume.REFUSED
        if (AgentJobRegistry.isQueued("resume:$childId")) return Resume.QUEUED
        val lost = interrupted(blocks).firstOrNull { it.childId == childId } ?: return Resume.REFUSED
        val ports = effects(lost.toolId)
        return when {
            AgentJobRegistry.canStartChildJob -> if (runtime.resume(parent, lost, ports)) Resume.STARTED else Resume.REFUSED
            queueResume(parent, lost, ports) -> Resume.QUEUED
            else -> Resume.REFUSED
        }
    }

    private fun queueResume(parent: AgentSubagentRuntime.Parent, anchor: AgentSubagentRuntime.ResumeAnchor,
        effects: AgentSubagentRuntime.Effects): Boolean {
        parent.checkBranch()
        AgentJobRegistry.registerQueuedStarter(parent.sessionId, effects.queuedStarter)
        AgentJobRegistry.registerInterruptedCounter(parent.sessionId, effects.interruptedCount)
        val args = JSONObject().put(HelperRunner.QUEUED_RESUME_CHILD_KEY, anchor.childId).toString()
        return AgentJobRegistry.enqueueDelegation(AgentJobRegistry.QueuedDelegation(parent.sessionId, args, "resume:${anchor.childId}"))
    }

    fun resumeTool(argsJson: String, parent: AgentSubagentRuntime.Parent, blocks: List<Block>,
        effects: (String) -> AgentSubagentRuntime.Effects): ToolExecutionResult {
        parent.checkBranch()
        val args = runCatching { JSONObject(argsJson) }.getOrElse { JSONObject() }
        val title = args.optString("tool_title", "").ifEmpty { "resume sub agents" }
        if (parent.helper) return ToolExecutionResult(HelperRunner.rejectionJson("depth_limit", "A sub agent cannot resume another."), false, toolTitle = title)
        val wanted = args.optString("child_session_id", "").trim()
        val candidates = interrupted(blocks).map { it.childId }.distinct().filter { wanted.isEmpty() || it == wanted }
        if (candidates.isEmpty()) return ToolExecutionResult(JSONObject().put("ok", true).put("resumed", 0)
            .put("detail", if (wanted.isEmpty()) "No interrupted sub agents in this conversation." else "That sub agent is not interrupted; nothing to resume.").toString(), true, toolTitle = title)
        val resumed = JSONArray()
        val queued = JSONArray()
        val failed = JSONArray()
        candidates.forEach { child -> when (resumeCard(child, parent, blocks, effects)) {
            Resume.STARTED -> resumed.put(child)
            Resume.QUEUED -> queued.put(child)
            Resume.REFUSED -> failed.put(child)
        } }
        val json = JSONObject().put("ok", resumed.length() > 0 || queued.length() > 0)
            .put("resumed", resumed.length()).put("child_session_ids", resumed)
            .also { if (failed.length() > 0) it.put("failed", failed) }
            .also { if (queued.length() > 0) it.put("queued", queued) }
            .put("detail", buildList {
                if (resumed.length() > 0) add("${resumed.length()} sub agent(s) restarted; each reports back as a new message when it finishes. Do not re-delegate them. Elapsed and turn counts cover only the resumed part.")
                if (queued.length() > 0) add("All slots are busy; this resume is queued and starts when one frees.")
                if (failed.length() > 0 && resumed.length() == 0 && queued.length() == 0) add("None could be resumed.")
            }.joinToString(" "))
        return ToolExecutionResult(json.toString(), true, toolTitle = title)
    }

    fun queued(argsJson: String, toolId: String, parent: AgentSubagentRuntime.Parent, blocks: List<Block>,
        effects: (String) -> AgentSubagentRuntime.Effects): Boolean {
        parent.checkBranch()
        if (parent.sessionId.isEmpty() || parent.helper) return false
        val child = runCatching { JSONObject(argsJson).optString(HelperRunner.QUEUED_RESUME_CHILD_KEY, "") }.getOrDefault("")
        if (child.isNotEmpty()) {
            val original = interrupted(blocks).firstOrNull { it.childId == child } ?: return false
            // A competing start may have used the free slot before this callback; park the resume again.
            return if (AgentJobRegistry.canStartChildJob) runtime.resume(parent, original, effects(original.toolId))
                else queueResume(parent, original, effects(original.toolId))
        }
        val args = runCatching { JSONObject(argsJson).put(HelperRunner.QUEUED_REENTRY_KEY, true).put("wait", false).toString() }.getOrNull() ?: return false
        return runtime.queued(args, parent.copy(toolId = toolId, priorDelegations = 0), effects(toolId))
    }

    fun neverStarted(block: Block, parent: AgentSubagentRuntime.Parent, blocks: List<Block>,
        effects: (String) -> AgentSubagentRuntime.Effects): Boolean {
        if (block.args.isBlank() || HelperRunner.isControlOnly(block.args, block.content.ifEmpty { null })) return false
        return queued(block.args, block.id, parent, blocks, effects)
    }

    fun status(argsJson: String, parent: AgentSubagentRuntime.Parent,
        snapshot: (String) -> AgentSubagentRuntime.Snapshot?): ToolExecutionResult {
        parent.checkBranch()
        val registry = AgentJobRegistry
        val args = runCatching { JSONObject(argsJson) }.getOrElse { JSONObject() }
        val action = args.optString("action", "status").lowercase()
        val id = args.optString("job_id", "").trim()
        val title = args.optString("tool_title", "").ifEmpty { "subagent" }
        var jobs = registry.list().filter { it.target.parentSessionIdOrNull == parent.sessionId }
        fun result(json: JSONObject, success: Boolean) = ToolExecutionResult(json.toString(), success, toolTitle = title)
        if (id.isNotEmpty()) {
            jobs = jobs.filter { it.id == id || it.id.startsWith(id) }
            if (jobs.isEmpty()) return result(JSONObject().put("ok", false).put("error", "job_not_found").put("job_id", id), false)
        }
        if (action == "steer") {
            val message = args.optString("message", "").trim()
            if (id.isEmpty()) return result(JSONObject().put("ok", false).put("error", "job_id_required_for_steer"), false)
            if (message.isEmpty()) return result(JSONObject().put("ok", false).put("error", "message_required_for_steer"), false)
            val target = jobs.first()
            if (!target.isActive) return result(JSONObject().put("ok", false).put("status", "rejected").put("job_id", target.id)
                .put("reason", "already_finished").put("state", target.state.wire)
                .put("detail", "That sub agent has already finished — its result stands. Delegate a new task instead."), false)
            if (!registry.steer(target.id, message)) return result(JSONObject().put("ok", false).put("status", "rejected")
                .put("job_id", target.id).put("reason", "child_not_running").put("detail", "That sub agent has no live session to steer."), false)
            return result(JSONObject().put("ok", true).put("status", "queued").put("job_id", target.id)
                .also { target.agentName?.let { name -> it.put("agent", name) } }
                .put("detail", "Queued. The sub agent reads it at its next turn; a tool call already running is not interrupted. It may finish before consuming it."), true)
        }
        if (action == "cancel") {
            if (id.isEmpty()) return result(JSONObject().put("ok", false).put("error", "job_id_required_for_cancel"), false)
            jobs.filter { it.isActive }.forEach { registry.cancel(it.id, "agent_status cancel by parent model") }
        }
        val entries = JSONArray()
        jobs.forEach { original ->
            val job = registry.job(original.id) ?: original
            val missed = registry.missedSteersFor(job.id)
            val json = JSONObject().put("job_id", job.id).put("title", job.title)
                .also { job.agentName?.let { name -> it.put("agent", name) } }
                .also { if (missed.isNotEmpty()) it.put("missed_steer", JSONArray(missed)).put("missed_steer_note", "The run ended before reading these — the result does not reflect them.") }
                .put("state", job.state.wire).put("model_origin", job.modelOrigin ?: JSONObject.NULL)
                .put("tier_used", job.tierUsed ?: JSONObject.NULL).put("elapsed_s", (job.elapsedMs ?: 0L) / 1000)
                .put("child_session_id", job.runSessionId ?: JSONObject.NULL)
                .put("delivery", if (job.then is AgentJobThen.None) "tool_result" else "new_message_when_done")
            if (job.state == AgentJobState.RUNNING) job.runSessionId?.let { child ->
                val current = snapshot(child)
                if (current != null && current.tool.isNotEmpty()) json.put("current_tool", current.tool).put("current_status", current.activity.take(120))
                json.put("loop_iteration", current?.turns ?: 0)
            }
            if (!job.isActive) job.resultText?.let { json.put("result", it.take(2000)) }
            entries.put(json)
        }
        val waiting = registry.queuedCount(parent.sessionId)
        val note = "Running agents deliver their final result automatically as a new message in this conversation; you do not need to poll for it."
        return result(JSONObject().put("ok", true).put("action", action).put("count", entries.length()).put("agents", entries)
            .put("queued", waiting).put("note", if (waiting > 0) "$note $waiting more are queued and will start as slots free — do not re-delegate them." else note), true)
    }
}
