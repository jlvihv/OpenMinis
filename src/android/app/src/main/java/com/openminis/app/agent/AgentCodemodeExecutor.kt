package com.openminis.app.agent

import android.content.Context
import android.util.Base64
import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.repository.ChatRepository
import com.openminis.app.sandbox.PRootKernel
import com.openminis.app.tools.CodemodeStore
import com.openminis.app.tools.CodemodeTool
import com.openminis.app.tools.ToolExecutionResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.UUID

/** Branch-owned native codemode lifecycle, including nested preflight, store entries and artifacts. */
internal class AgentCodemodeExecutor(private val context: Context, private val repository: ChatRepository) {
    suspend fun execute(argsJson: String, toolId: String, input: AgentToolExecutor.InputContext,
        definitions: List<AgentToolDefinition>, disabled: (String) -> ToolExecutionResult,
        update: (List<CodemodeTool.Call>) -> Unit,
        invoke: suspend (String, String, String) -> ToolExecutionResult): ToolExecutionResult {
        if (CodemodeTool.mode(context) == "off") return disabled(CodemodeTool.NAME)
        val source = try {
            val value = JSONObject(argsJson).opt("code")
            require(value is String) { "code must be a string" }
            value
        } catch (failure: Exception) {
            return ToolExecutionResult("Expected JavaScript source in code: ${failure.message}", false,
                toolTitle = CodemodeTool.NAME)
        }
        input.checkBranch()
        // Full transcript, not effective/compacted context: custom entries survive restart/fork/rewind.
        val store = CodemodeStore.read(repository.loadMessages(input.sessionId).map { it.partsJson })
        input.checkBranch()
        val tools = definitions.filter { it.name != CodemodeTool.NAME }
        val result = CodemodeTool.execute(context, source, toolId, tools, store,
            spill = { text -> artifact(input, "txt") { it.writeText(text) }.first },
            invoke = { name, arguments, id ->
                input.checkBranch()
                val definition = tools.firstOrNull { it.name == name }
                    ?: throw IllegalArgumentException("Unknown or unavailable tool: $name")
                val args = JSONObject(arguments)
                AgentToolRound.validate(name, args, listOf(definition))?.let { throw IllegalArgumentException(it) }
                invoke(name, args.toString(), id)
            }, onUpdate = update,
            appendEntry = { writes -> withContext(NonCancellable + Dispatchers.IO) {
                repository.appendCodemodeStoreEntry(input.sessionId, writes)
            } })
        val images = result.images.map { image ->
            val bytes = Base64.decode(image.data, Base64.DEFAULT)
            val extension = when (image.mimeType) { "image/jpeg" -> "jpg"; "image/gif" -> "gif"; "image/webp" -> "webp"; else -> "png" }
            val (path, file) = artifact(input, extension) { it.writeBytes(bytes) }
            AgentContentPart.ImageData(bytes, image.mimeType!!, linuxPath = path) to file.absolutePath
        }
        val first = images.firstOrNull()
        return ToolExecutionResult(result.output, result.success,
            imageData = first?.first?.data, imageMimeType = first?.first?.mimeType,
            imageLinuxPath = first?.first?.linuxPath, imageFilePath = first?.second,
            toolTitle = CodemodeTool.titleFromSource(source) ?: CodemodeTool.NAME,
            additionalImages = images.drop(1).map { it.first }, detailsJson = CodemodeTool.details(result.calls))
    }

    private suspend fun artifact(input: AgentToolExecutor.InputContext, extension: String,
        write: (java.io.File) -> Unit): Pair<String, java.io.File> = withContext(Dispatchers.IO) {
        input.checkBranch()
        val path = "/var/minis/offloads/codemode-${UUID.randomUUID()}.$extension"
        val file = PRootKernel.resolveSessionHostPath(input.fsSessionId, path, context)
            ?: error("Unable to resolve codemode output path")
        file.parentFile?.mkdirs()
        write(file)
        path to file
    }
}
