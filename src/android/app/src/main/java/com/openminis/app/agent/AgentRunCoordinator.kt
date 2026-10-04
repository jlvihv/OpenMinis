package com.openminis.app.agent

import com.openminis.app.logging.AppLogger
import com.openminis.app.service.SessionActivityTracker
import com.openminis.app.service.SessionConcurrencyManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

internal class AgentRunCoordinator {
    @Volatile var job: Job? = null
        private set

    fun launch(
        scope: CoroutineScope,
        sessionId: String,
        label: String,
        bypassSlot: Boolean,
        markFailure: Boolean,
        title: () -> String?,
        stop: () -> Unit,
        beforeInactive: () -> Unit,
        failed: (Exception) -> Unit,
        settled: () -> Unit,
        body: suspend () -> Unit,
    ): Job {
        val previous = job
        val launched = scope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
            try {
                previous?.join()
                run(sessionId, label, bypassSlot, markFailure, title, stop, beforeInactive, failed, body)
            } finally {
                if (job === coroutineContext[Job]) settled()
            }
        }
        job = launched
        launched.start()
        return launched
    }

    private suspend fun run(
        sessionId: String,
        label: String,
        bypassSlot: Boolean = false,
        markFailure: Boolean = true,
        title: () -> String?,
        stop: () -> Unit,
        beforeInactive: () -> Unit,
        failed: (Exception) -> Unit,
        body: suspend () -> Unit,
    ) {
        AppLogger.info(TAG, "$label run ENTER sid=$sessionId")
        var lease: SessionConcurrencyManager.Lease? = null
        var active = false
        try {
            if (!bypassSlot) {
                println("[T-STALL-DIAG] $label PRE-ACQUIRE sid=$sessionId ${SessionConcurrencyManager.diagSnapshot()}")
                lease = SessionConcurrencyManager.acquireSlot(sessionId)
            }
            SessionActivityTracker.setActive(sessionId, onStop = stop, sessionTitle = title())
            active = true
            try {
                body()
            } catch (_: CancellationException) {
                AppLogger.info(TAG, "$label run CANCELLED sid=$sessionId")
            } catch (error: Exception) {
                AppLogger.error(TAG, "$label run EXCEPTION ${error.javaClass.simpleName}: ${error.message}")
                if (markFailure) SessionActivityTracker.markStreamError(sessionId)
                failed(error)
            }
        } catch (_: CancellationException) {
            AppLogger.info(TAG, "$label CANCELLED waiting for slot sid=$sessionId")
        } finally {
            try {
                if (active) try { beforeInactive() } finally { SessionActivityTracker.setInactive(sessionId) }
            } finally {
                lease?.close()
            }
            AppLogger.info(TAG, "$label run EXIT sid=$sessionId")
        }
    }

    private companion object { const val TAG = "ChatViewModel.Stream" }
}
