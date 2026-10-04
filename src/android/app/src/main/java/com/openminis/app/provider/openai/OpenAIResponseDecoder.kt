package com.openminis.app.provider.openai

import com.openminis.app.data.model.LLMError
import com.openminis.app.data.model.LLMUsage
import com.openminis.app.provider.safeOptString
import org.json.JSONObject
import java.io.IOException

/** Wire receipts -> normalized domain values; independent of HTTP calls and streaming lifetime. */
internal object OpenAIResponseDecoder {
    fun chatUsage(usage: JSONObject): LLMUsage = normalizedUsage(
        usage.optInt("prompt_tokens", 0), usage.optInt("completion_tokens", 0),
        usage.optJSONObject("prompt_tokens_details")?.optInt("cached_tokens")?.takeIf { it > 0 }
            ?: usage.optInt("prompt_cache_hit_tokens", 0).takeIf { it > 0 },
    )

    fun responsesUsage(usage: JSONObject): LLMUsage = normalizedUsage(
        usage.optInt("input_tokens", 0), usage.optInt("output_tokens", 0),
        usage.optJSONObject("input_tokens_details")?.optInt("cached_tokens")?.takeIf { it > 0 },
    )

    private fun normalizedUsage(total: Int, output: Int, cached: Int?): LLMUsage = LLMUsage(
        // Both protocols count cached tokens inside full input. Domain input is fresh-only.
        // Preserve the existing nonnegative guard for inconsistent upstream receipts.
        inputTokens = cached?.let { (total - it).takeIf { fresh -> fresh >= 0 } } ?: total,
        outputTokens = output, cacheReadInputTokens = cached, latestContextTokens = total,
    )

    fun httpError(status: Int, body: String): LLMError {
        if (status == 401 || status == 403) return LLMError.InvalidApiKey()
        if (status == 429) return LLMError.RateLimited()
        val message = try {
            val error = JSONObject(body).optJSONObject("error")
            "[$status] ${error?.safeOptString("message", "") ?: body}"
        } catch (_: Exception) { "HTTP $status: ${body.take(500)}" }
        if (status in setOf(500, 502, 503, 504, 529)) {
            if (status == 503 && (body.contains("no_available_providers") || body.contains("model_not_found")))
                return LLMError.ProviderError(message, httpStatus = status)
            return LLMError.TransientError(message, httpStatus = status)
        }
        return LLMError.ProviderError(message, httpStatus = status)
    }

    fun transportError(error: Throwable): LLMError = when (error) {
        is LLMError -> error
        is IOException -> LLMError.NetworkError(error)
        else -> LLMError.Unknown(error)
    }
}
