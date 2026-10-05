package com.openminis.app.agent

import com.openminis.app.data.repository.ChatRepository
import kotlinx.coroutines.*

/** Branch-bound error sticker writes; terminal failures settle before their run releases successors. */
internal class AgentErrorJournal(private val repository: ChatRepository, private val currentSession: () -> String) {
    suspend fun terminal(session: String, error: String) = withContext(NonCancellable + Dispatchers.IO) {
        checkBranch(session)
        repository.persistTurnError(session, error)
        checkBranch(session)
    }

    suspend fun clear(session: String, rowIds: Set<String>) = withContext(NonCancellable + Dispatchers.IO) {
        checkBranch(session)
        if (rowIds.isEmpty()) repository.updateLastAssistantError(session, null)
        else repository.dao.clearRuntimeErrors(session, rowIds)
        checkBranch(session)
    }

    private fun checkBranch(session: String) {
        if (currentSession() != session) throw CancellationException("error journal branch changed")
    }
}
