package com.openminis.app.agent

import android.content.Context
import android.util.Base64
import com.openminis.app.agent.shell.OnDemandBash
import com.openminis.app.data.EnvVarRedactor
import com.openminis.app.sandbox.ExecutionCoordinator
import com.openminis.app.terminal.MinisUrlMarker
import com.openminis.app.tools.ToolExecutionResult
import kotlinx.coroutines.CancellationException
import org.json.JSONObject

/** Complete Bash tool execution. Presentation receives cleaned live lines and open-URL events only. */
internal class AgentBashExecutor(private val context: Context) {
    suspend fun execute(argsJson: String, sessionId: String, fsSessionId: String,
        line: (String) -> Unit, openUrl: (String) -> Unit): ToolExecutionResult {
        return try {
            val args = JSONObject(argsJson)
            require(args.opt("command") is String) { "command must be a string" }
            require(args.opt("tool_title") is String && args.getString("tool_title").isNotBlank()) {
                "tool_title is required and must be a non-blank string"
            }
            val script = args.getString("command")
            val title = args.getString("tool_title")
            if (script.isBlank()) return ToolExecutionResult("Error: 'command' is required", false, toolTitle = title)
            val timeout = if (!args.has("timeout") || args.isNull("timeout")) Long.MAX_VALUE else {
                val value = args.opt("timeout")
                require(value is Number) { "timeout must be a number" }
                val seconds = value.toDouble()
                require(seconds.isFinite() && seconds > 0 && seconds * 1000 <= Int.MAX_VALUE) {
                    "timeout must be positive and at most 2147483.647 seconds"
                }
                (seconds * 1000).toLong().coerceAtLeast(1)
            }
            val installer = OnDemandBash.Executor { command, budget ->
                ExecutionCoordinator.execute(sessionId = sessionId, command = command, timeout = budget,
                    fsSessionId = fsSessionId).exitCode
            }
            when (val availability = OnDemandBash.ensureBash(context, installer)) {
                is OnDemandBash.Outcome.Available -> Unit
                is OnDemandBash.Outcome.Unavailable -> return ToolExecutionResult(
                    "Error: Bash is unavailable: ${availability.reason}", false, toolTitle = title)
            }
            val result = ExecutionCoordinator.execute(sessionId = sessionId, fsSessionId = fsSessionId,
                captureBashOutput = true, command = wrap("cd /var/minis/workspace || exit;\n$script"),
                timeout = timeout, lineCallback = { raw ->
                    val (cleaned, urls) = MinisUrlMarker.extract(raw)
                    urls.forEach(openUrl)
                    if (cleaned.isNotEmpty() || raw.isEmpty()) line(cleaned)
                })
            val (cleaned, urls) = MinisUrlMarker.extract(result.output)
            urls.forEach(openUrl)
            val output = cleaned.takeUnless(String::isBlank) ?: "(no output)"
            // Coordinator owns bounded capture/archive redaction; this masks remaining model-facing metadata.
            val (redacted, hits) = EnvVarRedactor.redactIfEnabled(output)
            if (hits > 0) android.util.Log.i("EnvVarRedact", "bash: masked $hits env-var value(s) in tool result")
            return ToolExecutionResult(output = result.queueNote?.let { "$redacted\n\n$it" } ?: redacted,
                success = result.exitCode == 0, toolTitle = title, timedOut = result.timedOut,
                structuredContentJson = result.structuredContentJson)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { ToolExecutionResult("Error: ${failure.message}", false) }
    }

    private fun wrap(script: String): String {
        val normalized = if (script.endsWith("\n")) script else script + "\n"
        val encoded = Base64.encodeToString(normalized.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        return "( printf %s '$encoded' | base64 -d > /tmp/.minis-exec-\$\$.sh && " +
            "bash /tmp/.minis-exec-\$\$.sh; rc=\$?; rm -f /tmp/.minis-exec-\$\$.sh; exit \$rc )"
    }
}
