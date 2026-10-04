package com.openminis.app.agent

import com.openminis.app.data.model.FallbackStrategy
import com.openminis.app.data.model.LLMError
import com.openminis.app.provider.LLMProvider

internal class AgentProviderRecovery<T>(
    initial: List<T>,
    strategy: FallbackStrategy,
    entryId: String?,
    private val identity: (T) -> String,
    private val candidates: (LLMProvider) -> List<T>,
) {
    var strategy = strategy
        private set
    private val remaining = initial.toMutableList()
    private val tried = mutableSetOf<String>()
    private val reasons = mutableListOf<String>()
    val failureTrail: List<String> get() = reasons.toList()

    init { entryId?.let(tried::add) }

    fun switch(initial: List<T>, strategy: FallbackStrategy, entryId: String?) {
        remaining.clear()
        remaining.addAll(initial)
        this.strategy = strategy
        tried.clear()
        entryId?.let(tried::add)
    }

    fun succeeded(entryId: String?) {
        if (tried.size > 1) {
            tried.clear()
            entryId?.let(tried::add)
        }
    }

    fun allows(error: Throwable, contextOverflow: Boolean, rateLimited: Boolean, serverError: Boolean): Boolean =
        !contextOverflow && (rateLimited || serverError || (error as? LLMError)?.isFallbackable == true || strategy == FallbackStrategy.always)

    fun next(provider: LLMProvider, entryId: String?): T? {
        entryId?.let(tried::add)
        val next = remaining.removeFirstOrNull() ?: candidates(provider).firstOrNull { identity(it) !in tried }
        next?.let { tried.add(identity(it)) }
        return next
    }

    fun recordFailure(modelName: String, reason: String) { reasons.add("⚠️ $modelName: $reason") }
}
