package com.openminis.app.agent

internal class AgentToolInputTrace(private val capacity: Int) {
    private val inputs = mutableMapOf<String, MutableList<String>>()

    init { require(capacity > 0) }

    @Synchronized fun append(id: String, accumulated: String) {
        val ring = inputs.getOrPut(id) { mutableListOf() }
        ring.add(accumulated)
        if (ring.size > capacity) ring.subList(0, ring.size - capacity).clear()
    }

    @Synchronized operator fun get(id: String): List<String>? = inputs[id]?.toList()
    @Synchronized fun remove(id: String): List<String>? = inputs.remove(id)?.toList()
    @Synchronized fun clear() = inputs.clear()
}
