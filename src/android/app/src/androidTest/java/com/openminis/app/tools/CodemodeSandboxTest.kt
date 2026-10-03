package com.openminis.app.tools

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/** Exercises Android JNI, native QuickJS, interrupts and nested calls. */
class CodemodeSandboxTest {
    private suspend fun run(code: String, invoke: suspend (String, String, String) -> ToolExecutionResult = { _, _, _ -> ToolExecutionResult("ok", true) }): CodemodeTool.Result {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val tools = listOf(AgentToolDefinition("read", "Read data", mapOf("id" to AgentToolParam("integer", "id")), listOf("id")))
        return withContext(Dispatchers.Default) {
            CodemodeTool.execute(context, code, "test", tools, JSONObject(),
                spill = { text -> File(context.cacheDir, "codemode-test.txt").apply { writeText(text) }.path }, invoke = invoke)
        }
    }

    @Test fun parallelCallsUnicodeAndStore() = runBlocking {
        val active = AtomicInteger()
        val peak = AtomicInteger()
        val result = run("const values = await Promise.all([tools.read({id:1}),tools.read({id:2})]); text(values); store('value', values); return '完成🌍';") { _, args, _ ->
            val concurrent = active.incrementAndGet()
            peak.updateAndGet { maxOf(it, concurrent) }
            delay(50)
            active.decrementAndGet()
            ToolExecutionResult(JSONObject(args).getInt("id").toString(), true)
        }
        assertTrue(result.success)
        assertEquals(2, peak.get())
        assertTrue(result.output.contains("完成🌍"))
        assertEquals(2, result.calls.size)
        assertNotNull(result.storeWrites)
    }

    @Test fun deadlineInterruptsInfiniteLoop() = runBlocking {
        val result = run("// @options: {\"timeout_ms\":200}\nwhile (true) {}")
        assertFalse(result.success)
        assertTrue(result.output.contains("timed out"))
        assertTrue(run("return 'still alive'").success)
    }

    @Test fun deadlineInterruptsPromiseLoop() = runBlocking {
        val result = run("// @options: {\"timeout_ms\":200}\nwhile (true) await Promise.resolve()")
        assertFalse(result.success)
        assertTrue(result.output.contains("timed out"))
    }

    @Test fun nativeStackGuardAndNoHostBindings() = runBlocking {
        val recursion = run("function recur() { recur() } try { recur() } catch(e) { return e instanceof RangeError }")
        assertTrue(recursion.success)
        assertTrue(recursion.output.contains("true"))
        val host = run("return [typeof java, typeof bridge, typeof std, typeof os, typeof WebAssembly, typeof fetch].every(t => t === 'undefined')")
        assertTrue(host.success)
        assertTrue(host.output.contains("true"))
    }

    @Test fun nativeSyntaxErrorIncludesHeader() = runBlocking {
        val result = run("return (")
        assertFalse(result.success)
        assertTrue(result.output.contains("SyntaxError"))
    }

    @Test fun partialOutputAndNoStoreOnFailure() = runBlocking {
        val result = run("text('partial'); store('x', 1); await tools.read({id:1}); throw Error('boom');")
        assertFalse(result.success)
        assertTrue(result.output.contains("partial"))
        assertTrue(result.output.contains("boom"))
        assertNull(result.storeWrites)
    }

    @Test fun cancellingParentStopsBusyVm() = runBlocking {
        val task = async(Dispatchers.Default) { run("while (true) {}") }
        delay(200)
        task.cancel()
        task.join()
        assertTrue(task.isCancelled)
    }

    @Test fun largeOutputSurvivesNativeTransportAndSpills() = runBlocking {
        val result = run("// @options: {\"max_output_tokens\":10}\ntext('中文🌍'.repeat(10000));")
        assertTrue(result.success)
        assertNotNull(result.fullOutputPath)
        assertEquals("中文🌍".repeat(10000), File(result.fullOutputPath!!).readText())
    }
}
