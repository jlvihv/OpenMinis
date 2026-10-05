package com.openminis.app.agent

import com.openminis.app.data.db.MessageEntity
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMStreamChunk
import com.openminis.app.data.model.ModelAttributionSnapshot
import com.openminis.app.provider.LLMProvider
import com.openminis.app.provider.anthropic.AnthropicProvider
import com.openminis.app.scheduled.ScriptedToolTurn
import com.openminis.app.tools.ToolExecutionResult
import com.openminis.app.logging.AppLogger
import org.json.JSONObject

/** Owns a turn's request -> tools -> durable commits -> convergence ordering. */
internal class AgentTurnRuntime<T>(
    private val writer: AgentJournalWriter,
    private val conversation: AgentConversationJournal,
    private val continuation: AgentTurnContinuation,
    private val reply: AgentReplyContent,
    cancellation: AgentCancellationCoordinator,
    planner: AgentContextPlanner,
    summarizer: CompactionSummarizer,
    private val trace: AgentToolInputTrace,
    private val detector: ToolLoopDetector,
    private val cycle: AgentRequestCycle<T>,
    private val turn: Int,
    private val bubbleId: String,
    cancelledMarker: String,
    attribution: ModelAttributionSnapshot,
) {
    private val content = AgentTurnContent()
    private val journal = cancellation.begin(writer, content, bubbleId, cancelledMarker)
    private val model = AgentModelTurn(writer.sessionId, turn, planner, summarizer, content, trace, journal, attribution)
    val reportedContext: Int get() = model.reportedContext

    data class Request(
        val provider: () -> LLMProvider,
        val historySize: () -> Int,
        val scripted: () -> ScriptedToolTurn?,
        val enhancedCache: () -> Boolean,
        val prepare: suspend () -> AgentModelTurn.Input,
        val wireHistory: suspend (List<LLMMessage>) -> List<LLMMessage>,
        val configure: (LLMProvider) -> Unit,
        val firstChunk: () -> Unit,
        val consume: suspend (LLMStreamChunk) -> Unit,
        val completed: suspend () -> Unit,
    )
    data class Recovery<T>(
        val retrying: suspend (Throwable, AgentRequestRecovery.Retry) -> Unit,
        val countdown: (Int) -> Unit,
        val retryCancelled: () -> Unit,
        val clearRetry: suspend () -> Unit,
        val rollbackPresentation: suspend (Boolean) -> Unit,
        val healOverflow: suspend () -> Boolean,
        val adopted: suspend (T, String, Boolean) -> Unit,
        val skippedCandidates: () -> List<String>,
    )
    data class Tools(
        val definitions: () -> List<AgentToolDefinition>,
        val concurrency: Int,
        val starting: (String, JSONObject) -> Unit,
        val running: suspend (String) -> Unit,
        val blocked: suspend (String, String) -> Unit,
        val finished: suspend (String, String, ToolExecutionResult, Boolean) -> Unit,
        val invoke: suspend (String, String, String) -> ToolExecutionResult,
    )
    data class Presentation(
        val modelFinished: suspend (String, Boolean) -> Unit,
        val metadata: () -> Map<String, AgentJournalWriter.ToolPresentation>,
        val awaiting: suspend () -> Unit,
        val committed: suspend (MessageEntity) -> Unit,
    )
    data class Completion(
        val window: Int?, val userWaiting: Boolean,
        val scheduledNudge: String, val helper: Boolean, val turnCap: Int, val pendingSteers: Int,
    )
    data class Boundary(
        val queue: () -> List<AgentToolBoundary.Prompt>,
        val exchangeCompleted: suspend () -> Unit,
        val stopped: suspend (List<String>) -> Unit,
        val insert: suspend (List<String>) -> Boolean,
    )
    sealed interface Result {
        data class Converged(val decision: AgentTurnContinuation.Decision) : Result
        data object ToolsCommitted : Result
        data object DelegationStopped : Result
    }

    suspend fun execute(request: Request, recovery: Recovery<T>, tools: Tools,
        presentation: Presentation, completion: () -> Completion, boundary: Boundary): Result {
        cycle.run(attempt = {
            val provider = request.provider()
            (provider as? AnthropicProvider)?.enhancedCache = request.enhancedCache()
            request.configure(provider)
            model.collect(provider, request.scripted(), request.historySize(), request.prepare,
                request.wireHistory, request.firstChunk, request.consume, request.completed)
        }, retrying = recovery.retrying, countdown = recovery.countdown,
            retryCancelled = recovery.retryCancelled, clearRetry = recovery.clearRetry,
            rollback = { discard ->
                journal.retireAttempt()
                recovery.rollbackPresentation(discard)
                content.resetAttempt()
            }, healOverflow = { error ->
                model.noteOverflow(error.detail)
                recovery.healOverflow()
            }, adopted = recovery.adopted, skippedCandidates = recovery.skippedCandidates)

        val text = content.visibleText()
        val calls = content.calls
        conversation.appendAssistant(content)
        if (text.isEmpty() && calls.isEmpty()) {
            AppLogger.warning("AgentTurnRuntime", "empty turn=$turn finishReason=${content.finishReason} reasoningBlobLen=${content.opaqueReasoningLength ?: -1} model=${request.provider().model.id}")
        }
        reply.accept(bubbleId, content)
        presentation.modelFinished(text, calls.isNotEmpty())
        if (calls.isEmpty()) {
            commitAssistant(presentation)
            val input = completion()
            return Result.Converged(continuation.converged(reply.visible, content.finishReason,
                input.window, model.usage?.latestContextTokens ?: 0, input.userWaiting, input.scheduledNudge,
                input.helper, turn, input.turnCap, input.pendingSteers))
        }

        val metadata = presentation.metadata()
        journal.recordPresentation(metadata)
        writer.preview(content.parts(), metadata)
        val results = AgentToolRound(detector, trace, tools.definitions()).execute(calls, tools.concurrency,
            journal, tools.starting, tools.running, tools.blocked, tools.finished, tools.invoke)
        presentation.awaiting()
        commitAssistant(presentation)
        conversation.commitResults(journal, results)
        boundary.exchangeCompleted()
        conversation.checkBranch()
        val muted = com.openminis.app.agent.jobs.AgentJobRegistry.isDelegationMuted(writer.sessionId)
        when (val plan = AgentToolBoundary.decide(muted, boundary.queue())) {
            is AgentToolBoundary.Plan.Stop -> {
                AppLogger.info("AgentTurnRuntime", "[delegate_task] turn=$turn delegation stopped; results retained, dropping ${plan.dropIds.size} callback(s)")
                boundary.stopped(plan.dropIds)
                return Result.DelegationStopped
            }
            is AgentToolBoundary.Plan.Insert -> {
                AppLogger.info("AgentTurnRuntime", "[QueueInsert] turn=$turn count=${plan.ids.size} scheduled=${plan.scheduled}")
                if (boundary.insert(plan.ids)) {
                    continuation.promptInserted(plan.scheduled)
                    trace.clear()
                }
            }
            AgentToolBoundary.Plan.Continue -> Unit
        }
        return Result.ToolsCommitted
    }

    private suspend fun commitAssistant(presentation: Presentation) {
        conversation.commitAssistant(model, presentation.metadata())?.let { presentation.committed(it) }
    }
}
