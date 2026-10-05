package com.openminis.app.agent

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.RequestUsageRecord
import com.openminis.app.data.repository.ChatRepository
import kotlinx.coroutines.*

/** Retry settles the exact captured response; paid usage is retained as a hidden receipt. */
internal class AgentRetryJournal(private val repository: ChatRepository, private val session: String,
    private val history: MutableList<LLMMessage>, private val currentSession: () -> String) {
    suspend fun prepare() {
        currentCoroutineContext().ensureActive()
        withContext(NonCancellable + Dispatchers.Main) {
            checkBranch()
            val tail = history.lastOrNull()?.takeIf { it.role == LLMMessage.Role.ASSISTANT }
            withContext(Dispatchers.IO) {
                tail?.dbMessageId?.let { id -> repository.dao.retireRetryAssistant(session, id,
                    RequestUsageRecord.parts(RequestUsageRecord.Purpose.CONVERSATION)) }
                repository.updateLastAssistantError(session, null)
            }
            checkBranch()
            // Completion patches can arrive during IO; preserve their updated tool results.
            if (tail != null && history.lastOrNull() === tail) history.removeAt(history.lastIndex)
            val calls = history.flatMap { it.contentParts.filterIsInstance<AgentContentPart.ToolUse>().map { part -> part.id } }.toSet()
            for (i in history.indices.reversed()) {
                val message = history[i]
                if (message.role != LLMMessage.Role.USER) continue
                val parts = message.contentParts.filter { it !is AgentContentPart.ToolResult || it.id in calls }
                if (parts.isEmpty() && message.contentParts.isNotEmpty()) history.removeAt(i)
                else if (parts.size != message.contentParts.size) history[i] = message.copy(contentParts = parts)
            }
        }
        currentCoroutineContext().ensureActive()
    }
    private fun checkBranch() {
        if (currentSession() != session) throw CancellationException("retry branch changed")
    }
}
