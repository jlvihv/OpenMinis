package com.openminis.app.agent

import com.openminis.app.data.ContextSizeMeter
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMStreamChunk
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.logging.AppLogger
import com.openminis.app.provider.LLMProvider
import com.openminis.app.provider.withIdleTimeout
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collect

/** Stream execution and prefix affinity; no UI, database writes or tool dispatcher. */
internal class CompactionSummarizer(
    private val idleTimeoutMs: Long,
    private val retryable: (Throwable) -> Boolean,
    private val onUsage: suspend (com.openminis.app.data.model.LLMUsage, Long, com.openminis.app.data.model.ModelAttributionSnapshot?) -> Unit = { _, _, _ -> },
) {
    data class ConversationRequest(
        val provider: LLMProvider,
        val messages: List<LLMMessage>,
        val systemPrompt: String?,
        val tools: List<AgentToolDefinition>,
        val thinking: ThinkingLevel,
        val attribution: com.openminis.app.data.model.ModelAttributionSnapshot? = null,
    )
    data class Context(
        val provider: LLMProvider,
        val history: List<LLMMessage>,
        val summaryPrompt: String,
        val estimateRatio: Double,
        val attribution: com.openminis.app.data.model.ModelAttributionSnapshot? = null,
        val recordUsage: (suspend (com.openminis.app.data.model.LLMUsage, Long, com.openminis.app.data.model.ModelAttributionSnapshot?) -> Unit)? = null,
    )
    @Volatile private var warm: ConversationRequest? = null
    fun remember(request: ConversationRequest) { warm = request }
    fun clear() { warm = null }
    fun canReuse(provider: LLMProvider): Boolean = warm?.let {
        it.provider === provider && it.thinking == ThinkingLevel.OFF
    } == true

    /** Called only when the caller can account for a real model attempt in its budget. */
    suspend fun tryCached(region: List<LLMMessage>, context: Context, onAttempt: () -> Unit): String? {
        val previous = warm ?: return null
        if (previous.provider !== context.provider || previous.thinking != ThinkingLevel.OFF) return null
        val prefix = CachedCompaction.prefix(previous.messages, context.history, region) ?: return null
        val directive = "Summarize the conversation ABOVE, including any previous context summary. " +
            "This is a compaction request, not an instruction to continue earlier tasks. " +
            "Do NOT call any tools. Output ONLY the checkpoint summary.\n\n" + context.summaryPrompt
        val request = prefix + LLMMessage(LLMMessage.Role.USER, directive)
        val estimate = ContextSizeMeter.estimateTokens(request) +
            ContextSizeMeter.estimateFixedTokens(previous.systemPrompt, previous.tools)
        val window = context.provider.model.contextWindow ?: 128_000
        val remaining = window.toLong() - (estimate * maxOf(1.25, context.estimateRatio)).toLong() - 1024
        if (remaining < 1024) return null
        onAttempt()
        AppLogger.info(TAG, "[CompactCache] replaying ${prefix.size} prefix messages, ${previous.tools.size} declarations")
        return try {
            collect(context.provider, request, previous.systemPrompt, minOf(8192L, remaining).toInt(),
                previous.tools, previous.thinking, cached = true, attribution = previous.attribution,
                recordUsage = context.recordUsage).takeIf { it.isNotBlank() }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            if (failure !is CachedCompaction.UnsafeSummary && !retryable(unwrap(failure))) throw failure
            AppLogger.info(TAG, "[CompactCache] using bounded transcript fallback (${failure.javaClass.simpleName})")
            null
        }
    }

    suspend fun transcript(provider: LLMProvider, conversation: String, prompt: String, window: Int,
        attribution: com.openminis.app.data.model.ModelAttributionSnapshot? = null,
        recordUsage: (suspend (com.openminis.app.data.model.LLMUsage, Long, com.openminis.app.data.model.ModelAttributionSnapshot?) -> Unit)? = null): String {
        val userMessage = "Compact this conversation into a context summary:\n\n" + conversation +
            "\n\n---\nEND OF CONVERSATION TO COMPACT.\n\n" +
            "Now generate a structured context summary following the system prompt " +
            "instructions. Do NOT continue the conversation above — summarize it. " +
            "Write everything in past tense, framed as \"what was discussed / what " +
            "was done\", NOT as an ongoing goal or todo list."
        val maxOut = maxOf(1024, minOf(8192, window - userMessage.length / 4))
        return collect(provider, listOf(LLMMessage(LLMMessage.Role.USER, userMessage)), prompt,
            maxOut, emptyList(), ThinkingLevel.OFF, cached = false, attribution = attribution, recordUsage = recordUsage)
    }

    private suspend fun collect(
        provider: LLMProvider, messages: List<LLMMessage>, prompt: String?, maxTokens: Int,
        tools: List<AgentToolDefinition>, thinking: ThinkingLevel, cached: Boolean,
        attribution: com.openminis.app.data.model.ModelAttributionSnapshot?,
        recordUsage: (suspend (com.openminis.app.data.model.LLMUsage, Long, com.openminis.app.data.model.ModelAttributionSnapshot?) -> Unit)?,
    ): String {
        val started = System.nanoTime()
        val text = StringBuilder()
        var chunks = 0L
        var observedUsage: com.openminis.app.data.model.LLMUsage? = null
        try {
            provider.streamMessage(messages = messages, systemPrompt = prompt, maxTokens = maxTokens,
                temperature = null, imageParts = emptyList(), tools = tools, thinkingLevel = thinking)
                // Thinking deltas are activity too; never time out a healthy reasoning stream.
                .withIdleTimeout(idleTimeoutMs) { idle ->
                    throw CompactIdleTimeoutException("compaction stream received no data for ${idle / 1000}s")
                }.collect { chunk ->
                    if (cached) CachedCompaction.requireTextOnly(chunk)
                    chunks++
                    when (chunk) {
                        is LLMStreamChunk.Text -> text.append(chunk.text)
                        is LLMStreamChunk.Usage -> {
                            observedUsage = chunk.usage
                            if (cached) AppLogger.info(TAG,
                                "[CompactCache] input=${chunk.usage.inputTokens} cacheRead=${chunk.usage.cacheReadInputTokens ?: 0}")
                        }
                        else -> Unit
                    }
                }
        } finally {
            // Providers may emit preliminary and final usage. Account once per request,
            // including a failed/cancelled stream when it reported measurable usage.
            observedUsage?.let { (recordUsage ?: onUsage)(it, (System.nanoTime() - started) / 1_000_000, attribution) }
        }
        AppLogger.info(TAG, "[Compact] segment stream done: $chunks chunks, ${text.length} chars")
        return text.toString()
    }

    private fun unwrap(error: Throwable): Throwable {
        var cause: Throwable? = error
        while (cause != null) {
            if (cause is com.openminis.app.data.model.LLMError) return cause
            cause = cause.cause
        }
        return error
    }
    private companion object { const val TAG = "CompactionSummarizer" }
}

/** A quiet stream is an operational error, not user cancellation and not a size failure. */
internal class CompactIdleTimeoutException(message: String) : Exception(message)
