package com.openminis.app.agent

import com.openminis.app.data.ContextPolicy
import com.openminis.app.logging.AppLogger
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

/** Run-owned context pressure, compaction budget and no-progress settlement. */
internal class AgentContextGate(private val planner: AgentContextPlanner, private val branch: () -> Unit) {
    enum class Action { PROCEED, COMPACTED, STOP }
    private var compactions = 0
    private val limit = 3

    init { planner.beginLoop() }

    suspend fun check(measurement: AgentContextPlanner.Measurement, policyWindow: Pair<ContextPolicy, Int>?,
        compact: suspend () -> Boolean, measuredAfter: () -> Int,
        compacting: (Int, Int) -> Unit): Action {
        coroutineContext.ensureActive()
        branch()
        val tokens = measurement.measured
        if (tokens <= 0 || policyWindow == null) return Action.PROCEED
        val (policy, window) = policyWindow
        val verdict = policy.check(tokens, window)
        AppLogger.info("AgentContextGate", "[CtxMeter] in-loop history=${measurement.history} fixed=${measurement.fixed} ratio=${measurement.ratio}(${measurement.source}) measured=$tokens threshold=${policy.compactThreshold} window=$window verdict=$verdict")
        when (verdict) {
            ContextPolicy.CheckResult.OK -> return Action.PROCEED
            ContextPolicy.CheckResult.EXHAUSTED -> return Action.STOP
            ContextPolicy.CheckResult.NEEDS_COMPACT -> {
                if (compactions >= limit || planner.compactionMadeNoProgress) return settle(measurement, window)
                // Auto-compact preferences govern send-time interaction, not an in-flight loop.
                AppLogger.info("AgentContextGate", "[AutoCompact] mid-loop compact #${compactions + 1}: $tokens / $window")
                compacting(tokens, window)
                val success = compact()
                coroutineContext.ensureActive()
                branch()
                if (!success) {
                    planner.markCompactionNoProgress()
                    return settle(measurement, window)
                }
                val after = measuredAfter()
                planner.noteCompaction(tokens, after)
                compactions++
                AppLogger.info("AgentContextGate", "[AutoCompact] result measured $tokens → $after noProgress=${planner.compactionMadeNoProgress}")
                return Action.COMPACTED
            }
        }
    }

    private fun settle(measurement: AgentContextPlanner.Measurement, window: Int): Action {
        val step = planner.settle(measurement, window)
        AppLogger.info("AgentContextGate", "[AutoCompact] settle after $compactions compaction(s) measured=${measurement.measured} raw=${measurement.history + measurement.fixed} window=$window noProgress=${planner.compactionMadeNoProgress} step=$step")
        return when (step) {
            ContextPolicy.InLoopStep.SEND_WITHIN_WINDOW, ContextPolicy.InLoopStep.SEND_UNCALIBRATED_ONCE -> Action.PROCEED
            else -> Action.STOP
        }
    }
}
