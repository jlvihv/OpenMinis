package com.openminis.app.agent

import com.openminis.app.agent.jobs.AgentJobRegistry
import com.openminis.app.sandbox.ExecutionCoordinator
import com.openminis.app.service.SessionActivityTracker

/** Stops only the claimed Job; display cleanup is returned to the host. */
internal class AgentStopRuntime(private val runs: AgentRunCoordinator,
    private val cancellation: AgentCancellationCoordinator) {
    enum class Reason { USER, MODEL_RETRY_SWITCH }
    sealed interface Result {
        data object Idle : Result
        data object Mutation : Result
        data class Run(val view: AgentTurnJournal.StopView?) : Result
    }
    fun stop(session: String, draftAlias: String?, reason: Reason = Reason.USER): Result {
        val job = runs.job?.takeUnless { it.isCompleted } ?: return Result.Idle
        if (runs.mutating) { job.cancel(); return Result.Mutation }
        val view = if (reason == Reason.USER) {
            AgentJobRegistry.muteDelegationResults(session)
            AgentJobRegistry.dropQueuedDelegations(session, "user-stopped")
            AgentJobRegistry.cancelAll(session, "user-stopped")
            cancellation.requestStop()
        } else null
        job.cancel()
        val owners = listOfNotNull(session, draftAlias).distinct()
        owners.forEach { owner ->
            SessionActivityTracker.setInactive(owner)
            if (reason == Reason.USER) ExecutionCoordinator.stopCurrentCommand(owner)
        }
        return Result.Run(view)
    }
}
