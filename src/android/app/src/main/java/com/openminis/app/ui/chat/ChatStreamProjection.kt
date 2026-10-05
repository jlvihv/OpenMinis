package com.openminis.app.ui.chat

import com.openminis.app.data.model.LLMStreamChunk
import com.openminis.app.logging.AppLogger
import com.openminis.app.service.SessionActivityTracker
import com.openminis.app.tools.CodemodeTool
import com.openminis.app.tools.CoreToolNames
import kotlinx.coroutines.yield
import org.json.JSONObject

internal class ChatStreamProjection(
    private val turn: Int,
    private val turnStart: Int,
    private val blocks: MutableList<AssistantBlock>,
    private val sessionId: String,
    private val prefix: () -> String,
    private val firstText: () -> Unit,
    private val publish: suspend (Snapshot) -> Unit,
) {
    data class Snapshot(val content: String, val blocks: List<AssistantBlock>, val reason: String? = null)
    private val text = StringBuilder()
    private val thinking = StringBuilder()
    private var activeText: StringBuilder? = null
    private var textIndex = -1
    private var postToolMergeLogged = false
    private var pendingText = false
    private var lastUiMs = 0L
    private var lastFlushedLength = 0
    private var lastFileInputMs = 0L
    private var lastOtherInputMs = 0L
    private var monolithic = false
    private var modelId = ""

    fun configure(monolithic: Boolean, modelId: String) {
        this.monolithic = monolithic
        this.modelId = modelId
    }

    fun text(): String = text.toString()
    val thinkingLength: Int get() = thinking.length

    private fun materializeText() {
        val value = activeText ?: return
        val index = if (monolithic) textIndex else blocks.lastIndex
        if (index in blocks.indices && blocks[index].kind == "text")
            blocks[index] = blocks[index].copy(content = value.toString())
    }

    private suspend fun emit(reason: String? = null) {
        publish(Snapshot(prefix() + text, blocks.toList(), reason))
    }

    private fun finishThinking() {
        val index = blocks.indexOfFirst { it.kind == "thinking" && it.id == "thinking_$turn" }
        if (index >= 0 && blocks[index].toolStatus != ToolBlockStatus.SUCCESS)
            blocks[index] = blocks[index].copy(toolStatus = ToolBlockStatus.SUCCESS)
    }

    suspend fun accept(chunk: LLMStreamChunk) {
        when (chunk) {
            is LLMStreamChunk.ThinkingDelta -> {
                thinking.append(chunk.text)
                SessionActivityTracker.setThinking(true)
                val index = blocks.indexOfFirst { it.kind == "thinking" && it.id == "thinking_$turn" }
                if (index < 0) blocks.add(AssistantBlock("thinking_$turn", "thinking", thinking.toString(), toolTitle = "Thinking"))
                else blocks[index] = blocks[index].copy(content = thinking.toString())
                emit()
            }
            is LLMStreamChunk.Text -> {
                SessionActivityTracker.setThinking(false)
                firstText()
                finishThinking()
                text.append(chunk.text)
                val last = blocks.lastIndex
                val active = activeText
                val buffer = if (monolithic && textIndex >= 0 && active != null) {
                    if (!postToolMergeLogged && blocks.subList(textIndex + 1, blocks.size).any { it.kind == "tool_use" }) {
                        postToolMergeLogged = true
                        AppLogger.info("ChatViewModel.Stream", "[T-android-tool-splits-reply-fix] post-tool_calls content delta merged into pre-tool text block (model=$modelId)")
                    }
                    active.append(chunk.text)
                } else if (!monolithic && last >= 0 && blocks[last].kind == "text" && active != null) {
                    active.append(chunk.text)
                } else {
                    val fresh = StringBuilder(chunk.text)
                    activeText = fresh
                    val block = AssistantBlock("text_${turn}_${blocks.size}", "text", chunk.text)
                    val firstTool = if (monolithic) (turnStart until blocks.size).firstOrNull { blocks[it].kind == "tool_use" } else null
                    if (firstTool != null) { blocks.add(firstTool, block); textIndex = firstTool }
                    else { blocks.add(block); if (monolithic) textIndex = blocks.lastIndex }
                    fresh
                }
                pendingText = true
                val length = text.length
                val now = System.currentTimeMillis()
                val newline = length < 5_000 && chunk.text.contains('\n') && length - lastFlushedLength >= 50
                if (now - lastUiMs >= throttle(length) || newline) {
                    lastUiMs = now
                    lastFlushedLength = length
                    pendingText = false
                    SessionActivityTracker.publishLiveReply(sessionId, buffer)
                    materializeText()
                    emit("publish")
                }
            }
            is LLMStreamChunk.ToolUseStart -> {
                android.util.Log.d("ToolChain[VM]", "[turn=$turn] ToolUseStart id=${chunk.id} name=${chunk.name}")
                finishThinking()
                if (text.isNotEmpty() && pendingText) {
                    pendingText = false
                    lastUiMs = System.currentTimeMillis()
                    lastFlushedLength = text.length
                    materializeText()
                    if (!monolithic) activeText = null
                    emit("pretool-flush")
                    yield()
                }
                lastFileInputMs = 0
                lastOtherInputMs = 0
                if (blocks.none { it.id == chunk.id }) {
                    blocks.add(AssistantBlock(id = chunk.id, kind = "tool_use", toolName = chunk.name,
                        toolStatus = ToolBlockStatus.STREAMING, toolTitle = StreamToolPresentation.friendly(chunk.name),
                        startTimeMs = System.currentTimeMillis()))
                    emit()
                }
            }
            is LLMStreamChunk.ToolInputDelta -> {
                if (AppLogger.traceEnabled) android.util.Log.d("ToolChain[VM]", "[turn=$turn] ToolInputDelta id=${chunk.id} len=${chunk.accumulated.length}")
                val index = blocks.indexOfFirst { it.id == chunk.id }
                if (index >= 0) {
                    val previous = blocks[index]
                    val codemode = previous.toolName == CodemodeTool.NAME
                    val codeTitle = if (codemode) CodemodeTool.titleFromSource(
                        StreamToolPresentation.partial("code", chunk.accumulated) ?: chunk.accumulated) else null
                    val completeTitle = if (codemode) codeTitle else com.openminis.app.service.completedToolTitle(chunk.accumulated)
                    SessionActivityTracker.publishToolTitle(sessionId, previous.toolName, completeTitle)
                    val partial = if (codemode) codeTitle else StreamToolPresentation.partial("tool_title", chunk.accumulated)
                    val title = when {
                        !partial.isNullOrEmpty() -> partial
                        previous.toolTitle.isNotEmpty() && previous.toolTitle != previous.toolName -> previous.toolTitle
                        else -> StreamToolPresentation.friendly(previous.toolName)
                    }
                    blocks[index] = previous.copy(toolArgs = chunk.accumulated, toolTitle = title, content = "")
                    val heavy = CoreToolNames.isMutation(previous.toolName)
                    val now = System.currentTimeMillis()
                    val last = if (heavy) lastFileInputMs else lastOtherInputMs
                    if (now - last >= if (heavy) 1_000L else 200L) {
                        if (heavy) lastFileInputMs = now else lastOtherInputMs = now
                        emit()
                    }
                }
            }
            is LLMStreamChunk.ToolCallComplete -> {
                android.util.Log.d("ToolChain[VM]", "[turn=$turn] ToolCallComplete id=${chunk.id} name=${chunk.name} args=${chunk.args.toString().take(300)}")
                val title = StreamToolPresentation.provided(chunk.name, chunk.args) ?: StreamToolPresentation.friendly(chunk.name)
                SessionActivityTracker.publishToolTitle(sessionId, chunk.name, title)
                val index = blocks.indexOfFirst { it.id == chunk.id }
                if (index >= 0) {
                    blocks[index] = blocks[index].copy(toolStatus = ToolBlockStatus.PENDING, toolTitle = title,
                        toolArgs = chunk.args.toString(), content = "", thoughtSignature = chunk.thoughtSignature ?: blocks[index].thoughtSignature)
                    emit()
                }
            }
            else -> Unit
        }
    }

    suspend fun finish() {
        if (pendingText) {
            pendingText = false
            materializeText()
            emit("final-flush")
        }
        resetGates()
    }

    fun resetAttempt() {
        text.setLength(0)
        thinking.setLength(0)
        activeText = null
        textIndex = -1
        pendingText = false
        resetGates()
    }

    private fun resetGates() {
        lastUiMs = 0
        lastFlushedLength = 0
        lastFileInputMs = 0
        lastOtherInputMs = 0
    }

    private fun throttle(length: Int): Long = when {
        length < 500 -> 150L
        length < 2_000 -> 300L
        length < 32_000 -> 500L
        length < 64_000 -> 1_000L
        length < 128_000 -> 1_500L
        else -> 2_000L
    }
}

