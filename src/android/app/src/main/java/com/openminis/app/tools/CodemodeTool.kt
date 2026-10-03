package com.openminis.app.tools

import android.content.Context
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.util.Locale
import kotlin.math.ceil

/** Pi codemode contract at a276dabe5; unmodified prelude hosted by native QuickJS-NG. */
object CodemodeTool {
    const val NAME = "codemode"
    const val PREFS = "agent_settings"
    const val MODE_KEY = "codemode.mode"
    const val INLINE_BUDGET_KEY = "codemode.inlineBudget"
    const val GUIDELINE = "Use codemode to batch independent tool calls (Promise.allSettled), chain them, or filter large output, instead of many separate calls."
    private const val SAFE_INTEGER = 9_007_199_254_740_991L
    val SOURCE_GRAMMAR = """
start: options_source | plain_source
options_source: OPTIONS_LINE NEWLINE SOURCE
plain_source: SOURCE

OPTIONS_LINE: /[ \t]*\/\/ @options:[^\r\n]*/
NEWLINE: /\r?\n/
SOURCE: /[\s\S]+/
""".trimIndent()

    data class Source(val code: String, val maxOutputTokens: Long = 10_000, val timeoutMs: Long? = null)
    data class Call(val id: String, val name: String, val args: String, @Volatile var status: String = "running", @Volatile var durationMs: Long? = null, @Volatile var error: String? = null)
    data class Output(val type: String, val text: String? = null, val data: String? = null, val mimeType: String? = null)
    data class Result(val output: String, val success: Boolean, val images: List<Output>, val storeWrites: String?, val calls: List<Call>, val fullOutputPath: String? = null)

    fun mode(context: Context): String = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getString(MODE_KEY, "on").let { if (it in listOf("off", "on", "only")) it!! else "on" }

    fun identifier(name: String): String {
        if (name.isEmpty()) return "_"
        return name.mapIndexed { index, char ->
            val valid = char in 'a'..'z' || char in 'A'..'Z' || char == '_' || char == '$' || (index > 0 && char in '0'..'9')
            if (valid) char else '_'
        }.joinToString("")
    }

    fun parseSource(input: String): Source {
        require(input.isNotBlank()) { "Expected JavaScript source text (non-empty). Provide JS only, optionally with a first line // @options: {\"max_output_tokens\": 1000}." }
        val newline = input.indexOf('\n')
        val first = (if (newline < 0) input else input.substring(0, newline)).removeSuffix("\r").trimStart()
        if (!first.startsWith("// @options:")) return Source(input)
        val code = if (newline < 0) "" else input.substring(newline)
        require(code.isNotBlank()) { "The @options line must be followed by JavaScript source on subsequent lines" }
        val options = try {
            val json = kotlinx.serialization.json.Json.parseToJsonElement(first.substringAfter("// @options:").trim())
            if (json is kotlinx.serialization.json.JsonObject) JSONObject(json.toString()) else null
        }
            catch (e: Exception) { throw IllegalArgumentException("@options must be valid JSON: ${e.message}") }
        require(options != null) { "@options must be a JSON object with supported fields max_output_tokens and timeout_ms" }
        for (key in options.keys()) require(key in listOf("max_output_tokens", "timeout_ms")) { "@options only supports max_output_tokens and timeout_ms; got $key" }
        fun integer(key: String, default: Long?): Long? {
            if (!options.has(key)) return default
            val value = options.get(key)
            require(value is Number && value.toDouble().isFinite() && value.toDouble() >= 0 &&
                value.toDouble() <= SAFE_INTEGER && value.toDouble() == value.toLong().toDouble()) {
                "@options field $key must be a non-negative safe integer"
            }
            return value.toLong()
        }
        val timeout = integer("timeout_ms", null)
        require(timeout == null || timeout in 1..2_147_483_647L) { "@options field timeout_ms must be a positive integer up to 2147483647" }
        return Source(code, integer("max_output_tokens", 10_000)!!, timeout)
    }

    fun declaration(tool: AgentToolDefinition): String {
        val fields = tool.parameters.map { (name, p) ->
            val type = p.enumValues?.joinToString(" | ") { JSONObject.quote(it) } ?: when (p.type) {
                "integer", "number" -> "number"
                "boolean" -> "boolean"
                "string" -> "string"
                else -> "unknown"
            }
            "  /** ${p.description.replace("*/", "* /")} */\n  ${JSONObject.quote(name)}${if (name in tool.required) "" else "?"}: $type;"
        }.joinToString("\n")
        return "${tool.description}\ndeclare const tools: { ${identifier(tool.name)}(args: {\n$fields\n}): Promise<string>; };"
    }

