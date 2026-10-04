package com.openminis.app.provider.openai

import java.security.MessageDigest

/** Stable conversation affinity, independent of message edits, compaction and modality. */
internal object OpenAIPromptCache {
    fun key(sessionId: String?, fallbackId: String): String {
        val id = sessionId?.trim()?.takeIf { it.isNotEmpty() } ?: fallbackId
        return if (id.length <= 64) id else digest(id)
    }

    fun codexHeaders(body: org.json.JSONObject): Map<String, String> {
        val key = body.optString("prompt_cache_key", "").takeIf { it.isNotBlank() } ?: return emptyMap()
        return mapOf("session-id" to key, "x-client-request-id" to key)
    }

    /** Log only fingerprints, never prompt text, credentials or the session identifier. */
    fun diagnostics(body: org.json.JSONObject): String =
        "key=${digest(body.optString("prompt_cache_key", "")).take(16)} " +
        "instructions=${digest(body.optString("instructions", "")).take(16)} " +
        "tools=${digest(body.optJSONArray("tools")?.toString().orEmpty()).take(16)} " +
        "inputItems=${body.optJSONArray("input")?.length() ?: 0}"

    private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
