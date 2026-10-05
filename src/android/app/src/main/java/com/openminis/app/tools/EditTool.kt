package com.openminis.app.tools

import android.content.Context
import com.openminis.app.data.ContextOffload
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import com.openminis.app.sandbox.PRootKernel
import kotlinx.coroutines.*
import org.json.JSONObject

object EditTool {
    const val NAME = "edit"
    fun definition() = AgentToolDefinition(NAME,
        "Edit a single file using exact text replacement. Every edits[].oldText must match a unique, non-overlapping region of the ORIGINAL file: merge changes that touch the same or nearby lines into one entry instead of emitting overlapping or nested edits, and do not pad an oldText with large unchanged regions just to reach distant changes.",
        mapOf("path" to AgentToolParam("string", "Path to the file to edit (relative or absolute)"),
            "edits" to AgentToolParam("array", "One or more replacements.",
                items = AgentToolParam("object", "Targeted replacement", properties = mapOf(
                    "oldText" to AgentToolParam("string", "Exact text to replace; must be unique in the original file and not overlap any other edits[].oldText."),
                    "newText" to AgentToolParam("string", "Replacement text for this targeted edit.")), required = listOf("oldText", "newText"))),
            "tool_title" to AgentToolParam("string", "User-visible summary of this call.")), listOf("path", "edits", "tool_title"), propertyOrdering = listOf("tool_title", "path", "edits"))

    internal fun arguments(args: JSONObject): List<PiFileEditor.Edit> {
        fun text(obj: JSONObject, key: String): String = obj.get(key).let { require(it is String) { "$key must be a string" }; it }
        val edits = args.getJSONArray("edits")
        return (0 until edits.length()).map { i -> edits.getJSONObject(i).let { PiFileEditor.Edit(text(it, "oldText"), text(it, "newText")) } }
    }
    suspend fun execute(argsJson: String, sessionId: String, context: Context): ToolExecutionResult = withContext(Dispatchers.IO) {
        try {
            val args = JSONObject(argsJson)
            require(CoreToolNames.validate(args, definition()) == null) { CoreToolNames.validate(args, definition()).orEmpty() }
            val path = CoreToolNames.linuxPath(args.getString("path"))
            val edits = arguments(args)
            require(edits.isNotEmpty()) { "edits must contain at least one replacement" }
            if (edits.any { ContextOffload.isOffloadPlaceholder(it.newText) }) return@withContext ToolExecutionResult(
                ContextOffload.placeholderWriteRefusal("newText", path), false)
            if (PRootKernel.isLinuxPathUnderReadOnlyMount(path)) return@withContext ToolExecutionResult(
                "Error: $path is inside a read-only mounted folder. Change writability in Settings → Mount External Folders.", false)
            val file = PRootKernel.resolveSessionHostPath(sessionId, path, context) ?: error("Cannot resolve path: $path")
            FileMutationQueue.withFile(file) {
                currentCoroutineContext().ensureActive()
                require(file.isFile && file.canRead() && file.canWrite()) { "Cannot edit file: $path" }
                val result = PiFileEditor.apply(file.readText(Charsets.UTF_8), edits, path)
                currentCoroutineContext().ensureActive()
                require(!PRootKernel.isLinuxPathUnderReadOnlyMount(path)) { "$path is inside a read-only mounted folder" }
                file.writeText(result.content, Charsets.UTF_8)
                currentCoroutineContext().ensureActive()
                val details = JSONObject().put("diff", PiToolText.utf8Prefix(result.diff, 32 * 1024))
                    .put("firstChangedLine", result.firstChangedLine)
                if (result.patch.toByteArray(Charsets.UTF_8).size <= 32 * 1024) details.put("patch", result.patch)
                else {
                    val patchPath = "/var/minis/offloads/edit-${java.util.UUID.randomUUID()}.patch"
                    // The edit already succeeded; an optional preview spill must not invite re-execution.
                    runCatching {
                        PRootKernel.resolveSessionHostPath(sessionId, patchPath, context)?.let { patch ->
                            patch.parentFile?.mkdirs(); patch.writeText(result.patch); details.put("patch_path", patchPath)
                        }
                    }
                }
                ToolExecutionResult("Successfully replaced ${edits.size} block(s) in $path.", true,
                    toolTitle = args.optString("tool_title", NAME), detailsJson = details.toString())
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { ToolExecutionResult("Error editing file: ${failure.message}", false) }
    }
}