internal object StreamToolPresentation {
    fun partial(key: String, json: String): String? {
        for (pattern in listOf("\"$key\": \"", "\"$key\":\"")) {
            val at = json.indexOf(pattern)
            if (at < 0) continue
            val after = json.substring(at + pattern.length)
            var index = 0
            while (index < after.length) {
                if (after[index] == '\\') { index += 2; continue }
                if (after[index] == '"') break
                index++
            }
            return after.substring(0, index.coerceAtMost(after.length)).replace("\\n", "\n")
                .replace("\\t", "\t").replace("\\\"", "\"").replace("\\/", "/").replace("\\\\", "\\")
        }
        return null
    }

    fun provided(name: String, args: JSONObject): String? =
        if (name == CodemodeTool.NAME) CodemodeTool.titleFromArguments(args)
        else args.optString("tool_title", "").takeIf { it.isNotBlank() }

    fun friendly(name: String): String = when (name) {
        "bash" -> "Execute Bash"
        "read" -> "Read File"
        "write" -> "Write File"
        "edit" -> "Edit File"
        "browser" -> "Browse Web"
        "web_search" -> "Search Web"
        else -> name.split('_').filter { it.isNotEmpty() }.joinToString(" ") { it.replaceFirstChar(Char::uppercase) }
    }
}
