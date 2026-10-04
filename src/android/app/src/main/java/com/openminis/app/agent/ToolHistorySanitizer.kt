package com.openminis.app.agent

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.logging.AppLogger
import com.openminis.app.provider.ToolPairing

/** Final model-input pairing repair. The durable journal is never modified. */
internal object ToolHistorySanitizer {
    fun repair(history: List<LLMMessage>, sessionId: String): List<LLMMessage> {
        val uses = history.flatMap { it.contentParts }.filterIsInstance<AgentContentPart.ToolUse>()
            .map { ToolPairing.key(it.id) }.toSet()
        val results = history.flatMap { it.contentParts }.filterIsInstance<AgentContentPart.ToolResult>()
            .map { ToolPairing.key(it.id) }.toSet()
        val orphanedResults = results - uses
        val orphanedUses = (uses - results).toMutableSet()
        // The final assistant's unanswered calls are in flight, not interrupted.
        history.lastOrNull()?.takeIf { it.role == LLMMessage.Role.ASSISTANT }?.contentParts
            ?.filterIsInstance<AgentContentPart.ToolUse>()?.forEach { orphanedUses.remove(ToolPairing.key(it.id)) }
        if (orphanedResults.isEmpty() && orphanedUses.isEmpty()) return history
        val roles = history.flatMap { message -> message.contentParts.mapNotNull { part ->
            val id = when (part) {
                is AgentContentPart.ToolUse -> part.id.takeIf { ToolPairing.key(it) in orphanedUses }
                is AgentContentPart.ToolResult -> part.id.takeIf { ToolPairing.key(it) in orphanedResults }
                else -> null
            }
            id?.let { "${message.role}:$it" }
        } }.joinToString(",")
        AppLogger.warning("ToolHistorySanitizer", "[CompactDiag] orphan tool parts in OUTGOING history — repairing. " +
            "session=$sessionId orphanedOutputs=${orphanedResults.size} [${orphanedResults.sorted().joinToString(",")}] " +
            "orphanedCalls=${orphanedUses.size} [${orphanedUses.sorted().joinToString(",")}] " +
            "orphanRoles=[$roles] historyCount=${history.size}")
        return buildList {
            for (message in history) {
                val parts = message.contentParts.filter { part ->
                    part !is AgentContentPart.ToolResult || ToolPairing.key(part.id) !in orphanedResults
                }
                if (parts.isEmpty() && message.contentParts.isNotEmpty()) continue
                add(if (parts.size == message.contentParts.size) message else message.copy(contentParts = parts))
                if (message.role != LLMMessage.Role.ASSISTANT) continue
                val pending = parts.filterIsInstance<AgentContentPart.ToolUse>().filter { ToolPairing.key(it.id) in orphanedUses }
                if (pending.isNotEmpty()) add(LLMMessage(LLMMessage.Role.USER, "", contentParts = pending.map {
                    AgentContentPart.ToolResult(it.id, it.name, "Tool execution was interrupted by an unexpected error.", isError = true)
                }))
            }
        }
    }
}
