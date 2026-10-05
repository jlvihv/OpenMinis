package com.openminis.app.agent

import com.openminis.app.data.db.*
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.RequestUsageRecord
import com.openminis.app.data.repository.ChatRepository
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject

/** Exact-identity, atomic rewinds for explicit user retry/rerun commands. No ordinal fallback. */
internal class AgentRewindJournal(private val repository: ChatRepository, private val session: String,
    private val history: MutableList<LLMMessage>, private val currentSession: () -> String,
    private val decode: (MessageEntity) -> LLMMessage?, private val subagents: AgentSubagentJournal) {
    sealed interface Target {
        data class User(val rowIds: Set<String>, val keepAnchor: Boolean = true) : Target
        data class Tool(val id: String) : Target
    }
    fun delete(scope: CoroutineScope, coordinator: AgentRunCoordinator, target: Target.User,
        failed: (Exception) -> Unit, published: (List<MessageEntity>, CompactMarkerEntity?) -> Unit): Job =
        coordinator.mutate(scope, failed) {
            checkBranch()
            prepare(target, published = published)
        }

    suspend fun prepare(target: Target, replacement: AgentQueuedUserInput? = null,
        accepted: (MessageEntity) -> Unit = {}, published: (List<MessageEntity>, CompactMarkerEntity?) -> Unit) {
        require(replacement == null || target is Target.User && !target.keepAnchor) { "Replacement requires a user edit target" }
        currentCoroutineContext().ensureActive()
        checkBranch()
        val rows = repository.loadMessages(session)
        val matches = rows.mapIndexedNotNull { index, row -> when (target) {
            is Target.User -> if (row.role == "user" && row.id in target.rowIds) index to -1 else null
            is Target.Tool -> if (row.role != "assistant") null else {
                val parts = runCatching { JSONArray(row.partsJson) }.getOrNull()
                (0 until (parts?.length() ?: 0)).firstOrNull { i ->
                    val part = parts?.optJSONObject(i)
                    part?.optString("type") == "toolUse" && part.optJSONObject("value")?.optString("toolUseId") == target.id
                }?.let { index to it }
            }
        } }
        require(matches.size == 1) { "The selected rewind anchor is missing or ambiguous; no history was changed" }
        val (index, partIndex) = matches.single()
        val anchor = rows[index]
        val keep = rows.take(index + if (target is Target.User && target.keepAnchor) 1 else 0).mapTo(mutableSetOf()) { it.id }
        val changedParts = mutableMapOf<String, String>()
        if (target is Target.Tool && partIndex > 0) {
            val source = JSONArray(anchor.partsJson)
            val prefix = JSONArray()
            for (i in 0 until partIndex) prefix.put(source.get(i))
            keep.add(anchor.id)
            changedParts[anchor.id] = prefix.toString()
            // Parallel calls share an assistant row. Preserve already-completed results of retained calls.
            val calls = (0 until prefix.length()).mapNotNull { i -> prefix.optJSONObject(i)
                ?.takeIf { it.optString("type") == "toolUse" }?.optJSONObject("value")?.optString("toolUseId") }.toSet()
            for (row in rows.drop(index + 1)) {
                if (row.role != "user") continue
                val parts = runCatching { JSONArray(row.partsJson) }.getOrNull() ?: continue
                val results = JSONArray()
                for (i in 0 until parts.length()) {
                    val part = parts.optJSONObject(i) ?: continue
                    if (part.optString("type") == "toolResult" && part.optJSONObject("value")?.optString("toolUseId") in calls) results.put(part)
                }
                if (results.length() > 0) { keep.add(row.id); changedParts[row.id] = results.toString() }
            }
        }
        val stable = keep - changedParts.keys
        val invalidMarkers = repository.dao.listCompactMarkers(session).filter { marker ->
            if (marker.version >= 2) marker.lastCompactedMessageId !in stable
            else listOfNotNull(marker.firstKeptMessageId, marker.lastCompactedMessageId, marker.boundaryMessageId)
                .let { ids -> ids.isEmpty() || ids.any { it !in stable } }
        }.map { it.id }
        val receipts = rows.filter { (it.id !in keep || it.id in changedParts) && it.tokenUsage != null }.associate { row ->
            val json = JSONObject(row.tokenUsage!!).put("contextEligible", false)
            row.id to json.toString()
        }
        currentCoroutineContext().ensureActive()
        withContext(NonCancellable + Dispatchers.IO) {
            subagents.rewind { retire ->
                checkBranch()
                val prepared = replacement?.let { repository.rewindReplacement(session, it.partsJson) }
                val inserted = repository.dao.applyRuntimeRewind(session, rows, keep, changedParts, receipts,
                    RequestUsageRecord.parts(RequestUsageRecord.Purpose.CONVERSATION), invalidMarkers,
                    prepared?.first, prepared?.second, repository.rewindPreview(rows, keep, changedParts))
                val remaining = repository.loadMessages(session)
                val retainedCalls = remaining.filter { it.role == "assistant" }.flatMap { row ->
                    val parts = runCatching { JSONArray(row.partsJson) }.getOrDefault(JSONArray())
                    (0 until parts.length()).mapNotNull { i -> parts.optJSONObject(i)
                        ?.takeIf { it.optString("type") == "toolUse" }?.optJSONObject("value")?.optString("toolUseId") }
                }.toSet()
                withContext(NonCancellable + Dispatchers.Main) {
                    val registry = com.openminis.app.agent.jobs.AgentJobRegistry
                    val jobs = registry.list().filter { job ->
                        val target = job.target as? com.openminis.app.agent.jobs.AgentJobTarget.ChildOfCurrent
                        target?.parentSessionId == session && target.parentToolUseId?.let { tool ->
                            tool !in retainedCalls && retainedCalls.none { tool.startsWith("$it/") }
                        } == true
                    }
                    retire(session, jobs.mapTo(mutableSetOf()) { it.id }, jobs.mapNotNull { job ->
                        (job.target as? com.openminis.app.agent.jobs.AgentJobTarget.ChildOfCurrent)?.parentToolUseId
                    }.toSet())
                    registry.dropRewoundDelegations(session, retainedCalls)
                    jobs.forEach { job ->
                        registry.setThen(job.id, com.openminis.app.agent.jobs.AgentJobThen.None)
                        if (job.isActive) registry.cancel(job.id, "parent history rewound")
                    }
                }
                checkBranch()
                val rebuilt = remaining.mapNotNull { row ->
                    if (inserted != null && row.id == inserted.id) replacement!!.message(row.id) else decode(row)
                }
                val marker = repository.dao.latestCompactMarker(session)
                withContext(NonCancellable + Dispatchers.Main) {
                    checkBranch()
                    history.clear()
                    history.addAll(rebuilt)
                    published(remaining, marker)
                    if (inserted != null) accepted(inserted)
                }
            }
        }
        currentCoroutineContext().ensureActive()
    }
    private fun checkBranch() {
        if (currentSession() != session) throw CancellationException("rewind branch changed")
    }
}
