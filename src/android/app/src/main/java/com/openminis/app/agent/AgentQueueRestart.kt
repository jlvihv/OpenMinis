package com.openminis.app.agent

import kotlinx.coroutines.*

/** One owned grace/join/admission gate; UI supplies current readiness and a synchronous run launcher. */
internal class AgentQueueRestart(private val scope: CoroutineScope) {
    data class Effects(val currentSession: () -> String, val queued: () -> Boolean, val busy: () -> Boolean,
        val compacting: () -> Boolean, val providerAvailable: () -> Boolean,
        val unavailable: () -> Unit, val launch: () -> Unit)
    private var pending: Job? = null
    private var owner: String? = null
    fun kick(session: String, previous: Job?, effects: Effects) {
        if (!scope.isActive || (pending?.isActive == true && owner == session)) return
        pending?.cancel()
        owner = session
        val worker = scope.launch(Dispatchers.Main, start = CoroutineStart.LAZY) {
            delay(200)
            previous?.join()
            if (effects.currentSession() != session || !effects.queued() || effects.busy() || effects.compacting()) return@launch
            if (!effects.providerAvailable()) { effects.unavailable(); return@launch }
            effects.launch()
        }
        pending = worker
        worker.start()
    }
}
