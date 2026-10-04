package com.openminis.app.tools

import com.openminis.app.data.EnvVarRedactor
import kotlinx.coroutines.CancellationException
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class BashCaptureTest {
    @Test fun outputIsBoundedInMemoryButCompleteOnDisk() {
        val directory = Files.createTempDirectory("bash-test").toFile()
        val capture = PiBashCapture(directory)
        try {
            val chunk = "中文🌍\n".repeat(1000)
            capture.append("HEAD_UNIQUE\n")
            repeat(200) { capture.append(chunk) }; capture.append("TAIL_UNIQUE\n"); capture.finish()
            assertEquals(chunk.toByteArray().size.toLong() * 200 + 24, capture.bytes)
            assertEquals(capture.bytes, capture.file!!.length())
            assertTrue(capture.prefixText.length <= PiBashOutput.SCRIPT_MAX_BYTES + 2)
            assertTrue(capture.tailText.length <= PiToolText.MAX_BYTES + 2)
            assertTrue(capture.scriptTailText.length <= PiBashOutput.SCRIPT_MAX_BYTES / 2 + 2)
            val json = JSONObject(PiBashOutput.format(capture, 7, 100, "/full.log", false).structured!!)
            assertEquals(7, json.getInt("exit_code")); assertTrue(json.getBoolean("truncated"))
            val script = json.getString("output")
            assertTrue(script.toByteArray().size <= PiBashOutput.SCRIPT_MAX_BYTES + 100)
            assertTrue(script.startsWith("HEAD_UNIQUE\n")); assertTrue(script.endsWith("TAIL_UNIQUE\n"))
            assertTrue(script.contains("bytes omitted")); assertFalse(script.contains('\uFFFD'))
            // Exactly-at-limit output is complete, not head/tail-spliced.
            val small = PiBashCapture(directory)
            try {
                small.append("x".repeat(PiBashOutput.SCRIPT_MAX_BYTES)); small.finish()
                val complete = JSONObject(PiBashOutput.format(small, 0, 1, "/full.log", false).structured!!)
                assertFalse(complete.getBoolean("truncated"))
                assertEquals(PiBashOutput.SCRIPT_MAX_BYTES, complete.getString("output").length)
            } finally { small.discard() }
        } finally { capture.discard(); assertTrue(directory.listFiles()!!.isEmpty()); directory.delete() }
    }
    @Test fun streamedRedactionMatchesWholeTextEvenAcrossChunkBoundaries() {
        val directory = Files.createTempDirectory("bash-test").toFile()
        val capture = PiBashCapture(directory); var output = capture
        try {
            val values = listOf("ABCDEF", "DEFGHIJK", "秘密令牌123456")
            val source = "ABCDEFGHIJK 秘密令牌123456\n".repeat(1000)
            source.chunked(7).forEach(capture::append); capture.finish()
            val masked = capture.redact(values); output = masked.first
            val expected = EnvVarRedactor.redact(source, values)
            assertEquals(expected.second, masked.second)
            assertEquals(expected.first, output.file!!.readText())
        } finally { output.discard(); capture.discard(); assertTrue(directory.listFiles()!!.isEmpty()); directory.delete() }
    }
    @Test fun cancellationAndDiskFailureDoNotLeakOrAdvertisePartialArchives() {
        val directory = Files.createTempDirectory("bash-test").toFile()
        val capture = PiBashCapture(directory)
        try {
            repeat(20) { capture.append("secret-token-12345".repeat(1000)) }; capture.finish()
            var checks = 0
            try {
                capture.redact(listOf("secret-token-12345")) { if (++checks > 3) throw CancellationException("stop") }
                fail("Cancellation ignored")
            } catch (_: CancellationException) { }
            assertEquals(1, directory.listFiles()!!.size)
        } finally { capture.discard() }
        val notDirectory = directory.resolve("file").apply { writeText("x") }
        val failed = PiBashCapture(notDirectory)
        try {
            repeat(200) { failed.append("x".repeat(8192)) }; failed.finish()
            assertNull(failed.file)
            val json = JSONObject(PiBashOutput.format(failed, 0, 1, null, false).structured!!)
            assertTrue(json.getBoolean("truncated")); assertFalse(json.has("full_output_path"))
        } finally { failed.discard(); directory.deleteRecursively() }
    }
}
