package com.openminis.app.agent

import com.openminis.app.data.model.*
import com.openminis.app.provider.LLMProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class AgentBoundariesTest {
    @Test fun journalKindsAndUsageAggregationAreIndependentOfViewModel() {
        val facts = RuntimeContextSnapshot.message("facts", "row")
        assertFalse(JournalProjection.startsUserTurn(facts))
        assertFalse(JournalProjection.isConversationUser(facts))
        assertTrue(JournalProjection.isHidden(RuntimeContextSnapshot.encode("facts")))
        assertEquals(facts, JournalProjection.runtimeMessage(RuntimeContextSnapshot.encode("facts"), "row"))
        assertTrue(JournalProjection.startsUserTurn(LLMMessage(LLMMessage.Role.USER, "task")))
        val stats = SessionTokenStats.fromUsageRecords(listOf(
            """{"inputTokens":100,"outputTokens":5,"cacheReadTokens":900,"cacheCreationTokens":100,"streamMs":1000,"latestContextTokens":1100}""",
            "bad JSON", """{"inputTokens":200,"outputTokens":100,"latestContextTokens":200}""",
        ))
        assertEquals(900.0 / 1300 * 100, stats.cacheHitRate!!, 0.0001)
        assertEquals(0.0, stats.latestCacheHitRate!!, 0.0)
        assertEquals(5.0, stats.outputTokensPerSecond!!, 0.0)
        assertEquals(200, stats.context)
        val auxiliary = RequestUsageRecord.json(LLMUsage(100, 5, cacheReadInputTokens = 900, latestContextTokens = 1000),
            RequestUsageRecord.Purpose.COMPACTION, 1000)
        val combined = SessionTokenStats.fromUsageRecords(listOf(
            """{"inputTokens":200,"latestContextTokens":200}""", auxiliary.toString()))
        assertEquals(200, combined.context) // Summary requests must not overwrite conversation pressure.
        assertEquals(90.0, combined.latestCacheHitRate!!, 0.0)
        assertEquals(1, combined.auxiliaryRequests)
        val parts = RequestUsageRecord.parts(RequestUsageRecord.Purpose.COMPACTION)
        assertTrue(JournalProjection.isHidden(parts))
        assertFalse(JournalProjection.isModelVisible(parts))
        assertTrue(JournalProjection.isModelVisible(RuntimeContextSnapshot.encode("facts")))
    }

    @Test fun summaryExecutionPreservesPrefixAndFailsClosed() = runBlocking {
        val provider = RecordingProvider()
        val observed = mutableListOf<Pair<LLMUsage, ModelAttributionSnapshot?>>()
        val engine = CompactionSummarizer(2000, { false }) { usage, _, attribution -> observed.add(usage to attribution) }
        val attribution = ModelAttributionSnapshot("test", "test", "openAI", "test-instance")
        val usage = LLMUsage(100, 5, cacheReadInputTokens = 900, latestContextTokens = 1000)
        provider.reply = listOf(LLMStreamChunk.Text("checkpoint"), LLMStreamChunk.Usage(LLMUsage(1, 0)), LLMStreamChunk.Usage(usage))
        val history = listOf(LLMMessage(LLMMessage.Role.USER, "original task"))
        val context = CompactionSummarizer.Context(provider, history, "summary policy", 1.0)
        var attempts = 0
        assertNull(engine.tryCached(history, context) { attempts++ })
        assertEquals(0, attempts)
        engine.remember(CompactionSummarizer.ConversationRequest(provider, history, "original policy", emptyList(), ThinkingLevel.OFF, attribution))
        assertEquals("checkpoint", engine.tryCached(history, context) { attempts++ })
        assertEquals(history, provider.sent.dropLast(1))
        assertEquals("original policy", provider.prompt)
        assertEquals(1, attempts)
        assertEquals(listOf(usage to attribution), observed)
        provider.reply = listOf(LLMStreamChunk.ToolUseStart("bad", "bash"))
        assertNull(engine.tryCached(history, context) { attempts++ })
        assertEquals(2, attempts)
        provider.reply = listOf(LLMStreamChunk.Text("checkpoint"))
        assertEquals("checkpoint", engine.transcript(provider, "transcript", "summary policy", 128000))
        assertTrue(provider.sent.single().content.contains("END OF CONVERSATION TO COMPACT."))
        assertEquals("summary policy", provider.prompt)
        engine.clear()
        assertNull(engine.tryCached(history, context) { attempts++ })
        assertEquals(2, attempts)
    }

    @Test fun coordinatorSplitsWithinBudgetWithoutAMergeCall() = runBlocking {
        val provider = RecordingProvider().apply { failures = 1 }
        val engine = CompactionSummarizer(2000, { true })
        val count = java.util.concurrent.atomic.AtomicInteger()
        val progress = mutableListOf<Pair<Int, Int>>()
        val coordinator = CompactionCoordinator(engine, count, 6, { true }) { depth, issued ->
            progress.add(depth to issued)
        }
        val messages = listOf(LLMMessage(LLMMessage.Role.USER, "task"), LLMMessage(LLMMessage.Role.ASSISTANT, "answer"))
        val context = CompactionSummarizer.Context(provider, emptyList(), "policy", 1.0)
        assertEquals("checkpoint\n\ncheckpoint", coordinator.summarize(messages, null, context, 128000))
        assertEquals(3, count.get())
        assertEquals(listOf(0 to 1, 1 to 2, 1 to 3), progress)
        val smallBudget = CompactionCoordinator(engine, java.util.concurrent.atomic.AtomicInteger(), 2, { true }) { _, _ -> }
        provider.failures = 1
        try { smallBudget.summarize(messages, null, context, 128000); fail("Unaffordable split accepted") }
        catch (_: IllegalStateException) { }
    }

    @Test fun effectiveHistoryKeepsBranchBoundariesAndStableWarmup() {
        val projection = HistoryProjection()
        val task = LLMMessage(LLMMessage.Role.USER, "old task", dbMessageId = "task")
        val call = LLMMessage(LLMMessage.Role.ASSISTANT, "", dbMessageId = "call",
            contentParts = listOf(AgentContentPart.ToolUse("tool", "bash", org.json.JSONObject())))
        val result = LLMMessage(LLMMessage.Role.USER, "", dbMessageId = "result",
            contentParts = listOf(AgentContentPart.ToolResult("tool", "bash", "x".repeat(1001))))
        val done = LLMMessage(LLMMessage.Role.ASSISTANT, "done", dbMessageId = "done")
        val next = LLMMessage(LLMMessage.Role.USER, "new task", dbMessageId = "next",
            contentParts = listOf(AgentContentPart.Text("new task")))
        val history = listOf(task, call, RuntimeContextSnapshot.message("facts", "runtime"), result, done, next)
        val marker = com.openminis.app.data.db.CompactMarkerEntity("marker", "session", "summary", 0, 5, 0,
            lastCompactedMessageId = "done", version = 2)
        assertEquals(0, projection.walkBack(history, 4, 1, 100).priorIdx)
        var trims = 0
        val first = projection.project(history, "summary", marker, 1) { warm, _, _ ->
            trims++; HistoryProjection.Trim(warm, false)
        }
        assertFalse(first.flatMap { it.contentParts }.any { it is AgentContentPart.ToolUse || it is AgentContentPart.ToolResult })
        assertTrue(first.last().content.contains("<context-summary>"))
        assertTrue((first.last().contentParts.first() as AgentContentPart.Text).text.contains("<context-summary>"))
        assertEquals(next.content, history.last().content) // Original branch never mutated.
        val second = projection.project(history, "summary", marker, 1) { _, _, _ ->
            trims++; HistoryProjection.Trim(emptyList(), true)
        }
        assertEquals(1, second.size)
        assertEquals(second, projection.project(history, "summary", marker, 1) { _, _, _ -> error("Pinned trim recomputed") })
        assertEquals(2, trims)
        val rewound = history.take(1)
        assertEquals(rewound, projection.project(rewound, "summary", marker, 1) { _, _, _ -> error("Missing anchor trimmed") })
        val paired = listOf(call.copy(contentParts = listOf(AgentContentPart.ToolUse("tool|fc", "bash", org.json.JSONObject()))),
            result.copy(contentParts = listOf(AgentContentPart.ToolResult("tool", "bash", "ok"))))
        assertSame(paired, ToolHistorySanitizer.repair(paired, "test"))
        val inFlight = listOf(task, call)
        assertSame(inFlight, ToolHistorySanitizer.repair(inFlight, "test"))
        val interrupted = ToolHistorySanitizer.repair(listOf(task, call, next), "test")
        assertTrue(interrupted.flatMap { it.contentParts }.filterIsInstance<AgentContentPart.ToolResult>().single().isError)
        assertEquals(listOf(task), ToolHistorySanitizer.repair(listOf(task, result), "test"))
        val legacy = marker.copy(version = 1, firstKeptMessageId = "next")
        assertEquals(next, projection.project(history, "summary", legacy, 1) { _, _, _ -> error("Legacy trim") }.last())
    }

    private class RecordingProvider : LLMProvider {
        override val name = "test"
        override var model = LLMModel("test", "test", "test", contextWindow = 128000)
        var failures = 0
        var sent = emptyList<LLMMessage>()
        var prompt: String? = null
        var reply: List<LLMStreamChunk> = listOf(LLMStreamChunk.Text("checkpoint"))
        override suspend fun sendMessageClamped(messages: List<LLMMessage>, systemPrompt: String?, maxTokens: Int,
            temperature: Double?, imageParts: List<LLMMessage.ImagePart>, tools: List<AgentToolDefinition>,
            thinkingLevel: ThinkingLevel): LLMResponse = error("Summaries must stream")
        override fun streamMessageClamped(messages: List<LLMMessage>, systemPrompt: String?, maxTokens: Int,
            temperature: Double?, imageParts: List<LLMMessage.ImagePart>, tools: List<AgentToolDefinition>,
            thinkingLevel: ThinkingLevel): Flow<LLMStreamChunk> {
            sent = messages; prompt = systemPrompt
            assertNull(temperature)
            assertEquals(ThinkingLevel.OFF, thinkingLevel)
            if (failures-- > 0) return kotlinx.coroutines.flow.flow { throw IllegalStateException("oversize") }
            return flowOf(*reply.toTypedArray())
        }
    }
}
