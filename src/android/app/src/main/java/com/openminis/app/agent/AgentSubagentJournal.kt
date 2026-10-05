package com.openminis.app.agent

import com.openminis.app.agent.jobs.AgentJobRegistry
import com.openminis.app.data.db.MessageEntity
import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.repository.ChatRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray

/** Serializes initial tool-result commits with background child finalization on the original branch. */
internal class AgentSubagentJournal(private val repository: ChatRepository,
    private val history: MutableList<LLMMessage>, private val currentSession: () -> String) {
    private data class Key(val session: String, val tool: String)
    private data class Final(val content: String, val success: Boolean)
    private val lock = Mutex()
    private val completed = mutableMapOf<Key, Final>()
    private val retiredJobs = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    fun isRetired(job: String): Boolean = job in retiredJobs

    suspend fun <T> rewind(block: suspend (retire: (String, Set<String>, Set<String>) -> Unit) -> T): T = lock.withLock {
        block { session, jobs, tools ->
            retiredJobs.addAll(jobs)
            completed.keys.removeAll { it.session == session && it.tool in tools }
        }
    }

    suspend fun record(session: String, tool: String, content: String, success: Boolean, expectsResultRow: Boolean = true) {
        // Nested codemode calls have no protocol tool_result row; their callback/trace is the carrier.
        if (expectsResultRow) persist(session, tool, content, success, null)
    }

    private suspend fun persist(session: String, tool: String, content: String, success: Boolean, expected: Final?) {
        withContext(NonCancellable + Dispatchers.IO) {
            lock.withLock {
                val job = runCatching { org.json.JSONObject(content).optString("job_id") }.getOrDefault("")
                if (job in retiredJobs) return@withLock
                val key = Key(session, tool)
                if (expected != null && completed[key] !== expected) return@withLock
                val final = expected ?: Final(content, success)
                completed[key] = final
                var stored = false
                for (row in repository.dao.loadMessages(session)) {
                    if (row.role != "user" || !row.partsJson.contains(tool)) continue
                    val parts = runCatching { JSONArray(row.partsJson) }.getOrNull() ?: continue
                    var changed = false
                    for (i in 0 until parts.length()) {
                        val item = parts.optJSONObject(i) ?: continue
                        if (item.optString("type") != "toolResult") continue
                        val value = item.optJSONObject("value") ?: continue
                        if (value.optString("toolUseId") != tool) continue
                        value.put("output", content).put("success", success)
                        value.optJSONObject("snapshot")?.let { snapshot ->
                            if (snapshot.optString("type") == "text") snapshot.put("text", content)
                        }
                        changed = true
                    }
                    if (changed) { repository.updateMessageParts(row.id, parts.toString()); stored = true; break }
                }
                withContext(NonCancellable + Dispatchers.Main) {
                    if (currentSession() == session && !AgentJobRegistry.isDelegationMuted(session)) {
                        for (i in history.indices) {
                            val message = history[i]
                            val parts = rewrite(session, message.contentParts)
                            if (parts != message.contentParts) history[i] = message.copy(contentParts = parts)
                        }
                    }
                }
                if (stored) completed.remove(key)
            }
        }
    }

    suspend fun commit(session: String, journal: AgentTurnJournal, parts: List<AgentContentPart>,
        publish: (List<AgentContentPart>, MessageEntity?) -> Unit) {
        withContext(NonCancellable + Dispatchers.IO) {
            lock.withLock {
                val resolved = rewrite(session, parts)
                val row = journal.toolResults(resolved)
                withContext(NonCancellable + Dispatchers.Main) {
                    publish(if (AgentJobRegistry.isDelegationMuted(session)) parts else resolved, row)
                }
                if (row != null) resolved.filterIsInstance<AgentContentPart.ToolResult>().forEach { completed.remove(Key(session, it.id)) }
            }
        }
    }

    suspend fun flush(session: String) = withContext(NonCancellable + Dispatchers.IO) {
        val pending = lock.withLock { completed.filterKeys { it.session == session }.toMap() }
        for ((key, final) in pending) persist(key.session, key.tool, final.content, final.success, final)
    }

    private fun rewrite(session: String, parts: List<AgentContentPart>): List<AgentContentPart> = parts.map { part ->
        if (part !is AgentContentPart.ToolResult) part else completed[Key(session, part.id)]?.let {
            part.copy(content = it.content, isError = !it.success)
        } ?: part
    }
}
