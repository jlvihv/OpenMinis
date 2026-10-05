package com.openminis.app.ui.chat

import com.openminis.app.data.db.MessageEntity
import com.openminis.app.logging.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal class ChatMessagePublication(
    private val scope: CoroutineScope,
    private val messages: MutableStateFlow<List<ChatMessage>>,
    private val currentSession: () -> String,
    private val mergeOverrides: (List<AssistantBlock>) -> List<AssistantBlock>,
) {
    private class Flush(val sessionId: String) {
        var lastMs = 0L
        var lastLength = 0
        var job: Job? = null
        var pending: StreamingDelta? = null
    }
    private val states = mutableMapOf<String, Flush>()
    private val live = MutableStateFlow<Map<String, StreamingDelta>>(emptyMap())
    val streaming: StateFlow<Map<String, StreamingDelta>> = live.asStateFlow()

    @Synchronized fun update(id: String, content: String, streaming: Boolean,
        blocks: List<AssistantBlock>, awaiting: Boolean) {
        val canonical = messages.value
        val index = canonical.indexOfLast { it.id == id }
        if (index < 0) { discard(id); return }
        val delta = StreamingDelta(content, mergeOverrides(blocks).toList(), awaiting)
        if (!streaming) {
            clearState(id)
            messages.update { list -> list.map { if (it.id == id) apply(it, delta) else it } }
            live.update { it - id }
            return
        }
        val owner = currentSession()
        var state = states[id]
        if (state == null || state.sessionId != owner) {
            clearState(id)
            state = Flush(owner)
            states[id] = state
        }
        val flush = state
        val previous = live.value[id]
        val statusChanged = previous != null && previous.toolBlocks.size == delta.toolBlocks.size &&
            delta.toolBlocks.indices.any { previous.toolBlocks[it].toolStatus != delta.toolBlocks[it].toolStatus }
        val structural = previous == null || previous.toolBlocks.size != delta.toolBlocks.size ||
            previous.isAwaitingModelResponse != awaiting || statusChanged
        val elapsed = System.currentTimeMillis() - flush.lastMs
        val gate = throttle(content.length)
        val newChunk = if (content.length > flush.lastLength) content.substring(flush.lastLength.coerceAtMost(content.length)) else ""
        val newline = content.length < 5_000 && newChunk.contains('\n') && content.length - flush.lastLength >= 50
        if (structural || elapsed >= gate || newline) {
            flush.job?.cancel()
            flush.job = null
            flush.pending = null
            publish(id, flush, delta)
        } else {
            flush.pending = delta
            if (flush.job == null) flush.job = scope.launch {
                delay((gate - elapsed).coerceAtLeast(16L))
                synchronized(this@ChatMessagePublication) {
                    if (states[id] !== flush) return@synchronized
                    val latest = flush.pending
                    flush.pending = null
                    flush.job = null
                    if (latest != null) publish(id, flush, latest)
                }
            }
        }
        if (canonical[index].error != null) messages.update { list ->
            list.map { if (it.id == id) it.copy(error = null) else it }
        }
    }

    private fun publish(id: String, state: Flush, delta: StreamingDelta) {
        if (states[id] !== state || currentSession() != state.sessionId || messages.value.none { it.id == id }) {
            discard(id)
            return
        }
        live.update { it + (id to delta.copy(toolBlocks = mergeOverrides(delta.toolBlocks).toList())) }
        state.lastMs = System.currentTimeMillis()
        state.lastLength = delta.content.length
    }

    @Synchronized fun clearState(id: String) { states.remove(id)?.job?.cancel() }
    @Synchronized fun discard(id: String) { clearState(id); live.update { it - id } }
    @Synchronized fun clear() {
        states.values.forEach { it.job?.cancel() }
        states.clear()
        live.value = emptyMap()
    }
    @Synchronized fun retain(ids: Set<String>) {
        states.keys.filter { it !in ids }.forEach(::clearState)
        live.update { map -> map.filterKeys { it in ids } }
    }

    @Synchronized fun patch(blocks: (List<AssistantBlock>) -> List<AssistantBlock>?) {
        live.update { map -> map.mapValues { (_, delta) ->
            blocks(delta.toolBlocks)?.let { delta.copy(toolBlocks = it) } ?: delta
        } }
        states.values.forEach { state -> state.pending?.let { delta ->
            blocks(delta.toolBlocks)?.let { state.pending = delta.copy(toolBlocks = it) }
        } }
    }

    @Synchronized fun flushAll() {
        val owner = currentSession()
        val latest = live.value.filterKeys { states[it]?.sessionId == owner }.toMutableMap()
        states.forEach { (id, state) ->
            if (state.sessionId == owner) state.pending?.let { latest[id] = it }
        }
        states.values.forEach { it.job?.cancel() }
        states.clear()
        if (latest.isNotEmpty()) messages.update { list ->
            list.map { message -> latest[message.id]?.let { apply(message, it) } ?: message }
        }
        live.value = emptyMap()
    }

    @Synchronized fun stamp(sessionId: String, id: String, entity: MessageEntity) {
        if (sessionId != currentSession()) return
        val usage = ChatTokenUsage.parse(entity.tokenUsage)
        val hit = messages.value.any { it.id == id }
        if (hit) messages.update { list -> list.map { message ->
            if (message.id == id) message.copy(tokenUsage = usage ?: message.tokenUsage, completedAt = entity.createdAt) else message
        } }
        AppLogger.info("ChatViewModel.Stream", "[UsageCapsule] live stamp ui=${id.take(20)} hit=$hit " +
            "ctx=${usage?.latestContextTokens ?: -1} db=${entity.id.take(8)}")
    }

    private fun apply(message: ChatMessage, delta: StreamingDelta): ChatMessage = message.copy(
        content = delta.content, isStreaming = false,
        toolBlocks = mergeOverrides(delta.toolBlocks).toList(), isAwaitingModelResponse = delta.isAwaitingModelResponse)

    private fun throttle(length: Int): Long = when {
        length < 500 -> 200L
        length < 2_000 -> 300L
        length < 32_000 -> 500L
        length < 64_000 -> 1_000L
        length < 128_000 -> 1_500L
        else -> 2_000L
    }
}
