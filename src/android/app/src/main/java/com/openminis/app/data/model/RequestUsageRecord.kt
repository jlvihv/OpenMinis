package com.openminis.app.data.model

import org.json.JSONArray
import org.json.JSONObject

/** One normalized accounting format for conversation and auxiliary model requests. */
object RequestUsageRecord {
    const val TYPE = "request-usage"
    enum class Purpose(val wireName: String) { CONVERSATION("conversation"), COMPACTION("compaction"), TITLE("title"), GROUP_SUGGEST("group-suggest") }

    fun parts(purpose: Purpose): String = JSONArray().put(JSONObject()
        .put("type", TYPE).put("value", JSONObject().put("purpose", purpose.wireName))).toString()

    fun isEntry(partsJson: String): Boolean {
        if (!partsJson.contains("\"$TYPE\"")) return false
        return runCatching {
            val parts = JSONArray(partsJson)
            parts.length() == 1 && parts.getJSONObject(0).optString("type") == TYPE
        }.getOrDefault(false)
    }

    fun json(usage: LLMUsage, purpose: Purpose = Purpose.CONVERSATION, streamMs: Long? = null): JSONObject =
        JSONObject().put("inputTokens", usage.inputTokens).put("outputTokens", usage.outputTokens)
            .put("cacheCreationTokens", usage.cacheCreationInputTokens ?: 0)
            .put("cacheReadTokens", usage.cacheReadInputTokens ?: 0)
            .put("latestContextTokens", usage.latestContextTokens).put("purpose", purpose.wireName)
            .also { if (streamMs != null) it.put("streamMs", streamMs) }

    /** Legacy rows have no purpose and are ordinary conversation usage. */
    fun isConversation(json: JSONObject): Boolean =
        json.optString("purpose", Purpose.CONVERSATION.wireName) == Purpose.CONVERSATION.wireName
}
