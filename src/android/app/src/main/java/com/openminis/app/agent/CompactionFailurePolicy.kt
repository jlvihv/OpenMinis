package com.openminis.app.agent

import com.openminis.app.data.model.LLMError
import kotlinx.coroutines.CancellationException
import java.io.IOException

/** Smaller input helps only size-dependent failures; unknown provider refusals retain bounded splitting. */
internal object CompactionFailurePolicy {
    fun parameterRejection(error: LLMError.ProviderError): Boolean {
        val message = error.message?.lowercase() ?: return false
        val sizes = listOf("context_length", "context length", "too long", "too large", "maximum context",
            "max_tokens", "token limit", "exceeds", "reduce the length", "payload")
        if (sizes.any(message::contains)) return false
        val parameters = listOf("is not supported", "unsupported parameter", "unsupported value", "invalid_request_error",
            "invalid parameter", "unrecognized request argument", "does not support parameter", "extra_forbidden")
        return parameters.any(message::contains)
    }

    fun canSplit(error: Throwable): Boolean = when (error) {
        is CancellationException, is CompactIdleTimeoutException, is IOException -> false
        is LLMError.Cancelled, is LLMError.NetworkError, is LLMError.RateLimited,
        is LLMError.TransientError, is LLMError.InvalidApiKey -> false
        is LLMError.ProviderError -> !parameterRejection(error)
        else -> true
    }
}
