package com.openminis.app.tools

import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam

object SubagentTool {
    const val NAME = "subagent"

    fun definition(rosterNames: List<String>): AgentToolDefinition =
        AgentToolDefinition(
            name = NAME,
            description = "Delegate substantial, self-contained work to an isolated sub agent, or inspect/control your agents. It cannot see this chat or its memory. Avoid trivial 1-2 call tasks or work needing user confirmation. Up to 3 run concurrently; extras queue automatically: never re-delegate queued work. With wait=false, final/partial results arrive as new [Background task finished …] messages, including on cancellation/timeout/failure; no status polling is needed. End your turn when idle, never promise later reporting.",
            parameters = mapOf(
                "tool_title" to AgentToolParam("string", "User-visible 5-10 word summary, in the user's language."),
                "action" to AgentToolParam("string", "delegate (default): start task; status: inspect one/all; steer: correct a running agent; cancel: stop and return partial results; resume: restart interrupted runs (job_id/child_session_id for one, omit both for all).", enumValues = listOf("delegate", "status", "steer", "cancel", "resume")),
                "task" to AgentToolParam("string", "Required for delegate. Complete brief: goal, success criteria, paths/URLs, constraints and deliverable; the agent sees nothing else."),
                "agent" to AgentToolParam("string", "Delegate only. Choose by roster description; omitted = general agent.", enumValues = rosterNames),
                "model_choice" to AgentToolParam("string", "Delegate, Auto agents only; pinned groups ignore this. same_as_me (default): current model, always use when unsure. default_model: strongest group, only for a concrete need for more capability. sub_model: light group, only for mechanical, bounded, easily verified work. Judge task demands, not duration; switching may change cost/quality.", enumValues = listOf("same_as_me", "default_model", "sub_model")),
                "context" to AgentToolParam("string", "Delegate only. Optional raw material appended verbatim to task."),
                "max_minutes" to AgentToolParam("integer", "Delegate time budget: default 10, maximum 60 minutes; timeout stops the run and returns partial results."),
                "wait" to AgentToolParam("boolean", "Delegate only. false (default): return job_id immediately, result arrives later. true: wait for a required dependency; a user message switches the run to background."),
                "progress_report" to AgentToolParam("string", "Delegate only; ignored with wait=true. none (default): final only; frequent: changes every 15s; moderate: once/minute. Progress costs model turns: enable only when needed, never poll or re-delegate on progress.", enumValues = listOf("none", "frequent", "moderate")),
                "job_id" to AgentToolParam("string", "Returned job id or prefix. Required for steer/cancel; optional for status (omit = all in this chat) and resume."),
                "message" to AgentToolParam("string", "Required for steer. Correction delivered at the next turn, without interrupting an active tool; reported missed if already finished."),
                "child_session_id" to AgentToolParam("string", "Resume only. One interrupted child; omit both ids to resume all in this chat."),
            ),
            required = listOf("tool_title"),
            propertyOrdering = listOf(
                "tool_title", "action", "task", "agent", "model_choice", "context",
                "max_minutes", "wait", "progress_report", "job_id", "message",
                "child_session_id",
            ),
        )
}