    fun definition(tools: List<AgentToolDefinition>, inlineBudget: Int = 3000): AgentToolDefinition {
        val sections = mutableListOf("Run JavaScript that calls other tools. The input is raw JavaScript (not JSON, no code fence), run as an async function body in a QuickJS sandbox: top-level await and return work. No Node, file system, network, or timers.\n" +
            "- await tools.<name>({ ...args }) resolves to a string and rejects with an Error on failure. Calls still running when the script ends are cancelled.\n" +
            "- Optional first line: // @options: {\"max_output_tokens\": 10000, \"timeout_ms\": 60000}\n" +
            "Globals:\n- text(value), image(dataUrlOrImageBlock), console.log(...), and top-level return add output; exit() ends the script.\n" +
            "- store(key, value) and load(key) keep JSON values across codemode calls.\n" +
            "- ALL_TOOLS, searchTools(query, { limit?, namespace? }), describeTool(name), describeNamespace(name): find unlisted tools.")
        var remaining = inlineBudget
        val declarations = tools.filter { it.name != NAME }.map { it.name to declaration(it) }
        val selected = mutableSetOf<String>()
        for ((name, text) in declarations.sortedBy { it.second.length }) {
            val cost = (text.length + 3) / 4
            if (cost > remaining) break
            remaining -= cost
            selected.add(name)
        }
        for ((name, text) in declarations) if (name in selected) sections.add("### `${identifier(name)}`\n$text")
        return AgentToolDefinition(NAME, sections.joinToString("\n\n"), mapOf("code" to AgentToolParam("string", "Raw JavaScript source.")), listOf("code"))
    }

    /** Stable BM25 ranking, matching pi's discovery default of eight results. */
    fun search(tools: List<AgentToolDefinition>, query: String, limit: Int): JSONArray {
        require(limit > 0) { "searchTools() limit must be a positive integer" }
        fun tokens(text: String): List<String> {
            val stopWords = setOf("a", "an", "and", "are", "as", "at", "be", "by", "for", "from", "in", "is", "it", "of", "on", "or", "that", "the", "this", "to", "with")
            return text.replace(Regex("([a-z0-9])([A-Z])"), "$1 $2")
                .replace(Regex("([A-Z]+)([A-Z][a-z])"), "$1 $2").lowercase(Locale.ROOT)
                .split(Regex("[^a-z0-9]+"))
                .filter { it.isNotEmpty() && it !in stopWords }.map { term ->
                    when {
                        term.length > 4 && term.endsWith("ies") -> term.dropLast(3) + "y"
                        term.length > 4 && Regex("(ches|shes|sses|xes|zes)$").containsMatchIn(term) -> term.dropLast(2)
                        term.length > 3 && term.endsWith("s") && !term.endsWith("ss") -> term.dropLast(1)
                        else -> term
                    }
                }
        }
        val docs = tools.map { tool -> tokens(listOf(tool.name, tool.name.replace('_', ' '), tool.description,
            tool.parameters.entries.joinToString(" ") { (name, param) -> "$name ${param.description}" }).joinToString(" ")) }
        val average = docs.map { it.size }.average().coerceAtLeast(1.0)
        val terms = tokens(query).distinct()
        val scores = docs.mapIndexed { index, doc ->
            val frequencies = doc.groupingBy { it }.eachCount()
            val score = terms.sumOf { term ->
                val count = frequencies[term] ?: 0
                val df = docs.count { term in it }
                val idf = kotlin.math.ln(1 + (docs.size - df + 0.5) / (df + 0.5))
                idf * count * 2.2 / (count + 1.2 * (0.25 + 0.75 * doc.size / average))
            }
            index to score
        }
        return JSONArray(scores.filter { it.second > 0 }.sortedByDescending { it.second }.take(limit).map {
            JSONObject().put("name", identifier(tools[it.first].name)).put("description", declaration(tools[it.first]))
        })
    }

    suspend fun execute(context: Context, source: String, toolCallId: String, tools: List<AgentToolDefinition>,
        store: JSONObject, spill: suspend (String) -> String,
        invoke: suspend (String, String, String) -> ToolExecutionResult,
        onUpdate: (List<Call>) -> Unit = {},
        appendEntry: suspend (String) -> Unit = {},
    ): Result = executeWithTransport(CodemodeSandbox(context), source, toolCallId, tools, store, spill, invoke, onUpdate, appendEntry)

