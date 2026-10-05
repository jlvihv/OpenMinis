package com.openminis.app.agent

import com.openminis.app.data.ContextPolicy
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.FallbackStrategy
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMStreamChunk
import com.openminis.app.data.model.ModelAttributionSnapshot
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.logging.AppLogger
import com.openminis.app.provider.LLMProvider
import com.openminis.app.scheduled.ScriptedToolTurn

/** Owns the whole conversation loop; the host supplies snapshots, execution adapters and display effects. */
internal class AgentConversationRuntime<T>(
    private val writer: AgentJournalWriter,
    private val history: MutableList<LLMMessage>,
    currentSession: () -> String,
    initial: Selection<T>,
    prompt: String?,
    private val turnCap: Int,
    private val helper: Boolean,
    private val planner: AgentContextPlanner,
    private val cancellation: AgentCancellationCoordinator,
    private val summarizer: CompactionSummarizer,
    private val detector: ToolLoopDetector,
    traceLimit: Int,
    private val cancelledMarker: String,
    scripted: ScriptedToolTurn?,
    private val resumePrevious: Boolean = false,
) {
    data class Selection<T>(val provider: LLMProvider, val candidates: List<T>, val strategy: FallbackStrategy,
        val entryId: String?, val attribution: ModelAttributionSnapshot)
    data class Models<T>(
        val begin: suspend (LLMProvider) -> LLMProvider?,
        val takeSelection: suspend (LLMProvider) -> Selection<T>?,
        val identity: (T) -> String,
        val candidates: (LLMProvider) -> List<T>,
        val provider: (T) -> LLMProvider,
        val attribution: (T) -> ModelAttributionSnapshot,
        val prompt: (LLMProvider, String?) -> String?,
        // Compare-and-bind on the host's owning dispatcher, never overwrite a later user choice.
        val bindFallback: suspend (LLMProvider?, T, Boolean) -> Boolean,
    )
    data class Context(
        val sanitize: () -> Unit,
        val snapshot: suspend () -> Unit,
        val tools: () -> List<AgentToolDefinition>,
        val window: () -> Int?,
        val offload: suspend (Int, Int) -> Unit,
        val measurement: () -> AgentContextPlanner.Measurement,
        val measured: () -> Int,
        val policy: () -> Pair<ContextPolicy, Int>?,
        val compact: suspend () -> Boolean,
        val compacting: (Int, Int) -> Unit,
    )
    data class Presentation(
        val started: suspend () -> String,
        val compacted: suspend () -> String,
        val contextStopped: suspend () -> Unit,
        val steer: suspend (LLMMessage, AgentLoopEngine.Turn) -> Unit,
        val converged: suspend (AgentTurnContinuation.Decision, AgentLoopEngine.Turn) -> Unit,
        val limitReached: suspend () -> Unit,
    )
    data class InputSource(val history: List<LLMMessage>, val window: Int, val thinking: ThinkingLevel)
    data class RequestEffects(
        val enhancedCache: () -> Boolean,
        val configure: (LLMProvider) -> Unit,
        val source: suspend () -> InputSource,
        val wireHistory: suspend (List<LLMMessage>) -> List<LLMMessage>,
        val firstChunk: () -> Unit,
        val consume: suspend (LLMStreamChunk) -> Unit,
        val completed: suspend () -> Unit,
    )
    data class InsertedReply(val bubbleId: String, val scripted: ScriptedToolTurn?)
    data class TurnEffects<T>(
        val request: RequestEffects,
        val recovery: AgentTurnRuntime.Recovery<T>,
        val tools: AgentTurnRuntime.Tools,
        val presentation: AgentTurnRuntime.Presentation,
        val completion: () -> AgentTurnRuntime.Completion,
        val boundary: AgentTurnRuntime.Boundary,
        val insertedReply: () -> InsertedReply?,
    )

    val conversation = AgentConversationJournal(writer, history, currentSession)
    private val continuation = AgentTurnContinuation(history, writer)
    private val reply = AgentReplyContent()
    private val trace = AgentToolInputTrace(traceLimit)
    private val helperPolicy = AgentHelperTurnPolicy(history, helper)
    val toolsWithdrawn: Boolean get() = helperPolicy.toolsWithdrawn
    var provider = initial.provider
        private set
    var attribution = initial.attribution
        private set
    var systemPrompt = prompt
        private set
    var contextTokens = 0
        private set
    private var entryId = initial.entryId
    private var pendingScripted = scripted
    private var bubbleId = ""
    private var expectedBinding: LLMProvider? = null
    private val initialSelection = initial
    private var providerRecovery: AgentProviderRecovery<T>? = null
    val failureTrail: List<String> get() = providerRecovery?.failureTrail.orEmpty()

    suspend fun run(models: Models<T>, context: Context, presentation: Presentation,
        wrapUpRequested: () -> Boolean, steers: () -> List<String>,
        turnEffects: (AgentLoopEngine.Turn, () -> Int) -> TurnEffects<T>): AgentLoopEngine.Outcome {
        check(providerRecovery == null) { "conversation runtime can only run once" }
        expectedBinding = models.begin(provider)
        conversation.checkBranch()
        if (resumePrevious) conversation.prepareResume()
        val gate = AgentContextGate(planner, conversation::checkBranch)
        val recovery = AgentProviderRecovery(initialSelection.candidates, initialSelection.strategy, entryId,
            models.identity, models.candidates)
        providerRecovery = recovery
        val cycle = AgentRequestCycle(recovery, { provider }, { entryId }, models.provider)
        bubbleId = presentation.started()
        val outcome = AgentLoopEngine(turnCap).run conversationTurn@ { frame ->
            conversation.checkBranch()
            models.takeSelection(provider)?.let { selection ->
                recovery.switch(selection.candidates, selection.strategy, selection.entryId)
                val from = provider.model.displayName
                provider = selection.provider
                attribution = selection.attribution
                entryId = selection.entryId
                expectedBinding = provider
                systemPrompt = models.prompt(provider, systemPrompt)
                AppLogger.info("AgentConversationRuntime", "[SwitchModel] $from → ${provider.model.displayName} turn=${frame.index + 1}")
            }
            conversation.checkBranch()
            context.sanitize()
            helperPolicy.beforeTurn(frame, wrapUpRequested())
            if (helper) {
                val pending = steers()
                if (pending.isNotEmpty()) {
                    val message = checkNotNull(conversation.appendSteers(pending))
                    presentation.steer(message, frame)
                }
            }
            conversation.checkBranch()
            context.snapshot()
            conversation.checkBranch()
            planner.updateFixedTokens(systemPrompt, if (toolsWithdrawn) emptyList() else context.tools())
            context.window()?.takeIf { it > 0 }?.let { context.offload(it, context.measured()) }
            when (gate.check(context.measurement(), context.policy(), context.compact, context.measured, context.compacting)) {
                AgentContextGate.Action.COMPACTED -> {
                    bubbleId = presentation.compacted()
                    trace.clear()
                    return@conversationTurn AgentLoopEngine.Action.Next
                }
                AgentContextGate.Action.STOP -> {
                    presentation.contextStopped()
                    return@conversationTurn AgentLoopEngine.Action.Stop(AgentLoopEngine.StopReason.CONTEXT)
                }
                AgentContextGate.Action.PROCEED -> Unit
            }
            val turn = AgentTurnRuntime(writer, conversation, continuation, reply, cancellation, planner,
                summarizer, trace, detector, cycle, frame.index, bubbleId, cancelledMarker, attribution)
            val effects = turnEffects(frame) { turn.reportedContext }
            val request = effects.request
            val result = turn.execute(
                request = AgentTurnRuntime.Request(provider = { conversation.checkBranch(); provider }, historySize = { history.size },
                    scripted = {
                        val pending = pendingScripted.takeUnless { toolsWithdrawn }
                        pendingScripted = null
                        pending
                    }, enhancedCache = request.enhancedCache, configure = request.configure,
                    prepare = {
                        conversation.checkBranch()
                        val source = request.source()
                        conversation.checkBranch()
                        AgentModelTurn.Input(provider, source.history, source.window, systemPrompt,
                            if (toolsWithdrawn) emptyList() else context.tools(), source.thinking, attribution)
                    }, wireHistory = { history ->
                        conversation.checkBranch()
                        request.wireHistory(history).also { conversation.checkBranch() }
                    }, firstChunk = request.firstChunk,
                    consume = { chunk ->
                        if (chunk is LLMStreamChunk.Usage && turn.reportedContext > 0) contextTokens = turn.reportedContext
                        request.consume(chunk)
                    }, completed = request.completed),
                recovery = effects.recovery.copy(adopted = { candidate, reason, realChange ->
                    conversation.checkBranch()
                    provider = models.provider(candidate)
                    entryId = models.identity(candidate)
                    attribution = models.attribution(candidate)
                    if (models.bindFallback(expectedBinding, candidate, realChange)) expectedBinding = provider
                    conversation.checkBranch()
                    effects.recovery.adopted(candidate, reason, realChange)
                }), tools = effects.tools, presentation = effects.presentation, completion = effects.completion,
                boundary = effects.boundary.copy(insert = { ids ->
                    val inserted = effects.boundary.insert(ids)
                    if (inserted) {
                        val next = checkNotNull(effects.insertedReply()) { "committed prompt has no reply descriptor" }
                        bubbleId = next.bubbleId
                        next.scripted?.let { pendingScripted = it }
                    }
                    inserted
                }))
            conversation.checkBranch()
            when (result) {
                is AgentTurnRuntime.Result.Converged -> {
                    presentation.converged(result.decision, frame)
                    result.decision.action
                }
                AgentTurnRuntime.Result.DelegationStopped -> AgentLoopEngine.Action.Stop(AgentLoopEngine.StopReason.DELEGATION_STOPPED)
                AgentTurnRuntime.Result.ToolsCommitted -> AgentLoopEngine.Action.Next
            }
        }
        conversation.checkBranch()
        if (outcome is AgentLoopEngine.Outcome.LimitReached) presentation.limitReached()
        return outcome
    }
}
