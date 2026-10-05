package com.openminis.app.agent

import com.openminis.app.data.db.CompactMarkerEntity
import com.openminis.app.data.model.FallbackStrategy
import com.openminis.app.data.model.LLMError
import com.openminis.app.provider.LLMProvider
import com.openminis.app.logging.AppLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.coroutineContext

/** Owns the complete compaction task: request fallback, deadline, marker commit, cancellation and settlement. */
internal class AgentCompactionRuntime(
    private val coordinator: CompactionCoordinator,
    private val calls: AtomicInteger,
    private val timeoutMs: Long,
) {
    @Volatile var job: Job? = null
        private set
    @Volatile private var settledJob: Job? = null
    data class Input(val context: CompactionSummarizer.Context, val window: Int)
    sealed interface Notice {
        data object Empty : Notice
        data object TotalTimeout : Notice
        data object IdleTimeout : Notice
        data object Cancelled : Notice
        data class Failed(val error: Exception) : Notice
    }
    data class Settlement(val succeeded: Boolean, val timedOut: Boolean, val calls: Int, val visible: Boolean)

    fun <T> launch(
        scope: CoroutineScope,
        journal: AgentCompactionJournal,
        currentSession: () -> String,
        entryId: String?,
        previousSummary: String?,
        provider: () -> LLMProvider?,
        prepare: suspend () -> Input,
        candidates: (LLMProvider) -> List<T>,
        identity: (T) -> String,
        adopted: suspend (T) -> Unit,
        publish: (CompactMarkerEntity) -> Unit,
        restored: (CompactMarkerEntity?) -> Unit,
        notice: (Notice, Int) -> Unit,
        settled: (Settlement) -> Unit,
        successful: () -> Unit,
        finished: ((Boolean) -> Unit)?,
    ): Job {
        check(job?.isActive != true || job === settledJob) { "A compaction is already running" }
        val previous = job
        val owned = scope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
            val executing = coroutineContext[Job]
            var timedOut = false
            suspend fun notify(event: Notice) = withContext(NonCancellable + Dispatchers.Main) {
                if (currentSession() == journal.sessionId) notice(event, calls.get())
            }
            try {
                previous?.join()
                coroutineContext.ensureActive()
                calls.set(0)
                val tried = mutableSetOf<String>()
                entryId?.let(tried::add)
                val strategy = FallbackStrategy.default
                var attempt = 0
                var summary: String
                while (true) {
                    coroutineContext.ensureActive()
                    if (currentSession() != journal.sessionId) throw CancellationException("compaction branch changed")
                    attempt++
                    try {
                        summary = withTimeout(timeoutMs) {
                            val input = prepare()
                            coordinator.summarize(journal.plan.messages, previousSummary,
                                input.context, input.window, depth = 0)
                        }.trim()
                        break
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        val actual = unwrap(failure)
                        val error = actual as? LLMError
                        val fallback = error?.isFallbackable == true || error?.isHttpServerError == true ||
                            actual is LLMError.RateLimited || strategy == FallbackStrategy.always
                        val from = provider()
                        val next = if (fallback && from != null) candidates(from).firstOrNull { identity(it) !in tried } else null
                        if (next == null) throw failure
                        tried.add(identity(next))
                        withContext(Dispatchers.Main) {
                            if (currentSession() != journal.sessionId) throw CancellationException("compaction branch changed")
                            adopted(next)
                        }
                        AppLogger.warning("AgentCompactionRuntime", "attempt $attempt on ${from?.model?.displayName} failed (${actual.javaClass.simpleName}); switching candidate")
                        calls.set(0)
                    }
                }
                if (summary.isEmpty()) notify(Notice.Empty)
                else journal.commit(summary, publish, restored)
            } catch (failure: TimeoutCancellationException) {
                timedOut = true
                notify(Notice.TotalTimeout)
            } catch (failure: CompactIdleTimeoutException) {
                timedOut = true
                notify(Notice.IdleTimeout)
            } catch (failure: CancellationException) {
                if (journal.committed == null && executing?.isCancelled == true) notify(Notice.Cancelled)
                throw failure
            } catch (failure: Exception) {
                AppLogger.warning("AgentCompactionRuntime", "compaction failed: ${failure.javaClass.simpleName}")
                notify(Notice.Failed(failure))
            } finally {
                val success = journal.committed != null
                try {
                    withContext(NonCancellable + Dispatchers.Main) {
                        if (job === executing) {
                            // UI may accept a successor after settlement; it still joins this task's tail.
                            settledJob = executing
                            val visible = currentSession() == journal.sessionId
                            settled(Settlement(success, timedOut, calls.get(), visible))
                        }
                    }
                } finally { finished?.invoke(success) }
            }
            if (journal.committed != null) withContext(Dispatchers.Main) {
                if (job === executing && currentSession() == journal.sessionId) successful()
            }
        }
        job = owned
        owned.start()
        return owned
    }

    private fun unwrap(failure: Throwable): Throwable {
        var current: Throwable? = failure
        while (current != null) {
            if (current is LLMError) return current
            current = current.cause
        }
        return failure
    }
}
