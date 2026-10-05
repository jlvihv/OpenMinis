package com.openminis.app.agent

import com.openminis.app.agent.jobs.HelperRunner
import com.openminis.app.agent.jobs.HelperWrapUpReason
import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.logging.AppLogger

/** Run-owned helper budget instructions and tool withdrawal. */
internal class AgentHelperTurnPolicy(private val history: MutableList<LLMMessage>, private val enabled: Boolean) {
    var toolsWithdrawn = false
        private set
    private var warningInjected = false

    fun beforeTurn(frame: AgentLoopEngine.Turn, wrapUpRequested: Boolean) {
        if (!enabled) return
        if (!toolsWithdrawn && (wrapUpRequested || frame.isLast)) {
            toolsWithdrawn = true
            val reason = if (wrapUpRequested) HelperWrapUpReason.BUDGET else HelperWrapUpReason.TURNS
            append(HelperRunner.wrapUpPrompt(reason))
            AppLogger.info("AgentHelperTurnPolicy", "wrap-up turn injected reason=$reason turn=${frame.index + 1}")
        }
        val remaining = frame.remaining - 1
        if (!toolsWithdrawn && !warningInjected && remaining in 1..HelperRunner.TURN_WARNING_LEAD) {
            warningInjected = true
            append(HelperRunner.turnBudgetWarning(remaining))
            AppLogger.info("AgentHelperTurnPolicy", "turn budget warning injected remaining=$remaining")
        }
    }

    private fun append(note: String) {
        val index = history.lastIndex
        if (index >= 0 && JournalProjection.isConversationUser(history[index])) {
            val last = history[index]
            history[index] = if (last.contentParts.isEmpty()) last.copy(content = (last.content + "\n\n" + note).trim())
                else last.copy(contentParts = last.contentParts + AgentContentPart.Text(note))
        } else history.add(LLMMessage(LLMMessage.Role.USER, note))
    }
}
