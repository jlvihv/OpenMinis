package com.openminis.app.tools

import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam

object SubagentTool {
    const val NAME = "subagent"

    fun definition(rosterNames: List<String>): AgentToolDefinition =
        AgentToolDefinition(
            name = NAME,
            description = "Delegate to an isolated sub agent when you cannot keep up yourself — otherwise do the work directly. Best for substantial, self-contained work; also inspects/controls your agents. It cannot see this chat or its memory. Avoid trivial 1-2 call tasks or work needing user confirmation. Up to 3 run concurrently; extras queue — never re-delegate queued work. With wait=false, final/partial results arrive as [Background task finished …] messages, including on cancellation/timeout/failure: do not poll. End your turn when idle; never promise later reporting.",
            parameters = mapOf(
                "tool_title" to AgentToolParam("string", "User-visible summary of this call."),
                "action" to AgentToolParam("string", "delegate (default): start; status: inspect; steer: correct running; cancel: stop + partial; resume: restart interrupted", enumValues = listOf("delegate", "status", "steer", "cancel", "resume")),
                "task" to AgentToolParam("string", "Required for delegate. Complete brief: goal, success criteria, paths/URLs, constraints, deliverable; it sees nothing else."),
                "agent" to AgentToolParam("string", "Delegate only; pick by roster description (omit = general)", enumValues = rosterNames),
                "model_choice" to AgentToolParam("string", "Delegate, Auto agents only (pinned groups ignore it). same_as_me (default) = current model; default_model = strongest, only for a concrete capability need; sub_model = light, for mechanical bounded verified work. Judge demands, not duration.", enumValues = listOf("same_as_me", "default_model", "sub_model")),
                "context" to AgentToolParam("string", "Delegate only; raw material appended verbatim to task"),
                "max_minutes" to AgentToolParam("integer", "Delegate budget: default 10, max 60 minutes; timeout returns partial results"),
                "wait" to AgentToolParam("boolean", "Delegate only. false (default) = return job_id, result arrives later; true = wait for a dependency (a user message sends it to background)"),
                "progress_report" to AgentToolParam("string", "Delegate only; ignored with wait=true. none (default) | frequent (15s) | moderate (1/min). Progress costs model turns — never poll it.", enumValues = listOf("none", "frequent", "moderate")),
                "job_id" to AgentToolParam("string", "Job id or prefix: required for steer/cancel; optional for status (omit = all) and resume"),
                "message" to AgentToolParam("string", "Required for steer. Delivered at the next turn; reported missed if the agent already finished"),
                "child_session_id" to AgentToolParam("string", "Resume only: one interrupted child; omit both ids = all in this chat"),
            ),
            required = listOf("tool_title"),
            propertyOrdering = listOf(
                "tool_title", "action", "task", "agent", "model_choice", "context",
                "max_minutes", "wait", "progress_report", "job_id", "message",
                "child_session_id",
            ),
        )
}
