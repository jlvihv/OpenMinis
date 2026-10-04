package com.openminis.app.tools

import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam

object BashTool {
    const val NAME = "bash"

    fun definition(): AgentToolDefinition = AgentToolDefinition(
        name = NAME,
        description = "Execute a bash command in the current working directory. Returns stdout and stderr. Output is truncated to last 2000 lines or 50KB (whichever is hit first). If truncated, full output is saved to a temp file. Optionally provide a timeout in seconds.",
        parameters = mapOf(
            "command" to AgentToolParam("string", "Shell command to execute"),
            "timeout" to AgentToolParam("number", "Timeout in seconds (optional, no default timeout)"),
            "tool_title" to AgentToolParam("string", "Required non-blank user-visible summary, in the user's language."),
        ),
        required = listOf("command", "tool_title"),
        propertyOrdering = listOf("tool_title", "command", "timeout"),
        outputSchema = AgentToolParam("object", "Command result", properties = mapOf(
            "output" to AgentToolParam("string", "Combined stdout and stderr, possibly truncated"),
            "truncated" to AgentToolParam("boolean", "Whether programmatic output was truncated"),
            "full_output_path" to AgentToolParam("string", "Full output, when truncated"),
            "exit_code" to AgentToolParam("number", "Command exit code"),
            "wall_time_seconds" to AgentToolParam("number", "Elapsed execution time")),
            required = listOf("output", "truncated", "exit_code", "wall_time_seconds")),
    )
}
