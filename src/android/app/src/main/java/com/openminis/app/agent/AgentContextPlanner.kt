package com.openminis.app.agent

import com.openminis.app.data.ContextPolicy
import com.openminis.app.data.ContextSizeMeter
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.LLMUsage
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.logging.AppLogger
import com.openminis.app.provider.LLMProvider

internal class AgentContextPlanner {
    private companion object {
        const val MIN_OUTPUT_TOKENS = 1024
        const val MAX_OUTPUT_TOKENS = 128_000
    }
    data class Measurement(val history: Int, val fixed: Int, val ratio: Double, val source: String, val measured: Int)
    data class Dispatch(
        val sessionId: String?, val generation: Long, val model: LLMModel, val window: Int,
        val fixed: Int, val estimate: Int, val ratio: Double, val predicted: Int,
    )
    data class Plan(val dispatch: Dispatch, val maxTokens: Int, val thinking: ThinkingLevel)

    private val ratios = mutableMapOf<String, Double>()
    private var lastLearned: Double? = null
    private var sessionId: String? = null
    private var generation: Long = 0
    @Volatile var fixedTokens: Int = 0
        private set
    @Volatile var lastDispatchRatio: Double = 1.0
        private set

    @Volatile var extrapolatedSendUsed: Boolean = false
        private set
    @Volatile var compactionMadeNoProgress: Boolean = false
        private set

    @Synchronized fun beginLoop() { compactionMadeNoProgress = false }
    @Synchronized fun markCompactionNoProgress() { compactionMadeNoProgress = true }
    @Synchronized fun noteCompaction(before: Int, after: Int) { compactionMadeNoProgress = after >= before }

    @Synchronized fun settle(measurement: Measurement, window: Int): ContextPolicy.InLoopStep {
        val step = ContextPolicy.inLoopStep(ContextPolicy.CheckResult.NEEDS_COMPACT,
            measurement.measured, measurement.history + measurement.fixed, window,
            canCompact = false, ratio = measurement.ratio, uncalibratedSendUsed = extrapolatedSendUsed)
        if (step == ContextPolicy.InLoopStep.SEND_UNCALIBRATED_ONCE) extrapolatedSendUsed = true
        return step
    }

    @Synchronized fun updateFixedTokens(system: String?, tools: List<AgentToolDefinition>) {
        fixedTokens = ContextSizeMeter.estimateFixedTokens(system, tools)
    }

    @Synchronized fun ratio(modelId: String?): Double = ContextSizeMeter.ratioFor(modelId, ratios, lastLearned)

    @Synchronized fun measure(history: List<LLMMessage>, modelId: String?, override: Double? = null): Measurement {
        val historyTokens = ContextSizeMeter.estimateTokens(history)
        val factor = override ?: ratio(modelId)
        val source = if (override != null) "forced" else ContextSizeMeter.ratioSource(modelId, ratios, lastLearned)
        return Measurement(historyTokens, fixedTokens, factor, source,
            ContextSizeMeter.calibrated(historyTokens + fixedTokens, factor))
    }

    @Synchronized fun plan(provider: LLMProvider, model: LLMModel, history: List<LLMMessage>, window: Int,
        system: String?, tools: List<AgentToolDefinition>, thinking: ThinkingLevel): Plan {
        val dispatch = dispatch(history, model, window, ContextSizeMeter.estimateFixedTokens(system, tools))
        return Plan(dispatch, outputBudget(provider, dispatch.predicted, window, model),
            if (model.supportsReasoning == true) thinking else ThinkingLevel.OFF)
    }

    private fun dispatch(history: List<LLMMessage>, model: LLMModel, window: Int, fixed: Int): Dispatch {
        fixedTokens = fixed
        val estimate = ContextSizeMeter.estimateTokens(history) + fixed
        val factor = ratio(model.id)
        lastDispatchRatio = factor
        return Dispatch(sessionId, generation, model, window, fixed, estimate, factor,
            ContextSizeMeter.calibrated(estimate, factor))
    }

