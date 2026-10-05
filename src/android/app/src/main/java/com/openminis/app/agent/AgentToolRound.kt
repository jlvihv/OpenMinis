package com.openminis.app.agent

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.provider.ToolJsonRepair
import com.openminis.app.scheduled.ScriptedToolTurn
import com.openminis.app.tools.CoreToolNames
import com.openminis.app.tools.ToolExecutionResult
import com.openminis.app.logging.AppLogger
import org.json.JSONObject

/** Owns tool-round policy and model-facing results; presentation receives events only. */
internal class AgentToolRound(
    private val detector: ToolLoopDetector,
    private val trace: AgentToolInputTrace,
    private val tools: List<AgentToolDefinition>,
) {
    suspend fun execute(
        calls: List<Triple<String, String, JSONObject>>,
        concurrency: Int,
        journal: AgentTurnJournal,
        starting: (String, JSONObject) -> Unit,
        running: suspend (String) -> Unit,
        blocked: suspend (String, String) -> Unit,
        finished: suspend (String, String, ToolExecutionResult, Boolean) -> Unit,
        invoke: suspend (String, String, String) -> ToolExecutionResult,
    ): List<AgentContentPart> = AgentToolBatchCoordinator.execute(calls, concurrency) call@ { (id, name, args) ->
        starting(name, args)
        val repairs = ToolJsonRepair.repair(name, args, trace[id]?.lastOrNull(), tools)
        if (repairs.isNotEmpty()) {
            AppLogger.warning("ToolPreflight", "[ToolRepair] REPAIRED tool=$name id=$id strategies=[${repairs.joinToString()}] argsKeys=[${args.keys().asSequence().toList().sorted().joinToString()}] rawTail=<<<${trace[id]?.lastOrNull()?.take(500).orEmpty()}>>>")
        }
        val truncation = repairs.firstOrNull { it.startsWith("truncation+") }
        val params = parseParams(args)
        suspend fun reject(modelMessage: String, displayMessage: String): List<AgentContentPart> {
            detector.record(name, params, result = null, errorMessage = modelMessage, toolCallId = id)
            val parts = listOf(AgentContentPart.ToolResult(id = id, name = name, content = modelMessage, isError = true))
            journal.completed(parts)
            blocked(id, displayMessage)
            return parts
        }
        if (truncation != null && CoreToolNames.isMutation(name)) {
            val path = args.optString("path", "").ifBlank { args.optString("file_path", "") }
            val message = buildString {
                append("Error: This call was NOT executed. Its argument stream was truncated ")
                append("in transit (repair strategy: $truncation), so the `content` ")
                append("your client sent was cut short and would have written an incomplete file")
                if (path.isNotBlank()) append(" to $path")
                append(". Nothing was written to disk — the target file is unchanged.\n\n")
                append("The most likely cause is the response hitting its output-token limit ")
                append("mid-argument. Re-issue this write in smaller pieces: write the first ")
                append("part, then append the rest with follow-up calls, rather than repeating ")
                append("the same oversized call.")
            }
            AppLogger.warning("ToolPreflight", "[ToolRepair] REFUSED truncated write tool=$name id=$id strategy=$truncation path=$path")
            trace.remove(id)
            return@call reject(message, "Blocked: arguments were truncated in transit")
        }
        running(id)
        val check = if (ScriptedToolTurn.isScriptedId(id)) LoopCheckResult.NONE else detector.check(name, params)
        if (check.level == Level.CRITICAL) {
            val message = check.message ?: "[LOOP BLOCKED] tool execution blocked"
            return@call reject(message, message)
        }
        val invalid = validate(name, args, tools)
        if (invalid != null) {
            val chunks = trace.remove(id).orEmpty()
            AppLogger.warning("ToolPreflight", "BLOCKED tool=$name id=$id reason=\"$invalid\" argsKeys=[${args.keys().asSequence().toList().sorted().joinToString()}] chunkCount=${chunks.size} lastChunk=<<<${chunks.lastOrNull()?.take(500).orEmpty()}>>>")
            chunks.forEachIndexed { index, chunk ->
                AppLogger.warning("ToolPreflight", "  chunk[$index] bytes=${chunk.toByteArray(Charsets.UTF_8).size} raw=<<<${chunk.take(500)}>>>")
            }
            return@call reject("Error: Tool call rejected before execution. $invalid The arguments your client sent were empty or missing required fields — re-issue the call with all required parameters filled in. Do not retry with the same empty arguments.", "Blocked invalid tool call")
        }
        val result = invoke(name, args.toString(), id)
        journal.recordToolPresentation(id, AgentJournalWriter.ToolPresentation(result.toolTitle, result.pageURL.orEmpty(), result.imageFilePath.orEmpty()))
        val recorded = detector.record(name, params, result = result.output.takeIf { result.success }, errorMessage = result.output.takeUnless { result.success }, toolCallId = id)
        var output = if (recorded.level == Level.WARNING && recorded.message != null) "${result.output}\n\n${recorded.message}" else result.output
        if (truncation != null) {
            output += "\n\n<system-reminder>The argument stream for this call was truncated in transit and auto-closed by the client (repair strategy: $truncation) before execution. The arguments actually used may be incomplete — verify the result and re-issue the call with complete arguments if anything is missing.</system-reminder>"
        }
        val parts = listOf(AgentContentPart.ToolResult(id = id, name = name, content = output, isError = !result.success,
            imageData = result.imageData, imageMimeType = result.imageMimeType, imageLinuxPath = result.imageLinuxPath, detailsJson = result.detailsJson)) + result.additionalImages
        // A stop during Main-thread publication must not turn an already executed tool into a cancellation.
        journal.completed(parts)
        finished(id, name, result, truncation != null)
        parts
    }

    companion object {
        fun emptyStringAllowed(tool: String, field: String): Boolean = tool == "write" && field == "content"

        fun validate(name: String, args: JSONObject, tools: List<AgentToolDefinition>): String? {
            val definition = tools.firstOrNull { it.name == name } ?: return null
            val required = definition.required
            if ("tool_title" in required && (args.opt("tool_title") !is String || args.getString("tool_title").isBlank())) {
                return "Tool '$name': tool_title is required and must be a non-blank string."
            }
            if (args.length() == 0 && required.isNotEmpty()) {
                return "Tool '$name' was called with empty arguments {} but requires: ${required.joinToString(", ")}."
            }
            val missing = required.filter { field ->
                !args.has(field) || args.isNull(field) ||
                    (args.opt(field) is String && args.getString(field).isEmpty() && !emptyStringAllowed(name, field))
            }
            if (missing.isNotEmpty()) return "Tool '$name' is missing required parameter(s): ${missing.joinToString(", ")}."
            return if (name in setOf("read", "write", "edit", "bash")) CoreToolNames.validate(args, definition) else null
        }

        private fun parseParams(args: JSONObject): Map<String, Any?> = args.keys().asSequence().associateWith { key ->
            args.opt(key).let { if (it == JSONObject.NULL) null else it }
        }
    }
}
