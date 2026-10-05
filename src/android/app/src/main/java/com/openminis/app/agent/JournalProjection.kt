package com.openminis.app.agent

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage

/** Semantic boundary between durable journal records, visible turns and model input. */
internal object JournalProjection {
    fun isHidden(partsJson: String): Boolean =
        !isModelVisible(partsJson) || RuntimeContextSnapshot.decode(partsJson) != null

    fun isModelVisible(partsJson: String): Boolean =
        !com.openminis.app.data.model.LegacyCodemodeEntry.isEntry(partsJson) &&
            !com.openminis.app.data.model.RequestUsageRecord.isEntry(partsJson)

    fun runtimeMessage(partsJson: String, rowId: String): LLMMessage? =
        RuntimeContextSnapshot.decode(partsJson)?.let { RuntimeContextSnapshot.message(it, rowId) }

    /** User-role transport does not make runtime facts or tool results user-task boundaries. */
    fun startsUserTurn(message: LLMMessage): Boolean =
        message.role == LLMMessage.Role.USER && !message.isRuntimeContext &&
            message.contentParts.none { it is AgentContentPart.ToolResult }

    /** Some replay operations may merge into tool-result rows, but never into owned facts. */
    fun isConversationUser(message: LLMMessage): Boolean =
        message.role == LLMMessage.Role.USER && !message.isRuntimeContext
}
