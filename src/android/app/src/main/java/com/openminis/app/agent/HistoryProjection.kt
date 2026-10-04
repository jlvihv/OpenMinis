package com.openminis.app.agent

import com.openminis.app.data.db.CompactMarkerEntity
import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage

/** Branch history -> model context. Never mutates durable rows or executes tools. */
internal class HistoryProjection {
    private val warmUpDrops = mutableMapOf<String, Int>()
    fun dropForMarker(id: String): Int? = warmUpDrops[id]

    data class Trim(val kept: List<LLMMessage>, val decided: Boolean)
    data class WalkBack(val priorIdx: Int?, val userTextTurnsFound: Int, val messageCount: Int, val stopReason: String)

    fun project(
        history: List<LLMMessage>, summary: String?, marker: CompactMarkerEntity?, keepTurns: Int,
        trim: (warm: List<LLMMessage>, tail: List<LLMMessage>, summary: String) -> Trim,
    ): List<LLMMessage> {
        if (summary.isNullOrBlank() || marker == null) return history.toList()
        val wrapped = "<context-summary>\n" +
            "The following is a summary of the earlier conversation that was compacted to save context space.\n" +
            "Treat it as background context only. The user's most recent message (below or in the next turn) takes precedence — if it changes the task, the goal, or any numbers/scope, follow the new instruction and do not resume the old plan from this summary. Do not re-run discovery (scanning skills, re-reading files) unless the new instruction requires it.\n\n" +
            summary + "\n</context-summary>"
        if (marker.version < 2) return legacy(history, marker, wrapped)
        val anchor = marker.lastCompactedMessageId?.takeIf { it.isNotEmpty() }
            ?.let { id -> history.indexOfLast { it.dbMessageId == id } } ?: -1
        if (anchor < 0) return history.toList() // Unresolvable anchors must not erase context.
        val prior = walkBack(history, anchor, keepTurns, 100).priorIdx ?: (anchor + 1)
        val raw = if (prior <= anchor) history.subList(prior, anchor + 1) else emptyList()
        val dropped = raw.flatMap { it.contentParts }.filterIsInstance<AgentContentPart.ToolResult>()
            .filter { it.content.length > 1000 }.map { it.id }.toSet()
        val pruned = raw.mapNotNull { message ->
            if (message.contentParts.isEmpty()) message else {
                val parts = message.contentParts.filter { part -> when (part) {
                    is AgentContentPart.ToolUse -> part.id !in dropped
                    is AgentContentPart.ToolResult -> part.id !in dropped
                    else -> true
                } }
                if (parts.isEmpty()) null else message.copy(contentParts = parts)
            }
        }.dropWhile { it.role != LLMMessage.Role.USER }
        val tail = history.drop(anchor + 1)
        val warm = warmUpDrops[marker.id]?.let { pruned.drop(minOf(it, pruned.size)) }
            ?: trim(pruned, tail, wrapped).let { decision ->
                // Unknown budgets must not pin a speculative keep-all decision forever.
                if (decision.decided) warmUpDrops[marker.id] = pruned.size - decision.kept.size
                decision.kept
            }
        val firstUser = tail.indexOfFirst(JournalProjection::isConversationUser)
        if (firstUser < 0) return warm + tail + LLMMessage(LLMMessage.Role.USER, wrapped)
        val target = tail[firstUser]
        val firstText = target.contentParts.indexOfFirst { it is AgentContentPart.Text }
        val parts = when {
            target.contentParts.isEmpty() -> target.contentParts
            firstText < 0 -> listOf(AgentContentPart.Text(wrapped)) + target.contentParts
            else -> target.contentParts.mapIndexed { index, part ->
                if (index == firstText && part is AgentContentPart.Text) AgentContentPart.Text(wrapped + "\n\n" + part.text) else part
            }
        }
        // Keep both representations consistent: different serializers consume different ones.
        val injected = target.copy(content = wrapped + "\n\n" + target.content, contentParts = parts)
        return warm + tail.take(firstUser) + injected + tail.drop(firstUser + 1)
    }

    private fun legacy(history: List<LLMMessage>, marker: CompactMarkerEntity, summary: String): List<LLMMessage> {
        val head = LLMMessage(LLMMessage.Role.USER, summary)
        val kept = marker.firstKeptMessageId?.takeIf { it.isNotEmpty() }
            ?: marker.boundaryMessageId?.takeIf { it.isNotEmpty() }
        if (kept != null) {
            val index = history.indexOfFirst { it.dbMessageId == kept }
            return if (index < 0) history.toList() else listOf(head) + history.drop(index)
        }
        val index = marker.lastCompactedMessageId?.takeIf { it.isNotEmpty() }
            ?.let { id -> history.indexOfLast { it.dbMessageId == id } } ?: -1
        return listOf(head) + history.drop(index + 1)
    }

    fun walkBack(history: List<LLMMessage>, anchor: Int, maxTurns: Int, maxMessages: Int): WalkBack {
        if (anchor !in history.indices) return WalkBack(null, 0, 0, "invalidAnchor")
        var accepted: Int? = null; var turns = 0; var messages = 0
        for (index in anchor downTo 0) {
            val message = history[index]
            if (!JournalProjection.startsUserTurn(message)) continue
            val size = anchor - index + 1
            if (size > maxMessages) return WalkBack(accepted, turns, messages, "messageCapWouldExceed")
            accepted = index; messages = size
            if (message.content.isNotBlank() || message.contentParts.any { it is AgentContentPart.Text && it.text.isNotBlank() }) {
                turns++
                if (turns >= maxTurns) return WalkBack(accepted, turns, messages, "userTextTargetMet")
            }
        }
        return WalkBack(accepted, turns, messages, "reachedStart")
    }
}
