package com.openminis.app.agent

import com.openminis.app.data.db.MessageEntity
import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMUsage
import com.openminis.app.data.model.ModelAttributionSnapshot
import com.openminis.app.data.model.RequestUsageRecord
import com.openminis.app.data.repository.ChatRepository
import com.openminis.app.tools.CodemodeTool
import com.openminis.app.logging.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

internal class AgentJournalWriter(private val repository: ChatRepository, val sessionId: String) {
    data class ToolPresentation(val title: String = "", val pageURL: String = "", val imagePath: String = "")
    data class Receipt(
        val usage: LLMUsage?,
        val streamMs: Long,
        val attribution: ModelAttributionSnapshot?,
        val calibration: Triple<Int, Int, String?>?,
    ) {
        fun usageJson(): String? = usage?.let {
            RequestUsageRecord.json(it, streamMs = streamMs).also { json ->
                calibration?.let { (estimate, fixed, model) ->
                    json.put("estimatedRequestTokens", estimate).put("estimatedFixedTokens", fixed)
                    if (model != null) json.put("calibrationModelId", model)
                }
            }.toString()
        }
    }

    suspend fun assistant(parts: List<AgentContentPart>, receipt: Receipt, reasoning: String?,
        metadata: Map<String, ToolPresentation>): MessageEntity? {
        if (parts.isEmpty()) return null
        return repository.appendMessage(sessionId, "assistant", assistantParts(parts, metadata),
            receipt.usageJson(), reasoningContent = reasoning, modelSnapshot = receipt.attribution)
    }

    suspend fun usageOnly(receipt: Receipt): MessageEntity? {
        val usage = receipt.usageJson() ?: return null
        return repository.appendMessage(sessionId, "usage", RequestUsageRecord.parts(RequestUsageRecord.Purpose.CONVERSATION),
            tokenUsage = usage, modelSnapshot = receipt.attribution)
    }

    suspend fun user(partsJson: String): MessageEntity = repository.appendMessage(sessionId, "user", partsJson)

    suspend fun reminder(text: String): MessageEntity = repository.appendMessage(sessionId, "user",
        assistantParts(listOf(AgentContentPart.Text(text)), emptyMap()))

    suspend fun toolResults(parts: List<AgentContentPart>): MessageEntity? {
        val json = toolResultParts(parts) ?: return null
        return repository.appendMessage(sessionId, "user", json)
    }

    suspend fun compactionUsage(usage: LLMUsage, duration: Long, attribution: ModelAttributionSnapshot?) {
        withContext(NonCancellable + Dispatchers.IO) {
            try {
                repository.recordRequestUsage(sessionId, RequestUsageRecord.Purpose.COMPACTION,
                    usage, duration, attribution)
            } catch (failure: Exception) {
                AppLogger.warning("AgentJournalWriter", "[Usage] compaction accounting write failed (${failure.javaClass.simpleName})")
            }
        }
    }

    suspend fun preview(parts: List<AgentContentPart>, metadata: Map<String, ToolPresentation>) {
        if (parts.isNotEmpty()) repository.updateSessionPreview(sessionId, assistantParts(parts, metadata))
    }

    companion object {
        fun assistantParts(parts: List<AgentContentPart>, metadata: Map<String, ToolPresentation>): String =
            parts.mapNotNull { part -> when (part) {
                is AgentContentPart.Text -> """{"type":"text","value":${quote(part.text)}}"""
                is AgentContentPart.ToolUse -> if (part.name.isBlank()) null else {
                    val meta = metadata[part.id]
                    val signature = part.thoughtSignature?.let(::quote) ?: "null"
                    """{"type":"toolUse","value":{"toolUseId":${quote(part.id)},"name":${quote(part.name)},"input":${quote(part.input.toString())},"description":${quote(meta?.title.orEmpty())},"pageURL":${quote(meta?.pageURL.orEmpty())},"imageFilePath":${quote(meta?.imagePath.orEmpty())},"thoughtSignature":$signature}}"""
                }
                else -> null
            } }.joinToString(",", "[", "]")

        private fun toolResultParts(parts: List<AgentContentPart>): String? {
            val results = parts.filterIsInstance<AgentContentPart.ToolResult>()
            if (results.isEmpty()) return null
            return results.map { result ->
                val snapshot = quote(result.content.lines().takeLast(30).joinToString("\n"))
                val imageMetadata = if (result.name == CodemodeTool.NAME) {
                    val position = parts.indexOf(result)
                    val images = mutableListOf<AgentContentPart.ImageData>()
                    if (result.imageData != null && result.imageLinuxPath != null) images.add(
                        AgentContentPart.ImageData(result.imageData, result.imageMimeType ?: "image/png", result.imageLinuxPath))
                    images.addAll(parts.drop(position + 1).takeWhile { it !is AgentContentPart.ToolResult }
                        .filterIsInstance<AgentContentPart.ImageData>())
                    ",\"images\":" + JSONArray(images.map { image -> JSONObject()
                        .put("path", image.linuxPath).put("mimeType", image.mimeType) })
                } else ""
                val details = result.detailsJson?.let { ",\"detailsJson\":${quote(it)}" }.orEmpty()
                """{"type":"toolResult","value":{"toolUseId":${quote(result.id)},"name":${quote(result.name)},"output":${quote(result.content)},"success":${!result.isError}$imageMetadata$details,"snapshot":{"type":"text","text":$snapshot}}}"""
            }.joinToString(",", "[", "]")
        }

        private fun quote(text: String): String = buildString {
            append('"')
            for (char in text) when (char) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (char.code < 0x20) append("\\u%04x".format(char.code)) else append(char)
            }
            append('"')
        }
    }
}
