package com.openminis.app.agent

import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMStreamChunk
import com.openminis.app.data.model.LLMUsage
import com.openminis.app.data.model.ModelAttributionSnapshot
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.provider.LLMProvider
import com.openminis.app.scheduled.ScriptedToolTurn
import com.openminis.app.logging.AppLogger
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.collect

/** Owns request construction, normalized stream collection and journal receipts for one model turn. */
internal class AgentModelTurn(
    private val sessionId: String,
    private val turn: Int,
    private val planner: AgentContextPlanner,
    private val summarizer: CompactionSummarizer,
    val content: AgentTurnContent,
    private val trace: AgentToolInputTrace,
    private val journal: AgentTurnJournal,
    attribution: ModelAttributionSnapshot,
) {
    data class Input(
        val provider: LLMProvider,
        val history: List<LLMMessage>,
        val window: Int,
        val system: String?,
        val tools: List<AgentToolDefinition>,
        val thinking: ThinkingLevel,
        val attribution: ModelAttributionSnapshot,
    )

    var usage: LLMUsage? = null
        private set
    var attribution = attribution
        private set
    var calibration: Triple<Int, Int, String?>? = null
        private set
    var dispatch: AgentContextPlanner.Dispatch? = null
        private set
    var streamMs = 0L
        private set
    var reportedContext = 0
        private set

    init { receipt() }

    suspend fun collect(
        provider: LLMProvider,
        scripted: ScriptedToolTurn?,
        historySize: Int,
        prepare: suspend () -> Input,
        wireHistory: suspend (List<LLMMessage>) -> List<LLMMessage>,
        firstChunk: () -> Unit,
        consume: suspend (LLMStreamChunk) -> Unit,
        completed: suspend () -> Unit,
    ) {
        dispatch = null
        calibration = null
        val attempt = AgentStreamAttempt(sessionId, turn, provider.javaClass.simpleName, historySize,
            content, trace, provider.streamTextIsMonolithic, firstChunk,
            duration = { elapsed -> streamMs += elapsed; journal.recordDuration(streamMs) })
        attempt.collect(create = {
            if (scripted != null) {
                AppLogger.info("ChatVMStream", "[ScheduledPrefill] turn=$turn streaming ${scripted.calls.size} prefilled tool call(s) [${scripted.calls.joinToString { it.toolName + ":" + it.id }}] in place of a model request")
                flowOf(*scripted.asStreamChunks().toTypedArray())
            } else {
                val input = prepare()
                val model = input.provider.model
                val plan = planner.plan(input.provider, model, input.history, input.window, input.system, input.tools, input.thinking)
                dispatch = plan.dispatch
                attribution = input.attribution
                AppLogger.info("ChatViewModel", "[CtxMeter] dispatch model=${model.id} estimate=${plan.dispatch.estimate} ratio=${"%.3f".format(plan.dispatch.ratio)} predicted=${plan.dispatch.predicted} maxTokens=${plan.maxTokens}")
                val history = wireHistory(input.history)
                val warm = CompactionSummarizer.ConversationRequest(input.provider, CachedCompaction.snapshot(history),
                    input.system, input.tools, plan.thinking, input.attribution)
                val stream = input.provider.streamMessage(history, input.system, plan.maxTokens, tools = input.tools, thinkingLevel = plan.thinking)
                kotlinx.coroutines.flow.flow {
                    stream.collect { chunk ->
                        if (chunk is LLMStreamChunk.Usage && chunk.usage.latestContextTokens > 0) summarizer.remember(warm)
                        emit(chunk)
                    }
                }
            }
        }, consume = { chunk ->
            if (chunk is LLMStreamChunk.Usage) {
                usage = chunk.usage
                reportedContext = planner.reportedContext(chunk.usage)
                if (reportedContext > 0) calibration = planner.calibrate(dispatch, reportedContext)
                receipt()
            }
            consume(chunk)
        }, completed = completed)
    }

    fun noteOverflow(detail: String) { planner.overflow(dispatch, detail) }

    private fun receipt() { journal.recordReceipt(AgentJournalWriter.Receipt(usage, streamMs, attribution, calibration)) }
}
