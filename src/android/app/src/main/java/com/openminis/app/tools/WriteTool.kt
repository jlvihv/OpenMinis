package com.openminis.app.tools

import android.content.Context
import com.openminis.app.data.ContextOffload
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import com.openminis.app.sandbox.PRootKernel
import kotlinx.coroutines.*
import org.json.JSONObject

object WriteTool {
    const val NAME = "write"
    fun definition() = AgentToolDefinition(NAME,
        "Write content to a file. Creates the file if it doesn't exist, overwrites if it does. Automatically creates parent directories.",
        mapOf("path" to AgentToolParam("string", "Path to the file to write (relative or absolute)"),
            "content" to AgentToolParam("string", "Content to write to the file"),
            "tool_title" to AgentToolParam("string", "User-visible summary of this call.")), listOf("path", "content", "tool_title"), propertyOrdering = listOf("tool_title", "path", "content"))

    suspend fun execute(argsJson: String, sessionId: String, context: Context): ToolExecutionResult = withContext(Dispatchers.IO) {
        try {
            val args = JSONObject(argsJson)
            require(CoreToolNames.validate(args, definition()) == null) { CoreToolNames.validate(args, definition()).orEmpty() }
            val rawPath = args.getString("path")
            require(rawPath.isNotBlank()) { "path is required" }
            val path = CoreToolNames.linuxPath(rawPath)
            val content = args.getString("content")
            val title = args.optString("tool_title", NAME)
            if (ContextOffload.isOffloadPlaceholder(content)) return@withContext ToolExecutionResult(
                ContextOffload.placeholderWriteRefusal("content", path), false, toolTitle = title)
            if (PRootKernel.isLinuxPathUnderReadOnlyMount(path)) return@withContext ToolExecutionResult(
                "Error: $path is inside a read-only mounted folder. Change writability in Settings → Mount External Folders.", false, toolTitle = title)
            val file = PRootKernel.resolveSessionHostPath(sessionId, path, context) ?: error("Cannot resolve path: $path")
            FileMutationQueue.withFile(file) {
                currentCoroutineContext().ensureActive()
                file.parentFile?.let { require(it.isDirectory || it.mkdirs()) { "Cannot create parent directory: $path" } }
                currentCoroutineContext().ensureActive()
                require(!PRootKernel.isLinuxPathUnderReadOnlyMount(path)) { "$path is inside a read-only mounted folder" }
                file.writeText(content, Charsets.UTF_8)
                currentCoroutineContext().ensureActive()
                ToolExecutionResult("Successfully wrote to $path", true, toolTitle = title)
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { ToolExecutionResult("Error writing file: ${failure.message}", false) }
    }
}
