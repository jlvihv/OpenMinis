package com.openminis.app.agent

import com.openminis.app.data.db.CompactMarkerEntity
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.repository.ChatRepository
import com.openminis.app.logging.AppLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/** Captured compaction range and durable marker publication; UI receives committed snapshots only. */
internal class AgentCompactionJournal(
    private val repository: ChatRepository,
    val sessionId: String,
    val plan: Plan,
    private val currentSession: () -> String,
) {
    data class Plan(val history: List<LLMMessage>, val previous: CompactMarkerEntity?,
        val anchor: Int, val start: Int) {
        val messages: List<LLMMessage> get() = history.subList(start, anchor + 1)
    }
    enum class Rejection { EMPTY, NO_PERSISTED_ANCHOR, ALREADY_COMPACTED }
    sealed interface Preparation {
        data class Ready(val plan: Plan) : Preparation
        data class Rejected(val reason: Rejection) : Preparation
    }
    @Volatile var committed: CompactMarkerEntity? = null
        private set

    suspend fun commit(summary: String, publish: (CompactMarkerEntity) -> Unit,
        restored: (CompactMarkerEntity?) -> Unit): CompactMarkerEntity {
        coroutineContext.ensureActive()
        checkBranch()
        require(summary.isNotBlank()) { "Compaction produced no output" }
        val ids = withContext(Dispatchers.IO) { repository.dao.loadMessages(sessionId).map { it.id }.toSet() }
        checkBranch()
        var anchor = plan.anchor
        while (anchor >= plan.start && (plan.history[anchor].dbMessageId.isNullOrEmpty() || plan.history[anchor].dbMessageId !in ids)) anchor--
        check(anchor >= plan.start) { "Compaction could not anchor to a persisted message" }
        if (anchor != plan.anchor) AppLogger.warning("AgentCompactionJournal", "anchor walked back ${plan.anchor} → $anchor")
        val marker = CompactMarkerEntity(id = java.util.UUID.randomUUID().toString(), sessionId = sessionId,
            summary = summary, firstKeptSortOrder = Int.MAX_VALUE, compactedCount = plan.messages.size,
            createdAt = System.currentTimeMillis(), lastCompactedMessageId = plan.history[anchor].dbMessageId,
            version = 2)
        var inserted = false
        coroutineContext.ensureActive()
        try {
            return withContext(NonCancellable + Dispatchers.IO) {
                checkBranch()
                repository.dao.insertCompactMarker(marker)
                inserted = true
                withContext(NonCancellable + Dispatchers.Main) {
                    checkBranch()
                    publish(marker)
                    committed = marker
                }
                marker
            }
        } catch (failure: Exception) {
            if (inserted && committed == null) withContext(NonCancellable + Dispatchers.IO) {
                try {
                    val removed = repository.dao.deleteCompactMarker(marker.id)
                    withContext(NonCancellable + Dispatchers.Main) {
                        if (currentSession() == sessionId) restored(plan.previous)
                    }
                    AppLogger.info("AgentCompactionJournal", "rolled back marker ${marker.id.take(8)} rows=$removed")
                } catch (rollbackFailure: Exception) {
                    AppLogger.warning("AgentCompactionJournal", "marker rollback failed: ${rollbackFailure.javaClass.simpleName}")
                }
            }
            throw failure
        }
    }

    private fun checkBranch() {
        if (currentSession() != sessionId) throw CancellationException("compaction branch changed")
    }

    companion object {
        fun prepare(history: List<LLMMessage>, previous: CompactMarkerEntity?, anchorOverride: Int?): Preparation {
            if (history.isEmpty()) return Preparation.Rejected(Rejection.EMPTY)
            val snapshot = CachedCompaction.snapshot(history)
            var anchor = anchorOverride?.coerceIn(0, snapshot.lastIndex) ?: snapshot.lastIndex
            while (anchor >= 0 && snapshot[anchor].dbMessageId.isNullOrEmpty()) anchor--
            if (anchor < 0) return Preparation.Rejected(Rejection.NO_PERSISTED_ANCHOR)
            val previousId = previous?.let { if (it.version >= 2) it.lastCompactedMessageId?.takeIf(String::isNotEmpty)
                else it.firstKeptMessageId?.takeIf(String::isNotEmpty) ?: it.boundaryMessageId?.takeIf(String::isNotEmpty) }
            val previousIndex = previousId?.let { id -> snapshot.indexOfFirst { it.dbMessageId == id } } ?: -1
            val start = if (previousIndex < 0) 0 else if ((previous?.version ?: 1) >= 2) previousIndex + 1 else previousIndex
            if (start > anchor) return Preparation.Rejected(Rejection.ALREADY_COMPACTED)
            return Preparation.Ready(Plan(snapshot, previous, anchor, start))
        }
    }
}
