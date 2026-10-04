package com.openminis.app.agent

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import org.json.JSONObject

/** Conservative cache eligibility; never replay stale/edited or tool-unbalanced history. */
internal object CachedCompaction {
    class UnsafeSummary(message: String) : IllegalStateException(message)

    /** Summarizers have no dispatcher; tool requests and incomplete output fail closed. */
    fun requireTextOnly(chunk: com.openminis.app.data.model.LLMStreamChunk) {
        when (chunk) {
            is com.openminis.app.data.model.LLMStreamChunk.ToolUseStart,
            is com.openminis.app.data.model.LLMStreamChunk.ToolInputDelta,
            is com.openminis.app.data.model.LLMStreamChunk.ToolCallComplete -> throw UnsafeSummary("Compaction requested a tool; no tool was executed")
            is com.openminis.app.data.model.LLMStreamChunk.Finished -> if (chunk.stopReason in listOf("length", "max_tokens", "max_output_tokens"))
                throw UnsafeSummary("Compaction output was truncated")
            else -> Unit
        }
    }

    fun snapshot(messages: List<LLMMessage>): List<LLMMessage> = messages.map { message ->
        message.copy(contentParts = message.contentParts.map { part ->
            if (part is AgentContentPart.ToolUse) part.copy(input = JSONObject(part.input.toString())) else part
        })
    }

    fun prefix(warm: List<LLMMessage>, current: List<LLMMessage>, region: List<LLMMessage>): List<LLMMessage>? {
        if (warm.isEmpty() || region.isEmpty()) return null
        // Use a contiguous actual conversation region, never the flattened fallback transcript.
        val start = current.indices.firstOrNull { index ->
            index + region.size <= current.size && region.indices.all { same(current[index + it], region[it]) }
        } ?: return null
        val prefix = current.take(start + region.size)
        if ((0 until minOf(warm.size, prefix.size)).any { !same(warm[it], prefix[it]) }) return null
        val pending = mutableSetOf<String>()
        for (message in prefix) for (part in message.contentParts) when (part) {
            is AgentContentPart.ToolUse -> if (!pending.add(part.id)) return null
            is AgentContentPart.ToolResult -> if (!pending.remove(part.id)) return null
            else -> Unit
        }
        return prefix.takeIf { pending.isEmpty() }
    }

    private fun same(a: LLMMessage, b: LLMMessage): Boolean {
        if (a.role != b.role || a.reasoningContent != b.reasoningContent || a.audioParts != b.audioParts) return false
        if (a.imageParts.size != b.imageParts.size || a.imageParts.indices.any { i ->
            val x = a.imageParts[i]; val y = b.imageParts[i]
            x.mimeType != y.mimeType || x.linuxPath != y.linuxPath || x.noVisionPlaceholder != y.noVisionPlaceholder || !x.data.contentEquals(y.data)
        }) return false
        if (a.contentParts.size != b.contentParts.size) return false
        if (a.contentParts.isEmpty()) return a.content == b.content
        return a.contentParts.indices.all { i ->
            val x = a.contentParts[i]; val y = b.contentParts[i]
            if (x is AgentContentPart.ToolUse && y is AgentContentPart.ToolUse)
                x.copy(input = y.input) == y && x.input.toString() == y.input.toString()
            else x == y
        }
    }
}
