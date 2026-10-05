package com.openminis.app.agent

import com.openminis.app.data.db.*
import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.repository.ChatRepository
import kotlinx.coroutines.*

/** Coherent, branch-bound restore: decode locally, validate/heal atomically, then publish owned history. */
internal class AgentHistoryRestore(private val repository: ChatRepository,
    private val history: MutableList<LLMMessage>, private val currentSession: () -> String) {
    data class Link(val id: String, val sourceIds: Set<String>)
    enum class Interrupted { RESULTS, CALLS, CONTINUE, USER }
    data class Loaded<T>(val messages: List<MessageEntity>, val ordered: T, val llmHistory: List<LLMMessage>,
        val marker: CompactMarkerEntity?, val dividerIndex: Int?, val usages: List<String>,
        val interrupted: Interrupted?, val loadMs: Long, val transformMs: Long)

    suspend fun <T> restore(session: String, decode: (MessageEntity) -> LLMMessage?,
        project: (List<MessageEntity>) -> T, links: (T) -> List<Link>): Loaded<T> {
        checkBranch(session)
        val loaded = withContext(Dispatchers.IO) {
            val started = System.currentTimeMillis()
            val snapshot = repository.dao.runtimeHistorySnapshot(session)
            val readAt = System.currentTimeMillis()
            val ordered = project(snapshot.messages)
            val transformedAt = System.currentTimeMillis()
            val replay = snapshot.messages.mapNotNull { row ->
                if (ChatRepository.isEmptyAssistantCarrier(row.role, row.partsJson)) null else decode(row)
            }
            checkBranch(session)
            val resolution = resolve(snapshot, replay, links(ordered))
            currentCoroutineContext().ensureActive()
            withContext(NonCancellable) {
                checkBranch(session)
                repository.dao.validateRuntimeRestore(session, snapshot, resolution.second)
            }
            Loaded(snapshot.messages, ordered, replay, resolution.second ?: snapshot.marker,
                resolution.first, snapshot.usages, interrupted(replay), readAt - started, transformedAt - readAt)
        }
        currentCoroutineContext().ensureActive()
        withContext(Dispatchers.Main) {
            checkBranch(session)
            history.clear()
            history.addAll(loaded.llmHistory)
        }
        return loaded
    }

    private fun resolve(snapshot: RuntimeHistorySnapshot, replay: List<LLMMessage>, links: List<Link>): Pair<Int?, CompactMarkerEntity?> {
        val marker = snapshot.marker ?: return null to null
        val positions = snapshot.messages.mapIndexed { index, row -> row.id to index }.toMap()
        val replayIds = replay.filterNot { it.isRuntimeContext }.mapNotNull { it.dbMessageId }.toSet()
        fun after(id: String): Int? {
            val raw = positions[id] ?: return null
            if (replay.none { it.dbMessageId == id }) return null
            val direct = links.indexOfLast { it.id == id || id in it.sourceIds }
            if (direct >= 0) return direct + 1
            // Hidden runtime rows can be valid anchors without their own display bubble.
            return links.indexOfLast { link -> (link.sourceIds + link.id).any { (positions[it] ?: Int.MAX_VALUE) <= raw } } + 1
        }
        val first = if (marker.version >= 2) null else marker.firstKeptMessageId?.takeIf { it.isNotBlank() }
            ?: marker.boundaryMessageId?.takeIf { it.isNotBlank() }
        val index = if (first != null) {
            val direct = links.indexOfFirst { it.id == first || first in it.sourceIds }
            val raw = positions[first]
            if (direct >= 0) direct else if (raw != null && replay.any { it.dbMessageId == first })
                links.indexOfFirst { link -> (link.sourceIds + link.id).any { (positions[it] ?: -1) >= raw } }
                    .takeIf { it >= 0 } ?: links.size
            else null
        } else marker.lastCompactedMessageId?.takeIf { it.isNotBlank() }?.let(::after)
        if (index != null) return index to null
        // A timestamp fallback must refer to actual replay content, never hidden receipts or an arbitrary last row.
        val anchor = snapshot.messages.lastOrNull { it.createdAt < marker.createdAt && it.id in replayIds }
            ?: return 0 to null
        val healed = marker.copy(firstKeptSortOrder = Int.MAX_VALUE, boundaryMessageId = null,
            firstKeptMessageId = null, lastCompactedMessageId = anchor.id, uiBoundarySortOrder = null, version = 2)
        return (after(anchor.id) ?: 0) to healed
    }

    private fun interrupted(replay: List<LLMMessage>): Interrupted? {
        val last = replay.lastOrNull { !it.isRuntimeContext } ?: return null
        return when (last.role) {
            LLMMessage.Role.ASSISTANT -> if (last.contentParts.any { it is AgentContentPart.ToolUse }) Interrupted.CALLS else null
            LLMMessage.Role.USER -> when {
                last.contentParts.isNotEmpty() && last.contentParts.all { it is AgentContentPart.ToolResult } -> Interrupted.RESULTS
                last.contentParts.size == 1 && (last.contentParts.first() as? AgentContentPart.Text)?.text
                    ?.contains("The user stopped the previous response") == true -> Interrupted.CONTINUE
                else -> Interrupted.USER
            }
            else -> null
        }
    }

    private fun checkBranch(session: String) {
        if (currentSession() != session) throw CancellationException("history restore branch changed")
    }
}
