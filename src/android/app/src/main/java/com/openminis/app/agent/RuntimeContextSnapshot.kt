package com.openminis.app.agent

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import org.json.JSONArray
import org.json.JSONObject

/** Durable, append-only runtime facts; user messages cannot masquerade as owned snapshots. */
internal object RuntimeContextSnapshot {
    const val TYPE = "runtime-context"
    data class Facts(val date: String, val timezone: String, val language: String, val modelCount: Int) {
        fun render(): String = RuntimeContextSnapshot.render(date, timezone, language, modelCount)
    }
    fun render(date: String, timezone: String, language: String, modelCount: Int): String =
        "<system-reminder>\nCurrent runtime context (supersedes earlier runtime-context snapshots):\n" +
            "- Current date: $date ($timezone)\n- Device language: $language\n" +
            "- minis-model-use models available: $modelCount\n</system-reminder>"

    fun encode(text: String): String = JSONArray().put(JSONObject().put("type", TYPE).put("value", text)).toString()
    fun decode(partsJson: String): String? {
        // Do not parse megabytes of unrelated tool/image JSON just to test ownership.
        if (!partsJson.contains("\"$TYPE\"")) return null
        return runCatching {
            val parts = JSONArray(partsJson)
            if (parts.length() != 1) null else parts.getJSONObject(0).let {
                (it.opt("value") as? String)?.takeIf { text -> it.optString("type") == TYPE && text.isNotBlank() }
            }
        }.getOrNull()
    }

    fun message(text: String, rowId: String? = null): LLMMessage = LLMMessage(
        role = LLMMessage.Role.USER, content = text, contentParts = listOf(AgentContentPart.Text(text)),
        dbMessageId = rowId, isRuntimeContext = true,
    )

    /** Scan the effective branch, not an instance-level cache or hidden, compacted history. */
    fun changed(history: List<LLMMessage>, text: String): Boolean =
        history.lastOrNull { it.isRuntimeContext }?.content != text
}
