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
        for (tool in tools) {
            assertTrue("${tool.name} title must be required", "tool_title" in tool.required)
            val args = JSONObject("""{"path":"a","command":"true","content":"x","edits":[{"oldText":"a","newText":""}],"action":"status","tool_title":"检查文件"}""")
            assertNull(com.openminis.app.ui.chat.ChatViewModel.preflightValidateToolCallImpl(tool.name, args, tools))
            for (title in listOf(null, JSONObject.NULL, 7, "", " \n")) {
                args.put("tool_title", title)
                assertTrue(com.openminis.app.provider.ToolJsonRepair.repair(tool.name, args, null, tools).isEmpty())
                assertNotNull(com.openminis.app.ui.chat.ChatViewModel.preflightValidateToolCallImpl(tool.name, args, tools))
            }
        }
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
        val cache = com.openminis.app.provider.openai.OpenAIPromptCache
        assertEquals("conversation-a", cache.key("conversation-a", "fallback"))
        assertEquals("conversation-a", cache.key(" conversation-a ", "fallback"))
        assertNotEquals(cache.key("conversation-a", "fallback"), cache.key("conversation-b", "fallback"))
        assertEquals("stable-fallback", cache.key(null, "stable-fallback"))
        assertEquals(cache.key(null, "stable-fallback"), cache.key(" ", "stable-fallback"))
        assertEquals(64, cache.key("x".repeat(100), "fallback").length)
        val cacheBody = JSONObject().put("prompt_cache_key", "conversation-a").put("instructions", "private instructions")
        assertEquals(mapOf("session-id" to "conversation-a", "x-client-request-id" to "conversation-a"), cache.codexHeaders(cacheBody))
        assertTrue(cache.codexHeaders(JSONObject()).isEmpty())
        assertFalse(cache.diagnostics(cacheBody).contains("conversation-a"))
        assertFalse(cache.diagnostics(cacheBody).contains("private instructions"))
        val stats = com.openminis.app.ui.chat.ChatViewModel.SessionTokenStats(100000, 0, 12800, 0, 0, 0,
            latestInput = 14595, latestCacheRead = 14208)
        assertEquals(97.3484, stats.latestCacheHitRate!!, 0.001)
        assertTrue(stats.cacheHitRate!! < 12)
        assertEquals(0.0, stats.copy(latestCacheRead = 0).latestCacheHitRate!!, 0.0)
        val source = "// @options: {\"tool_title\":\"并行整理账单与生成报告\",\"timeout_ms\":60000}\nreturn 1;"
        assertEquals("并行整理账单与生成报告", CodemodeTool.parseSource(source).toolTitle)
        assertEquals("并行整理账单与生成报告", CodemodeTool.titleFromSource(source.substringBefore('\n')))
        assertEquals("并行整理账单与生成报告", CodemodeTool.titleFromArguments(JSONObject().put("code", source)))
        assertTrue(CodemodeTool.definition(tools).description.contains("tool_title"))
        for (code in listOf("return 1;", "// @options: {}\nreturn 1;")) {
            try { CodemodeTool.parseSource(code); fail("Missing title accepted") } catch (_: IllegalArgumentException) { }
        }
        assertFalse(CodemodeTool.SOURCE_GRAMMAR.contains("plain_source"))
        for (bad in listOf("null", "7", "\" \"")) {
            try { CodemodeTool.parseSource("// @options: {\"tool_title\":$bad}\nreturn 1;"); fail("Invalid title accepted") }
            catch (_: IllegalArgumentException) { }
        }
        val valid = JSONObject("""{"tool_title":"删除匹配文本","path":"a","edits":[{"oldText":"a","newText":""}]}""")
        val editDefinition = EditTool.definition()
        val originalArray = valid.getJSONArray("edits")
        assertTrue(com.openminis.app.provider.ToolJsonRepair.repair("edit", valid, null, tools).isEmpty())
        assertSame(originalArray, valid.getJSONArray("edits"))
        assertNull(CoreToolNames.validate(valid, editDefinition))
        assertEquals(listOf(PiFileEditor.Edit("a", "")), EditTool.arguments(valid))
        for (bad in listOf("""{"path":7,"edits":[]}""", """{"path":"a","edits":"[]"}""",
            """{"path":"a","edits":{"oldText":"a","newText":"b"}}""")) {
            val input = JSONObject(bad).put("tool_title", "编辑文件")
            val before = input.toString()
            assertTrue(com.openminis.app.provider.ToolJsonRepair.repair("edit", input, null, tools).isEmpty())
            assertEquals(before, input.toString())
            assertNotNull(CoreToolNames.validate(input, editDefinition))
        }
        for (json in listOf("""{"oldText":"a","newText":"b"}""", """{"edits":"[]"}""")) {
            try { EditTool.arguments(JSONObject(json)); fail("Old input accepted") } catch (_: Exception) { }
        }
    }
    @Test fun cacheOptimizationsPreserveOwnedBranchFactsAndBalancedPrefixes() {
        val runtime = com.openminis.app.agent.RuntimeContextSnapshot
        val first = runtime.render("2026-10-04", "UTC", "zh-CN", 3)
        val updated = runtime.render("2026-10-05", "UTC", "zh-CN", 4)
        val task = com.openminis.app.data.model.LLMMessage(com.openminis.app.data.model.LLMMessage.Role.USER, "task", dbMessageId = "u")
        val restored = runtime.message(runtime.decode(runtime.encode(first))!!, "r")
        val branch = listOf(task, restored)
        assertFalse(runtime.changed(branch, first))
        assertFalse(runtime.changed(branch.toList(), first)) // fork/reload keeps the owned snapshot
        assertTrue(runtime.changed(branch.take(1), first)) // rewind removes it
        assertTrue(runtime.changed(branch, updated))
        assertEquals(first, branch.last().content) // updates never rewrite old snapshots
        assertTrue(runtime.changed(listOf(task.copy(content = first)), first)) // user text cannot impersonate ownership
        assertNull(runtime.decode("""[{"type":"text","value":"runtime-context"}]"""))

        val cache = com.openminis.app.agent.CachedCompaction
        val use = com.openminis.app.data.model.AgentContentPart.ToolUse("call", "bash", JSONObject("""{"command":"true","tool_title":"测试"}"""))
        val call = com.openminis.app.data.model.LLMMessage(com.openminis.app.data.model.LLMMessage.Role.ASSISTANT, "", contentParts = listOf(use), dbMessageId = "a")
        val result = task.copy(content = "", contentParts = listOf(com.openminis.app.data.model.AgentContentPart.ToolResult("call", "bash", "ok")), dbMessageId = "t")
        val final = call.copy(content = "done", contentParts = emptyList(), dbMessageId = "f")
        val warm = cache.snapshot(listOf(task, restored, call, result))
        val current = listOf(task, restored, call, result, final)
        assertEquals(current, cache.prefix(warm, current, listOf(call, result, final)))
        assertNull(cache.prefix(warm, current, listOf(call))) // unpaired boundary is not a valid API request
        assertNull(cache.prefix(warm, current.toMutableList().apply { set(0, task.copy(content = "edited")) }, listOf(final)))
        assertNotNull(cache.prefix(warm, current.map { it.copy(dbMessageId = null) }, listOf(final))) // ids are not wire input
        use.input.put("command", "changed")
        assertEquals("true", (warm[2].contentParts[0] as com.openminis.app.data.model.AgentContentPart.ToolUse).input.getString("command"))
        assertNull(cache.prefix(warm, current, listOf(final)))
        for (chunk in listOf(com.openminis.app.data.model.LLMStreamChunk.ToolUseStart("call", "bash"),
            com.openminis.app.data.model.LLMStreamChunk.ToolCallComplete("call", "bash", JSONObject()),
            com.openminis.app.data.model.LLMStreamChunk.Finished("length"))) {
            try { cache.requireTextOnly(chunk); fail("Unsafe summary accepted") }
            catch (_: com.openminis.app.agent.CachedCompaction.UnsafeSummary) { }
        }
        cache.requireTextOnly(com.openminis.app.data.model.LLMStreamChunk.Text("checkpoint"))
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
