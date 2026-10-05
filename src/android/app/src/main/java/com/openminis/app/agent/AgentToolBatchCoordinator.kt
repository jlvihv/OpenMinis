package com.openminis.app.agent

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.service.SessionActivityTracker
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

internal object AgentToolBatchCoordinator {
    suspend fun <T> execute(calls: List<T>, concurrency: Int,
        completed: (List<AgentContentPart>) -> Unit = {},
        operation: suspend (T) -> List<AgentContentPart>): List<AgentContentPart> = coroutineScope {
        val slots = Semaphore(concurrency)
        calls.map { call ->
            async {
                slots.withPermit {
                    SessionActivityTracker.toolStarted()
                    try { operation(call).also(completed) } finally { SessionActivityTracker.toolFinished() }
                }
            }
        }.awaitAll().flatten()
    }
}
