package com.openminis.app.tools

import android.content.Context
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import com.openminis.app.sandbox.PRootKernel
import org.json.JSONObject
import kotlinx.coroutines.ensureActive

object ReadTool {
    const val NAME = "read"

    fun definition(): AgentToolDefinition = AgentToolDefinition(
        name = NAME,
        description = "Read a file. Supports text and images (jpg, png, gif, webp, bmp). Images are sent as attachments. Text output is truncated to 2000 lines or 50KB (whichever is hit first). Use offset/limit for large files and continue with offset until complete.",
        parameters = mapOf(
            "path" to AgentToolParam("string", "Path to the file to read (relative or absolute)"),
            "offset" to AgentToolParam("integer", "Line number to start reading from (1-indexed)"),
            "limit" to AgentToolParam("integer", "Maximum number of lines to read"),
            "tool_title" to AgentToolParam("string", "User-visible summary of this call, in the user's language."),
        ),
        required = listOf("path", "tool_title"),
        propertyOrdering = listOf("tool_title", "path", "offset", "limit"),
    )

    suspend fun execute(argsJson: String, sessionId: String, context: Context,
        resizeOptions: com.openminis.app.data.model.ModelImageResizeOptions? = null,
        supportsImages: Boolean = true): ToolExecutionResult =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val args = JSONObject(argsJson)
                require(CoreToolNames.validate(args, definition()) == null) { CoreToolNames.validate(args, definition()).orEmpty() }
                val path = CoreToolNames.linuxPath(args.getString("path"))
                val file = PRootKernel.resolveSessionHostPath(sessionId, path, context) ?: error("Cannot resolve path: $path")
                require(file.isFile && file.canRead()) { "Not a readable file: $path" }
                if (PiToolText.imageMime(file) != null) {
                    val image = ImageReader.execute(argsJson, sessionId, context, resizeOptions, supportsImages)
                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                    return@withContext image
                }
                val text = file.readText(Charsets.UTF_8)
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                ToolExecutionResult(PiToolText.read(text, path, args.optInt("offset", 1),
                    args.optInt("limit").takeIf { args.has("limit") && !args.isNull("limit") }), true,
                    toolTitle = args.optString("tool_title", NAME))
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (failure: Exception) { ToolExecutionResult("Error reading file: ${failure.message}", false) }
        }
}
