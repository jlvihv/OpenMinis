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
}
