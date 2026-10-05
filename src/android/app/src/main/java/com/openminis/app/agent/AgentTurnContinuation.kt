package com.openminis.app.agent

import com.openminis.app.agent.jobs.HelperRunner
import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage

/** Run-owned convergence, empty-turn recovery and scheduled/helper continuation decisions. */
internal class AgentTurnContinuation(
    private val history: MutableList<LLMMessage>,
    private val writer: AgentJournalWriter,
) {
    enum class Hint { EMPTY_AFTER_REMINDER, EMPTY_CONTEXT_LARGE, EMPTY_GENERIC }
    data class Decision(val action: AgentLoopEngine.Action, val hint: Hint? = null)
    private var emptyReminderInjected = false
    private var scheduledResumeOwed = false

    fun promptInserted(scheduled: Boolean) { scheduledResumeOwed = scheduled }

    suspend fun converged(
        visible: Boolean,
        finishReason: String?,
        window: Int?,
        usedContext: Int,
        userWaiting: Boolean,
        scheduledNudge: String,
        helper: Boolean,
        turn: Int,
        turnCap: Int,
        pendingSteers: Int,
    ): Decision {
        if (finishReason == null && visible) return Decision(AgentLoopEngine.Action.Stop(AgentLoopEngine.StopReason.INTERRUPTED))
        val empty = EmptyTurnRecovery.shouldClassifyAsEmptyTurn(visible, false, finishReason)
        var hint: Hint? = null
        if (empty) {
            when (val plan = EmptyTurnRecovery.plan(history, emptyReminderInjected)) {
                is EmptyTurnRecovery.Plan.NudgeExistingPart -> {
                    emptyReminderInjected = true
                    history.removeAt(history.lastIndex)
                    val tail = history[plan.tailIndex]
                    val parts = tail.contentParts.toMutableList()
                    parts[plan.partIndex] = when (val part = parts[plan.partIndex]) {
                        is AgentContentPart.ToolResult -> part.copy(content = part.content + EmptyTurnRecovery.REMINDER)
                        is AgentContentPart.Text -> AgentContentPart.Text(part.text + EmptyTurnRecovery.REMINDER)
                        else -> part
                    }
                    history[plan.tailIndex] = tail.copy(contentParts = parts)
                    return Decision(AgentLoopEngine.Action.Next)
                }
                EmptyTurnRecovery.Plan.AppendStandalone -> {
                    emptyReminderInjected = true
                    history.removeAt(history.lastIndex)
                    history.add(LLMMessage(LLMMessage.Role.USER, EmptyTurnRecovery.REMINDER,
                        contentParts = listOf(AgentContentPart.Text(EmptyTurnRecovery.REMINDER))))
                    return Decision(AgentLoopEngine.Action.Next)
                }
                EmptyTurnRecovery.Plan.GiveUp -> Unit
            }
            hint = when {
                emptyReminderInjected -> Hint.EMPTY_AFTER_REMINDER
                window != null && window > 0 && usedContext > 0 && usedContext.toDouble() / window > 0.70 -> Hint.EMPTY_CONTEXT_LARGE
                else -> Hint.EMPTY_GENERIC
            }
        }
        if (scheduledResumeOwed && !empty && (finishReason == "stop" || finishReason == "end_turn") && !userWaiting) {
            scheduledResumeOwed = false
            history.add(LLMMessage(LLMMessage.Role.USER, scheduledNudge, contentParts = listOf(AgentContentPart.Text(scheduledNudge))))
            writer.reminder(scheduledNudge)
            return Decision(AgentLoopEngine.Action.Next)
        }
        if (HelperRunner.shouldContinueForSteer(helper, empty, turn, turnCap, pendingSteers)) return Decision(AgentLoopEngine.Action.Next, hint)
        return Decision(AgentLoopEngine.Action.Stop(AgentLoopEngine.StopReason.COMPLETED), hint)
    }
}
