package com.openminis.app.agent

import com.openminis.app.data.db.MessageEntity
import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import kotlinx.coroutines.CancellationException

/** Captured-branch history publication and durable turn commits. */
internal class AgentConversationJournal(
    private val writer: AgentJournalWriter,
    private val history: MutableList<LLMMessage>,
    private val currentSession: () -> String,
) {
    private var pendingAssistant: LLMMessage? = null

    fun appendAssistant(content: AgentTurnContent) {
        checkBranch()
        val message = LLMMessage(LLMMessage.Role.ASSISTANT, content.visibleText(),
            contentParts = content.parts(), reasoningContent = content.reasoningContent())
        history.add(message)
        pendingAssistant = message
    }

    suspend fun commitAssistant(turn: AgentModelTurn, metadata: Map<String, AgentJournalWriter.ToolPresentation>): MessageEntity? {
        val entity = turn.commitAssistant(metadata)
        checkBranch()
        val pending = pendingAssistant
        val index = history.indexOfLast { it === pending }
        if (entity != null && index >= 0) history[index] = history[index].copy(dbMessageId = entity.id)
        return entity
    }

    suspend fun commitResults(journal: AgentTurnJournal, parts: List<AgentContentPart>) {
        val entity = journal.toolResults(parts)
        checkBranch()
        history.add(LLMMessage(LLMMessage.Role.USER, "", contentParts = parts, dbMessageId = entity?.id))
    }

    private fun checkBranch() {
        if (currentSession() != writer.sessionId) throw CancellationException("agent branch changed during commit")
    }
}
