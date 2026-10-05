package com.openminis.app.agent

import com.openminis.app.data.model.AgentContentPart

/** Logical reply visibility comes from accepted runtime content, never tool-card presentation. */
internal class AgentReplyContent {
    private var bubbleId: String? = null
    private val text = StringBuilder()
    private var toolsSeen = false

    fun accept(id: String, content: AgentTurnContent) {
        if (bubbleId != id) {
            bubbleId = id
            text.setLength(0)
            toolsSeen = false
        }
        text.append(content.visibleText())
        toolsSeen = toolsSeen || content.parts().any { it is AgentContentPart.ToolUse }
    }

    val visible: Boolean get() = text.isNotBlank() || toolsSeen
}
