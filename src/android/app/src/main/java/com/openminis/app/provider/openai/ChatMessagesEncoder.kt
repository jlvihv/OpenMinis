package com.openminis.app.provider.openai

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.provider.ImageBudget
import com.openminis.app.tools.ImageReader
import org.json.JSONArray
import org.json.JSONObject

/** Chat Completions history projection, including reasoning echoes and request-local tool ids. */
internal object ChatMessagesEncoder {
    fun encode(messages: List<LLMMessage>, system: String?, images: List<LLMMessage.ImagePart>,
        supportsImages: Boolean, includeReasoning: Boolean): JSONArray {
        val output = JSONArray()
        if (system != null) output.put(turn("system", system))
        val lastUser = messages.indexOfLast { it.role == LLMMessage.Role.USER }
        for ((index, message) in messages.withIndex()) {
            if (message.contentParts.isNotEmpty()) {
                if (message.role == LLMMessage.Role.ASSISTANT) {
                    val item = JSONObject().put("role", "assistant")
                    if (includeReasoning) item.put("reasoning_content", message.reasoningContent ?: "")
                    val text = message.contentParts.filterIsInstance<AgentContentPart.Text>()
                    if (text.isNotEmpty()) item.put("content", text.joinToString("") { it.text })
                    val calls = message.contentParts.filterIsInstance<AgentContentPart.ToolUse>()
                    if (calls.isNotEmpty()) item.put("tool_calls", JSONArray().apply {
                        calls.forEach { call -> put(JSONObject().put("id", callId(call.id)).put("type", "function")
                            .put("function", JSONObject().put("name", call.name).put("arguments", call.input.toString()))) }
                    })
                    output.put(item)
                } else {
                    val carriers = mutableListOf<JSONObject>()
                    for (result in message.contentParts.filterIsInstance<AgentContentPart.ToolResult>()) {
                        output.put(turn("tool", result.content).put("tool_call_id", callId(result.id)))
                        result.imageData?.takeIf { it.isNotEmpty() && supportsImages }?.let { bytes ->
                            carriers.add(turn("user", JSONArray().put(textBlock(
                                "[Image returned by ${result.name} (tool_call_id: ${callId(result.id)})]"))
                                .put(imageBlock(bytes, result.imageMimeType, true, null))))
                        }
                    }
                    // Keep every tool reply contiguous, then its image carriers.
                    carriers.forEach { output.put(it) }
                    val texts = message.contentParts.filterIsInstance<AgentContentPart.Text>()
                    if (message.contentParts.any { it is AgentContentPart.ImageData }) {
                        val content = JSONArray()
                        for (part in message.contentParts) when (part) {
                            is AgentContentPart.Text -> if (part.text.isNotEmpty()) content.put(textBlock(part.text))
                            is AgentContentPart.ImageData -> content.put(imageBlock(part.data, part.mimeType, supportsImages, part.noVisionPlaceholder))
                            else -> Unit
                        }
                        output.put(turn("user", content))
                    } else if (texts.isNotEmpty()) output.put(turn("user", texts.joinToString("") { it.text }))
                    // Top-level images historically attach only to legacy messages on this protocol.
                }
            } else {
                val item = JSONObject().put("role", message.role.value)
                if (includeReasoning && message.role == LLMMessage.Role.ASSISTANT)
                    item.put("reasoning_content", message.reasoningContent ?: "")
                val attachImages = index == lastUser && message.role == LLMMessage.Role.USER && images.isNotEmpty()
                if (attachImages || message.audioParts.isNotEmpty()) {
                    val content = JSONArray()
                    if (attachImages) for (image in images) {
                        content.put(imageBlock(image.data, image.mimeType, supportsImages, image.noVisionPlaceholder))
                        if (supportsImages) ImageReader.pathNote(image.linuxPath)?.let { content.put(textBlock(it)) }
                    }
                    for (audio in message.audioParts) content.put(JSONObject().put("type", "input_audio")
                        .put("input_audio", JSONObject().put("data", audio.base64Data).put("format", audio.format)))
                    if (attachImages || message.content.isNotEmpty()) content.put(textBlock(message.content))
                    item.put("content", content)
                } else item.put("content", message.content)
                output.put(item)
            }
        }
        dedupe(output)
        return output
    }

    private fun turn(role: String, content: Any) = JSONObject().put("role", role).put("content", content)
    private fun textBlock(text: String) = JSONObject().put("type", "text").put("text", text)
    private fun imageBlock(bytes: ByteArray, mime: String?, supported: Boolean, placeholder: String?): JSONObject {
        if (!supported) return textBlock(placeholder ?: "[Image attached but this model does not support vision input]")
        val upload = ImageBudget.prepareForUpload(bytes, mime)
        return if (upload != null) JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", upload.dataUrl()))
            else textBlock(ImageBudget.UNSENDABLE_IMAGE_PLACEHOLDER)
    }
    private fun callId(id: String): String {
        val raw = id.substringBefore('|')
        if (raw.length <= 64) return raw
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
        return "call_${digest.joinToString("") { "%02x".format(it) }.take(56)}"
    }
    private fun dedupe(messages: JSONArray) {
        val seen = HashMap<String, Int>()
        val pending = HashMap<String, String>()
        var count = 0
        for (i in 0 until messages.length()) {
            val item = messages.optJSONObject(i) ?: continue
            when (item.optString("role")) {
                "assistant" -> {
                    val calls = item.optJSONArray("tool_calls") ?: continue
                    for (j in 0 until calls.length()) {
                        val call = calls.optJSONObject(j) ?: continue
                        val raw = call.optString("id", "")
                        if (raw.isEmpty()) continue
                        val used = seen[raw] ?: 0
                        val renamed = if (used == 0) raw else "$raw-${used + 1}"
                        seen[raw] = used + 1
                        if (used > 0) { count++; call.put("id", renamed) }
                        pending[raw] = renamed
                    }
                }
                "tool" -> {
                    val raw = item.optString("tool_call_id", "")
                    if (raw.isEmpty()) continue
                    val renamed = pending[raw] ?: continue
                    if (renamed != raw) item.put("tool_call_id", renamed)
                }
            }
        }
        if (count > 0) android.util.Log.w("OpenAIProvider",
            "[dedupe-tool-call-id] renamed $count duplicate tool_call_id(s) across messages — likely DB-loaded history or cross-provider switch")
    }
}
