package com.openminis.app.provider.openai

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.provider.ImageBudget
import com.openminis.app.tools.CodemodeTool
import org.json.JSONArray
import org.json.JSONObject

/** Ordered Responses input projection; no model selection, authentication or request overrides. */
internal object ResponsesInputEncoder {
    fun encode(messages: List<LLMMessage>, images: List<LLMMessage.ImagePart>,
        supportsImages: Boolean, codemodeGrammar: Boolean): JSONArray {
        val input = JSONArray()
        val lastUser = messages.indexOfLast { it.role == LLMMessage.Role.USER }
        for ((index, message) in messages.withIndex()) {
            val extraImages = if (index == lastUser && message.role == LLMMessage.Role.USER) images else emptyList()
            if (message.contentParts.isNotEmpty()) {
                if (message.role == LLMMessage.Role.ASSISTANT) {
                    val text = message.contentParts.filterIsInstance<AgentContentPart.Text>().joinToString("") { it.text }
                    if (text.isNotEmpty()) input.put(turn("assistant", text))
                    for (call in message.contentParts.filterIsInstance<AgentContentPart.ToolUse>()) {
                        val (callId, itemId) = splitId(call.id)
                        val safeCall = capId(callId)
                        val safeItem = itemId?.let { capId(it) } ?: "fc_syn_${safeCall.takeLast(24)}"
                        val raw = codemodeGrammar && call.name == CodemodeTool.NAME
                        input.put(JSONObject().put("type", if (raw) "custom_tool_call" else "function_call")
                            .put("id", capId("${if (raw) "ctc" else "fc"}_${safeItem.removePrefix("fc_").removePrefix("ctc_")}"))
                            .put("call_id", safeCall).put("name", call.name)
                            .put(if (raw) "input" else "arguments", if (raw) call.input.optString("code") else call.input.toString()))
                    }
                } else {
                    // Parallel outputs must remain contiguous. Image carriers follow the whole run.
                    val carriers = mutableListOf<JSONObject>()
                    for (result in message.contentParts.filterIsInstance<AgentContentPart.ToolResult>()) {
                        input.put(JSONObject().put("type", if (codemodeGrammar && result.name == CodemodeTool.NAME)
                            "custom_tool_call_output" else "function_call_output")
                            .put("call_id", capId(splitId(result.id).first)).put("output", result.content))
                        result.imageData?.takeIf { it.isNotEmpty() && supportsImages }?.let { bytes ->
                            carriers.add(turn("user", JSONArray().put(textBlock("[Image returned by ${result.name}]"))
                                .put(imageBlock(bytes, result.imageMimeType ?: "image/jpeg", supportsImages, null))))
                        }
                    }
                    carriers.forEach { input.put(it) }
                    val textParts = message.contentParts.filterIsInstance<AgentContentPart.Text>()
                    val hasImages = message.contentParts.any { it is AgentContentPart.ImageData }
                    if (hasImages || extraImages.isNotEmpty()) {
                        val content = JSONArray()
                        if (hasImages) {
                            for (part in message.contentParts) when (part) {
                                is AgentContentPart.Text -> if (part.text.isNotEmpty()) content.put(textBlock(part.text))
                                is AgentContentPart.ImageData -> content.put(imageBlock(part.data, part.mimeType, supportsImages, part.noVisionPlaceholder))
                                is AgentContentPart.ToolUse, is AgentContentPart.ToolResult -> Unit
                                else -> com.openminis.app.logging.AppLogger.error("OpenAIProvider",
                                    "[responses] DROPPED unconvertible content part ${part.javaClass.simpleName} on role=user — it will NOT " +
                                        "reach the model. Add an encoding branch for it.")
                            }
                        } else {
                            val text = textParts.joinToString("") { it.text }
                            if (text.isNotEmpty()) content.put(textBlock(text))
                        }
                        extraImages.forEach { content.put(imageBlock(it.data, it.mimeType, supportsImages, it.noVisionPlaceholder)) }
                        input.put(turn("user", content))
                    } else if (textParts.isNotEmpty()) input.put(turn("user", textParts.joinToString("") { it.text }))
                }
            } else if (message.audioParts.isNotEmpty() || extraImages.isNotEmpty()) {
                val content = JSONArray()
                for (audio in message.audioParts) content.put(JSONObject().put("type", "input_audio")
                    .put("input_audio", JSONObject().put("data", audio.base64Data).put("format", audio.format)))
                if (message.content.isNotEmpty()) content.put(textBlock(message.content))
                extraImages.forEach { content.put(imageBlock(it.data, it.mimeType, supportsImages, it.noVisionPlaceholder)) }
                input.put(turn(message.role.value, content))
            } else input.put(turn(message.role.value, message.content))
        }
        val paired = ResponsesToolPairing.sanitize(input)
        if (paired.changed) android.util.Log.w("OpenAIProvider",
            "[sanitize-responses] repaired outgoing tool pairing: droppedOrphanOutputs=${paired.droppedOrphanOutputs} " +
                "droppedDuplicateOutputs=${paired.droppedDuplicateOutputs} placeholderCalls=${paired.placeholderCalls} items=${input.length()}")
        return paired.items
    }

    private fun turn(role: String, content: Any) = JSONObject().put("role", role).put("content", content)
    private fun textBlock(text: String) = JSONObject().put("type", "input_text").put("text", text)
    private fun splitId(id: String): Pair<String, String?> {
        val separator = id.indexOf('|')
        return if (separator < 0) id to null else id.substring(0, separator) to id.substring(separator + 1)
    }
    private fun capId(id: String) = id.take(64)
    private fun imageBlock(bytes: ByteArray, mime: String, supported: Boolean, placeholder: String?): JSONObject {
        if (!supported) return textBlock(placeholder ?: "[Image attached but this model does not support vision input]")
        val upload = ImageBudget.prepareForUpload(bytes, mime)
        return if (upload != null) JSONObject().put("type", "input_image").put("image_url", upload.dataUrl())
            else textBlock(ImageBudget.UNSENDABLE_IMAGE_PLACEHOLDER)
    }
}
