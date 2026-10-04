package com.openminis.app.tools

import org.json.JSONObject

internal object PiBashOutput {
    const val SCRIPT_MAX_BYTES = 1024 * 1024
    data class Result(val text: String, val structured: String?)
    fun format(capture: PiBashCapture, exitCode: Int, durationMs: Long, fullPath: String?, executionFailed: Boolean): Result {
        val tail = PiToolText.tail(capture.tailText).copy(truncated = capture.modelTruncated,
            totalLines = capture.lines.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        val truncated = capture.bytes > SCRIPT_MAX_BYTES
        val scriptOutput = if (!truncated) PiToolText.utf8Prefix(capture.prefixText, SCRIPT_MAX_BYTES) else {
            val head = PiToolText.utf8Prefix(capture.prefixText, SCRIPT_MAX_BYTES / 2)
            val end = PiToolText.utf8Suffix(capture.scriptTailText, SCRIPT_MAX_BYTES / 2)
            val omitted = capture.bytes - head.toByteArray(Charsets.UTF_8).size - end.toByteArray(Charsets.UTF_8).size
            "$head\n\n[... $omitted bytes omitted ...]\n\n$end"
        }
        return render(tail, scriptOutput, truncated, exitCode, durationMs, fullPath, executionFailed)
    }
    private fun render(tail: PiToolText.Truncated, scriptOutput: String, truncated: Boolean,
        exitCode: Int, durationMs: Long, fullPath: String?, executionFailed: Boolean): Result {
        var text = tail.content.ifEmpty { "(no output)" }
        if (tail.truncated) text += "\n\n[Showing last ${tail.outputLines} of ${tail.totalLines} lines (50KB/2000-line limit). " +
            (fullPath?.let { "Full output: $it" } ?: "Could not save full output") + "]"
        if (exitCode != 0 && !executionFailed) text += "\n\nCommand exited with code $exitCode"
        val structured = if (executionFailed) null else JSONObject().put("output", scriptOutput).put("truncated", truncated)
            .put("exit_code", exitCode).put("wall_time_seconds", kotlin.math.round(durationMs / 100.0) / 10.0)
            .also { if (truncated && fullPath != null) it.put("full_output_path", fullPath) }.toString()
        return Result(text, structured)
    }
}
