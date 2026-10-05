package com.openminis.app.ui.chat

import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Bounded live Bash display. Delayed publication cannot overwrite a finished/cancelled tool. */
internal class ChatBashProjection(
    private val scope: CoroutineScope,
    private val toolId: String,
    private val blocks: MutableList<AssistantBlock>,
    private val active: () -> Boolean,
    private val publish: () -> Unit,
) {
    private var lastPublished = 0L
    private var tail: String? = null

    @Synchronized fun line(value: String) {
        if (!active()) return
        val index = blocks.indexOfFirst { it.id == toolId }
        if (index < 0) return
        val previous = tail ?: blocks[index].content
        val updated = if (previous.isEmpty()) value else "$previous\n$value"
        tail = updated.lines().takeLast(50).joinToString("\n")
        val now = SystemClock.elapsedRealtime()
        if (toolId.contains('/') || now - lastPublished < 100) return
        lastPublished = now
        scope.launch(Dispatchers.Main) {
            if (!active()) return@launch
            val slot = blocks.indexOfFirst { it.id == toolId }
            if (slot < 0 || blocks[slot].toolStatus != ToolBlockStatus.RUNNING) return@launch
            val content = synchronized(this@ChatBashProjection) { tail.orEmpty() }
            blocks[slot] = blocks[slot].copy(content = content)
            publish()
        }
    }
}
