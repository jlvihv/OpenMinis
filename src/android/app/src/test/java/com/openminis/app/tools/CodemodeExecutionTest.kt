package com.openminis.app.tools

import com.openminis.app.data.model.AgentToolDefinition
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CodemodeExecutionTest {
    private class Transport : CodemodeTransport {
        val events = Channel<JSONObject>(Channel.UNLIMITED)
        var closed = false
        var onSettle: suspend (Boolean, String?) -> Unit = { _, _ -> }
        override suspend fun start(data: JSONObject) {}
        override suspend fun receive() = events.receive()
        override suspend fun settle(id: Int, success: Boolean, payload: String?) = onSettle(success, payload)
        override fun close() { closed = true }
        fun call() { events.trySend(JSONObject().put("type", "call").put("target", "tool").put("id", 1).put("name", "test").put("args", "{}")) }
        fun done(ok: Boolean = true) { events.trySend(JSONObject().put("type", "done").put("ok", ok).put("writes", "[[\"a\",\"1\"]]")
            .put("error", "{\"message\":\"failed\"}")) }
        fun text(text: String) { events.trySend(JSONObject().put("type", "output").put("item", JSONObject().put("type", "text").put("text", text))) }
    }
    private suspend fun run(transport: Transport, source: String = "return 1", append: suspend (String) -> Unit = {},
        spill: suspend (String) -> String = { "spill.txt" }, invoke: suspend (String, String, String) -> ToolExecutionResult = { _, _, _ -> ToolExecutionResult("ok", true) }) =
        CodemodeTool.executeWithTransport(transport, source, "parent", listOf(AgentToolDefinition("test", "test", emptyMap())),
            JSONObject(), spill, invoke, appendEntry = append)

    @Test fun invalidOptionsCloseTransport() = runBlocking {
        val t = Transport()
        try {
            run(t, "// @options: {\"timeout_ms\":0}\nreturn 1")
            fail("Invalid timeout accepted")
        } catch (_: IllegalArgumentException) { }
        assertTrue(t.closed)
    }

    @Test fun independentToolCancellationRejectsRatherThanHangs() = runBlocking {
        val t = Transport()
        t.call()
        t.onSettle = { ok, payload -> assertFalse(ok); assertEquals("cancelled by tool", payload); t.done() }
        val result = withTimeout(1000) { run(t, invoke = { _, _, _ -> throw CancellationException("cancelled by tool") }) }
        assertTrue(result.success)
        assertEquals("cancelled", result.calls.single().status)
        assertTrue(t.closed)
    }

    @Test fun timeoutClosesTransportAndCancelsNestedWork() = runBlocking {
        val t = Transport()
        val started = CompletableDeferred<Unit>()
        val stopped = CompletableDeferred<Unit>()
        t.call()
        val result = run(t, "// @options: {\"timeout_ms\":100}\nreturn 1", invoke = { _, _, _ ->
            started.complete(Unit)
            try { awaitCancellation() } finally { stopped.complete(Unit) }
        })
        assertTrue(started.isCompleted)
        assertTrue(stopped.isCompleted)
        assertTrue(t.closed)
        assertFalse(result.success)
        assertTrue(result.output.contains("timed out"))
        assertNull(result.storeWrites)
    }

    @Test fun parentTimeoutIsNotConvertedIntoSuccessfulToolReturn() = runBlocking {
        val t = Transport()
        try {
            withTimeout(50) { run(t) }
            fail("Parent timeout was swallowed")
        } catch (_: TimeoutCancellationException) { }
        assertTrue(t.closed)
    }

    @Test fun storeCommitsBeforeSpillAndOnlyOnSuccess() = runBlocking {
        val t = Transport()
        var committed = false
        t.text("x".repeat(100)); t.done()
        val result = run(t, "// @options: {\"max_output_tokens\":2}\nreturn 1", append = { committed = true }, spill = {
            assertTrue(committed); assertEquals(100, it.length); "full.txt"
        })
        assertTrue(result.success)
        assertEquals("full.txt", result.fullOutputPath)
        val failed = Transport().apply { text("partial"); done(false) }
        val error = run(failed, append = { fail("Committed failed script") })
        assertFalse(error.success)
        assertTrue(error.output.contains("partial"))
        assertNull(error.storeWrites)
    }

    @Test fun errorsRejectWithToolText() = runBlocking {
        val t = Transport().apply { call() }
        t.onSettle = { ok, payload -> assertFalse(ok); assertEquals("permission denied", payload); t.done(false) }
        val result = run(t, invoke = { _, _, _ -> ToolExecutionResult("permission denied", false) })
        assertFalse(result.success)
        assertEquals("error", result.calls.single().status)
    }
}
