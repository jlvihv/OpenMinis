package com.openminis.app.agent

import com.openminis.app.logging.AppLogger
import com.openminis.app.service.SessionActivityTracker
import com.openminis.app.service.SessionConcurrencyManager
import kotlinx.coroutines.*

internal class AgentRunCoordinator {
    @Volatile var job: Job? = null
        private set

    fun launch(scope: CoroutineScope, sessionId: String, label: String, bypassSlot: Boolean,
        markFailure: Boolean, title: () -> String?, stop: () -> Unit, beforeInactive: () -> Unit,
        failed: (Exception) -> Unit, settled: () -> Unit,
        prepareSession: suspend () -> String = { sessionId }, prepare: suspend () -> Unit = {},
        body: suspend () -> Unit): Job {
        val previous = job
        val launched = scope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
            val ownedJob = coroutineContext[Job]
            var prepared = false
            try {
                previous?.join()
                // Preparation belongs to the synchronously claimed run, even before a draft has a real id.
                val owner = withContext(Dispatchers.Main) { prepareSession() }
                currentCoroutineContext().ensureActive()
                withContext(Dispatchers.Main) { prepare() }
                currentCoroutineContext().ensureActive()
                prepared = true
                run(owner, label, bypassSlot, markFailure, title, stop, beforeInactive, failed, body)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (!prepared) withContext(NonCancellable + Dispatchers.Main) { failed(failure) }
                else throw failure
            } finally {
                withContext(NonCancellable + Dispatchers.Main) {
                    if (job === ownedJob) settled()
                }
            }
        }
        job = launched
        launched.start()
        return launched
    }

    private suspend fun run(sessionId: String, label: String, bypassSlot: Boolean, markFailure: Boolean,
        title: () -> String?, stop: () -> Unit, beforeInactive: () -> Unit,
        failed: (Exception) -> Unit, body: suspend () -> Unit) {
        AppLogger.info(TAG, "$label run ENTER sid=$sessionId")
        var lease: SessionConcurrencyManager.Lease? = null
        var active = false
        try {
            if (!bypassSlot) lease = SessionConcurrencyManager.acquireSlot(sessionId)
            withContext(Dispatchers.Main) {
                SessionActivityTracker.setActive(sessionId, onStop = stop, sessionTitle = title())
                active = true
            }
            try { body() }
            catch (_: CancellationException) { AppLogger.info(TAG, "$label run CANCELLED sid=$sessionId") }
            catch (error: Exception) {
                AppLogger.error(TAG, "$label run EXCEPTION ${error.javaClass.simpleName}: ${error.message}")
                if (markFailure) SessionActivityTracker.markStreamError(sessionId)
                withContext(NonCancellable + Dispatchers.Main) { failed(error) }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            if (!active) withContext(NonCancellable + Dispatchers.Main) { failed(failure) }
            else throw failure
        } finally {
            withContext(NonCancellable + Dispatchers.Main) {
                if (active) try { beforeInactive() } finally { SessionActivityTracker.setInactive(sessionId) }
                lease?.close()
            }
            AppLogger.info(TAG, "$label run EXIT sid=$sessionId")
        }
    }
    private companion object { const val TAG = "ChatViewModel.Stream" }
}
