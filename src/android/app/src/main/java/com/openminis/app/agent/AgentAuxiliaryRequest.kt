package com.openminis.app.agent

import com.openminis.app.data.model.*
import com.openminis.app.data.repository.ChatRepository
import com.openminis.app.provider.LLMProvider
import kotlinx.coroutines.*

/** A text-only metadata request, with its last measured usage settled even on failure/cancellation. */
internal class AgentAuxiliaryRequest(private val repository: ChatRepository) {
    suspend fun text(session: String, provider: LLMProvider, attribution: ModelAttributionSnapshot?,
        purpose: RequestUsageRecord.Purpose, prompt: String, system: String, maxTokens: Int): String {
        currentCoroutineContext().ensureActive()
        val text = StringBuilder()
        var usage: LLMUsage? = null
        var finished = false
        val started = System.nanoTime()
        try {
            provider.streamMessage(listOf(LLMMessage(LLMMessage.Role.USER, prompt)), system, maxTokens,
                temperature = null, thinkingLevel = ThinkingLevel.OFF).collect { chunk -> when (chunk) {
                is LLMStreamChunk.Text -> {
                    check(text.length + chunk.text.length <= 65_536) { "Auxiliary response exceeded its text limit" }
                    text.append(chunk.text)
                }
                is LLMStreamChunk.Usage -> usage = chunk.usage
                is LLMStreamChunk.Finished -> finished = true
                is LLMStreamChunk.ToolUseStart, is LLMStreamChunk.ToolInputDelta,
                is LLMStreamChunk.ToolCallComplete, is LLMStreamChunk.MediaAttachment -> error("Auxiliary request returned non-text output")
                else -> Unit
            } }
            check(finished) { "Auxiliary stream ended without a completion event" }
            return text.toString()
        } finally {
            usage?.let { measured -> withContext(NonCancellable) {
                repository.recordRequestUsage(session, purpose, measured, (System.nanoTime() - started) / 1_000_000, attribution)
            } }
        }
    }
}
