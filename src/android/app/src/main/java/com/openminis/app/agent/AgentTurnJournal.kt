package com.openminis.app.agent

import com.openminis.app.data.db.MessageEntity
import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal class AgentTurnJournal(
    private val writer: AgentJournalWriter,
    private val content: AgentTurnContent,
    val bubbleId: String,
    private val cancelledMarker: String,
) {
    val sessionId get() = writer.sessionId
    private val commits = Mutex()
    private val state = Any()
    private var requested = false
    private var receipt = AgentJournalWriter.Receipt(null, 0, null, null)
    private var assistantRow: MessageEntity? = null
    private var assistantMessage: LLMMessage? = null
    private var resultsCommitted = false
    private var presentation = emptyMap<String, AgentJournalWriter.ToolPresentation>()
    private val completed = mutableMapOf<String, List<AgentContentPart>>()

    val stopRequested: Boolean get() = synchronized(state) { requested }
    data class StopView(val bubbleId: String, val pendingIds: Set<String>, val resumable: Boolean)
    data class Commit(val sessionId: String, val bubbleId: String,
        val messages: List<LLMMessage>, val assistantRow: MessageEntity?)

    fun requestStop(): StopView = synchronized(state) {
        requested = true
        val parts = content.parts()
        val pending = parts.filterIsInstance<AgentContentPart.ToolUse>().map { it.id }.filterNot(completed::containsKey).toSet()
        StopView(bubbleId, pending, parts.isNotEmpty() || assistantRow != null)
    }

    fun recordPresentation(value: Map<String, AgentJournalWriter.ToolPresentation>) = synchronized(state) {
        presentation = value.toMap()
    }

    fun recordToolPresentation(id: String, value: AgentJournalWriter.ToolPresentation) = synchronized(state) {
        val previous = presentation[id]
        presentation = presentation + (id to value.copy(
            title = value.title.ifEmpty { previous?.title.orEmpty() },
            pageURL = value.pageURL.ifEmpty { previous?.pageURL.orEmpty() },
            imagePath = value.imagePath.ifEmpty { previous?.imagePath.orEmpty() }))
    }

    fun recordReceipt(value: AgentJournalWriter.Receipt) = synchronized(state) { receipt = value }
    fun recordDuration(duration: Long) = synchronized(state) { receipt = receipt.copy(streamMs = duration) }
    fun completed(parts: List<AgentContentPart>) = synchronized(state) {
        parts.filterIsInstance<AgentContentPart.ToolResult>().firstOrNull()?.let { completed[it.id] = parts.toList() }
    }

    suspend fun assistant(parts: List<AgentContentPart>, receipt: AgentJournalWriter.Receipt, reasoning: String?,
        metadata: Map<String, AgentJournalWriter.ToolPresentation>): MessageEntity? = commits.withLock {
        withContext(NonCancellable + Dispatchers.IO) {
            val existing = synchronized(state) { assistantRow }
            existing ?: writer.assistant(parts, receipt, reasoning, metadata)?.also { row ->
                synchronized(state) {
                    assistantRow = row
                    assistantMessage = LLMMessage(LLMMessage.Role.ASSISTANT, content.visibleText(),
                        contentParts = parts, reasoningContent = reasoning, dbMessageId = row.id)
                    this@AgentTurnJournal.receipt = receipt
                }
            }
        }
    }

    suspend fun toolResults(parts: List<AgentContentPart>): MessageEntity? = commits.withLock {
        withContext(NonCancellable + Dispatchers.IO) {
            writer.toolResults(parts).also { synchronized(state) { resultsCommitted = true } }
        }
    }

    suspend fun finishStop(): Commit? = withContext(NonCancellable + Dispatchers.IO) {
        commits.withLock {
            val stopped = synchronized(state) { requested }
            if (!stopped) return@withLock null
            val parts = content.parts()
            val calls = parts.filterIsInstance<AgentContentPart.ToolUse>()
            val additions = mutableListOf<LLMMessage>()
            val prior = synchronized(state) { assistantRow }
            synchronized(state) { assistantMessage }?.let(additions::add)
            if (prior == null && parts.isNotEmpty()) {
                val interrupted = if (calls.isEmpty()) parts + AgentContentPart.Text(
                    "<system-reminder>The user stopped this response. Content may be incomplete.</system-reminder>") else parts
                val savedReceipt = synchronized(state) { receipt }
                val row = writer.assistant(interrupted, savedReceipt, content.reasoningContent(), synchronized(state) { presentation })
                synchronized(state) { assistantRow = row }
                additions.add(LLMMessage(LLMMessage.Role.ASSISTANT, content.visibleText(), contentParts = interrupted,
                    reasoningContent = content.reasoningContent(), dbMessageId = row?.id))
            }
            if (calls.isNotEmpty() && !synchronized(state) { resultsCommitted }) {
                val results = calls.flatMap { call -> synchronized(state) { completed[call.id] } ?: listOf(
                    AgentContentPart.ToolResult(call.id, call.name, cancelledMarker, isError = true)) }
                val row = writer.toolResults(results)
                synchronized(state) { resultsCommitted = true }
                additions.add(LLMMessage(LLMMessage.Role.USER, "", contentParts = results, dbMessageId = row?.id))
            }
            Commit(sessionId, bubbleId, additions, synchronized(state) { assistantRow })
        }
    }
}
