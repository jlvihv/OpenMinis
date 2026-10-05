package com.openminis.app.agent

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMStreamChunk
import com.openminis.app.logging.AppLogger
import org.json.JSONObject

internal class AgentTurnContent {
    private sealed interface Segment {
        class Text(val text: StringBuilder) : Segment
        class Tool(var part: AgentContentPart.ToolUse) : Segment
        data object Thinking : Segment
    }
    private val segments = mutableListOf<Segment>()
    private val completed = mutableListOf<Triple<String, String, JSONObject>>()
    val calls: List<Triple<String, String, JSONObject>> get() = synchronized(this) { completed.toList() }
    private val signatures = mutableMapOf<String, String>()
    val thoughtSignatures: Map<String, String> get() = synchronized(this) { signatures.toMap() }
    private val starts = mutableMapOf<String, Int>()
    private val completions = mutableMapOf<String, Int>()
    private val inFlight = mutableMapOf<String, String>()
    private var thinkingSeen = false
    private var monolithicText: Segment.Text? = null
    private val reasoning = StringBuilder()
    private var opaqueReasoning: String? = null
    @Volatile var finishReason: String? = null
        private set

    @Synchronized fun accept(chunk: LLMStreamChunk, monolithic: Boolean): LLMStreamChunk = when (chunk) {
        is LLMStreamChunk.ThinkingDelta -> chunk.also { thinking(); reasoning.append(it.text) }
        is LLMStreamChunk.ReasoningContent -> chunk.also { opaqueReasoning = it.content }
        is LLMStreamChunk.Finished -> chunk.also { finishReason = it.stopReason }
        is LLMStreamChunk.Text -> chunk.also { text(it.text, monolithic) }
        is LLMStreamChunk.ToolUseStart -> chunk.copy(id = start(chunk.id, chunk.name))
        is LLMStreamChunk.ToolInputDelta -> chunk.copy(id = inputId(chunk.id))
        is LLMStreamChunk.ToolCallComplete -> chunk.copy(id = complete(chunk))
        else -> chunk
    }

    @Synchronized fun visibleText(): String = buildString {
        segments.forEach { if (it is Segment.Text) append(it.text) }
    }

    val opaqueReasoningLength: Int? get() = synchronized(this) { opaqueReasoning?.length }
    @Synchronized fun reasoningContent(): String? = opaqueReasoning ?: reasoning.toString().takeIf { it.isNotEmpty() }

    private fun thinking() {
        if (!thinkingSeen) {
            thinkingSeen = true
            segments.add(Segment.Thinking)
        }
    }

    private fun text(delta: String, monolithic: Boolean) {
        if (monolithic) {
            val text = monolithicText ?: Segment.Text(StringBuilder()).also { text ->
                val firstTool = segments.indexOfFirst { it is Segment.Tool }
                if (firstTool < 0) segments.add(text) else segments.add(firstTool, text)
                monolithicText = text
            }
            text.text.append(delta)
        } else {
            val text = segments.lastOrNull() as? Segment.Text
                ?: Segment.Text(StringBuilder()).also(segments::add)
            text.text.append(delta)
        }
    }

    private fun start(rawId: String, name: String): String {
        val count = (starts[rawId] ?: 0) + 1
        starts[rawId] = count
        val id = if (count == 1) rawId else "$rawId-$count"
        if (count > 1) AppLogger.warning("ChatViewModel.Stream",
            "[ToolDedupe] duplicate tool_call id on stream start: '$rawId' #$count -> renamed '$id'")
        inFlight[rawId] = id
        if (segments.none { it is Segment.Tool && it.part.id == id }) {
            segments.add(Segment.Tool(AgentContentPart.ToolUse(id, name, JSONObject())))
        }
        return id
    }

    private fun inputId(rawId: String): String = inFlight[rawId] ?: rawId

    private fun complete(chunk: LLMStreamChunk.ToolCallComplete): String {
        val count = (completions[chunk.id] ?: 0) + 1
        completions[chunk.id] = count
        val id = if (count == 1) chunk.id else "${chunk.id}-$count"
        completed.add(Triple(id, chunk.name, chunk.args))
        chunk.thoughtSignature?.let { signatures[id] = it }
        val slot = segments.filterIsInstance<Segment.Tool>().firstOrNull { it.part.id == id }
        val part = AgentContentPart.ToolUse(id, chunk.name, JSONObject(chunk.args.toString()),
            thoughtSignature = chunk.thoughtSignature ?: slot?.part?.thoughtSignature)
        if (slot == null) segments.add(Segment.Tool(part)) else slot.part = part
        return id
    }

    @Synchronized fun parts(): List<AgentContentPart> = segments.mapNotNull { segment -> when (segment) {
        is Segment.Text -> segment.text.toString().takeIf { it.isNotEmpty() }?.let(AgentContentPart::Text)
        is Segment.Tool -> segment.part.takeIf { it.name.isNotBlank() }
        Segment.Thinking -> null
    } }

    @Synchronized fun resetAttempt() {
        segments.clear()
        completed.clear()
        signatures.clear()
        thinkingSeen = false
        monolithicText = null
        reasoning.setLength(0)
        opaqueReasoning = null
        finishReason = null
    }
}
