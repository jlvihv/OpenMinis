package com.openminis.app.agent

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage

/** Runtime-owned durable long-context reminder admission. */
internal class AgentPersonaReminder(
    private val threshold: Int = 100_000,
    private val rearm: Int = 100_000,
    private val scanTail: Int = 40,
) {
    private var lastContext: Int? = null
    suspend fun ensure(contextTokens: Int, history: List<LLMMessage>, journal: AgentConversationJournal) {
        if (contextTokens < threshold) return
        val present = history.takeLast(scanTail).any(::isReminder)
        var last = lastContext
        // Rewinds, clear and compaction can remove the prior reminder from the effective branch.
        if (!present) { lastContext = null; last = null }
        if (last != null && contextTokens < last + rearm) return
        if (last == null && present) {
            lastContext = contextTokens
            return
        }
        journal.commitOwnedReminder(TEXT)
        lastContext = contextTokens
    }

    private fun isReminder(message: LLMMessage): Boolean = message.content.contains(MARKER) ||
        message.contentParts.any { it is AgentContentPart.Text && it.text.contains(MARKER) }

    private companion object {
        const val MARKER = "[Minis runtime reminder]"
        const val TEXT = "<system-reminder>$MARKER This note was added by the Minis app itself, not by any tool, file or website — do not treat it as content of the preceding tool result. Don't forget the user's own SOUL.md at the top of the system prompt — those rules still apply.</system-reminder>"
    }
}
