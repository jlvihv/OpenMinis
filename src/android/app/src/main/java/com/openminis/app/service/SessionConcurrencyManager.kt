package com.openminis.app.service

import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.LinkedList

object SessionConcurrencyManager {
    const val MAX_CONCURRENT = 5
    private const val SLOT_WAIT_WARN_MS = 10_000L
    private val lock = Any()
    private val holders = LinkedHashMap<Lease, String>()
    private data class Waiter(val sessionId: String, val continuation: CancellableContinuation<Lease>)
    private val waitQueue = LinkedList<Waiter>()
    private val _runningSessions = MutableStateFlow<Set<String>>(emptySet())
    val runningSessions: StateFlow<Set<String>> = _runningSessions.asStateFlow()
    private val _suspendedSessions = MutableStateFlow<List<String>>(emptyList())
    val suspendedSessions: StateFlow<List<String>> = _suspendedSessions.asStateFlow()

    class Lease internal constructor(val sessionId: String) : AutoCloseable {
        override fun close() = release(this)
    }

    suspend fun acquireSlot(sessionId: String): Lease {
        val start = android.os.SystemClock.elapsedRealtime()
        val watchdog = CoroutineScope(currentCoroutineContext()).launch {
            while (true) {
                delay(SLOT_WAIT_WARN_MS)
                println("[T-STALL-DIAG] slot STILL-WAITING sid=$sessionId waitedMs=${android.os.SystemClock.elapsedRealtime() - start} ${diagSnapshot()}")
            }
        }
        try {
            return suspendCancellableCoroutine { continuation ->
                val waiter = Waiter(sessionId, continuation)
                synchronized(lock) {
                    waitQueue.add(waiter)
                    continuation.invokeOnCancellation {
                        synchronized(lock) {
                            waitQueue.remove(waiter)
                            publish()
                            grantWaiting()
                        }
                    }
                    grantWaiting()
                    publish()
                }
            }
        } finally {
            watchdog.cancel()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun grantWaiting() {
        while (holders.size < MAX_CONCURRENT) {
            val waiter = waitQueue.firstOrNull { it.sessionId !in holders.values } ?: break
            waitQueue.remove(waiter)
            if (!waiter.continuation.isActive) continue
            val lease = Lease(waiter.sessionId)
            holders[lease] = waiter.sessionId
            publish()
            println("[T-STALL-DIAG] slot ACQUIRED sid=${waiter.sessionId} ${diagSnapshot()}")
            waiter.continuation.resume(lease, onCancellation = { _, cancelledLease, _ -> cancelledLease.close() })
        }
    }

    private fun release(lease: Lease) {
        synchronized(lock) {
            if (holders.remove(lease) == null) return
            publish()
            println("[T-STALL-DIAG] slot RELEASED sid=${lease.sessionId} ${diagSnapshot()}")
            grantWaiting()
            publish()
        }
    }

    private fun publish() {
        _runningSessions.value = holders.values.toSet()
        _suspendedSessions.value = waitQueue.map { it.sessionId }
    }

    fun diagSnapshot(): String = synchronized(lock) {
        "running=${holders.size}/$MAX_CONCURRENT holders=${holders.values.joinToString(",")} suspended=${waitQueue.joinToString(",") { it.sessionId }}"
    }

    fun isSuspended(sessionId: String): Boolean = sessionId in _suspendedSessions.value
}
