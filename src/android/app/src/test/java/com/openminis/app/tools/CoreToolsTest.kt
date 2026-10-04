package com.openminis.app.tools

import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

/** Minimal regressions for contracts that can damage files or deadlock calls. */
class CoreToolsTest {
    @Test fun registryAndEditInputsHaveOnlyTheNewContract() {
        val tools = AgentTools.makeAgentTools(codemodeMode = "off")
        assertEquals(listOf("read", "bash", "edit", "write", "browser", "subagent"), tools.map { it.name })
        val enabled = AgentTools.makeAgentTools(codemodeMode = "on")
        assertEquals(listOf("read", "bash", "edit", "write", "browser", "subagent", "codemode"), enabled.map { it.name })
        assertTrue(enabled.all { it.name.matches(Regex("[a-z]+")) })
        assertNull(AgentToolSwitch.governing("browser_use"))
        assertNull(AgentToolSwitch.governing("subagent_task"))
        assertEquals(AgentToolSwitch.BROWSER, AgentToolSwitch.governing("browser"))
        assertEquals(AgentToolSwitch.AGENTS, AgentToolSwitch.governing("subagent"))
        assertFalse(tools.any { it.name in listOf("image", "read_image", "file_read", "file_write", "file_edit", "shell_execute") })
        assertTrue(tools.first().description.contains("Images are sent as attachments."))
        assertEquals("Current model does not support images. The image will be omitted from this request.",
            ImageReader.NON_VISION_NOTE.removeSurrounding("[", "]"))
        assertEquals("a\n\n[1 more lines in file. Use offset=2 to continue.]", PiToolText.read("a\nb", "a", limit = 1))
        val profile = com.openminis.app.data.model.ModelInputLimits(
            com.openminis.app.data.model.ModelImageLimits(com.openminis.app.data.model.ModelImageResizeOptions(maxWidth = 1536)))
        val encoded = kotlinx.serialization.json.Json.encodeToString(com.openminis.app.data.model.ModelInputLimits.serializer(), profile)
        assertEquals(profile, kotlinx.serialization.json.Json.decodeFromString(com.openminis.app.data.model.ModelInputLimits.serializer(), encoded))
        val source = "// @options: {\"tool_title\":\"并行整理账单与生成报告\",\"timeout_ms\":60000}\nreturn 1;"
        assertEquals("并行整理账单与生成报告", CodemodeTool.parseSource(source).toolTitle)
        assertEquals("并行整理账单与生成报告", CodemodeTool.titleFromSource(source.substringBefore('\n')))
        assertEquals("并行整理账单与生成报告", CodemodeTool.titleFromArguments(JSONObject().put("code", source)))
        assertTrue(CodemodeTool.definition(tools).description.contains("tool_title"))
        for (bad in listOf("null", "7", "\" \"")) {
            try { CodemodeTool.parseSource("// @options: {\"tool_title\":$bad}\nreturn 1;"); fail("Invalid title accepted") }
            catch (_: IllegalArgumentException) { }
        }
        val valid = JSONObject("""{"path":"a","edits":[{"oldText":"a","newText":""}]}""")
        val editDefinition = EditTool.definition()
        val originalArray = valid.getJSONArray("edits")
        assertTrue(com.openminis.app.provider.ToolJsonRepair.repair("edit", valid, null, tools).isEmpty())
        assertSame(originalArray, valid.getJSONArray("edits"))
        assertNull(CoreToolNames.validate(valid, editDefinition))
        assertEquals(listOf(PiFileEditor.Edit("a", "")), EditTool.arguments(valid))
        for (bad in listOf("""{"path":7,"edits":[]}""", """{"path":"a","edits":"[]"}""",
            """{"path":"a","edits":{"oldText":"a","newText":"b"}}""")) {
            val input = JSONObject(bad)
            val before = input.toString()
            assertTrue(com.openminis.app.provider.ToolJsonRepair.repair("edit", input, null, tools).isEmpty())
            assertEquals(before, input.toString())
            assertNotNull(CoreToolNames.validate(input, editDefinition))
        }
        for (json in listOf("""{"oldText":"a","newText":"b"}""", """{"edits":"[]"}""")) {
            try { EditTool.arguments(JSONObject(json)); fail("Old input accepted") } catch (_: Exception) { }
        }
    }
    @Test(timeout = 2000) fun editsMatchOriginalRejectAmbiguityAndPreserveFileStyle() {
        assertEquals("B\nX\n", PiFileEditor.apply("A\nB\n",
            listOf(PiFileEditor.Edit("A", "B"), PiFileEditor.Edit("B", "X")), "a").content)
        assertEquals("\uFEFFa\r\nc\r\n", PiFileEditor.apply("\uFEFFa\r\nb\r\n",
            listOf(PiFileEditor.Edit("b", "c")), "a").content)
        assertEquals("x", PiFileEditor.apply(" ", listOf(PiFileEditor.Edit(" ", "x")), "a").content)
        assertEquals("\"x\"\nuntouched “y”  \n", PiFileEditor.apply("“x”\nuntouched “y”  \n",
            listOf(PiFileEditor.Edit("\"x\"", "\"x\"")), "a").content)
        assertEquals("\"b\"\n\"a\"\n", PiFileEditor.apply("“a”\n“b”\n",
            listOf(PiFileEditor.Edit("\"a\"", "\"b\""), PiFileEditor.Edit("\"b\"", "\"a\"")), "a").content)
        for (edits in listOf(listOf(PiFileEditor.Edit("A", "x")),
            listOf(PiFileEditor.Edit("AA", "x"), PiFileEditor.Edit("A", "y")),
            listOf(PiFileEditor.Edit("AA", "AA")))) {
            try { PiFileEditor.apply("AA", edits, "a"); fail("Unsafe edit accepted") } catch (_: IllegalArgumentException) { }
        }
    }
    @Test fun mutationQueueSerializesWritesAndReleasesCancelledWaiters() = runBlocking {
        val file = Files.createTempFile("core-queue", ".txt").toFile().apply { writeText("0") }
        try {
            coroutineScope { repeat(20) { launch(Dispatchers.Default) { FileMutationQueue.withFile(file) {
                val n = file.readText().toInt(); delay(1); file.writeText((n + 1).toString())
            } } } }
            assertEquals("20", file.readText())
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            val owner = launch { FileMutationQueue.withFile(file) { entered.complete(Unit); release.await() } }
            entered.await()
            val waiter = launch { FileMutationQueue.withFile(file) { fail("Cancelled waiter entered") } }
            yield(); waiter.cancelAndJoin(); release.complete(Unit); owner.join()
            withTimeout(1000) { FileMutationQueue.withFile(file) { assertEquals("20", file.readText()) } }
        } finally { file.delete() }
    }
}
