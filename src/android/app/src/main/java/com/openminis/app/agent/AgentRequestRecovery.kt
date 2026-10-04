package com.openminis.app.agent

import com.openminis.app.data.model.LLMError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

internal class AgentRequestRecovery {
    data class Retry(val attempt: Int, val limit: Int, val delaySeconds: Int)
    private var attempts = 0
    private val delays = intArrayOf(1, 2, 4)

    fun nextRetry(error: Throwable): Retry? {
        if (error !is LLMError.NetworkError && error !is LLMError.TransientError) return null
        if (attempts >= delays.size) return null
        val seconds = delays[attempts++]
        return Retry(attempts, delays.size, seconds)
    }

    suspend fun countdown(retry: Retry, remaining: (Int) -> Unit, cancelled: () -> Unit) {
        try {
            coroutineContext.ensureActive()
            for (seconds in retry.delaySeconds downTo 1) {
                remaining(seconds)
                delay(1000)
            }
        } catch (error: CancellationException) {
            cancelled()
            throw error
        } finally { remaining(0) }
    }

    fun reset() { attempts = 0 }
}
