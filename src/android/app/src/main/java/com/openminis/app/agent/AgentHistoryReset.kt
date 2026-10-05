package com.openminis.app.agent

import com.openminis.app.agent.jobs.AgentJobRegistry
import com.openminis.app.agent.jobs.AgentJobTarget
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.repository.ChatRepository
import kotlinx.coroutines.*

/** Clear-history is a branch-bound mutation ordered after runs and compaction settlement. */
internal class AgentHistoryReset(
    private val repository: ChatRepository,
    private val history: MutableList<LLMMessage>,
    private val coordinator: AgentRunCoordinator,
    private val subagents: AgentSubagentJournal,
    private val currentSession: () -> String,
) {
    fun launch(scope: CoroutineScope, session: String, compaction: Job?, failed: suspend (Exception) -> Unit,
        publish: () -> Unit): Job = coordinator.mutate(scope, failed) {
        withContext(NonCancellable) {
            compaction?.let { if (it.isActive) it.cancel(); it.join() }
        }
        currentCoroutineContext().ensureActive()
        checkBranch(session)
        val jobs = AgentJobRegistry.list().filter { job ->
            (job.target as? AgentJobTarget.ChildOfCurrent)?.parentSessionId == session
        }
        subagents.reset(session, jobs.mapTo(mutableSetOf()) { it.id }) {
            withContext(NonCancellable + Dispatchers.Main) {
                checkBranch(session)
                AgentJobRegistry.muteDelegationResults(session)
                AgentJobRegistry.dropQueuedDelegations(session, "history-cleared")
                jobs.forEach { job ->
                    AgentJobRegistry.setThen(job.id, com.openminis.app.agent.jobs.AgentJobThen.None)
                    if (job.isActive) AgentJobRegistry.cancel(job.id, "history-cleared")
                }
            }
            withContext(NonCancellable + Dispatchers.IO) {
                checkBranch(session)
                repository.dao.clearRuntimeHistory(session, System.currentTimeMillis())
            }
            withContext(NonCancellable + Dispatchers.Main) {
                checkBranch(session)
                history.clear()
                publish()
            }
        }
        currentCoroutineContext().ensureActive()
    }

    private fun checkBranch(session: String) {
        if (currentSession() != session) throw CancellationException("history reset branch changed")
    }
}
