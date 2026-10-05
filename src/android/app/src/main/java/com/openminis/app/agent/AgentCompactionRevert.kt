package com.openminis.app.agent

import com.openminis.app.data.db.CompactMarkerEntity
import com.openminis.app.data.repository.ChatRepository
import kotlinx.coroutines.*

/** Owned revert admission, exact marker transaction and reload before any successor dispatches. */
internal class AgentCompactionRevert(private val repository: ChatRepository,
    private val coordinator: AgentRunCoordinator, private val subagents: AgentSubagentJournal,
    private val currentSession: () -> String) {
    fun launch(scope: CoroutineScope, session: String, expected: CompactMarkerEntity,
        failed: suspend (Exception) -> Unit, publish: (CompactMarkerEntity?) -> Unit,
        reload: suspend () -> Unit): Job = coordinator.mutate(scope, failed) {
        currentCoroutineContext().ensureActive()
        checkBranch(session)
        withContext(NonCancellable + Dispatchers.IO) {
            subagents.withHistoryMutation {
                checkBranch(session)
                val next = repository.dao.revertLatestCompactMarker(session, expected)
                withContext(NonCancellable + Dispatchers.Main) {
                    checkBranch(session)
                    publish(next)
                    reload()
                    checkBranch(session)
                }
            }
        }
        currentCoroutineContext().ensureActive()
    }
    private fun checkBranch(session: String) {
        if (currentSession() != session) throw CancellationException("compaction revert branch changed")
    }
}
