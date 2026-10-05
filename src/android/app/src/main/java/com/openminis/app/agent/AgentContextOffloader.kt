package com.openminis.app.agent

import android.content.Context
import com.openminis.app.data.*
import com.openminis.app.data.db.CompactMarkerEntity
import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.logging.AppLogger
import kotlinx.coroutines.CancellationException

/** Owns lossless disk spill decisions and request-history substitution on the captured branch. */
internal class AgentContextOffloader(private val context: Context, private val agentHistory: MutableList<LLMMessage>,
    private val projection: HistoryProjection, private val keepTurns: Int, private val currentSession: () -> String) {
    private fun checkBranch(session: String) {
        if (currentSession() != session) throw CancellationException("context offload branch changed")
    }
    private fun base64Size(bytes: Int): Int = if (bytes <= 0) 0 else (bytes + 2) / 3 * 4
    fun healOverflow(sid: String): Boolean {
        checkBranch(sid)
        if (sid.isEmpty()) return false
        val protectedCount = minOf(4, agentHistory.size)
        val upper = agentHistory.size - protectedCount
        if (upper <= 0) return false

        var bestMsg = -1
        var bestPart = -1
        var bestBytes = 0
        var bestKind = ""
        for (msgIdx in 0 until upper) {
            for ((partIdx, part) in agentHistory[msgIdx].contentParts.withIndex()) {
                val (bytes, kind) = when (part) {
                    is AgentContentPart.ImageData -> base64Size(part.data.size) to "image"
                    is AgentContentPart.ToolResult -> {
                        if (ContextOffload.isOffloadPlaceholder(part.content)) continue
                        (part.content.length + base64Size(part.imageData?.size ?: 0)) to "tool_result"
                    }
                    else -> continue
                }
                if (bytes > bestBytes) {
                    bestBytes = bytes; bestMsg = msgIdx; bestPart = partIdx; bestKind = kind
                }
            }
        }
        if (bestMsg < 0 || bestBytes < PayloadSizeAudit.MIN_BYTES_TO_AUDIT) return false

        val msg = agentHistory[bestMsg]
        val parts = msg.contentParts.toMutableList()
        val part = parts[bestPart]
        val tokens = countPartTokens(part)
        var resultReplacement: AgentContentPart.ToolResult? = null
        val linuxPath = when (part) {
            is AgentContentPart.ImageData -> ContextOffload.offloadImage(
                context, sid, part.data,
                toolId = "overflow_${bestMsg}_$bestPart",
                mimeType = part.mimeType,
            )
            is AgentContentPart.ToolResult -> {
                val imgPath = part.imageData?.let { data ->
                    ContextOffload.offloadImage(
                        context, sid, data,
                        toolId = part.id,
                        mimeType = part.imageMimeType ?: "image/png",
                    )
                }
                if (part.imageData != null && imgPath.isNullOrBlank()) return false
                val textPath = if (part.content.isNotEmpty()) ContextOffload.offloadContent(
                    context, sid, part.content, toolId = part.id, toolName = part.name) else null
                if (part.content.isNotEmpty() && textPath.isNullOrBlank()) return false
                val paths = listOfNotNull(textPath, imgPath).filter { it.isNotBlank() }
                resultReplacement = part.copy(content = paths.joinToString("\n") { ContextOffload.stub(tokens, bestBytes, it) },
                    imageData = null, imageMimeType = null)
                paths.firstOrNull().orEmpty()
            }
            else -> ""
        }
        if (linuxPath.isEmpty()) return false

        val stub = ContextOffload.stub(tokens, bestBytes, linuxPath)
        parts[bestPart] = when (part) {
            is AgentContentPart.ToolResult ->
                checkNotNull(resultReplacement)
            else -> AgentContentPart.Text(stub)
        }
        checkBranch(sid)
        agentHistory[bestMsg] = msg.copy(contentParts = parts)
        AppLogger.warning(
            TAG,
            "[OverflowSelfHeal] offloaded msgIdx=$bestMsg partIdx=$bestPart kind=$bestKind " +
                "bytes=$bestBytes (~$tokens tokens) → $linuxPath",
        )
        return true
    }

    private fun estimateContextTokens(): Int {
        var totalChars = 0
        var imageTokens = 0
        for (msg in agentHistory) {
            for (part in msg.contentParts) {
                when (part) {
                    is AgentContentPart.Text -> totalChars += part.text.length
                    is AgentContentPart.ToolUse -> totalChars += part.input.toString().length
                    is AgentContentPart.ToolResult -> {
                        totalChars += part.content.length
                        part.imageData?.let { imageTokens += BPETokenizer.countImageTokens(it) }
                    }
                    is AgentContentPart.ImageData -> {
                        imageTokens += BPETokenizer.countImageTokens(part.data)
                    }
                }
            }
        }
        return (totalChars / 3.5).toInt() + imageTokens
    }

    private fun countPartTokens(part: AgentContentPart): Int = when (part) {
        is AgentContentPart.Text -> BPETokenizer.countTokens(part.text)
        is AgentContentPart.ToolUse -> BPETokenizer.countTokens(part.input.toString())
        is AgentContentPart.ToolResult -> {
            BPETokenizer.countTokens(part.content) +
                (part.imageData?.let { BPETokenizer.countImageTokens(it) } ?: 0)
        }
        is AgentContentPart.ImageData -> BPETokenizer.countImageTokens(part.data)
    }

    private fun offloadScanStartIndex(summary: String?, marker: CompactMarkerEntity?): Int {
        if (summary.isNullOrBlank()) return 0
        val marker = marker ?: return 0
        if (marker.version < 2) return 0
        val anchorId = marker.lastCompactedMessageId?.takeIf { it.isNotEmpty() } ?: return 0
        val anchorIdx = agentHistory.indexOfLast { it.dbMessageId == anchorId }
        return if (anchorIdx < 0) 0 else anchorIdx + 1
    }

    private fun offloadWarmUpScanPlan(summary: String?, marker: CompactMarkerEntity?): Pair<List<Int>, Set<String>> {
        val none = emptyList<Int>() to emptySet<String>()
        if (summary.isNullOrBlank()) return none
        val marker = marker ?: return none
        if (marker.version < 2) return none
        val anchorId = marker.lastCompactedMessageId?.takeIf { it.isNotEmpty() } ?: return none
        val anchorIdx = agentHistory.indexOfLast { it.dbMessageId == anchorId }
        if (anchorIdx < 0) return none
        val priorIdx = projection.walkBack(agentHistory, anchorIdx, keepTurns, 100).priorIdx ?: return none
        if (priorIdx > anchorIdx) return none
        return warmUpScanIndices(agentHistory, priorIdx, anchorIdx, projection.dropForMarker(marker.id))
    }

    private data class OffloadCandidate(
        val msgIdx: Int,
        val partIdx: Int,
        val tokens: Int,
        val bytes: Int,
        val toolId: String,
        val toolName: String,
    )

    fun offload(
        sid: String, contextWindow: Int,
        lastContextTokens: Int,
        summary: String?, marker: CompactMarkerEntity?, policyOverride: ContextPolicy?, calibrationRatio: Double,
        force: Boolean = false,
    ) {
        checkBranch(sid)
        val policy = policyOverride
            ?: ContextPolicy.forContextWindow(contextWindow)

        if (!force && policy.offloadThreshold == 0) {
            return
        }

        val effectiveTokens =
            if (lastContextTokens > 0) lastContextTokens else estimateContextTokens()

        if (!force && effectiveTokens < policy.offloadThreshold) {
            return
        }

        val targetTokens = if (force) 0 else policy.offloadTarget
        val beforeTokens = effectiveTokens
        var currentTokens = effectiveTokens
        val pct = (effectiveTokens.toLong() * 100 / contextWindow.coerceAtLeast(1)).toInt()
        val remaining = contextWindow - beforeTokens

        AppLogger.info(TAG, "━━━ Context Offload Triggered ━━━")
        AppLogger.info(TAG, "  Window: $contextWindow tokens")
        AppLogger.info(TAG, "  Before: $beforeTokens tokens ($pct% of window, ~$remaining remaining)")
        if (force) {
            AppLogger.info(TAG, "  Mode: FORCE — offloading all eligible candidates")
        } else {
            AppLogger.info(TAG, "  Threshold: ${policy.offloadThreshold} → Target: $targetTokens")
            AppLogger.info(TAG, "  Need to free: ~${beforeTokens - targetTokens} tokens")
        }
        AppLogger.info(TAG, "  Agent history: ${agentHistory.size} messages")

        val protectedCount = minOf(4, agentHistory.size)
        val candidateUpper = agentHistory.size - protectedCount
        val scanStart = minOf(offloadScanStartIndex(summary, marker), candidateUpper)
        AppLogger.info(
            TAG,
            "  Scanning messages $scanStart..<$candidateUpper (last $protectedCount protected, " +
                "$scanStart before the compaction anchor skipped)",
        )

        val candidates = mutableListOf<OffloadCandidate>()
        var skippedAlreadyOffloaded = 0
        var skippedTooSmall = 0
        var skippedUnanswered = 0
        val warmUp = offloadWarmUpScanPlan(summary, marker)
        val warmUpIndices = warmUp.first.filter { it < scanStart }
        val answeredFrom = minOf(warmUpIndices.firstOrNull() ?: scanStart, scanStart)
        if (warmUpIndices.isNotEmpty()) {
            AppLogger.info(
                TAG,
                "  Warm-up before the anchor: scanning ${warmUpIndices.size} sent message(s) " +
                    "(${warmUp.second.size} pruned tool id(s) skipped)",
            )
        }
        val answeredToolUseIds: Set<String> = buildSet {
            for (m in agentHistory.subList(answeredFrom, agentHistory.size)) {
                for (pt in m.contentParts) {
                    if (pt is AgentContentPart.ToolResult) add(pt.id)
                }
            }
        }

        for (msgIdx in warmUpIndices + (scanStart until candidateUpper)) {
            val msg = agentHistory[msgIdx]
            val inWarmUp = msgIdx < scanStart
            for ((partIdx, part) in msg.contentParts.withIndex()) {
                if (inWarmUp) {
                    val prunedId = when (part) {
                        is AgentContentPart.ToolUse -> part.id
                        is AgentContentPart.ToolResult -> part.id
                        else -> null
                    }
                    if (prunedId != null && prunedId in warmUp.second) continue
                }
                when (part) {
                    is AgentContentPart.ToolResult -> {
                        if (ContextOffload.isOffloadPlaceholder(part.content)) {
                            skippedAlreadyOffloaded++
                            continue
                        }
                        val hasLargeContent = part.content.length > 500
                        val hasLargeImage = (part.imageData?.size ?: 0) > 1024
                        if (!hasLargeContent && !hasLargeImage) {
                            skippedTooSmall++
                            continue
                        }
                        val tokens = ContextSizeMeter.estimateTokens(part)
                        val bytes = part.content.toByteArray(Charsets.UTF_8).size +
                            (part.imageData?.size ?: 0)
                        candidates.add(OffloadCandidate(msgIdx, partIdx, tokens, bytes, part.id, part.name))
                    }
                    is AgentContentPart.ToolUse -> {
                        if (!com.openminis.app.tools.CoreToolNames.isMutation(part.name)) continue
                        val content = part.input.optString("content", "")

                        if (part.isOffloadedArgument ||
                            ContextOffload.isOffloadPlaceholder(content)
                        ) {
                            skippedAlreadyOffloaded++
                            continue
                        }

                        if (part.id !in answeredToolUseIds) {
                            skippedUnanswered++
                            continue
                        }

                        if (content.length <= 500) continue
                        val tokens = ContextSizeMeter.estimateTokens(part)
                        val bytes = content.toByteArray(Charsets.UTF_8).size
                        candidates.add(OffloadCandidate(msgIdx, partIdx, tokens, bytes, part.id, part.name))
                    }
                    is AgentContentPart.ImageData -> {
                        if (part.data.size <= 1024) {
                            skippedTooSmall++
                            continue
                        }
                        val tokens = ContextSizeMeter.estimateTokens(part)
                        val synthId = "img${msgIdx}_$partIdx"
                        candidates.add(OffloadCandidate(msgIdx, partIdx, tokens, part.data.size, synthId, "image"))
                    }
                    is AgentContentPart.Text -> Unit
                }
            }
        }

        candidates.sortByDescending { it.tokens }
        val totalCandidateTokens = candidates.sumOf { it.tokens }
        AppLogger.info(TAG, "  Candidates: ${candidates.size} parts (~$totalCandidateTokens est. tokens total)")
        AppLogger.info(
            TAG,
            "  Skipped: $skippedAlreadyOffloaded already offloaded, $skippedTooSmall too small, " +
                "$skippedUnanswered unanswered tool calls",
        )

        var offloadedCount = 0
        var freedTokens = 0

        for (candidate in candidates) {
            if (currentTokens <= targetTokens) break
            val candidateTokens = ContextSizeMeter.calibrated(candidate.tokens, calibrationRatio)

            val msg = agentHistory[candidate.msgIdx]
            val parts = msg.contentParts.toMutableList()
            val part = parts[candidate.partIdx]
            var linuxPath = ""

            val newPart: AgentContentPart? = when (part) {
                is AgentContentPart.ToolResult -> {
                    if (part.content.length > 500) {
                        linuxPath = ContextOffload.offloadContent(
                            context, sid, part.content,
                            toolId = part.id, toolName = part.name,
                        )
                    }
                    val textPath = linuxPath
                    val imgPath = part.imageData?.let { data ->
                        if (data.size > 1024) {
                            ContextOffload.offloadImage(
                                context, sid, data,
                                toolId = part.id,
                                mimeType = part.imageMimeType ?: "image/png",
                            )
                        } else ""
                    } ?: ""
                    if (linuxPath.isEmpty()) linuxPath = imgPath
                    // Never discard text/image bytes that did not obtain a durable spill path.
                    if (part.content.length > 500 && textPath.isBlank() ||
                        (part.imageData?.size ?: 0) > 1024 && imgPath.isBlank()) continue
                    val textSpilled = part.content.length > 500
                    val imageSpilled = imgPath.isNotBlank()
                    val body = if (textSpilled) ContextOffload.stub(candidateTokens, candidate.bytes, textPath) else part.content
                    val imageNote = if (imageSpilled) "\n" + ContextOffload.stub(candidateTokens, candidate.bytes, imgPath) else ""
                    val output = if (textSpilled) body + imageNote else
                        imageNote.trimStart() + if (body.isNotEmpty()) "\n$body" else ""
                    part.copy(content = output,
                        imageData = if (imageSpilled) null else part.imageData,
                        imageMimeType = if (imageSpilled) null else part.imageMimeType)
                }
                is AgentContentPart.ToolUse -> {
                    val content = part.input.optString("content", "")
                    linuxPath = ContextOffload.offloadContent(
                        context, sid, content,
                        toolId = part.id, toolName = part.name,
                    )
                    val newInput = org.json.JSONObject(part.input.toString())
                    newInput.put(
                        "content",
                        ContextOffload.prunedArgumentNotice(
                            candidateTokens, candidate.bytes, linuxPath,
                        ),
                    )
                    part.copy(input = newInput, isOffloadedArgument = true)
                }
                is AgentContentPart.ImageData -> {
                    linuxPath = ContextOffload.offloadImage(
                        context, sid, part.data,
                        toolId = candidate.toolId,
                        mimeType = part.mimeType,
                    )
                    AgentContentPart.Text(
                        ContextOffload.stub(candidateTokens, candidate.bytes, linuxPath),
                    )
                }
                is AgentContentPart.Text -> null
            }

            if (newPart == null || linuxPath.isBlank()) continue
            checkBranch(sid)
            parts[candidate.partIdx] = newPart
            agentHistory[candidate.msgIdx] = msg.copy(contentParts = parts)

            val saved = (candidateTokens - ContextSizeMeter.calibrated(ContextSizeMeter.estimateTokens(newPart), calibrationRatio)).coerceAtLeast(0)
            currentTokens -= saved
            freedTokens += saved
            offloadedCount++
            val afterPct = (currentTokens.toLong() * 100 / contextWindow.coerceAtLeast(1)).toInt()
            AppLogger.info(
                TAG,
                "  ✂ Offloaded #$offloadedCount: [${candidate.toolName}] id:${candidate.toolId.take(8)} ~$candidateTokens tokens (${candidate.bytes} bytes) → $linuxPath [now $currentTokens ($afterPct%)]",
            )
        }

        if (offloadedCount > 0) {
            val afterPct = (currentTokens.toLong() * 100 / contextWindow.coerceAtLeast(1)).toInt()
            AppLogger.info(TAG, "━━━ Context Offload Complete ━━━")
            AppLogger.info(TAG, "  Parts offloaded: $offloadedCount")
            AppLogger.info(TAG, "  Tokens freed: ~$freedTokens")
            AppLogger.info(TAG, "  Before: $beforeTokens/$contextWindow ($pct%)")
            AppLogger.info(TAG, "  After:  $currentTokens/$contextWindow ($afterPct%)")
            AppLogger.info(TAG, "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        }
    }

    companion object {
        private const val TAG = "AgentContextOffloader"
        fun warmUpScanIndices(
            history: List<LLMMessage>,
            priorIdx: Int,
            anchorIdx: Int,
            drop: Int?,
        ): Pair<List<Int>, Set<String>> {
            if (priorIdx < 0 || priorIdx > anchorIdx || anchorIdx >= history.size) return emptyList<Int>() to emptySet()
            val pruned = HashSet<String>()
            for (i in priorIdx..anchorIdx) {
                for (part in history[i].contentParts) {
                    if (part is AgentContentPart.ToolResult && part.content.length > 1000) pruned.add(part.id)
                }
            }
            val kept = ArrayList<Int>()
            for (i in priorIdx..anchorIdx) {
                val parts = history[i].contentParts
                val survives = parts.isEmpty() || parts.any { part ->
                    when (part) {
                        is AgentContentPart.ToolUse -> part.id !in pruned
                        is AgentContentPart.ToolResult -> part.id !in pruned
                        else -> true
                    }
                }
                if (survives) kept.add(i)
            }
            while (kept.isNotEmpty() && history[kept.first()].role != LLMMessage.Role.USER) kept.removeAt(0)
            val trimmed = drop?.let { kept.drop(minOf(it, kept.size)) } ?: kept
            return trimmed to pruned
        }
    }
}
