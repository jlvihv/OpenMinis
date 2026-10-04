package com.openminis.app.tools

import androidx.test.platform.app.InstrumentationRegistry
import com.openminis.app.data.model.AgentToolDefinition
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Small ART/JNI smoke suite; host tests cannot validate Android native loading. */
class CodemodeSandboxTest {
    private suspend fun run(code: String): CodemodeTool.Result {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        return withContext(Dispatchers.Default) {
            val source = if (code.startsWith("// @options:")) code else "// @options: {\"tool_title\":\"原生运行时测试\"}\n$code"
            CodemodeTool.execute(context, source, "smoke", listOf(AgentToolDefinition("read", "Read", emptyMap())),
                JSONObject(), spill = { text -> File(context.cacheDir, "codemode-smoke.txt").apply { writeText(text) }.path },
                invoke = { _, _, _ -> delay(10); ToolExecutionResult("ok", true) })
        }
    }
    @Test fun nativeBridgeHandlesUnicodeAndParallelPromises() = runBlocking {
        val result = run("const values = await Promise.all([tools.read({}), tools.read({})]); text(values); return '完成🌍';")
        assertTrue(result.success); assertEquals(2, result.calls.size); assertTrue(result.output.contains("完成🌍"))
        // Native decoder smoke checks share this small device suite, not a new test tree.
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val session = "read-smoke-${java.util.UUID.randomUUID()}"
        val path = "/var/minis/workspace/image.png"
        val file = com.openminis.app.sandbox.PRootKernel.resolveSessionHostPath(session, path, context)!!
        file.parentFile!!.mkdirs()
        try {
            val bitmap = android.graphics.Bitmap.createBitmap(4, 2, android.graphics.Bitmap.Config.ARGB_8888)
            try { file.outputStream().use { assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) } }
            finally { bitmap.recycle() }
            val input = JSONObject().put("path", path).put("tool_title", "读取测试图片").toString()
            val resized = ReadTool.execute(input, session, context,
                com.openminis.app.data.model.ModelImageResizeOptions(maxWidth = 2, maxHeight = 2), supportsImages = false)
            assertTrue(resized.success); assertNotNull(resized.imageData)
            assertTrue(resized.output.contains("displayed at 2x1")); assertTrue(resized.output.contains("Multiply coordinates by 2.00"))
            assertTrue(resized.output.contains(ImageReader.NON_VISION_NOTE))
            val omitted = ReadTool.execute(input, session, context,
                com.openminis.app.data.model.ModelImageResizeOptions(maxBytes = 8))
            assertTrue(omitted.success); assertNull(omitted.imageData); assertTrue(omitted.output.contains("Image omitted"))
            val gif = android.util.Base64.decode("R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7", android.util.Base64.DEFAULT)
            file.writeBytes(gif)
            val preserved = ReadTool.execute(input, session, context)
            assertTrue(preserved.success); assertEquals("image/gif", preserved.imageMimeType)
            assertArrayEquals(gif, preserved.imageData)
            val noisy = android.graphics.Bitmap.createBitmap(128, 64, android.graphics.Bitmap.Config.ARGB_8888)
            val random = java.util.Random(7)
            noisy.setPixels(IntArray(128 * 64) { random.nextInt() or (0xff shl 24) }, 0, 128, 0, 0, 128, 64)
            try { file.outputStream().use { assertTrue(noisy.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) } }
            finally { noisy.recycle() }
            val bounded = ReadTool.execute(input, session, context,
                com.openminis.app.data.model.ModelImageResizeOptions(maxBytes = 1024))
            assertTrue(bounded.output, bounded.success); assertNotNull(bounded.imageData)
            assertTrue(((bounded.imageData!!.size + 2) / 3) * 4 < 1024)
            assertTrue(bounded.output.contains("displayed at"))
            val oriented = android.graphics.Bitmap.createBitmap(4, 2, android.graphics.Bitmap.Config.ARGB_8888)
            try { file.outputStream().use { assertTrue(oriented.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, it)) } }
            finally { oriented.recycle() }
            androidx.exifinterface.media.ExifInterface(file).apply {
                setAttribute(androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION,
                    androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_90.toString()); saveAttributes()
            }
            val rotated = ReadTool.execute(input, session, context,
                com.openminis.app.data.model.ModelImageResizeOptions(maxWidth = 2, maxHeight = 2))
            assertTrue(rotated.output, rotated.success); assertTrue(rotated.output.contains("original 2x4, displayed at 1x2"))
            val rotatedBytes = requireNotNull(rotated.imageData)
            android.graphics.BitmapFactory.decodeByteArray(rotatedBytes, 0, rotatedBytes.size).let {
                assertEquals(1, it.width); assertEquals(2, it.height); it.recycle()
            }
        } finally { file.parentFile!!.parentFile!!.deleteRecursively() }
        checkRuntimeSnapshotReplay()
    }

    /** Disposable DB only: restart, fork and rewind never touch the user's chat database. */
    private suspend fun checkRuntimeSnapshotReplay() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "runtime-cache-smoke-${java.util.UUID.randomUUID()}"
        fun open() = androidx.room.Room.databaseBuilder(context,
            com.openminis.app.data.db.AppDatabase::class.java, name).build()
        var db = open()
        val runtime = com.openminis.app.agent.RuntimeContextSnapshot
        val text = runtime.render("2026-10-04", "UTC", "zh-CN", 3)
        try {
            var repo = com.openminis.app.data.repository.ChatRepository(db.chatDao())
            val session = repo.createSession("cache-test")
            repo.appendMessage(session.id, "user", """[{"type":"text","value":"test"}]""")
            val snapshot = repo.appendMessage(session.id, "user", runtime.encode(text))
            val attribution = com.openminis.app.data.model.ModelAttributionSnapshot("cache-test", "Cache Test", "openAI", "test-instance")
            repo.recordRequestUsage(session.id, com.openminis.app.data.model.RequestUsageRecord.Purpose.COMPACTION,
                com.openminis.app.data.model.LLMUsage(200, 5, cacheReadInputTokens = 800, latestContextTokens = 1000), 1000, attribution)
            assertEquals(snapshot.partsJson, db.chatDao().lastMessageParts(session.id))
            assertEquals("test", db.chatDao().getSession(session.id)!!.lastMessage)
            db.close(); db = open()
            repo = com.openminis.app.data.repository.ChatRepository(db.chatDao())
            assertEquals(text, repo.loadMessages(session.id).mapNotNull { runtime.decode(it.partsJson) }.single())
            val usageRow = repo.loadMessages(session.id).last()
            assertFalse(com.openminis.app.agent.JournalProjection.isModelVisible(usageRow.partsJson))
            assertEquals("test-instance", usageRow.providerInstanceId)
            val stats = com.openminis.app.data.model.SessionTokenStats.fromUsageRecords(repo.sessionTokenUsages(session.id))
            assertEquals(80.0, stats.cacheHitRate!!, 0.0)
            assertEquals(1, stats.auxiliaryRequests)
            assertEquals(0, stats.context)
            val copy = com.openminis.app.data.SessionForkManager(repo, filesDir = context.cacheDir)
                .duplicateSession(session.id)!!
            assertEquals(text, repo.loadMessages(copy).mapNotNull { runtime.decode(it.partsJson) }.single())
            assertEquals(1, repo.sessionTokenUsages(copy).size)
            repo.deleteMessagesAfter(session.id, snapshot.sortOrder)
            assertNull(runtime.decode(repo.loadMessages(session.id).last().partsJson))
            assertTrue(repo.sessionTokenUsages(session.id).isEmpty())
            assertEquals(text, repo.loadMessages(copy).mapNotNull { runtime.decode(it.partsJson) }.single())
            assertEquals(1, repo.sessionTokenUsages(copy).size)
        } finally { db.close(); context.deleteDatabase(name) }
    }
    @Test fun deadlineInterruptsVmAndNextInvocationStillWorks() = runBlocking {
        val result = run("// @options: {\"tool_title\":\"测试中断\",\"timeout_ms\":200}\nwhile (true) {}")
        assertFalse(result.success); assertTrue(result.output.contains("timed out"))
        assertTrue(run("return 'alive'").success)
        checkRealCoreTools()
    }
    /** Real PRoot and file tools, in a disposable session; no provider requests. */
    private suspend fun checkRealCoreTools() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val session = "core-smoke-${java.util.UUID.randomUUID()}"
        val coordinator = com.openminis.app.sandbox.ExecutionCoordinator
        val available = com.openminis.app.agent.shell.OnDemandBash.ensureBash(context,
            com.openminis.app.agent.shell.OnDemandBash.Executor { cmd, timeout ->
                coordinator.execute(session, cmd, timeout).exitCode
            })
        assertTrue("Bash unavailable: $available", available is com.openminis.app.agent.shell.OnDemandBash.Outcome.Available)
        suspend fun bash(command: String, timeout: Long = 60_000) = coordinator.execute(
            session, "cd /var/minis/workspace || exit; bash -c '" + command.replace("'", "'\\''") + "'",
            timeout, captureBashOutput = true)
        val root = com.openminis.app.sandbox.PRootKernel.resolveSessionHostPath(session, "/var/minis/workspace", context)!!.parentFile!!
        try {
            val tools = AgentTools.makeAgentTools().filter { it.name in listOf("read", "write", "edit", "bash") }
            val result = CodemodeTool.execute(context,
                "// @options: {\"tool_title\":\"文件与 Bash 集成测试\"}\n" +
                    "await tools.write({tool_title:'写入文件',path:'a.txt',content:'你好🌍'}); await tools.edit({tool_title:'编辑文件',path:'a.txt',edits:[{oldText:'你好',newText:'Hello'}]}); " +
                    "const textResult=await tools.read({tool_title:'读取文件',path:'a.txt'}); const shell=await tools.bash({tool_title:'测试退出码',command:'printf x >> counter; exit 119'}); return {textResult,exit:shell.exit_code};",
                "core-smoke", tools, JSONObject(), spill = { error("Unexpected spill") },
                invoke = { name, args, _ -> when (name) {
                    "read" -> ReadTool.execute(args, session, context)
                    "write" -> WriteTool.execute(args, session, context)
                    "edit" -> EditTool.execute(args, session, context)
                    "bash" -> bash(JSONObject(args).getString("command")).let { ToolExecutionResult(
                        it.output, it.exitCode == 0, timedOut = it.timedOut, structuredContentJson = it.structuredContentJson) }
                    else -> error("Unexpected tool")
                } })
            assertTrue(result.output, result.success)
            assertTrue(result.output.contains("Hello🌍")); assertTrue(result.output.contains("119"))
            assertEquals("x", java.io.File(root, "workspace/counter").readText())
            val large = bash("printf HEAD_UNIQUE; head -c 1200000 /dev/zero | tr '\\000' x; printf TAIL_UNIQUE")
            val json = JSONObject(large.structuredContentJson!!)
            val scriptOutput = json.getString("output")
            val diagnostic = "exit=${large.exitCode}; head=${scriptOutput.take(300)}; tail=${scriptOutput.takeLast(300)}"
            assertTrue(diagnostic, scriptOutput.startsWith("HEAD_UNIQUE"))
            assertTrue(diagnostic, scriptOutput.endsWith("TAIL_UNIQUE")); assertTrue(json.getBoolean("truncated"))
            val archive = com.openminis.app.sandbox.PRootKernel.resolveSessionHostPath(session, json.getString("full_output_path"), context)!!
            assertEquals(1_200_022L, archive.length())
            val stderr = bash("printf '[native_offload] user stderr\\n' >&2")
            assertEquals("[native_offload] user stderr\n", JSONObject(stderr.structuredContentJson!!).getString("output"))
            val normal124 = bash("printf '[Command timed out]'; exit 124")
            assertFalse(normal124.timedOut); assertEquals(124, JSONObject(normal124.structuredContentJson!!).getInt("exit_code"))
            val deadline = bash("sleep 5", 200)
            assertTrue(deadline.output, deadline.timedOut); assertNull(deadline.structuredContentJson)
            coroutineScope {
                val pending = async(Dispatchers.IO) { bash("sleep 10") }
                delay(500); withTimeout(5000) { pending.cancelAndJoin() }
            }
            assertEquals(0, bash("printf alive").exitCode)
        } finally { coordinator.sessionDidTerminate(session); root.deleteRecursively() }
    }
    @Test fun parentCancellationStopsVm() = runBlocking {
        val task = async(Dispatchers.Default) { run("while (true) {}") }
        delay(200); task.cancelAndJoin(); assertTrue(task.isCancelled)
    }
}
