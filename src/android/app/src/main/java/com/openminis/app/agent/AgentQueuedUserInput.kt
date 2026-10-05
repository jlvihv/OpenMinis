package com.openminis.app.agent

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage

/** Prepared media stays small in storage; expanded paste content is used only for model replay. */
internal data class AgentQueuedUserInput(
    val partsJson: String,
    val text: String,
    val parts: List<AgentContentPart>,
    val images: List<LLMMessage.ImagePart>,
    val expandedPaste: String? = null,
) {
    fun message(rowId: String): LLMMessage {
        val replay = expandedPaste?.let { body ->
            val leadingText = parts.takeWhile { it is AgentContentPart.Text }.size
            listOf(AgentContentPart.Text(body)) + parts.drop(leadingText)
        } ?: parts
        return LLMMessage(LLMMessage.Role.USER, expandedPaste ?: text, imageParts = images,
            contentParts = replay, dbMessageId = rowId)
    }
}
