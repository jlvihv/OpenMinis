package com.openminis.app.agent

import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

internal class AgentLoopEngine(private val turnLimit: Int) {
    data class Turn(val index: Int, val remaining: Int) {
        val isLast: Boolean get() = remaining == 1
    }
    enum class StopReason { COMPLETED, CONTEXT, INTERRUPTED, DELEGATION_STOPPED }
    sealed interface Action {
        data object Next : Action
        data class Stop(val reason: StopReason) : Action
    }
    sealed interface Outcome {
        data class Stopped(val turns: Int, val reason: StopReason) : Outcome
        data class LimitReached(val turns: Int) : Outcome
    }

    suspend fun run(step: suspend (Turn) -> Action): Outcome {
        var turns = 0
        for (index in 0 until turnLimit) {
            coroutineContext.ensureActive()
            val action = step(Turn(index, turnLimit - index))
            turns++
            if (action is Action.Stop) return Outcome.Stopped(turns, action.reason)
        }
        return Outcome.LimitReached(turns)
    }
}
