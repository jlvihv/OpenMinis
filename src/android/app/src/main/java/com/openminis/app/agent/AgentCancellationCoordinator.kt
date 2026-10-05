package com.openminis.app.agent

import java.util.concurrent.atomic.AtomicReference

internal class AgentCancellationCoordinator {
    private val active = AtomicReference<AgentTurnJournal?>()

    @Synchronized fun begin(writer: AgentJournalWriter, content: AgentTurnContent, bubbleId: String, marker: String): AgentTurnJournal {
        val turn = AgentTurnJournal(writer, content, bubbleId, marker)
        val previous = active.getAndSet(turn)
        if (previous?.stopRequested == true) turn.requestStop()
        return turn
    }

    @Synchronized fun requestStop(): AgentTurnJournal.StopView? = active.get()?.requestStop()

    suspend fun finish(): AgentTurnJournal.Commit? = active.getAndSet(null)?.finishStop()

    suspend fun settle(session: String, history: MutableList<com.openminis.app.data.model.LLMMessage>,
        currentSession: () -> String, subagents: AgentSubagentJournal,
        publish: (AgentTurnJournal.Commit) -> Unit) = kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
        val committed = finish()
        try { subagents.flush(committed?.sessionId ?: session) }
        catch (failure: Exception) {
            com.openminis.app.logging.AppLogger.warning("AgentCancellationCoordinator", "Result reconciliation failed: ${failure.javaClass.simpleName}")
        }
        if (committed != null) kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
            if (currentSession() != committed.sessionId) return@withContext
            committed.messages.forEach { message ->
                if (history.none { it.dbMessageId != null && it.dbMessageId == message.dbMessageId }) {
                    val pending = if (message.role == com.openminis.app.data.model.LLMMessage.Role.ASSISTANT)
                        history.indexOfLast { it.role == message.role && it.dbMessageId == null && it.contentParts == committed.acceptedParts }
                    else -1
                    if (pending >= 0) history[pending] = message else history.add(message)
                }
            }
            publish(committed)
        }
    }
}
