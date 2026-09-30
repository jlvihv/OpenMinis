package com.openminis.app.sandbox.offload

import android.content.ContextWrapper
import com.openminis.app.ProductionSources
import com.openminis.app.sandbox.NativeOffloadHandler
import com.openminis.app.sandbox.NativeOffloadRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OffloadHelpExitCodeTest {
    // Help must return without touching Android services or permissions.
    private val context = ContextWrapper(null)
    private val handlers: Map<String, NativeOffloadHandler> by lazy {
        mapOf(
            "android-alarm" to AlarmOffloadHandler(context),
            "android-photos" to PhotosOffloadHandler(context),
            "android-speech" to SpeechOffloadHandler(context),
            "android-a11y-cli" to AccessibilityOffloadHandler(context),
        )
    }

    private fun request(command: String, vararg args: String) = NativeOffloadRequest(
        pid = 1, argv = listOf(command, *args), env = emptyMap(), cwd = "/",
    )

    @Test
    fun `explicit help exits zero with or without a subcommand`() {
        for ((command, handler) in handlers) {
            val bare = handler.handle(request(command))
            for (args in listOf(
                listOf("--help"), listOf("-h"),
                listOf("set", "--help"), listOf("set", "-h"),
                listOf("--help", "set"), listOf("-h", "set"),
                listOf("--compact", "--help"),
                listOf("--help", "unknown", "--quiet"),
            )) {
                val result = handler.handle(request(command, *args.toTypedArray()))
                assertEquals("$command $args", 0, result.exitCode)
                assertTrue("$command must print help", result.output.isNotBlank())
                assertEquals("$command keeps its full help text", bare.output, result.output)
            }
        }
    }

    @Test
    fun `bare invocation still reports a missing subcommand`() {
        for ((command, handler) in handlers) {
            for (args in listOf(emptyList(), listOf("--compact"))) {
                val result = handler.handle(request(command, *args.toTypedArray()))
                assertEquals(command, 2, result.exitCode)
                assertTrue(result.output.isNotBlank())
            }
        }
    }

    @Test
    fun `help is always boolean and does not swallow the next positional`() {
        for (flag in listOf("--help", "-h")) {
            val args = OffloadArgs(listOf(flag, "set", "--time", "07:30"))
            assertTrue(args.hasFlag("h", "help"))
            assertEquals(listOf("set"), args.positional)
            assertEquals("07:30", args.get("time"))
            assertNull(args.get("h", "help"))
        }
    }

    @Test
    fun `ordinary commands and their values are unchanged`() {
        val args = OffloadArgs(listOf("set", "--time", "07:30", "--label", "Wake up"))
        assertNull(args.helpOrMissingCommand("help"))
        assertEquals(listOf("set"), args.positional)
        assertEquals("07:30", args.get("time"))
        assertEquals("Wake up", args.get("label"))
        val invalid = handlers.getValue("android-alarm").handle(request("android-alarm", "unknown"))
        assertEquals(2, invalid.exitCode)
        assertTrue(invalid.output.contains("unknown subcommand"))
    }

    @Test
    fun `all five affected handlers use the shared early help result`() {
        // ModelUse needs the repository graph, so guard its wiring by source.
        for (name in listOf("Alarm", "Photos", "Speech", "Accessibility", "ModelUse")) {
            val source = ProductionSources.read("sandbox/offload/${name}OffloadHandler.kt")
                .substringAfter("override fun handle(request: NativeOffloadRequest)")
            assertTrue(name, source.contains("args.helpOrMissingCommand("))
            assertTrue(name, source.indexOf("args.helpOrMissingCommand(") < source.indexOf("return try"))
            assertTrue(name, !source.contains("NativeOffloadResult(if (args.positional.isEmpty()) 2 else 0"))
        }
    }
}
