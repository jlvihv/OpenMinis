package com.openminis.app.agent

/** Prompt delivery rules shared by in-loop insertion and idle queue drain. */
internal object AgentPromptBatching {
    fun <T> nextInsert(queue: List<T>, scheduled: (T) -> Boolean, user: (T) -> Boolean): List<T> {
        val first = queue.firstOrNull { scheduled(it) || user(it) } ?: return emptyList()
        return if (scheduled(first)) listOf(first) else queue.filterNot(scheduled)
    }

    fun <T> nextDrain(queue: List<T>, scheduled: (T) -> Boolean): List<T> {
        val first = queue.firstOrNull() ?: return emptyList()
        return if (scheduled(first)) listOf(first) else queue.filterNot(scheduled)
    }
}

internal object AgentToolBoundary {
    data class Prompt(val id: String, val scheduled: Boolean, val user: Boolean, val delegationCallback: Boolean)
    sealed interface Plan {
        data class Stop(val dropIds: List<String>) : Plan
        data class Insert(val ids: List<String>, val scheduled: Boolean) : Plan
        data object Continue : Plan
    }

    fun decide(delegationMuted: Boolean, queue: List<Prompt>): Plan {
        if (delegationMuted) return Plan.Stop(queue.filter { !it.user && !it.scheduled && it.delegationCallback }.map { it.id })
        val batch = AgentPromptBatching.nextInsert(queue, Prompt::scheduled, Prompt::user)
        return if (batch.isEmpty()) Plan.Continue else Plan.Insert(batch.map { it.id }, batch.singleOrNull()?.scheduled == true)
    }
}
