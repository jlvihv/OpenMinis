package com.openminis.app.agent

import android.content.Context
import com.openminis.app.agent.jobs.*
import com.openminis.app.data.repository.ChatRepository
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.data.model.SubAgentRoster
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.logging.AppLogger
import com.openminis.app.tools.ToolExecutionResult
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import org.json.JSONObject

/** Delegation/resume reservations, child execution, monitoring and durable completion lifecycles. */
internal class AgentSubagentRuntime(private val context: Context, private val repository: ChatRepository,
    private val providers: ProviderRepository, private val scope: CoroutineScope,
    private val journal: AgentSubagentJournal) {
    data class Parent(val sessionId: String, val toolId: String, val modelId: String?, val entryId: String?,
        val helper: Boolean, val priorDelegations: Int, val checkBranch: () -> Unit, val expectsResultRow: Boolean = true)
    data class Snapshot(val tool: String = "", val activity: String = "", val text: String = "",
        val turns: Int = 0, val error: String? = null)
    data class ResumeAnchor(val childId: String, val toolId: String, val title: String, val agent: String?)
    enum class Submission { SENT, QUEUED, COMPACTING, REJECTED }
    interface Child {
        val modelName: String
        val compacting: StateFlow<Boolean>
        val streaming: StateFlow<Boolean>
        suspend fun ready(): Boolean
        suspend fun submit(prompt: String): String?
        suspend fun submitResume(prompt: String): Submission
        suspend fun cancel()
        suspend fun settled()
        fun wrapUp()
        fun steer(message: String): Boolean
        fun missedSteers(): List<String>
        fun snapshot(): Snapshot
        suspend fun releaseTabs()
        fun stopTool()
    }
    data class Effects(
        val createChild: suspend (String, HelperConfig) -> Child,
        val queuedStarter: (AgentJobRegistry.QueuedDelegation) -> Boolean,
        val interruptedCount: () -> Int,
        val publish: suspend (String, AgentJobState?) -> Unit,
        val clear: suspend () -> Unit,
        val userWaiting: () -> Boolean,
        val progress: suspend (String, String?) -> String?,
    )

    /** Called on Main: claim the original child synchronously before any asynchronous setup. */
    fun resume(parent: Parent, anchor: ResumeAnchor, effects: Effects): Boolean {
        parent.checkBranch()
        if (!scope.isActive || parent.helper || !AgentJobRegistry.canStartChildJob || AgentJobRegistry.jobForSession(anchor.childId) != null) return false
        val roster = providers.subAgents
        val definition = SubAgentRoster.resolve(anchor.agent, roster) ?: roster.firstOrNull() ?: return false
        val owner = parent.copy(toolId = anchor.toolId)
        val job = AgentJobRegistry.register(title = anchor.title.ifEmpty { "agent" }, origin = AgentJobOrigin.TOOL,
            trigger = AgentJobTrigger.Immediate, target = AgentJobTarget.ChildOfCurrent(owner.sessionId, anchor.toolId),
            prompt = null, then = AgentJobThen.FollowUpParent(null), agentName = definition.name.takeIf { !definition.isBuiltIn },
            runSessionId = anchor.childId, wasResumed = true)
        AgentJobRegistry.registerInterruptedCounter(owner.sessionId, effects.interruptedCount)
        AgentJobRegistry.registerQueuedStarter(owner.sessionId, effects.queuedStarter)
        val live = java.util.concurrent.atomic.AtomicReference<Child?>()
        val verified = java.util.concurrent.atomic.AtomicBoolean(false)
        val failureText = java.util.concurrent.atomic.AtomicReference<String?>()
        val baseline = java.util.concurrent.atomic.AtomicReference<Set<String>?>()
        val submittedRun = java.util.concurrent.atomic.AtomicBoolean(false)
        AgentJobRegistry.setCompletionTask(job.id) { finished ->
            val run = live.get()
            if (run != null) withTimeoutOrNull(5_000L) { run.streaming.first { !it }; run.settled() }
            val before = baseline.get()
            val facts = HelperRunner.childRunFacts(if (verified.get() && submittedRun.get() && before != null)
                repository.dao.loadMessages(anchor.childId).filterNot { it.id in before } else emptyList())
            val summary = HelperRunner.runSummaryLine(facts.toolNames, facts.turns, facts.input, facts.output, facts.cacheRead)
            withContext(Dispatchers.Main) { AgentJobRegistry.setSummaryLine(job.id, summary) }
            val status = HelperRunner.resolvedStatus(HelperRunner.statusWord(finished.state), finished.resultText.orEmpty())
            val json = HelperRunner.resultJson(status, finished.resultText.orEmpty(), run?.modelName.orEmpty(),
                HelperModelTier.PRIMARY, "", facts.turns, finished.elapsedMs ?: 0L, anchor.childId, job.id, summary,
                deliveredAs = "new turn in this conversation", agentName = definition.name.takeIf { !definition.isBuiltIn },
                wasResumed = true, errorText = failureText.get() ?: run?.snapshot()?.error,
                thinkingLevel = if (verified.get()) repository.getSession(anchor.childId)?.thinkingOverride else null)
            journal.record(owner.sessionId, anchor.toolId, json, finished.state == AgentJobState.DONE, owner.expectsResultRow)
            if (!journal.isRetired(job.id)) effects.publish(json, finished.state)
        }
        scope.launch(Dispatchers.Main, start = CoroutineStart.UNDISPATCHED) {
            var child: Child? = null
            try {
                owner.checkBranch()
                val row = repository.getSession(anchor.childId)
                require(row != null && row.parentSessionId == owner.sessionId && row.parentToolUseId == anchor.toolId) {
                    "resumed child does not belong to the captured parent tool"
                }
                verified.set(true)
                baseline.set(repository.dao.loadMessages(anchor.childId).map { it.id }.toSet())
                owner.checkBranch()
                if (AgentJobRegistry.job(job.id)?.isActive != true) return@launch
                child = effects.createChild(anchor.childId, HelperConfig(owner.sessionId, anchor.toolId, job.id,
                    HelperRunner.MAX_TURNS, anchor.title, HelperModelTier.PRIMARY,
                    definition.name.takeIf { !definition.isBuiltIn }, definition.instructions))
                val run = child
                live.set(run)
                if (!run.ready()) error("resumed child could not resolve a provider")
                owner.checkBranch()
                if (AgentJobRegistry.job(job.id)?.isActive != true) { run.cancel(); return@launch }
                AgentJobRegistry.registerSteerHook(job.id, run::steer)
                AgentJobRegistry.registerMissedSteerDrain(job.id, run::missedSteers)
                AgentJobRegistry.registerTabRelease(job.id) { scope.launch(NonCancellable + Dispatchers.Main) { run.releaseTabs() } }
                AgentJobRegistry.markRunning(job.id, anchor.childId) { scope.launch(NonCancellable + Dispatchers.Main) { run.cancel() } }
                effects.publish(HelperRunner.progressJson(anchor.childId, anchor.title, "", "", 0L, background = true), AgentJobState.RUNNING)
                owner.checkBranch()
                val submitted = run.submitResume(HelperRunner.resumeNotice())
                submittedRun.set(submitted != Submission.REJECTED)
                if (submitted == Submission.REJECTED) {
                    AgentJobRegistry.finish(job.id, AgentJobState.FAILED, null)
                    return@launch
                }
                if (submitted == Submission.COMPACTING) {
                    run.compacting.first { !it }
                    withTimeoutOrNull(5_000L) { run.streaming.first { it } }
                }
                run.streaming.first { !it }
                withTimeoutOrNull(5_000L) { run.settled() }
                val before = checkNotNull(baseline.get())
                val facts = HelperRunner.childRunFacts(repository.dao.loadMessages(anchor.childId).filterNot { it.id in before })
                AgentJobRegistry.setSummaryLine(job.id, HelperRunner.runSummaryLine(facts.toolNames, facts.turns, facts.input, facts.output, facts.cacheRead))
                AgentJobRegistry.finish(job.id, if (run.snapshot().error != null) AgentJobState.FAILED else AgentJobState.DONE, facts.lastText)
            } catch (cancelled: CancellationException) {
                withContext(NonCancellable + Dispatchers.Main) { child?.cancel(); AgentJobRegistry.finish(job.id, AgentJobState.CANCELLED, null) }
                throw cancelled
            } catch (failure: Exception) {
                failureText.set(failure.message ?: failure.javaClass.simpleName)
                AppLogger.warning("AgentSubagentRuntime", "resume failed child=${anchor.childId.take(8)}: ${failure.message}")
                withContext(NonCancellable + Dispatchers.Main) { child?.cancel(); AgentJobRegistry.finish(job.id, AgentJobState.FAILED, null) }
            }
        }
        return true
    }

    /** Queue re-entry also runs under the runtime's scope, not a detached UI coroutine. */
    fun queued(args: String, parent: Parent, effects: Effects): Boolean {
        parent.checkBranch()
        if (!scope.isActive) return false
        val worker = scope.launch(Dispatchers.Main, start = CoroutineStart.UNDISPATCHED) {
            try {
                val result = execute(args, parent, effects)
                if (!result.success) {
                    journal.record(parent.sessionId, parent.toolId, result.output, false, parent.expectsResultRow)
                    effects.publish(result.output, AgentJobState.FAILED)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                AppLogger.warning("AgentSubagentRuntime", "queued delegation failed: ${failure.message}")
                val rejection = HelperRunner.rejectionJson("child_start_failed", failure.message ?: "The helper session did not start.")
                journal.record(parent.sessionId, parent.toolId, rejection, false, parent.expectsResultRow)
                effects.publish(rejection, AgentJobState.FAILED)
            }
        }
        worker.invokeOnCompletion { scope.launch(Dispatchers.Main) { AgentJobRegistry.drainIfStalled() } }
        return true
    }

    suspend fun execute(argsJson: String, parent: Parent, effects: Effects): ToolExecutionResult {
        val args = HelperRunner.parseArgs(argsJson)
        val title = args.title.ifEmpty { "agent" }
        fun reject(reason: String, detail: String) = ToolExecutionResult(HelperRunner.rejectionJson(reason, detail), false, toolTitle = title)
        parent.checkBranch()
        if (args.task.isEmpty()) return reject("empty_task", "`task` is required and must describe the whole job.")
        if (parent.helper) return reject("depth_limit", "Helpers cannot delegate further (max delegation depth is 1). Do the work yourself.")
        if (!AgentSettings.isEnabled(context)) return reject("disabled", "Delegation is turned off in Settings › Agents.")
        val roster = providers.subAgents
        val definition = SubAgentRoster.resolve(args.agentName, roster) ?: return reject("unknown_agent",
            "No sub agent named \"${args.agentName.orEmpty()}\". Available: ${roster.joinToString(", ") { it.name }}. Omit `agent` to use the general one.")
        val fromQueue = runCatching { JSONObject(argsJson).optBoolean(HelperRunner.QUEUED_REENTRY_KEY, false) }.getOrDefault(false)
        val overAllowance = !fromQueue && parent.priorDelegations >= HelperRunner.MAX_PER_ASSISTANT_TURN
        // Reserve on Main BEFORE any suspending child/session setup. PENDING owns a slot too.
        val reservation = withContext(Dispatchers.Main) {
            parent.checkBranch()
            AgentJobRegistry.registerQueuedStarter(parent.sessionId, effects.queuedStarter)
            AgentJobRegistry.registerInterruptedCounter(parent.sessionId, effects.interruptedCount)
            if (overAllowance || !AgentJobRegistry.canStartChildJob) null else AgentJobRegistry.register(
                title = title, origin = AgentJobOrigin.TOOL, trigger = AgentJobTrigger.Immediate,
                target = AgentJobTarget.ChildOfCurrent(parent.sessionId, parent.toolId), prompt = args.task,
                then = if (args.wait) AgentJobThen.None else AgentJobThen.FollowUpParent(null), agentName = definition.name)
        }
        if (reservation == null) {
            val queued = withContext(Dispatchers.Main) {
                parent.checkBranch()
                AgentJobRegistry.enqueueDelegation(AgentJobRegistry.QueuedDelegation(parent.sessionId, argsJson, parent.toolId, expectsResultRow = parent.expectsResultRow))
            }
            if (!queued) return reject("helper_limit", "${AgentJobRegistry.MAX_CONCURRENT_CHILD_JOBS} sub agents are already running and the queue is full. Wait for some to finish, then delegate this task again — it was NOT queued.")
            val detail = if (overAllowance) "More than ${HelperRunner.MAX_PER_ASSISTANT_TURN} delegations in one turn. This task is QUEUED and will start automatically as slots free; its result arrives as a new message like any other. Do not re-delegate it."
                else "All ${AgentJobRegistry.MAX_CONCURRENT_CHILD_JOBS} slots are busy. This task is QUEUED and will start automatically when one frees; its result arrives as a new message like any other. Do not re-delegate it."
            val json = HelperRunner.queuedJson(AgentJobRegistry.runningChildJobCount + AgentJobRegistry.queuedCount(parent.sessionId) - 1, detail)
            effects.publish(json, AgentJobState.RUNNING)
            scope.launch(Dispatchers.Main) { AgentJobRegistry.drainIfStalled() }
            return ToolExecutionResult(json, true, toolTitle = title)
        }
        var child: Child? = null
        var handedOff = false
        try {
            val parentRow = repository.getSession(parent.sessionId)
            val resolution = ModelTierResolver.resolveForSubAgent(definition.modelEntryId, args.modelChoice, providers,
                parentRow?.modelBinding, parentRow?.modelId ?: parent.modelId, parent.entryId)
                ?: return reject("no_model", "No model is configured for a helper to run on.")
            parent.checkBranch()
            val row = repository.createSession(modelId = resolution.seedModelId,
                title = HelperRunner.childSessionTitle(context, title, definition.name.takeIf { !definition.isBuiltIn }),
                parentSessionId = parent.sessionId, parentToolUseId = parent.toolId)
            parentRow?.source?.let { repository.dao.updateSource(row.id, it) }
            resolution.bindingJson?.let { repository.updateSessionBinding(row.id, it, resolution.seedModelId) }
            val thinking = definition.thinkingLevelOverride ?: parentRow?.thinkingOverride?.let { ThinkingLevel.decoded(it) }
            thinking?.let { repository.dao.updateThinkingOverride(row.id, it.name) }
            child = effects.createChild(row.id, HelperConfig(parent.sessionId, parent.toolId, reservation.id,
                HelperRunner.MAX_TURNS, title, resolution.tierUsed, definition.name.takeIf { !definition.isBuiltIn }, definition.instructions))
            val run = child
            withContext(Dispatchers.Main) {
                parent.checkBranch()
                AgentJobRegistry.setModelOrigin(reservation.id, resolution.origin.wire)
                AgentJobRegistry.setTierUsed(reservation.id, resolution.tierUsed.wire)
                AgentJobRegistry.setProgressLevel(reservation.id, if (args.wait) "none" else args.progressLevel)
                AgentJobRegistry.registerSteerHook(reservation.id, run::steer)
                AgentJobRegistry.registerMissedSteerDrain(reservation.id, run::missedSteers)
                AgentJobRegistry.registerTabRelease(reservation.id) {
                    scope.launch(NonCancellable + Dispatchers.Main) { run.releaseTabs() }
                }
            }
            val started = System.currentTimeMillis()
            effects.publish(HelperRunner.progressJson(row.id, title, "", "starting", 0, background = !args.wait), AgentJobState.RUNNING)
            if (!run.ready()) return reject("child_start_failed", "The helper session could not resolve a provider.")
            var immediateBackground: ToolExecutionResult? = null
            var startFailure: String? = null
            val sent = withContext(Dispatchers.Main) {
                parent.checkBranch()
                if (!AgentJobRegistry.job(reservation.id)!!.isActive) false else {
                    startFailure = run.submit(HelperRunner.childPrompt(args))
                    val accepted = startFailure == null
                    if (accepted) {
                        AgentJobRegistry.markRunning(reservation.id, row.id) {
                            scope.launch(NonCancellable + Dispatchers.Main) { run.cancel() }
                        }
                        if (!args.wait) immediateBackground = background(parent, effects, reservation.id, row.id,
                            run, resolution, args, title, started, false, definition.name)
                    }
                    accepted
                }
            }
            if (!sent) return reject("child_start_failed", "The helper session did not start (${startFailure ?: "job cancelled"}).")
            immediateBackground?.let { result ->
                handedOff = true
                return result
            }
            var status = "completed"
            var wrapUpAt: Long? = null
            var stoppedTool = false
            while (run.streaming.value) {
                currentCoroutineContext().ensureActive()
                val now = System.currentTimeMillis()
                if (now - started >= args.minutes * 60_000L) {
                    val asked = wrapUpAt
                    if (asked == null) { wrapUpAt = now; run.wrapUp() }
                    else if (now - asked >= HelperRunner.WRAP_UP_GRACE_MS) {
                        status = "timeout"
                        run.cancel()
                        withTimeoutOrNull(5_000L) { run.streaming.first { !it } }
                        break
                    } else if (now - asked >= HelperRunner.WRAP_UP_TOOL_PATIENCE_MS && !stoppedTool) {
                        stoppedTool = true; run.stopTool()
                    }
                }
                if (effects.userWaiting()) {
                    withContext(Dispatchers.Main) { AgentJobRegistry.setThen(reservation.id, AgentJobThen.FollowUpParent(null)) }
                    val result = background(parent, effects, reservation.id, row.id, run, resolution, args, title, started, true, definition.name,
                        wrapUpAt, stoppedTool)
                    handedOff = true
                    return result
                }
                val snapshot = run.snapshot()
                effects.publish(HelperRunner.progressJson(row.id, title, snapshot.tool, snapshot.activity, now - started), null)
                delay(1_000L)
            }
            withTimeoutOrNull(5_000L) { run.settled() }
            val facts = HelperRunner.childRunFacts(repository.dao.loadMessages(row.id))
            if (status == "completed") status = when {
                AgentJobRegistry.job(reservation.id)?.state == AgentJobState.CANCELLED -> "cancelled"
                run.snapshot().error != null -> "failed"
                else -> "completed"
            }
            status = HelperRunner.resolvedStatus(status, facts.lastText)
            val summary = HelperRunner.runSummaryLine(facts.toolNames, facts.turns, facts.input, facts.output, facts.cacheRead)
            withContext(Dispatchers.Main) {
                AgentJobRegistry.setSummaryLine(reservation.id, summary)
                AgentJobRegistry.finish(reservation.id, state(status), facts.lastText)
            }
            val json = resultJson(status, facts.lastText, resolution, args, facts.turns, System.currentTimeMillis() - started,
                row.id, reservation.id, summary, definition.name, run.snapshot().error, thinking?.name, false)
            effects.clear()
            handedOff = true
            return ToolExecutionResult(json, status == "completed", toolTitle = title)
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable + Dispatchers.Main) {
                AgentJobRegistry.setThen(reservation.id, AgentJobThen.None)
                child?.cancel()
                AgentJobRegistry.finish(reservation.id, AgentJobState.CANCELLED, null)
                effects.clear()
            }
            throw cancelled
        } finally {
            if (!handedOff) withContext(NonCancellable + Dispatchers.Main) {
                AgentJobRegistry.setThen(reservation.id, AgentJobThen.None)
                child?.cancel()
                AgentJobRegistry.finish(reservation.id, AgentJobState.FAILED, null)
                effects.clear()
            }
        }
    }

    private suspend fun background(parent: Parent, effects: Effects, jobId: String, childId: String, child: Child,
        resolution: HelperModelResolution, args: DelegateTaskArgs, title: String, started: Long,
        converted: Boolean, agent: String, wrapUpAt: Long? = null, toolStopped: Boolean = false): ToolExecutionResult {
        val monitors = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))
        try {
        withContext(Dispatchers.Main) {
            AgentJobRegistry.setCompletionTask(jobId) { finished ->
                monitors.cancel()
                withTimeoutOrNull(5_000L) { child.streaming.first { !it }; child.settled() }
                val facts = HelperRunner.childRunFacts(repository.dao.loadMessages(childId))
                val summary = HelperRunner.runSummaryLine(facts.toolNames, facts.turns, facts.input, facts.output, facts.cacheRead)
                withContext(Dispatchers.Main) { AgentJobRegistry.setSummaryLine(jobId, summary) }
                val status = HelperRunner.resolvedStatus(HelperRunner.statusWord(finished.state), finished.resultText.orEmpty())
                val json = resultJson(status, finished.resultText.orEmpty(), resolution, args, facts.turns,
                    finished.elapsedMs ?: System.currentTimeMillis() - started, childId, jobId, summary, agent,
                    child.snapshot().error, repository.getSession(childId)?.thinkingOverride, true)
                journal.record(parent.sessionId, parent.toolId, json, finished.state == AgentJobState.DONE, parent.expectsResultRow)
                if (!journal.isRetired(jobId)) effects.publish(json, finished.state)
            }
        }
        monitors.launch(Dispatchers.Default) {
            while (isActive && child.streaming.value && AgentJobRegistry.job(jobId)?.isActive == true) {
                delay(1_000L)
                withContext(Dispatchers.Main) { AgentJobRegistry.drainIfStalled() }
                if (!isActive || !child.streaming.value || AgentJobRegistry.job(jobId)?.isActive != true) break
                val snapshot = child.snapshot()
                effects.publish(HelperRunner.progressJson(childId, title, snapshot.tool,
                    snapshot.activity.ifEmpty { "running in background" }, System.currentTimeMillis() - started, background = true), AgentJobState.RUNNING)
            }
        }
        monitors.launch(Dispatchers.Default) {
            try {
                // Conversion does not buy another full budget or restart an already-running grace period.
                val remaining = (args.minutes * 60_000L - (System.currentTimeMillis() - started)).coerceAtLeast(1L)
                val ended = if (wrapUpAt != null) null else withTimeoutOrNull(remaining) { child.streaming.first { !it }; true }
                var timedOut = false
                if (ended == null && child.streaming.value) {
                    val asked = wrapUpAt ?: System.currentTimeMillis().also { child.wrapUp() }
                    val patience = (HelperRunner.WRAP_UP_TOOL_PATIENCE_MS - (System.currentTimeMillis() - asked)).coerceAtLeast(1L)
                    val patient = if (toolStopped) null else withTimeoutOrNull(patience) { child.streaming.first { !it }; true }
                    if (patient == null && child.streaming.value) {
                        if (!toolStopped) child.stopTool()
                        val grace = (HelperRunner.WRAP_UP_GRACE_MS - (System.currentTimeMillis() - asked)).coerceAtLeast(1L)
                        val graceful = withTimeoutOrNull(grace) { child.streaming.first { !it }; true }
                        if (graceful == null && child.streaming.value) {
                            timedOut = true; child.cancel()
                            withTimeoutOrNull(5_000L) { child.streaming.first { !it } }
                        }
                    }
                }
                withTimeoutOrNull(5_000L) { child.settled() }
                val facts = HelperRunner.childRunFacts(repository.dao.loadMessages(childId))
                withContext(Dispatchers.Main) {
                    AgentJobRegistry.setSummaryLine(jobId, HelperRunner.runSummaryLine(facts.toolNames, facts.turns, facts.input, facts.output, facts.cacheRead))
                    AgentJobRegistry.finish(jobId, when { timedOut -> AgentJobState.TIMEOUT; child.snapshot().error != null -> AgentJobState.FAILED; else -> AgentJobState.DONE }, facts.lastText)
                }
            } catch (cancelled: CancellationException) {
                withContext(NonCancellable + Dispatchers.Main) {
                    if (AgentJobRegistry.job(jobId)?.isActive == true) { child.cancel(); AgentJobRegistry.finish(jobId, AgentJobState.CANCELLED, null) }
                }
                throw cancelled
            } catch (failure: Exception) {
                AppLogger.warning("AgentSubagentRuntime", "background watcher failed: ${failure.message}")
                withContext(NonCancellable + Dispatchers.Main) { child.cancel(); AgentJobRegistry.finish(jobId, AgentJobState.FAILED, null) }
            }
        }
        progress(monitors, parent, effects, jobId, childId, child, title, started, args.progressLevel)
        return ToolExecutionResult(HelperRunner.backgroundStartJson(jobId, childId, resolution.modelLabel,
            resolution.tierUsed, args.minutes, converted, resolution = resolution), true, toolTitle = title)
        } catch (failure: Throwable) {
            monitors.cancel()
            throw failure
        }
    }

    private fun progress(monitors: CoroutineScope, parent: Parent, effects: Effects, jobId: String,
        childId: String, child: Child, title: String, started: Long, level: String) {
        val interval = when (level) { "frequent" -> HelperRunner.PROGRESS_INTERVAL_FREQUENT_MS; "moderate" -> HelperRunner.PROGRESS_INTERVAL_MODERATE_MS; else -> return }
        monitors.launch(Dispatchers.Default) {
            var signature = ""
            var pending: String? = null
            try {
            while (isActive) {
                delay(interval)
                val job = AgentJobRegistry.job(jobId) ?: break
                if (!child.streaming.value || job.state != AgentJobState.RUNNING) break
                val snapshot = child.snapshot()
                val next = "${snapshot.tool}|${snapshot.activity}|${snapshot.turns}|${snapshot.text.take(200)}"
                if (level == "frequent" && next == signature) continue
                signature = next
                val facts = HelperRunner.childRunFacts(repository.dao.loadMessages(childId))
                val summary = HelperRunner.runSummaryLine(facts.toolNames, facts.turns, facts.input, facts.output, facts.cacheRead)
                withContext(Dispatchers.Main) { AgentJobRegistry.setSummaryLine(jobId, summary) }
                val text = AgentCallback(kind = AgentCallback.Kind.PROGRESS, jobId = jobId, childSessionId = childId,
                    title = title, status = "running", tier = job.tierUsed,
                    elapsed = AgentJobRegistry.elapsedClock(System.currentTimeMillis() - started), tool = snapshot.tool,
                    activity = snapshot.activity.take(80), turn = snapshot.turns, agentName = job.agentName,
                    summary = summary.removePrefix("Summary: "), siblings = AgentJobRegistry.siblingSummary(parent.sessionId,
                        jobId, effects.interruptedCount()), body = snapshot.text.ifBlank { "(no message from the agent yet)" }.take(HelperRunner.PROGRESS_LAST_MESSAGE_MAX_CHARS)).xml
                pending = effects.progress(text, pending)
            }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { AppLogger.warning("AgentSubagentRuntime", "progress reporter failed: ${failure.message}") }
        }
    }

    private fun state(status: String) = when (status) {
        "completed", HelperRunner.NO_DELIVERABLE -> AgentJobState.DONE
        "cancelled" -> AgentJobState.CANCELLED
        "timeout" -> AgentJobState.TIMEOUT
        else -> AgentJobState.FAILED
    }

    private fun resultJson(status: String, text: String, resolution: HelperModelResolution, args: DelegateTaskArgs,
        turns: Int, elapsed: Long, childId: String, jobId: String, summary: String, agent: String?, error: String?,
        thinking: String?, background: Boolean) = HelperRunner.resultJson(status, text, resolution.modelLabel,
        resolution.tierUsed, args.tierRequested, turns, elapsed, childId, jobId, summary,
        deliveredAs = if (background) "new turn in this conversation" else null, agentName = agent,
        modelOrigin = resolution.origin, modelGroupUnavailable = resolution.modelGroupUnavailable,
        modelGroupName = resolution.modelGroupName, wasResumed = false, errorText = error,
        resolvedEntryId = resolution.resolvedEntryId, resolvedProviderLabel = resolution.resolvedProviderLabel,
        resolvedProviderType = resolution.resolvedProviderType, resolvedModelId = resolution.resolvedModelId,
        resolvedModelName = resolution.resolvedModelName, thinkingLevel = thinking)
}
