package com.openminis.app.agent

import com.openminis.app.data.ContextOverflowGuard
import com.openminis.app.data.model.LLMError
import com.openminis.app.provider.LLMProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

/** Run-owned request/recovery sequencing. Callbacks provide effects, never recovery decisions. */
internal class AgentRequestCycle<T>(
    private val providers: AgentProviderRecovery<T>,
    private val provider: () -> LLMProvider,
    private val entryId: () -> String?,
    private val candidateProvider: (T) -> LLMProvider,
) {
    private var overflowHealAttempted = false

    suspend fun run(
        attempt: suspend () -> Unit,
        retrying: suspend (Throwable, AgentRequestRecovery.Retry) -> Unit,
        countdown: (Int) -> Unit,
        retryCancelled: () -> Unit,
        clearRetry: suspend () -> Unit,
        rollback: suspend (Boolean) -> Unit,
        healOverflow: suspend (LLMError.ProviderError) -> Boolean,
        adopted: suspend (T, String, Boolean) -> Unit,
        skippedCandidates: () -> List<String>,
    ) {
        val recovery = AgentRequestRecovery()
        while (true) {
            coroutineContext.ensureActive()
            try {
                attempt()
                providers.succeeded(entryId())
                clearRetry()
                return
            } catch (failure: Exception) {
                coroutineContext.ensureActive()
                if (failure is CancellationException && failure.cause == null) throw failure
                val actual = unwrap(failure)
                val retry = recovery.nextRetry(actual)
                if (retry != null) {
                    retrying(actual, retry)
                    recovery.countdown(retry, countdown, retryCancelled)
                    clearRetry()
                    rollback(true)
                    continue
                }
                clearRetry()
                val overflow = actual as? LLMError.ProviderError
                val isOverflow = ContextOverflowGuard.isContextOverflow(overflow?.httpStatus, overflow?.detail)
                if (isOverflow && overflow != null && !overflowHealAttempted) {
                    overflowHealAttempted = true
                    if (healOverflow(overflow)) {
                        rollback(false)
                        continue
                    }
                }
                val rateLimited = actual is LLMError.RateLimited
                val shouldFallback = providers.allows(actual, isOverflow, rateLimited, (actual as? LLMError)?.isHttpServerError == true)
                val candidate = if (shouldFallback) providers.next(provider(), entryId()) else null
                if (candidate != null) {
                    val next = candidateProvider(candidate)
                    val old = provider()
                    val reason = when {
                        rateLimited -> "Rate limited"
                        actual is LLMError.ProviderError -> actual.detail
                        else -> actual.message ?: "Error"
                    }
                    providers.recordFailure(old.model.displayName, reason)
                    recovery.reset()
                    adopted(candidate, reason, next.model.id != old.model.id)
                    rollback(false)
                    continue
                }
                if (shouldFallback) {
                    val skipped = skippedCandidates()
                    if (providers.failureTrail.isNotEmpty() || skipped.isNotEmpty()) {
                        val trail = (providers.failureTrail + skipped).joinToString("\n")
                        throw LLMError.ProviderError("$trail\n${actual.message ?: actual.toString()}")
                    }
                }
                throw actual
            }
        }
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
