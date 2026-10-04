package com.openminis.app.agent

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.logging.AppLogger
import kotlinx.coroutines.CancellationException
import java.util.concurrent.atomic.AtomicInteger

/** Bounded summary planning. Model selection, branch commits and outer deadline belong to the caller. */
internal class CompactionCoordinator(
    private val summarizer: CompactionSummarizer,
    private val calls: AtomicInteger,
    private val maxCalls: Int,
    private val retryable: (Throwable) -> Boolean,
    private val onProgress: (depth: Int, calls: Int) -> Unit,
) {
    suspend fun summarize(
        messages: List<LLMMessage>, previousSummary: String?,
        context: CompactionSummarizer.Context, contextWindow: Int,
        depth: Int = 0,
    ): String {
        if (depth == 0) summarizer.tryCached(messages, context) { spend(0) }?.let { return it }
        val transcript = transcript(messages)
        val text = if (previousSummary.isNullOrBlank()) transcript else
            "Previous context summary:\n$previousSummary\n\nNew conversation to merge:\n$transcript"
        spend(depth)
        return try {
            summarizer.transcript(context.provider, text, context.summaryPrompt, contextWindow, context.attribution)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            if (!retryable(failure) || messages.size < 2 || depth >= 3) throw failure
            // Never begin siblings we cannot afford to finish. No third merge request.
            if (calls.get() + 2 > maxCalls) {
                AppLogger.info(TAG, "[Compact] not splitting at depth=$depth — ${calls.get()}/$maxCalls calls already spent")
                throw failure
            }
            val mid = messages.size / 2
            AppLogger.info(TAG, "[Compact] Splitting ${messages.size} messages into $mid + ${messages.size - mid} (depth=$depth)")
            val first = summarize(messages.take(mid), null, context, contextWindow, depth + 1)
            val second = summarize(messages.drop(mid), null, context, contextWindow, depth + 1)
            first + "\n\n" + second
        }
    }

    private fun spend(depth: Int) {
        val issued = calls.incrementAndGet()
        check(issued <= maxCalls) { "compaction exceeded its budget of $maxCalls model calls" }
        onProgress(depth, issued)
    }

    /** Preserve the bounded legacy representation; structured replay never goes through this. */
    fun transcript(messages: List<LLMMessage>): String = buildString {
        for (message in messages) {
            val role = message.role.name.lowercase()
            if (message.content.isNotEmpty()) append(role).append(": ").append(message.content.take(500)).append('\n')
            for (part in message.contentParts) when (part) {
                is AgentContentPart.Text -> append(role).append(": ").append(part.text.take(500)).append('\n')
                is AgentContentPart.ToolUse -> append(role).append(" [tool:").append(part.name)
                    .append("]: ").append(part.input.toString().take(200)).append('\n')
                is AgentContentPart.ToolResult -> append(role).append(" [result:").append(part.name)
                    .append("]: ").append(part.content.take(500)).append('\n')
                is AgentContentPart.ImageData -> append(role).append(" [image: ").append(part.mimeType).append("]\n")
            }
        }
    }
    private companion object { const val TAG = "CompactionCoordinator" }
}
