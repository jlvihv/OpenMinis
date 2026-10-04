package com.openminis.app.agent

import com.openminis.app.data.db.MessageEntity
import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.storage.PastedMedia
import com.openminis.app.logging.AppLogger
import org.json.JSONArray
import org.json.JSONObject

/** Durable message -> model input. Owns journal semantics, not Android paths or UI state. */
internal class HistoryMessageDecoder(private val media: Media) {
    interface Media {
        fun pastedText(relativePath: String): String?
        fun userImage(relativePath: String, mimeType: String, linuxPath: String?): UserImage?
        fun toolImage(linuxPath: String, mimeType: String): AgentContentPart.ImageData?
    }

    data class UserImage(val part: LLMMessage.ImagePart, val note: String? = null)

    fun decode(row: MessageEntity): LLMMessage? {
        if (!JournalProjection.isModelVisible(row.partsJson)) return null
        JournalProjection.runtimeMessage(row.partsJson, row.id)?.let { return it }
        val parts = mutableListOf<AgentContentPart>()
        val images = mutableListOf<LLMMessage.ImagePart>()
        val text = StringBuilder()
        try {
            val array = JSONArray(row.partsJson)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                when (obj.optString("type")) {
                    "text" -> {
                        val value = obj.optString("value", "")
                        // Keep attachment inventory in parts only, just like a fresh send.
                        if (!value.contains("<user-attached-files>")) text.append(value)
                        parts.add(AgentContentPart.Text(value))
                    }
                    "toolUse" -> {
                        val value = obj.getJSONObject("value")
                        parts.add(AgentContentPart.ToolUse(
                            value.optString("toolUseId", ""), value.optString("name", ""),
                            runCatching { JSONObject(value.optString("input", "{}")) }.getOrElse { JSONObject() },
                            thoughtSignature = value.optString("thoughtSignature", "").ifEmpty { null },
                        ))
                    }
                    "toolResult" -> {
                        val value = obj.getJSONObject("value")
                        val restored = mutableListOf<AgentContentPart.ImageData>()
                        value.optJSONArray("images")?.let { saved ->
                            for (j in 0 until saved.length()) {
                                val image = saved.getJSONObject(j)
                                runCatching { media.toolImage(image.optString("path"), image.getString("mimeType")) }
                                    .getOrNull()?.let { restored.add(it) }
                            }
                        }
                        parts.add(AgentContentPart.ToolResult(
                            id = value.optString("toolUseId", ""), name = value.optString("name", ""),
                            content = value.optString("output", ""), isError = !value.optBoolean("success", true),
                            imageData = restored.firstOrNull()?.data, imageMimeType = restored.firstOrNull()?.mimeType,
                            imageLinuxPath = restored.firstOrNull()?.linuxPath,
                            detailsJson = value.optString("detailsJson").ifEmpty { null },
                        ))
                        parts.addAll(restored.drop(1))
                    }
                    "mediaRef" -> {
                        val value = obj.optJSONObject("value") ?: continue
                        val relative = value.optString("relativePath", "")
                        if (relative.isEmpty()) continue
                        val mime = value.optString("mimeType", "image/jpeg")
                        if (PastedMedia.isPastedRef(mime, value.optString("originalFileName").takeUnless { value.isNull("originalFileName") })) {
                            val body = try { media.pastedText(relative) } catch (error: Exception) {
                                AppLogger.warning(TAG, "[Paste] restore failed for $relative: ${error.message}")
                                null
                            }
                            if (body == null) AppLogger.warning(TAG, "[Paste] missing pasted file for $relative — substituting placeholder")
                            val resolved = body ?: PastedMedia.MISSING_PLACEHOLDER
                            text.append(resolved)
                            parts.add(AgentContentPart.Text(resolved))
                        } else if (mime.startsWith("image/")) {
                            val image = media.userImage(relative, mime, value.optString("linuxPath", "").ifEmpty { null }) ?: continue
                            images.add(image.part)
                            parts.add(AgentContentPart.ImageData(image.part.data, image.part.mimeType,
                                linuxPath = image.part.linuxPath, noVisionPlaceholder = image.part.noVisionPlaceholder))
                            image.note?.let { parts.add(AgentContentPart.Text(it)) }
                        }
                        // Other attachments stay file references; never inline their bytes.
                    }
                }
            }
        } catch (_: Exception) {
            // Preserve the established malformed-row fallback, including preceding parts.
            text.clear().append(row.partsJson)
            parts.add(AgentContentPart.Text(row.partsJson))
        }
        return LLMMessage(if (row.role == "user") LLMMessage.Role.USER else LLMMessage.Role.ASSISTANT,
            text.toString(), imageParts = images, contentParts = parts, dbMessageId = row.id,
            reasoningContent = row.reasoningContent)
    }

    private companion object { const val TAG = "HistoryMessageDecoder" }
}
