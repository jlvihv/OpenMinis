package com.openminis.app.data.model

import org.json.JSONObject

/** Persisted session usage, independent of UI state and provider-specific token schemas. */
data class SessionTokenStats(
    val input: Long,
    val output: Long,
    val cacheRead: Long,
    val cacheWrite: Long,
    val context: Int,
    val loopCount: Int,
    val streamMs: Long = 0L,
    val streamOutput: Long = 0L,
    val latestInput: Long = 0L,
    val latestCacheRead: Long = 0L,
    val auxiliaryRequests: Int = 0,
) {
    val latestCacheHitRate: Double?
        get() = if (latestInput > 0 && cacheRead > 0) latestCacheRead.toDouble() / latestInput * 100 else null
    val cacheHitRate: Double?
        get() = (input + cacheRead + cacheWrite).takeIf { it > 0 && cacheRead > 0 }
            ?.let { cacheRead.toDouble() / it * 100 }
    val outputTokensPerSecond: Double?
        get() = if (streamMs > 0 && streamOutput > 0) streamOutput.toDouble() / (streamMs / 1000.0) else null

    companion object {
        /** Records must be in branch sort order, not insertion time. Invalid rows are skipped. */
        fun fromUsageRecords(records: List<String>, loopCount: Int = 0): SessionTokenStats {
            var input = 0L; var output = 0L; var read = 0L; var write = 0L
            var context = 0; var ms = 0L; var streamed = 0L
            var latestInput = 0L; var latestRead = 0L; var auxiliary = 0
            for (json in records) {
                val row = try { JSONObject(json) } catch (_: Exception) { continue }
                val fresh = row.optLong("inputTokens", 0L)
                val cached = row.optLong("cacheReadTokens", 0L)
                val created = row.optLong("cacheCreationTokens", 0L)
                val out = row.optLong("outputTokens", 0L)
                input += fresh; output += out; read += cached; write += created
                val total = fresh + cached + created
                if (total > 0) { latestInput = total; latestRead = cached }
                if (RequestUsageRecord.isConversation(row)) {
                    row.optInt("latestContextTokens", 0).takeIf { it > 0 }?.let { context = it }
                } else if (total > 0) auxiliary++
                val duration = row.optLong("streamMs", 0L)
                // Keep time and output matched, excluding legacy records with no measured duration.
                if (duration > 0 && out > 0) { ms += duration; streamed += out }
            }
            return SessionTokenStats(input, output, read, write, context, loopCount, ms, streamed, latestInput, latestRead, auxiliary)
        }
    }
}
