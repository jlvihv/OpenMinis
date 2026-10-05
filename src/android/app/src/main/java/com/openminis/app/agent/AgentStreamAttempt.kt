package com.openminis.app.agent

import android.os.SystemClock
import com.openminis.app.data.model.LLMStreamChunk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

internal class AgentStreamAttempt(
    private val sessionId: String,
    private val turn: Int,
    private val providerName: String,
    private val historySize: Int,
    private val content: AgentTurnContent,
    private val inputTrace: AgentToolInputTrace,
    private val monolithic: Boolean,
    private val firstChunk: () -> Unit,
    private val duration: (Long) -> Unit,
) {
    suspend fun collect(create: suspend () -> Flow<LLMStreamChunk>,
        consume: suspend (LLMStreamChunk) -> Unit,
        completed: suspend () -> Unit): Unit = coroutineScope {
        val started = SystemClock.elapsedRealtime()
        val firstSeen = AtomicBoolean(false)
        println("[T-STALL-DIAG] stream REQUEST-OUT sid=$sessionId turn=$turn provider=$providerName historySize=$historySize")
        val watchdog = launch(Dispatchers.IO) {
            var waited = 0L
            while (!firstSeen.get()) {
                delay(10_000L)
                if (firstSeen.get()) break
                waited += 10_000L
                println("[T-STALL-DIAG] stream NO-FIRST-CHUNK sid=$sessionId turn=$turn waitedMs=$waited provider=$providerName — request sent, provider has returned NOTHING (not even message_start)")
            }
        }
        try {
            create().collect { chunk ->
                if (firstSeen.compareAndSet(false, true)) {
                    firstChunk()
                    println("[T-STALL-DIAG] stream FIRST-CHUNK sid=$sessionId turn=$turn ttfbMs=${SystemClock.elapsedRealtime() - started} kind=${chunk.javaClass.simpleName}")
                }
                val observed = content.accept(chunk, monolithic)
                if (observed is LLMStreamChunk.ToolInputDelta) inputTrace.append(observed.id, observed.accumulated)
                consume(observed)
            }
            completed()
        } finally {
            watchdog.cancel()
            val elapsed = SystemClock.elapsedRealtime() - started
            if (!firstSeen.get()) println("[T-STALL-DIAG] stream ENDED-WITHOUT-CHUNK sid=$sessionId turn=$turn elapsedMs=$elapsed")
            duration(elapsed)
        }
    }
}
