package com.openminis.app.agent

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/** Joins the exact compaction started for this loop; stopping cannot leave a detached history writer. */
internal object AgentCompactionAwait {
    suspend fun run(start: ((Boolean) -> Unit) -> Job?): Boolean {
        val result = CompletableDeferred<Boolean>()
        var owned: Job? = null
        try {
            coroutineContext.ensureActive()
            owned = start { result.complete(it) }
            val succeeded = result.await()
            owned?.join()
            return succeeded
        } finally {
            if (!coroutineContext.isActive) withContext(NonCancellable) { owned?.cancelAndJoin() }
        }
    }
}