    internal suspend fun executeWithTransport(sandbox: CodemodeTransport, source: String, toolCallId: String,
        tools: List<AgentToolDefinition>, store: JSONObject, spill: suspend (String) -> String,
        invoke: suspend (String, String, String) -> ToolExecutionResult, onUpdate: (List<Call>) -> Unit = {},
        appendEntry: suspend (String) -> Unit = {},
    ): Result {
        val started = System.nanoTime()
        val parsed = try { parseSource(source) } catch (failure: Exception) { sandbox.close(); throw failure }
        val callable = tools.filter { it.name != NAME }
        val calls = java.util.concurrent.CopyOnWriteArrayList<Call>()
        fun publish() = onUpdate(calls.map { it.copy() })
        val output = mutableListOf<Output>()
        var writes: String? = null
        var success = false
        var error: String? = null
        try {
            val run: suspend () -> Unit = {
                coroutineScope {
                    val jobs = mutableListOf<kotlinx.coroutines.Job>()
                    try {
                        val data = JSONObject().put("code", parsed.code).put("store", store)
                            .put("tools", JSONArray(callable.map { JSONObject().put("name", it.name).put("jsName", identifier(it.name)).put("description", declaration(it)) }))
                            .put("globals", JSONArray(listOf("searchTools", "describeTool", "describeNamespace").map { JSONObject().put("name", it).put("spread", true) }))
                        sandbox.start(data)
                        while (true) {
                            val event = sandbox.receive()
                            when (event.getString("type")) {
                                "output" -> {
                                    val item = event.getJSONObject("item")
                                    output.add(Output(item.getString("type"), item.optString("text").takeIf { item.has("text") },
                                        item.optString("data").takeIf { item.has("data") }, item.optString("mimeType").takeIf { item.has("mimeType") }))
                                }
                                "call" -> {
                                    val id = event.getInt("id")
                                    val name = event.getString("name")
                                    val args = event.optString("args", "null")
                                    val isTool = event.getString("target") == "tool"
                                    val record = if (isTool) Call("$toolCallId/$id", name, args.take(200)) else null
                                    if (record != null) { calls.add(record); publish() }
                                    jobs.add(launch {
                                        val began = System.nanoTime()
                                        var payload: String? = null
                                        var ok = true
                                        try {
                                            if (isTool) {
                                                val result = invoke(name, args, record!!.id)
                                                check(result.success) { result.output.ifEmpty { "Tool $name failed" } }
                                                payload = JSONObject.quote(result.output)
                                            } else {
                                                val arguments = JSONArray(args)
                                                val value: Any? = when (name) {
                                                    "describeTool" -> {
                                                        val wanted = arguments.get(0)
                                                        require(wanted is String) { "describeTool() expects a tool name" }
                                                        callable.find { it.name == wanted || identifier(it.name) == wanted }?.let(::declaration)
                                                    }
                                                    "describeNamespace" -> {
                                                        require(arguments.get(0) is String) { "describeNamespace() expects a namespace name" }
                                                        null // Android's current tools have no namespaces.
                                                    }
                                                    "searchTools" -> {
                                                        val query = arguments.get(0)
                                                        require(query is String) { "searchTools() expects a query string" }
                                                        val options = arguments.optJSONObject(1)
                                                        val raw = options?.opt("limit")?.takeUnless { it == JSONObject.NULL } ?: 8
                                                        require(raw is Number && raw.toDouble() == raw.toInt().toDouble()) { "searchTools() limit must be a positive integer" }
                                                        val namespace = options?.opt("namespace")
                                                        require(namespace == null || namespace == JSONObject.NULL || namespace is String) { "searchTools() namespace must be a string" }
                                                        if (namespace is String && namespace.isNotEmpty()) JSONArray() else search(callable, query, raw.toInt())
                                                    }
                                                    else -> kotlin.error("Unknown codemode global: $name")
                                                }
                                                payload = when (value) { null -> null; is String -> JSONObject.quote(value); else -> value.toString() }
                                            }
                                            record?.status = "ok"
                                        } catch (cancelled: CancellationException) {
                                            record?.status = "cancelled"
                                            // Parent/script cancellation propagates. A tool cancelling its
                                            // own operation still rejects its JS promise, rather than hanging it.
                                            currentCoroutineContext().ensureActive()
                                            ok = false
                                            payload = cancelled.message ?: "Tool was cancelled"
                                            record?.error = payload.take(500)
                                        } catch (failure: Exception) {
                                            ok = false
                                            payload = failure.message ?: failure.toString()
                                            record?.status = "error"
                                            record?.error = payload.take(500)
                                        } finally {
                                            record?.durationMs = (System.nanoTime() - began) / 1_000_000
                                            if (record != null) publish()
                                        }
                                        sandbox.settle(id, ok, payload)
                                    })
                                }
                                "done" -> {
                                    success = event.getBoolean("ok")
                                    if (success) {
                                        writes = event.optString("writes", "[]")
                                        if (event.has("value")) {
                                            val value = JSONTokener(event.getString("value")).nextValue()
                                            output.add(Output("text", if (value is String) value else value.toString()))
                                        }
                                    } else {
                                        val failure = JSONObject(event.getString("error"))
                                        error = failure.optString("stack").ifEmpty { "${failure.optString("name", "Error")}: ${failure.optString("message")}" }
                                    }
                                    break
                                }
                                "crash" -> { error = "Script sandbox failed: ${event.optString("message")}"; break }
                            }
                        }
                    } finally {
                        // Interrupt VM first: a nested call may still be waiting for a settle
                        // evaluation while user JS spins. Never wait for children before closing.
                        sandbox.close()
                        jobs.forEach { it.cancel() }
                    }
                }
            }
            if (parsed.timeoutMs == null) run()
            else if (withTimeoutOrNull(parsed.timeoutMs) { run(); true } == null) {
                success = false
                error = "Script timed out: deadline of ${parsed.timeoutMs} ms expired"
            }
        } catch (cancelled: CancellationException) {
            throw cancelled // The chat loop owns user cancellation and its persisted cancelled result.
        } catch (failure: Exception) {
            success = false
            error = "Script sandbox failed: ${failure.message ?: failure}"
        } finally {
            sandbox.close()
            calls.filter { it.status == "running" }.forEach { it.status = "cancelled" }
        }
        if (success) writes?.takeIf { it != "[]" }?.let { appendEntry(it) }
        if (!success) {
            val summary = if (calls.isEmpty()) "No tool calls were made." else
                "Tool calls made before the failure (they are not undone): " + calls.joinToString(", ") { "${it.name} (${it.status})" }
            output.add(Output("text", "Script error:\n$error\n\n$summary"))
        }
        var text = output.filter { it.type == "text" }.joinToString("\n") { it.text.orEmpty() }
        val budget = if (parsed.maxOutputTokens > Int.MAX_VALUE / 4) Int.MAX_VALUE else (parsed.maxOutputTokens * 4).toInt()
        var fullPath: String? = null
        if (text.length > budget) {
            val original = text
            val head = budget / 2
            val tail = budget - head
            text = "Warning: truncated output (original token count: ${ceil(original.length / 4.0).toLong()})\nTotal output lines: ${original.count { it == '\n' } + 1}\n\n" +
                original.take(head) + "…${ceil((original.length - budget) / 4.0).toLong()} tokens truncated…" + original.takeLast(tail)
            try { fullPath = spill(original); text += "\n\n[Full output: $fullPath (read with offset/limit)]" }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { text += "\n\n[Could not save the full output: ${failure.message}]" }
        }
        val wallTime = String.format(Locale.ROOT, "%.1f", (System.nanoTime() - started) / 1_000_000_000.0)
        return Result("${if (success) "Script completed" else "Script failed"}\nWall time $wallTime seconds\nOutput:\n$text", success,
            output.filter { it.type == "image" }, if (success) writes else null, calls.map { it.copy() }, fullPath)
    }

    fun details(calls: List<Call>): String = JSONObject().put("calls", JSONArray(calls.map { call ->
        JSONObject().put("id", call.id).put("name", call.name).put("args", call.args).put("status", call.status)
            .put("durationMs", call.durationMs).put("error", call.error)
    })).toString()

    fun renderDetails(detailsJson: String?): String {
        if (detailsJson == null) return ""
        val calls = runCatching { JSONObject(detailsJson).getJSONArray("calls") }.getOrNull() ?: return ""
        return (0 until calls.length()).joinToString("\n") { index ->
            val call = calls.getJSONObject(index)
            "${call.getString("name")} (${call.getString("status")}) ${call.optString("args")}" +
                call.optString("error").takeIf { it.isNotEmpty() }?.let { "\n$it" }.orEmpty()
        }
    }

    /** Prelude takes JSON-encoded values; successful deltas are stored on the transcript branch. */
    fun applyWrites(store: JSONObject, writes: String) {
        val entries = JSONArray(writes)
        for (i in 0 until entries.length()) {
            val entry = entries.getJSONArray(i)
            if (entry.length() == 1) store.remove(entry.getString(0))
            else store.put(entry.getString(0), entry.getString(1))
        }
    }
}