    @Synchronized fun calibrate(dispatch: Dispatch?, reported: Int): Triple<Int, Int, String?>? {
        if (dispatch == null || dispatch.sessionId != sessionId) return null
        if (dispatch.generation != generation) return null
        val sample = ContextSizeMeter.calibrationRatio(reported, dispatch.estimate) ?: return null
        val model = dispatch.model.id
        val own = ratios[model]
        val updated = ContextSizeMeter.smoothed(own, sample)
        ratios[model] = updated
        lastLearned = updated
        extrapolatedSendUsed = false
        compactionMadeNoProgress = false
        val err = if (dispatch.predicted > 0) (dispatch.predicted - reported) * 100.0 / reported else 0.0
        AppLogger.info("ChatViewModel",
            "[CtxMeter] actual model=$model predicted=${dispatch.predicted} reported=$reported " +
                "err=${"%+.1f".format(err)}% estimate=${dispatch.estimate} sample=${"%.3f".format(sample)} " +
                "ratio=${"%.3f".format(dispatch.ratio)}→${"%.3f".format(updated)}" +
                if (own == null) " (first own sample)" else "")
        return Triple(dispatch.estimate, dispatch.fixed, model)
    }

    @Synchronized fun overflow(dispatch: Dispatch?, detail: String): Boolean {
        if (dispatch == null || dispatch.sessionId != sessionId) return false
        if (dispatch.generation != generation) return false
        val model = dispatch.model.id
        val current = ratio(model)
        val requested = ContextSizeMeter.requestedTokens(detail)
        val raised = ContextSizeMeter.ratioAfterOverflow(current, dispatch.estimate, requested, dispatch.window)
        ratios[model] = raised
        lastLearned = raised
        extrapolatedSendUsed = true
        AppLogger.warning("ChatViewModel",
            "[CtxMeter] rejected predicted=${dispatch.predicted} — provider rejected the request as too long — calibration model=$model " +
                "${"%.2f".format(current)} → ${"%.2f".format(raised)} (estimated=${dispatch.estimate} " +
                "statedTokens=${requested ?: "none"} window=${dispatch.window}); the next attempt will compact")
        return true
    }

    @Synchronized fun seed(sid: String, usageJsons: List<String>): Boolean {
        val learned = if (sid.isNotEmpty() && sessionId == sid)
            ContextSizeMeter.CalibrationState(ratios.toMap(), lastLearned, fixedTokens) else null
        val seeded = ContextSizeMeter.replayCalibration(usageJsons.mapNotNull(ContextSizeMeter::calibrationSample))
        val state = learned?.let { seeded.carryingOver(it) } ?: seeded
        ratios.clear()
        ratios.putAll(state.ratios)
        lastLearned = state.lastLearned
        fixedTokens = state.fixedTokens
        generation++
        sessionId = sid
        if (learned == null) extrapolatedSendUsed = false
        AppLogger.info("ChatViewModel",
            "[CtxMeter] seed session=${sid.take(8)} samples=${seeded.samples} keptInMemory=${learned?.ratios?.size ?: 0} " +
                "ratios=${state.ratios.entries.sortedBy { it.key }.joinToString(",") { "${it.key}=${"%.3f".format(it.value)}" }}")
        return seeded.samples > 0 || !learned?.ratios.isNullOrEmpty()
    }

    fun reportedContext(usage: LLMUsage): Int {
        if (usage.latestContextTokens > 0) return usage.latestContextTokens
        return (usage.inputTokens.coerceAtLeast(0).toLong() +
            (usage.cacheReadInputTokens ?: 0).coerceAtLeast(0) +
            (usage.cacheCreationInputTokens ?: 0).coerceAtLeast(0))
            .coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    fun outputBudget(provider: LLMProvider, inputTokens: Int, window: Int, model: LLMModel = provider.model): Int {
        val ceiling = minOf(MAX_OUTPUT_TOKENS, provider.effectiveMaxOutputTokens(model))
        if (window <= 0) return ceiling
        val input = inputTokens.coerceAtLeast(0)
        val remaining = window - input
        if (remaining <= 0) AppLogger.warning("ChatViewModel",
            "dynamicMaxTokens: input $input EXCEEDS window $window " +
                "(over by ${input - window}) — compaction guard should have fired")
        val result = minOf(ceiling, maxOf(remaining, MIN_OUTPUT_TOKENS))
        if (result < ceiling) AppLogger.info("ChatViewModel",
            "dynamicMaxTokens: $result (remaining=$remaining, ceiling=$ceiling, window=$window, input=$input, model=${model.id})")
        return result
    }
}
