package com.openminis.app.ui.chat

import com.openminis.app.data.storage.PastedMedia

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.compose.foundation.lazy.LazyListState
import com.openminis.app.agent.Level
import com.openminis.app.agent.ToolLoopDetector
import com.openminis.app.browser.BrowserActionInput
import com.openminis.app.browser.BrowserTabPool
import com.openminis.app.data.db.MessageEntity
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Compress
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Extension
import com.openminis.app.data.BPETokenizer
import com.openminis.app.data.ContextOffload
import com.openminis.app.data.ContextPolicy
import com.openminis.app.data.ContextSizeMeter
import com.openminis.app.data.model.SessionTokenStats
import com.openminis.app.agent.JournalProjection
import com.openminis.app.data.PayloadSizeAudit
import com.openminis.app.logging.AppLogger
import com.openminis.app.data.FileMentionIndex
import com.openminis.app.data.db.CompactMarkerEntity
import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.LLMError
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.LLMStreamChunk
import com.openminis.app.data.model.LLMUsage
import com.openminis.app.data.model.ModelGroup
import com.openminis.app.data.model.RoutingStrategy
import com.openminis.app.data.model.hasImageInput
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.R
import com.openminis.app.data.repository.ChatRepository
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.provider.ImageBudget
import com.openminis.app.provider.LLMProvider
import com.openminis.app.provider.ProviderFactory
import com.openminis.app.provider.catalogMaxThinkingLevel
import com.openminis.app.agent.CompactionSummarizer
import com.openminis.app.agent.AgentLoopEngine
import com.openminis.app.agent.AgentConversationRuntime
import com.openminis.app.provider.effectiveMaxThinkingLevel
import com.openminis.app.sandbox.ExecutionCoordinator
import com.openminis.app.terminal.MinisOpenUrlBroker
import com.openminis.app.tools.AgentTools
import com.openminis.app.tools.ImageReader
import com.openminis.app.tools.ToolExecutionResult
import com.openminis.app.offload.OffloadPermissionManager
import com.openminis.app.service.SessionActivityTracker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.json.JSONObject
import java.io.ByteArrayOutputStream

// [T-android-split-chat] StreamingDelta / ChatMessage / QueuedPrompt /
// ToolBlockStatus / SlashCommand / AssistantBlock moved verbatim to ChatModels.kt.

class ChatViewModel(
    internal val sessionId: String,
    private val chatRepository: ChatRepository,
    private val providerRepository: ProviderRepository,
    internal val context: Context,
    val skillRepository: com.openminis.app.data.repository.SkillRepository? = null,
    val mcpRepository: com.openminis.app.data.repository.MCPRepository? = null,
) : ViewModel() {

    companion object {
        /**
         * [T-fallback-respects-user-switch] Whether a mid-turn group fallback may
         * adopt its new member as the SESSION binding: only while the class-level
         * provider is still the one the loop started from (identity, not model id —
         * the user re-picking an entry builds a new provider). Otherwise the user
         * chose a model while the turn ran and that choice wins.
         */
        internal fun fallbackMayRebindSession(classProviderNow: Any?, classProviderAtSend: Any?): Boolean =
            classProviderNow === classProviderAtSend

        /**
         * [T-android-switch-model-next-request] Whether the loop should adopt
         * the user's pick now: a switch is pending and the picker has already
         * put a different provider in place. Kept pending while the picker has
         * not rebound yet, so a head that runs between the flag and the rebind
         * does not lose the switch.
         */
        internal fun modelSwitchToApply(pending: Boolean, chosen: Any?, loopProvider: Any?): Boolean =
            pending && chosen != null && chosen !== loopProvider

        /**
         * [T-android-concurrent-tools] Ceiling on tool calls executed at once
         * within one assistant turn.
         *
         * iOS uses 10 (AIChatViewModel+ConcurrentTools.maxConcurrentTools) and
         * this matches it deliberately, so a turn behaves the same on both
         * platforms. The number is not about the model — providers rarely emit
         * more than a handful — but about the device: each bash forks
         * a PRoot process and each browser drives a WebView with its own
         * renderer, and a phone's scheduler starts thrashing well before an
         * unbounded fan-out would finish. Calls past the ceiling wait for a
         * permit and run as slots free, so nothing is dropped or reordered.
         */
        const val MAX_CONCURRENT_TOOLS = 10

        /** [T-android-parity-fixes] Ceiling on bash `timeout`, stated
         *  in the tool schema (AgentTools.shellExecuteDefinition). */
        internal const val TAG = "ChatViewModel"

        // ── [T-android-compact-runaway] Compaction budgets ──────────────
        //
        // Compaction had no ceiling of any kind. Its only time bound was the
        // provider's OkHttp readTimeout (10 minutes on every provider), and
        // the split-retry path could issue up to 1+2+4+8 = 15 SEQUENTIAL leaf
        // calls before depth 3 stopped it. Slow-but-not-timing-out calls (a
        // rate-limited or queued model at ~80s each) therefore added up to
        // roughly 20 minutes of apparent hang — which matches the report.
        //
        // Three independent ceilings now bound it, because each catches a case
        // the others miss: the call budget stops fan-out, the wall-clock
        // timeout stops slow-but-few calls, and the existing depth cap stops
        // recursion.

        /**
         * Leaf LLM calls one compaction may issue in total, across every
         * segment. The depth-3 cap alone permits 15; this cuts the worst case
         * to a third of that while still allowing a full first split (1+2) plus
         * one deeper rescue.
         */
        internal const val MAX_COMPACT_LLM_CALLS = 6

        /**
         * [T-compact-idle-timeout] How long compaction may go WITHOUT receiving
         * any new stream data before it is declared stuck.
         *
         * This replaced a total-elapsed budget, which was measuring the wrong
         * thing. A healthy stream that is merely long looks identical to a hung
         * one under a wall-clock cap: the reported run was cancelled at 150s
         * having received 7,488 SSE events and 19,459 characters at ~40 tok/s —
         * data was flowing the entire time, and the only thing "wrong" was that
         * the transcript was big. Under an idle timer that run completes,
         * because the gap between consecutive deltas never approached this
         * value.
         *
         * Sized to sit under the providers' 10-minute socket readTimeout, so a
         * genuinely dead connection is reported by us — with the lock released
         * and a message the user can act on — rather than surfacing as a raw
         * socket error minutes later.
         */
        internal const val COMPACT_IDLE_TIMEOUT_MS = 120_000L

        /**
         * Absolute ceiling on one compaction run, idle timer notwithstanding.
         *
         * This is a backstop for the pathological cases an idle timer cannot
         * see — a model looping forever, or an unbounded response — where data
         * keeps arriving and so the idle timer keeps resetting, legitimately,
         * forever. It is deliberately generous: it must never be the limit that
         * decides a normal slow compaction's fate, or we are back to the bug
         * this change fixes. [COMPACT_IDLE_TIMEOUT_MS] is the working limit;
         * this one should effectively never fire.
         */
        internal const val COMPACT_MAX_TOTAL_MS = 900_000L

        /**
         * [T-compact-idle-timeout] Raised when a compaction stream goes quiet
         * for longer than [COMPACT_IDLE_TIMEOUT_MS].
         *
         * Deliberately NOT a TimeoutCancellationException. That type extends
         * CancellationException, which coroutines read as "this scope is
         * shutting down normally" — thrown from inside a flow it is
         * indistinguishable from the user pressing Cancel, and the splitter's
         * `catch (e: CancellationException) { throw e }` arm would re-throw it
         * before any retry logic saw it, while the outer handler would report a
         * silent cancel instead of a timeout. As a plain Exception it travels
         * the normal error path and can be classified like any other failure.
         */

        /**
         * Should a failed summary attempt be retried by splitting the input in
         * half? Pure predicate, in the companion so it is testable without an
         * Android-bound ViewModel; the compaction services receive this predicate.
         *
         * Splitting only helps when the failure was caused by the SIZE of the
         * request. Unclassified errors still split — an over-length refusal
         * arrives as an untyped ProviderError on most providers, and a summary
         * built from halves beats no summary — but the classes known to be
         * size-independent are excluded, because for those a split turns one
         * failure into up to 15 sequential slow calls. That amplification is
         * what produced the 15-20 minute apparent hang.
         */
        /**
         * [OpenMinis#377] Does this provider error say "I refuse this PARAMETER"
         * rather than "this payload is too big"?
         *
         * Only ever used to STOP a retry, so it is deliberately conservative: any
         * hint of an over-length problem disqualifies it, and an unrecognised
         * message falls through to splitting as before.
         */
        internal fun looksLikeParameterRejection(error: LLMError.ProviderError): Boolean =
            com.openminis.app.agent.CompactionFailurePolicy.parameterRejection(error)

        internal fun shouldSplitOnError(error: Throwable): Boolean =
            com.openminis.app.agent.CompactionFailurePolicy.canSplit(error)

        /**
         * [T-android-offload-warmup-scan] The index mapping behind
         * offloadWarmUpScanPlan, pure for tests: of history from priorIdx to anchorIdx,
         * the raw indices effectiveAgentHistoryUncounted sends (tool results
         * over 1000 chars and their calls pruned, text-only messages kept,
         * emptied messages dropped, leading non-user peeled, then the first
         * `drop` removed when the per-marker trim is decided), and the pruned ids.
         */
        internal fun warmUpScanIndices(history: List<LLMMessage>, priorIdx: Int, anchorIdx: Int, drop: Int?): Pair<List<Int>, Set<String>> =
            com.openminis.app.agent.AgentContextOffloader.warmUpScanIndices(history, priorIdx, anchorIdx, drop)

        /**
         * [T-android-append-to-input-eats-draft] Join the composer's current
         * [draft] with an appended [snippet]. Returns null when there is
         * nothing to append (the caller then leaves the draft untouched).
         *
         * Trims the incoming SNIPPET only. The old code called
         * `draft.trimEnd()` and assigned that trimmed copy back, so "Add to
         * input" silently rewrote the user's existing draft: a deliberate
         * trailing newline — a paragraph break they had just typed — was
         * swallowed and replaced by the separator space. The draft is the
         * user's own text and must come back byte-for-byte.
         *
         * The emptiness test still runs on a trimmed VIEW of the draft (a
         * whitespace-only draft counts as empty, rather than producing a
         * leading blank run), but that trimmed value drives the DECISION
         * only — it is never assigned back. Mirrors iOS `e6c0ace6a`.
         *
         * [T-android-append-to-input-fence] A snippet that opens or closes
         * with a code fence keeps the fence on its own line: a newline (not a
         * space) before an opening fence when the draft doesn't already end
         * in one, and a newline after a closing fence so further typing does
         * not land on the fence line and turn the block back into text.
         * Plain snippets are unchanged. Mirrors iOS `appendToInputText`
         * (44b465e11); `~~~` fences count too since the Android selection
         * returns raw markdown, which may use either.
         *
         * Pure and side-effect free so it can be unit-tested without an
         * Android runtime; see `AppendToInputTest`.
         */
        internal fun joinDraftWithSnippet(draft: String, snippet: String): String? {
            val cleaned = snippet.trim()
            if (cleaned.isEmpty()) return null
            val startsWithFence = cleaned.startsWith("```") || cleaned.startsWith("~~~")
            val tail = if (cleaned.endsWith("```") || cleaned.endsWith("~~~")) "\n" else " "
            if (draft.isBlank()) return cleaned + tail
            // Preserve the draft verbatim; only add a separator when it does
            // not already end in whitespace. A trailing newline is already a
            // separator, and adding a space after it would indent the new line.
            val separator = if (startsWithFence) {
                if (draft.endsWith("\n")) "" else "\n"
            } else {
                if (draft.last().isWhitespace()) "" else " "
            }
            return draft + separator + cleaned + tail
        }

        /**
         * [T-android-auto-grouping-injection] Strip the characters that would let
         * user-authored text escape its slot in the prompt's group list, then
         * bound the length.
         *
         * The list is rendered as `"name" — desc; "name2" — desc2`, so a quote,
         * bracket or semicolon inside a value can terminate the list early and the
         * remainder reads as instruction. Newlines do the same at the line level.
         * Collapses whitespace so a name padded with tabs/newlines can't blow the
         * budget either.
         *
         * Deliberately NOT escaping instead of stripping: the sanitized name has to
         * survive a round trip (the model echoes it back and we match it against
         * the real folder name), and an escape sequence would come back escaped.
         * Stripping keeps the value matchable — findFolderByName's trim +
         * case-fold absorbs the difference for every realistic group name.
         */
        internal fun promptSafe(raw: String, max: Int): String =
            com.openminis.app.agent.AgentTitleRuntime.promptSafe(raw, max)

        /**
         * (tool name → field names) where an EMPTY STRING is a semantically
         * valid value and must not be treated as "missing".
         *
         * These fields must still be PRESENT in args — they are just allowed
         * to hold "" as their content. Titles must be non-blank strings.
         *
         * The canonical case is `edit.new_string`, whose schema documents
         * "Use empty string to delete old_string". Blocking it broke a promised
         * deletion workflow and pushed the model into bash + python
         * file-rewrite workarounds. Mirrors iOS
         * AIChatViewModel.preflightEmptyStringAllowedFields.
         * [T-preflight-empty-string-allowed]
         */
        /** Compatibility entry for existing callers; runtime owns validation policy. */
        internal fun preflightEmptyStringAllowed(tool: String, field: String): Boolean =
            com.openminis.app.agent.AgentToolRound.emptyStringAllowed(tool, field)

        /**
         * Reject tool calls that have empty args or are missing required fields
         * BEFORE [executeTool] runs. Returns null when the call is well-formed,
         * or a human-readable reason string when it should be blocked.
         *
         * Driven off the canonical [AgentToolDefinition.required] list so the
         * validator never drifts from the schema published to the model. For
         * string fields we additionally require non-blank content — the model
         * occasionally emits `{"path": ""}` which passes the "key exists" check
         * but is just as broken as a missing key. We do NOT validate type beyond
         * string-emptiness here; richer schema checks (enum, regex, integer
         * range) belong in each tool's own helper because they need tool-specific
         * context.
         *
         * Mirror of iOS preflightValidateToolCall in AIChatViewModel.swift.
         *
         * Lives in the companion (and is `internal`) because it is PURE — it reads
         * only its parameters and companion constants — so unit tests can exercise
         * it without constructing a ChatViewModel and its dependency graph. Mirrors
         * the same `nonisolated static` move on iOS.
         */
        internal fun preflightValidateToolCallImpl(
            name: String,
            args: JSONObject,
            tools: List<AgentToolDefinition>,
        ): String? {
            return com.openminis.app.agent.AgentToolRound.validate(name, args, tools)
        }
        // [T-android-stream-flush-dualpath] Newline fast-path thresholds (iOS parity).
        // [T-android-larky-longsession-followup] see uiMessages / hasOlderMessages.
        /** Tail window size used by [uiMessages] when a session exceeds it. */
        const val INITIAL_VISIBLE_MESSAGE_CAP: Int = 200
        /** Each "load older" tap grows the cap by this many messages. */
        const val VISIBLE_MESSAGE_CAP_STEP: Int = 100
        /**
         * Sessions with this many or fewer messages bypass the windowing
         * machinery entirely — the derived `uiMessages` returns the same
         * list reference as `messages`, so Compose sees identity-equal
         * snapshots and the existing flat/stream pipeline is untouched.
         */
        const val LONG_SESSION_THRESHOLD: Int = 300
        // T258: tool block statuses with no committed tool_result. retryLast()
        // drops blocks in any of these states because they would orphan the
        // assistant tool_use entry on retry (the API rejects unmatched
        // tool_use_ids). SUCCESS / FAILED / TIMEOUT / CANCELLED all have a
        // matching tool_result row already persisted and survive the retry.
        private val IN_FLIGHT_TOOL_STATUSES = setOf(
            ToolBlockStatus.STREAMING,
            ToolBlockStatus.PENDING,
            ToolBlockStatus.RUNNING,
        )
        // T145 phase 1: dedicated tag so the streaming-state debug pipeline
        // can be filtered with `adb logcat -s Minis.ChatVMStream:D`.
        // Removed once the retry-state regression is rooted out.
        private const val TAG_STREAM = "ChatVMStream"

        /**
         * Placeholder title a session carries until LLM title generation names
         * it. A SENTINEL, not a display string: `generateTitle`'s skip guard
         * tests for this exact value to decide whether a session still needs a
         * title, so it must not be localized and must not drift.
         *
         * Named because it was previously written out at five sites, and the
         * overlay now needs to recognise it to avoid showing "New Chat" as if
         * it were a task name.
         */
        const val UNTITLED_SESSION_TITLE = "New Chat"
        /**
         * Hard ceiling on agent loop iterations within a single user turn.
         * Backstop against runaway tool-call cycles that slip past
         * [ToolLoopDetector] (e.g. visited args/results vary just enough to
         * dodge the global circuit breaker). On reaching the limit the loop
         * finalizes as resumable — see runAgentLoop's tail and
         * [finalizeAtTurnLimit] — so the user gets an inline explanation +
         * Resume button rather than a silently stuck "thinking" indicator.
         * Mirrors iOS AIChatViewModel.maxAgentTurns.
         */
        private const val MAX_AGENT_TURNS = 200
        /**
         * Sentinel prefix on synthetic tool_result output marking
         * user-cancelled calls. Aligned with iOS
         * AIChatViewModel.swift:5163 so a session sync'd between
         * platforms shows the same `<system-reminder>…` text the model
         * sees on the next API call (rather than "[cancelled by user]"
         * which iOS would treat as opaque tool output).
         */
        const val CANCELLED_MARKER =
            "<system-reminder>The user cancelled this operation. The returned result may be incomplete.</system-reminder>"

        /**
         * Pre-T13 cancelled marker. Kept only so [toLLMMessage]'s
         * tool-block restore can still recognise rows persisted by
         * earlier app versions and surface them as CANCELLED instead
         * of FAILED. Never emitted by this version.
         */
        private const val LEGACY_CANCELLED_MARKER = "[cancelled by user]"
        /**
         * Number of recent user-text turns kept verbatim as inference anchors when
         * compactAll runs. The summary stands in for everything older; the LLM
         * still sees the last N user-text turns + their assistant replies + tool
         * I/O so it can answer follow-ups that need verbatim detail rather than
         * the summary's distilled form. Mirrors iOS `compactKeepRecentUserTurns`.
         */
        private const val COMPACT_KEEP_RECENT_USER_TURNS = 3
        /// Max per-tool-call retained `accumulated` JSON snapshots from
        /// `ToolInputDelta`. Drained on preflight failure for diagnosis.
        private const val TOOL_INPUT_CHUNK_RING_MAX = 10

        /**
         * Factory for use with `viewModel(factory = ...)`. Binds the ChatViewModel
         * to a NavBackStackEntry's ViewModelStore so the streaming job survives
         * configuration changes (rotation) and re-entering the chat screen while
         * the backstack entry is alive.
         */
        fun factory(
            sessionId: String,
            chatRepository: ChatRepository,
            providerRepository: ProviderRepository,
            appContext: Context,
            skillRepository: com.openminis.app.data.repository.SkillRepository?,
            mcpRepository: com.openminis.app.data.repository.MCPRepository? = null,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return ChatViewModel(
                    sessionId = sessionId,
                    chatRepository = chatRepository,
                    providerRepository = providerRepository,
                    context = appContext,
                    skillRepository = skillRepository,
                    mcpRepository = mcpRepository,
                ) as T
            }
        }
    }

    private val mediaStore = com.openminis.app.data.storage.MediaStore(context)

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    // ── Long-session window cap ────────────────────────────────────────
    //
    // [T-android-larky-longsession-followup] On sessions with hundreds of
    // ChatMessage entries (Larky's 612-row monster, totalChars ~1.9MB)
    // feeding the whole list into the LazyColumn pipeline caused cascading
    // main-thread cost: per-frame regex/matcher churn from streaming-side
    // detection, repeated AnnotatedString construction for re-anchored
    // items, and LRU thrash on the markdown caches. The list-virtualization
    // is fine on its own, but the streaming pipeline (combine + sample) and
    // the FlatChat flattening both walk the full list every tick.
    //
    // Strategy: keep `_messages` as the canonical full list (every legacy
    // caller — compact / fork / regenerate / agentHistory / send pipeline —
    // still sees the whole thing) and expose a derived `uiMessages` that
    // takes the TAIL N. ChatScreen consumes `uiMessages`; everything else
    // keeps reading `messages`. When the list is short (<= cap) the derived
    // value IS the source list (same reference), so this is zero-overhead
    // for normal sessions.
    //
    // Users scroll up through the windowed slice; when they reach the top
    // of the tail-window AND older messages exist, [loadOlderMessages]
    // bumps the cap by [WINDOW_STEP] and the derived flow re-emits with
    // the older slice included.
    //
    // Reset on session load (different sessionId) is wired in loadSession.

    private val _visibleMessageCap = MutableStateFlow(INITIAL_VISIBLE_MESSAGE_CAP)
    /**
     * Current tail cap. Reflective via [uiMessages]; bump with
     * [loadOlderMessages] when the user scrolls past the windowed top.
     * Reset to [INITIAL_VISIBLE_MESSAGE_CAP] each time [loadSession]
     * (re)mounts a session — different sessions shouldn't inherit each
     * other's caps.
     */
    val visibleMessageCap: StateFlow<Int> = _visibleMessageCap.asStateFlow()

    /**
     * Tail-windowed view of [messages] for ChatScreen's LazyColumn. For
     * sessions with `count <= LONG_SESSION_THRESHOLD` or `count <= cap`
     * this returns the EXACT SAME list reference as `_messages.value` —
     * Compose / collectAsState gets identity-equal snapshots, no extra
     * allocation, no behavior change for normal sessions.
     */
    val uiMessages: StateFlow<List<ChatMessage>> =
        kotlinx.coroutines.flow.combine(_messages, _visibleMessageCap) { raw, cap ->
            // [T-bridge-message-ui-leak-android] Single UI-collection sink for
            // EVERY path that pushes messages to the list (loadSession, live
            // stream append, compact rebuild, snapshot reload, sync refresh…).
            // Filter the internal role-alternation bridge here so it can never
            // surface as a chat bubble regardless of which path produced it —
            // the Android analog of iOS applySnapshot (T-bridge-message-ui-leak).
            // Today the bridge lives in agentHistory only (never in _messages),
            // so this is defensive; it guards against a future refactor routing
            // the bridge into _messages. Only allocate a new list when a bridge
            // is actually present, keeping the identity-equal fast path intact.
            val full = if (raw.any { it.isInternalBridge }) raw.filterNot { it.isInternalBridge } else raw
            if (full.size <= LONG_SESSION_THRESHOLD || full.size <= cap) full
            // [T-android-uimessages-sublist-cme] `.toList()` is defensive
            // hardening, NOT a proven fix for the reported crash. Read the
            // measured facts before changing it back.
            //
            // `subList` returns a live VIEW sharing the parent's modCount, and
            // emitting it puts that view in Compose state (ChatScreen collects
            // `uiMessages`). That is a latent hazard worth closing on its own.
            //
            // MEASURED, so nobody re-derives it: a SubList only throws
            // ConcurrentModificationException when its PARENT is structurally
            // mutated IN PLACE (add/removeAt/clear). Every write here is
            // `_messages.value = <new list>` via `+` / filterNot / map, and all
            // of those ALLOCATE A FRESH ArrayList rather than mutating — so the
            // old view's parent is never touched and no CME results. Verified on
            // a JVM probe (`base + x`, `filterNot`, `map` all return a new
            // java.util.ArrayList; comparing a stale window after such a write
            // returned OK, not CME).
            //
            // Also verified end-to-end on device (Pixel 4a, build with this
            // `.toList()` deliberately REVERTED): create a multi-turn session,
            // long-press a middle user message → Edit → send. `truncateBeforeEdit`
            // provably ran (8 messages → 4), storing a live SubList as
            // `_messages.value`, and a further message was sent — NO crash. The
            // next `+` copies the SubList back into a plain ArrayList, so the
            // view stops being the state before anything can invalidate it.
            //
            // The user's crash (ArrayList$SubList.equals, main thread, realme
            // RMX5010 / Android 16, 2026-08-10/11/12) therefore still has an
            // UNIDENTIFIED trigger: something must mutate a subList's parent in
            // place. That site was not found in ChatViewModel; look next at
            // ChatFlatItems / ChatScreen and at any long-lived mutableListOf
            // whose contents reach Compose.
            //
            // Keep the copy regardless: the window is a snapshot by definition,
            // so copying is also the correct semantics. Only long sessions past
            // the cap allocate; the common path above still returns `raw`
            // unchanged and stays identity-equal.
            else full.subList(full.size - cap, full.size).toList()
        }.stateIn(
            viewModelScope,
            kotlinx.coroutines.flow.SharingStarted.Eagerly,
            emptyList(),
        )

    /**
     * Whether the current session has older messages above the window.
     * ChatScreen uses this to show / hide the "Load older messages" header
     * pill on the LazyColumn.
     */
    val hasOlderMessages: StateFlow<Boolean> =
        kotlinx.coroutines.flow.combine(_messages, _visibleMessageCap) { full, cap ->
            full.size > LONG_SESSION_THRESHOLD && full.size > cap
        }.stateIn(
            viewModelScope,
            kotlinx.coroutines.flow.SharingStarted.Eagerly,
            false,
        )

    /**
     * Bump the visible cap by [VISIBLE_MESSAGE_CAP_STEP], saturating at
     * the total message count. Safe to call when there are no older
     * messages — it's a no-op (cap clamps to size). Called by the
     * LazyColumn's "load older" header when the user reaches the top of
     * the windowed slice.
     */
    fun loadOlderMessages() {
        val totalNow = _messages.value.size
        if (totalNow <= LONG_SESSION_THRESHOLD) return
        val next = (_visibleMessageCap.value + VISIBLE_MESSAGE_CAP_STEP).coerceAtMost(totalNow)
        if (next != _visibleMessageCap.value) {
            _visibleMessageCap.value = next
        }
    }

    /**
     * Streaming side-channel — see [StreamingDelta]. During a live agent
     * turn, [updateAssistantMessage] writes delta-bearing fields here
     * INSTEAD of mutating the messages list. This isolates per-token
     * updates from ChatScreen's top-level recompose scope (the 8980-line
     * mega-composable was being walked at full slot-table cost on every
     * token, costing ~94 ms per recompose). Top-level subscribers
     * (`messages.any/.associate/.isNotEmpty/.lastOrNull`) only see a new
     * list reference at turn *boundaries* — at start (message added) and
     * end (final content synced back).
     *
     * Renderers that need streaming content (AssistantText, Thinking,
     * tool pills, etc.) read this flow per-item inside their composable
     * scope so Compose's stable-skip restricts the recompose blast radius
     * to that one item.
     *
     * The map is keyed by the assistant message id; absent ⇒ no live
     * stream (turn either hasn't started or has already flushed).
     */
    private val messagePublication = ChatMessagePublication(viewModelScope, _messages,
        currentSession = { activeSessionId }, mergeOverrides = ::mergeDelegateOverrides)
    private val _streamingById get() = messagePublication.streaming
    val streamingById: StateFlow<Map<String, StreamingDelta>> get() = messagePublication.streaming


    // Publication owns delayed jobs and live slots together; these adapters only route lifecycle events.
    private fun clearStreamFlushState(id: String) = messagePublication.discard(id)
    private fun clearAllStreamFlushStates() = messagePublication.clear()
    private fun retainStreamFlushStates(keptIds: Set<String>) = messagePublication.retain(keptIds)

    /**
     * Composer draft. Owned by VM so it survives navigation (e.g. push EnvVars
     * and pop back) — `ChatViewModelStore` keeps the VM alive across screen
     * pushes, but `remember { … }` inside `ChatScreen` does not. Mirrors iOS
     * `AIChatView` which binds against `vm.inputText`.
     */
    private val _inputText = MutableStateFlow("")
    val inputText: StateFlow<String> = _inputText.asStateFlow()

    /**
     * [T-android-slash-menu-align-ios-prepend] One-shot caret position the
     * composer should apply on the NEXT inputText emission, mirroring iOS
     * `pendingCaret`. Null means "no override — caret to end" (the existing
     * default). Set when the slash flow prepends "/ " (caret lands at 1, right
     * after the slash, so typing filters the menu) or inserts "/<skill> "
     * (caret after the prefix, before the preserved body). The composer reads
     * it once in its inputText LaunchedEffect and clears it via [consumePendingCaret].
     */
    internal val _pendingCaret = MutableStateFlow<Int?>(null)
    val pendingCaret: StateFlow<Int?> = _pendingCaret.asStateFlow()

    /** Read-and-clear the pending caret so it applies exactly once. */
    fun consumePendingCaret(): Int? {
        val c = _pendingCaret.value
        _pendingCaret.value = null
        return c
    }

    /**
     * Chat list scroll state. Hoisted onto the VM so it survives ChatScreen
     * recomposition / disposal triggered by forward navigation (file preview,
     * env-vars push, etc.). `rememberSaveable` was insufficient because the
     * surrounding composition is re-entered on pop and the SaveableStateHolder
     * scope doesn't always restore in time — keeping the LazyListState on the
     * session-scoped VM (kept alive by ChatViewModelStore) guarantees both the
     * firstVisibleItemIndex/offset and the layoutInfo cache survive intact, so
     * the LazyColumn paints its previous viewport on the first frame instead of
     * remeasuring from index 0 (white flash).
     */
    val listState: LazyListState = LazyListState(0, 0)

    fun setInputText(value: String) {
        _inputText.value = value
    }

    /**
     * [T-selection-add-to-input] Append [snippet] to the chat composer
     * with a single trailing space:
     *   - composer empty → `"<snippet> "`
     *   - composer non-empty → `"<existing><separator><snippet> "`
     *
     * [T-android-append-to-input-eats-draft] Trim the incoming SNIPPET only.
     * The old code called `current.trimEnd()` and assigned that trimmed copy
     * back, so "Add to input" silently rewrote the user's existing draft: a
     * deliberate trailing newline (a paragraph break they had just typed) was
     * swallowed and replaced by the separator space. The draft is the user's
     * own text and must come back byte-for-byte.
     *
     * The emptiness test still runs on a trimmed view — a draft of only
     * whitespace should be treated as empty rather than producing a leading
     * blank run — but that trimmed value drives the DECISION only, never the
     * assignment. Mirrors iOS `e6c0ace6a`.
     */
    fun appendToInputText(snippet: String) {
        val joined = joinDraftWithSnippet(_inputText.value, snippet) ?: return
        _inputText.value = joined
    }

    private val _isStreaming = MutableStateFlow(false)
    val isStreaming: StateFlow<Boolean> = _isStreaming.asStateFlow()

    /**
     * T261: tool detail sheet visibility, persistent across LazyColumn
     * recomposition / item disposal so a streaming tool's sheet doesn't
     * snap shut when its pill scrolls out of viewport. Stable key = tool
     * block id (server-assigned tool_use_id). Null = closed.
     *
     * Lifecycle: opened by [openToolDetail], closed by [closeToolDetail]
     * (user dismiss) or by ChatScreen's existence-guard LaunchedEffect when
     * the underlying block is gone (T258 retry-preserve drops in-flight
     * tools, session switch, etc.). Not persisted to disk — sheet is a
     * transient UI state.
     */
    internal val _selectedToolDetailId = MutableStateFlow<String?>(null)
    val selectedToolDetailId: StateFlow<String?> = _selectedToolDetailId.asStateFlow()

    // [T-android-split-chat] openToolDetail / closeToolDetail moved to ChatViewModelUiStateExt.kt.

    /**
     * True when the user cancelled mid-turn and the conversation can be
     * resumed by re-prompting the model to pick up where it left off.
     * Mirrors iOS AIChatViewModel.canResume. Cleared by [resume], by the
     * next real [sendMessage], or on error.
     */
    private val _canResume = MutableStateFlow(false)
    val canResume: StateFlow<Boolean> = _canResume.asStateFlow()

    /**
     * [T-android-group-pause-badge-restamp] Marks the ONE `_canResume = true`
     * assignment that is a RE-DETECTION of an interruption that already
     * happened (loadSession finding a still-unfinished DB tail, possibly days
     * old) rather than a live new interruption. Read by the badge collector to
     * decide whether the badge's entry timestamp may be overwritten — see the
     * collector's comment for why the badge must NOT be re-stamped there.
     *
     * Why a COUNTER and not a plain boolean set-then-cleared around the
     * assignment: the collector is an async `collect` on a StateFlow, running
     * on its own coroutine. A boolean cleared right after the assignment is
     * very likely already `false` by the time the collector is resumed and
     * observes the `true`, so the annotation would be lost and the stale badge
     * re-stamped anyway — the exact bug being fixed. Instead the flag is
     * STICKY: the detecting site raises it BEFORE assigning and never clears
     * it; the collector clears it only once it has actually consumed the
     * emission it annotates. The generation counter makes that consumption
     * unambiguous even if several loads race — the collector compares the
     * value it latched against the current one.
     *
     * StateFlow conflation is also handled by this shape: if `_canResume` is
     * already `true`, the re-detection assignment emits nothing at all, so the
     * collector never runs and never re-stamps — which is the desired outcome
     * (no push, no stamp change). The pending mark simply stays raised and is
     * consumed by the next `true` emission, which for this VM instance can
     * only come from the same load path re-running (every live-interruption
     * site is preceded by a `false`, i.e. by a real run that clears it — see
     * `markLiveInterruption`).
     */
    @Volatile private var redetectingInterruptedTailGen: Long = 0L
    @Volatile private var consumedRedetectGen: Long = 0L

    /**
     * Raise the re-detection mark for the next `_canResume = true` emission.
     * Mirrors iOS `isRedetectingInterruptedTail = true` at the +Persistence
     * detection site.
     */
    private fun markRedetectingInterruptedTail() {
        redetectingInterruptedTailGen += 1
    }

    /**
     * Cancel any pending re-detection mark. Called by every LIVE interruption
     * path before it sets `_canResume = true`, so an unconsumed mark left over
     * from a load (e.g. the load found the tail interrupted while `_canResume`
     * was already true, so nothing was emitted) can never leak onto a genuine
     * new interruption and suppress its re-stamp.
     */
    private fun markLiveInterruption() {
        consumedRedetectGen = redetectingInterruptedTailGen
    }

    /**
     * T187: id of a user message currently being re-edited via the
     * long-press → Edit context menu. While non-null, the composer
     * shows an "Exit Edit Mode" pill, and the next sendMessage()
     * call truncates the conversation from this message (inclusive)
     * before persisting the new content as a fresh user turn.
     * Mirrors iOS AIChatViewModel.editingMessageIndex.
     */
    private val _editingMessageId = MutableStateFlow<String?>(null)
    val editingMessageId: StateFlow<String?> = _editingMessageId.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _modelName = MutableStateFlow("")
    val modelName: StateFlow<String> = _modelName.asStateFlow()

    /** T201: gate the init-time `config.collect` re-resolver so the StateFlow's
     *  replay cache can't beat [loadSession] to setting `_modelName`. Without
     *  this, opening a session that previously fell back mid-run flashes the
     *  default model name for one frame before the persisted binding settles. */
    private val sessionLoaded = MutableStateFlow(false)
    private var historyLoadJob: Job? = null
    private var historyRestoreFailure: Exception? = null

    private val _sessionTitle = MutableStateFlow(UNTITLED_SESSION_TITLE)
    val sessionTitle: StateFlow<String> = _sessionTitle.asStateFlow()

    /**
     * [T-android-overlay-multitask] The session title for surfaces OUTSIDE the
     * chat, or null while the session has no real title yet.
     *
     * `_sessionTitle` holds the literal "New Chat" until LLM title generation
     * names the session — the same literal the generator's own skip guard
     * tests, so it is a sentinel rather than a display string. In the chat
     * that placeholder is fine: there is exactly one, and its position says
     * what it is. On the floating capsule it is not, because several tasks can
     * be on screen at once and "New Chat" tells the user nothing about which
     * is which.
     *
     * Returning null here lets the capsule fall back to the Soul name, which
     * at least identifies the assistant. The sentinel is deliberately not
     * matched in the view: it is an English literal that a localization pass
     * could change, and the check belongs next to the field that defines it.
     */
    private fun overlaySessionTitle(): String? =
        _sessionTitle.value.takeIf { it.isNotBlank() && it != UNTITLED_SESSION_TITLE }

    /** T-chat-title-pill: category drives the icon shown in the sticky title
     *  pill (mirrors SessionRow's categoryStyle lookup). Null on draft sessions
     *  and until LLM title-generation tags the session. */
    private val _sessionCategory = MutableStateFlow<String?>(null)
    val sessionCategory: StateFlow<String?> = _sessionCategory.asStateFlow()

    internal val _attachments = MutableStateFlow<List<InputAttachment>>(emptyList())
    val attachments: StateFlow<List<InputAttachment>> = _attachments.asStateFlow()

    /**
     * [T-android-paste-placeholder] Long pasted blocks folded out of the
     * composer, keyed by the `[Pasted#N]` marker left in its place.
     *
     * Scoped to this ViewModel, so it is per-session by construction: the
     * store hands each session its own instance, and switching chats cannot
     * leak an id from one buffer into another's placeholders. Memory-only —
     * see [PastedText] for why persisting it would be worse than not.
     */
    private val _pastedTexts = MutableStateFlow<List<PastedText>>(emptyList())
    val pastedTexts: StateFlow<List<PastedText>> = _pastedTexts.asStateFlow()

    /**
     * Next placeholder number. Monotonic for the session's lifetime and never
     * reused, even after entries are consumed or deleted: a recycled id would
     * let a stale marker left in the draft ("I pasted, deleted the chip, then
     * pasted again") silently expand to the WRONG text. Numbers are cheap.
     */
    private var nextPasteId: Int = 1

    /**
     * [T-android-paste-placeholder] Buffer [text], returning the marker to put
     * in the composer in its place.
     */
    fun stashPastedText(text: String): String {
        val entry = PastedText(id = nextPasteId++, text = text)
        _pastedTexts.value = _pastedTexts.value + entry
        AppLogger.info(TAG, "[Paste] stashed #${entry.id} (${text.length} chars)")
        return entry.placeholder
    }

    /**
     * [T-android-paste-oversize] Turn a very large paste into a real `.txt`
     * document attachment instead of a placeholder.
     *
     * Past [PASTE_AS_FILE_THRESHOLD] the user is effectively attaching a
     * document, and the placeholder path is the wrong shape for it: the block
     * would sit in memory for the whole draft and then have to be written out
     * anyway. Routing it through the ordinary attachment pipeline instead means
     * it inherits preview, removal, the `<user-attached-files>` inventory the
     * model can `cat`, and the same upload handling as a file the user picked —
     * none of which the buffer offers.
     *
     * The bytes go to `cacheDir/pasted_text`, matching where
     * [addAttachmentFromStagedShare] puts share-inbound copies: the composer may
     * hold this for a long time before send, so it must not live anywhere the
     * system might reclaim mid-draft.
     *
     * Returns null if the write fails, and the caller then leaves the paste in
     * the text field verbatim — worse-looking than a chip, but nothing is lost.
     */
    fun stashPastedTextAsFile(text: String): InputAttachment? {
        val dir = java.io.File(context.cacheDir, "pasted_text").apply { mkdirs() }
        // Timestamp + short uuid: sorts chronologically in a file listing and
        // cannot collide when two pastes land in the same millisecond.
        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
            .format(java.util.Date())
        val name = "Pasted_$stamp-${java.util.UUID.randomUUID().toString().take(8)}.txt"
        val file = java.io.File(dir, name)
        return try {
            file.writeText(text)
            val attachment = InputAttachment(
                fileName = name,
                uri = android.net.Uri.fromFile(file),
                mimeType = "text/plain",
                kind = InputAttachment.Kind.DOCUMENT,
            )
            addAttachment(attachment)
            AppLogger.info(
                TAG,
                "[Paste] oversize paste -> file attachment $name (${text.length} chars)",
            )
            attachment
        } catch (e: Exception) {
            AppLogger.warning(TAG, "[Paste] failed to write oversize paste: ${e.message}")
            null
        }
    }

    /**
     * Drop one buffered entry (the chip's delete button). The caller is
     * responsible for also removing the marker from the composer text — see
     * ChatScreen, which does both in one edit so the two never disagree.
     */
    fun removePastedText(id: Int) {
        _pastedTexts.value = _pastedTexts.value.filterNot { it.id == id }
    }

    /**
     * One-shot composer-side image-budget events (T-imgsize). Emitted by
     * [prepareUserAttachments] when [ImageBudget.applyMessageBudget] either
     * re-encodes oversize local attachments or drops images that would push
     * the message over the cumulative cap. ChatScreen collects this flow
     * and surfaces a localized Snackbar — provider-boundary compression
     * (history images) does not emit here to keep history-replay silent.
     */
    private val _imageBudgetEvent = MutableSharedFlow<ImageBudget.BudgetResult>(extraBufferCapacity = 4)
    val imageBudgetEvent: SharedFlow<ImageBudget.BudgetResult> = _imageBudgetEvent.asSharedFlow()

    /**
     * Request-level image-budget events (T-request-imgsize). Emitted by
     * [applyRequestImageBudget] when the cumulative history image payload
     * exceeds [ImageBudget.MAX_REQUEST_BYTES] and older images had to be
     * elided to text placeholders. Distinct from [imageBudgetEvent] so the
     * UI Snackbar can show a different message ("older images compacted")
     * and the two events don't race.
     */
    private val _requestBudgetEvent = MutableSharedFlow<ImageBudget.RequestBudgetPlan>(extraBufferCapacity = 4)
    val requestBudgetEvent: SharedFlow<ImageBudget.RequestBudgetPlan> = _requestBudgetEvent.asSharedFlow()

    /**
     * [T-android-tool-autoscroll] Fire-and-forget edge events that ask the
     * ChatScreen to scroll the LazyColumn to the visual bottom (index 0 under
     * reverseLayout). Distinct from the streaming-auto-follow collector — that
     * pipeline needs growth ticks to advance its distinctUntilChanged tuple,
     * but agent-loop START events (sendMessage, resume / "Continue", retry)
     * produce only a brief thinking placeholder before any content streams.
     * Without an explicit edge signal, the placeholder + composer interaction
     * area sits behind the input bar until the model's first token arrives
     * and the regular auto-follow finally fires. Each ViewModel entry that
     * starts a fresh agent-loop turn emits to this flow.
     */
    private val _forceScrollToBottom = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    val forceScrollToBottom: SharedFlow<Unit> = _forceScrollToBottom.asSharedFlow()

    /**
     * [T-android-readaloud-stop-stale] Emitted on the FIRST text delta of a new
     * reply, so any Read Aloud still playing from the previous reply is
     * stopped before the new content starts arriving.
     *
     * Deferred to the first delta rather than fired from send(): the old reply
     * should keep playing while the model is still thinking, and only yield
     * once there is actually new text to supersede it. Mirrors iOS
     * `d2fdc784f`, which sets `hasStoppedPreviousTTS` at the same point.
     *
     * The player is screen-scoped (ChatScreen owns it), so this is a signal
     * rather than a direct call — the ViewModel has no reference to it.
     */
    private val _stopStaleReadAloud = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    val stopStaleReadAloud: SharedFlow<Unit> = _stopStaleReadAloud.asSharedFlow()

    private var sessionContextLimitTokens: Int? = null

    private val _providerName = MutableStateFlow("")
    val providerName: StateFlow<String> = _providerName.asStateFlow()

    /** Incremented when a model fallback occurs — UI observes this to flash the model capsule. */
    private val _fallbackTrigger = MutableStateFlow(0)
    val fallbackTrigger: StateFlow<Int> = _fallbackTrigger.asStateFlow()

    private val _activeEntryId = MutableStateFlow<String?>(null)
    val activeEntryId: StateFlow<String?> = _activeEntryId.asStateFlow()

    // [T-p1-delegate-task] Set by the parent's executeDelegateTask on a CHILD
    // vm before its first turn. Its presence is what every helper-specific
    // branch keys off: tool set (no delegation), identity
    // preamble, turn cap, and the SessionConcurrencyManager bypass.
    internal var helperConfig: com.openminis.app.agent.jobs.HelperConfig? = null
    internal val isHelper: Boolean get() = helperConfig != null

    /**
     * [T-android-browser-tab-ownership] Who this view model is, as far as the
     * shared browser pool is concerned.
     *
     * A sub agent identifies as its own child session so the pool can keep its
     * tabs apart from its siblings' and from the chat's. The parent chat passes
     * its own session id, which the pool treats as PRIVILEGED (`owner ==
     * sessionId`) — a chat driving its own pool is not an agent competing with
     * itself, so it keeps full access exactly as before.
     */
    private val browserOwnerId: String? get() = activeSessionId.ifEmpty { null }
    /** [T-agent-wrapup-turn] The parent called time on the budget: the child's
     *  NEXT loop turn is the wrap-up turn (tools withdrawn, deliverable asked). */
    @Volatile internal var helperWrapUpRequested = false

    /**
     * [T-sub-agents-steer] Course corrections the parent model sent to this
     * running child, read at its next turn boundary.
     *
     * Delivered between turns rather than injected mid-flight: a tool call
     * already in progress is not interrupted, and the work done so far is kept
     * — which is the whole difference between steering and cancel-then-
     * re-delegate. Costs no extra turn.
     */
    private val pendingSteerMessages = java.util.Collections.synchronizedList(mutableListOf<String>())

    /** Queue a course correction. Returns false when the child is not running. */
    internal fun enqueueSteer(message: String): Boolean {
        if (helperConfig == null) return false
        val wasIdle = !_isStreaming.value
        pendingSteerMessages.add(message)
        // [T-subagent-steer-continues-loop] Port of iOS HelperRunner.swift:641.
        //
        // A child BETWEEN turns is still a running job — the steer hook only
        // exists while the job is live — but nothing is executing that could
        // consume the queued correction. Android used to reject the steer
        // outright on `!isStreaming`, so a correction aimed at a child that
        // happened to be between turns was refused; iOS instead restarts the
        // loop so it takes the turn that delivers it, exactly as a queued user
        // prompt wakes an idle conversation.
        //
        // The nudge prompt deliberately carries no instruction of its own: the
        // loop prepends the real correction at the top of the turn it starts.
        if (wasIdle) {
            AppLogger.info(TAG, "[subagent] steer arrived while the child was idle — nudging its loop")
            viewModelScope.launch {
                runCatching {
                    submitPrompt(com.openminis.app.agent.jobs.HelperRunner.STEER_NUDGE_PROMPT)
                }.onFailure {
                    AppLogger.warning(TAG, "[subagent] steer nudge failed: ${it.message}")
                }
            }
        }
        return true
    }

    /** [T-sub-agents-steer] Steers that arrived too late to be read. */
    internal fun drainMissedSteers(): List<String> = synchronized(pendingSteerMessages) {
        val out = pendingSteerMessages.toList()
        pendingSteerMessages.clear()
        out
    }
    /** [T-p2-shared-workspace] The session whose `/var/minis` bucket this vm's
     *  shell and file tools operate in. A helper works in its PARENT's
     *  workspace — it shares the
     *  parent's files — so what it writes is what the parent (and the user)
     *  can read. Its transcript, media rows and job identity stay its own. */
    internal val fsSessionId: String get() = helperConfig?.parentSessionId ?: activeSessionId
    /** [T-p2-background-helper] Delegate blocks written from OUTSIDE the agent
     *  loop (the background mirror / completion hook), keyed by tool_use id.
     *  Merged into every updateAssistantMessage so the loop's own republishes
     *  of its private block list do not clobber them; cleared once the final
     *  payload is persisted. */
    private val delegateBlockOverrides = java.util.concurrent.ConcurrentHashMap<String, AssistantBlock>()

    /** Prompts enqueued while the agent loop is running. Drained after the loop finishes. */
    private val _promptQueue = MutableStateFlow<List<QueuedPrompt>>(emptyList())
    val promptQueue: StateFlow<List<QueuedPrompt>> = _promptQueue.asStateFlow()

    /**
     * Input-token count reported by the most recent API call, used by
     * [ContextPolicy] as the "estimated tokens" gate before sending. Zero
     * means either we've never called the model or the provider didn't return
     * a usage payload — in which case we treat the turn as low-pressure.
     */
    private val _lastTurnContextTokens = MutableStateFlow(0)
    val lastTurnContextTokens: StateFlow<Int> = _lastTurnContextTokens.asStateFlow()

    /**
     * [T-ctx-measure-outbound] Calibration state for [ContextSizeMeter].
     *
     * Replaces `lastTurnContextTokensStale`, a one-shot "do not compact on this
     * number again" flag that only the in-loop path ever set: a send-time or
     * manual compaction left [_lastTurnContextTokens] at its pre-compaction
     * value with no flag, so the next check compacted again on it. The capacity
     * guards no longer read that value at all — it stays as the glow's source —
     * and judge [measureOutboundContextTokens] instead.
     *
     * [contextPlanner] owns calibration and immutable dispatch measurements.
     * [contextFixedTokens] is the estimated system prompt plus tool schemas.
     */
    private val contextPlanner = com.openminis.app.agent.AgentContextPlanner()
    /**
     * [T-ctx-warmup-fit] Per compaction marker: how many leading warm-up
     * messages were dropped to fit. Decided once, then reused, so the request
     * prefix stays stable across turns (see [trimWarmUpToFit]).
     */
    private val historyProjection = com.openminis.app.agent.HistoryProjection()
    private val contextFixedTokens get() = contextPlanner.fixedTokens
    private val lastDispatchRatio get() = contextPlanner.lastDispatchRatio
    /** Set by revertCompact; loadSession logs [CtxMeter] reverted once the reload has measured. */
    @Volatile private var pendingRevertLogMarker: String? = null

    /**
     * [T-ctx-measure-outbound] Estimated size of the next request: the
     * compaction-aware outbound history plus system prompt and tools, scaled by
     * the session's calibration. The one number every capacity decision reads.
     * Measured before the request image budget, like the calibration side.
     */
    private fun contextCalibrationRatio(modelId: String? = currentModel?.id): Double =
        contextPlanner.ratio(modelId)

    private fun measureOutboundContextTokens(ratio: Double? = null): Int = contextMeasurement(ratio).measured

    /** The measurement with its parts, for the [CtxMeter] logs. */
    private fun contextMeasurement(override: Double? = null) =
        contextPlanner.measure(effectiveAgentHistoryUncounted(), currentModel?.id, override)

    /** [CtxMeter] decide — every capacity decision with its inputs, so a field log can replay it. */
    private fun logContextDecision(site: String, m: com.openminis.app.agent.AgentContextPlanner.Measurement, threshold: Int, window: Int, result: String) {
        AppLogger.info(
            TAG,
            "[CtxMeter] decide site=$site model=${currentModel?.id ?: "?"} history=${m.history} fixed=${m.fixed} " +
                "ratio=${"%.3f".format(m.ratio)}(${m.source}) measured=${m.measured} threshold=$threshold " +
                "window=$window → $result",
        )
    }

    /**
     * The send-time check runs before any loop has measured this turn's system
     * prompt; without a fixed share it would judge history alone. The loop
     * replaces this with the exact figure before its first request.
     */
    private fun ensureContextFixedTokens() {
        if (contextFixedTokens > 0) return
        contextPlanner.updateFixedTokens(buildSystemPrompt(), callableAgentTools)
    }

    /**
     * Re-derive calibration from the session's persisted usage rows: the last
     * row carrying BOTH its report and our estimate of the same request. No raw
     * size is carried over, so a compaction or revert done since cannot make
     * the next decision stale. Returns true when a pair was found.
     */
    /**
     * Reloading the SAME session keeps what this view model already learned: a
     * revert reloads through loadSession, and a ratio raised by a provider
     * rejection exists only in memory (a rejected request stamps no pair), so
     * wiping it let the retry go out uncompacted and be rejected again —
     * measured on the iOS twin of this code. In-memory values are never older
     * than the rows', so they win; a different session starts from scratch.
     */
    private fun seedContextCalibration(usageJsons: List<String>): Boolean {
        return contextPlanner.seed(realSessionId.ifEmpty { sessionId }, usageJsons)
    }

    /**
     * Point the composer glow at the measurement. After a compaction or revert
     * [_lastTurnContextTokens] otherwise shows a context that no longer exists
     * until the next response replaces it.
     */
    private fun publishMeasuredContextUsage() {
        ensureContextFixedTokens()
        val measured = measureOutboundContextTokens()
        if (measured > 0) _lastTurnContextTokens.value = measured
    }

    /**
     * [T-ctx-warmup-fit] Trim a compaction's warm-up turns so the request fits
     * under the compact line. Mirrors iOS `trimWarmUpToFit`.
     *
     * A compaction keeps the last few user turns before its anchor as warm-up.
     * When those turns are large, or the model's tokenizer is denser than the
     * one the session ran on, summary + warm-up can still be over the line;
     * every later compaction keeps the same warm-up, so the session can never
     * continue on that model (measured on device with a 1.8x-denser model:
     * compacted and still at 100% of the window). Only runs over budget, so a
     * normal compaction's request and prompt-cache prefix are unchanged. Drops
     * whole user-TEXT turns oldest-first (tool results are USER messages too,
     * and cutting between a call and its result would orphan it), in
     * raw-estimate units so it cannot recurse into the measurement.
     */
    private fun trimWarmUpToFit(warmUp: List<LLMMessage>, rest: List<LLMMessage>, summaryText: String): WarmUpTrim {
        if (warmUp.isEmpty()) return WarmUpTrim(warmUp, decided = true)
        // [T-ctx-warmup-trim-undecided] No entry / unknown window / fixed
        // tokens not yet seeded: there is no budget to judge against, so the
        // warm-up passes through UNDECIDED and the caller must not cache it.
        val budget = warmUpBudget(
            window = effectiveContextWindowTokens(),
            compactThreshold = currentContextPolicy()?.first?.compactThreshold,
            ratio = contextCalibrationRatio(),
            fixedTokens = contextFixedTokens,
        ) ?: return WarmUpTrim(warmUp, decided = false)
        val restTokens = ContextSizeMeter.estimateTokens(rest) + ContextSizeMeter.estimateTokens(summaryText)
        if (ContextSizeMeter.estimateTokens(warmUp) + restTokens < budget) return WarmUpTrim(warmUp, decided = true)
        val drop = ContextSizeMeter.warmUpDrop(
            sizes = warmUp.map { ContextSizeMeter.estimateTokens(listOf(it)) },
            startsTurn = warmUp.map(JournalProjection::startsUserTurn),
            restTokens = restTokens,
            budget = budget,
        )
        AppLogger.info(TAG, "[CtxMeter] warmup trimmed to fit: kept ${warmUp.size - drop}/${warmUp.size} message(s) (budget=$budget raw tokens)")
        return WarmUpTrim(warmUp.drop(drop), decided = true)
    }

    // ---- [T-android-context-usage-hint] Context-window pressure surface ----
    //
    // Port of iOS [T-ios-context-usage-hint] / -realtime-crossing. Two paths
    // publish the same line, and they share one tracker so they cannot
    // double-announce the same threshold:
    //
    //   1. a turn ENDS (isStreaming true -> false) with real usage data;
    //   2. usage arrives MID-loop and crosses into a strictly higher tier —
    //      a long tool-running turn should not stay silent until it finishes.
    //
    // The glow reads [contextUsage] directly (live on every usage chunk, so
    // the border colour tracks pressure continuously), while the placeholder
    // line reads [contextUsageHint] (discrete, generation-keyed, retired by
    // focus/typing). Splitting them is what lets the glow update without
    // re-showing a line the user already dismissed.

    /**
     * Live context pressure, or null when there is nothing trustworthy to
     * show. Drives the composer's inner glow.
     *
     * [T-android-glow-threshold-only] This is a DERIVED view of
     * [_lastTurnContextTokens], not a value that publish events push into.
     *
     * It used to be assigned only inside `publishContextUsage`, which made the
     * glow an artifact of when that function happened to run rather than a
     * statement about the current context. Two consequences the user hit:
     * sending a message cleared the glow (the mid-loop path re-published with
     * `_lastTurnContextTokens` momentarily 0 — auto-compact zeroes it
     * deliberately at :4845 — and `ContextUsage.from` maps 0 to null), and a
     * turn that produced no usage chunk left the glow stuck at a stale tier.
     *
     * Deriving it fixes both by construction: the glow is on exactly while
     * measured usage sits at or above the warning threshold, and it changes
     * exactly when the measurement crosses a threshold. The discrete
     * placeholder LINE keeps its own gating in [_contextUsageHint] — that one
     * genuinely is event-driven (it must fire once, then be retired by focus
     * or typing), which is why the two are separate flows.
     */
    internal val contextUsage: StateFlow<ContextUsage?> =
        _lastTurnContextTokens
            .map { tokens ->
                // Re-resolve the window on every emission: switching model or
                // editing a group's contextLimitTokens changes the denominator
                // without changing the token count, and the glow must follow.
                ContextUsage.from(tokens, effectiveContextWindowTokens())
            }
            .stateIn(
                viewModelScope,
                kotlinx.coroutines.flow.SharingStarted.Eagerly,
                null,
            )

    /**
     * The discrete placeholder line. A new [ContextUsageHint.generation] is
     * what makes the composer show a line; the composer retires it on typing
     * or a second focus and never resurrects it.
     */
    private val _contextUsageHint = MutableStateFlow<ContextUsageHint?>(null)
    internal val contextUsageHint: StateFlow<ContextUsageHint?> = _contextUsageHint.asStateFlow()

    /** Monotonic generation counter for [_contextUsageHint]. */
    private var contextHintGeneration = 0

    /** Shared upward-crossing / rate-limit state for both publish paths. */
    private val contextTierTracker = ContextTierCrossingTracker()

    /**
     * True when the turn now ending was stopped by the user.
     *
     * A cancelled turn must not produce a usage line: the figure would be for
     * a request the user deliberately abandoned, and it would land right as
     * they reach for the composer to redirect. Set by [cancelStream] and
     * cleared when the next turn starts.
     */
    private var lastTurnWasCancelled = false

    /**
     * Publish context pressure for the turn that just produced [usedTokens].
     *
     * @param crossingOnly when true this is a MID-loop observation, which
     *   only shows a line on a genuine upward crossing. Loop-end passes false
     *   and shows a line for any non-normal tier, deduplicated against a
     *   crossing the mid-loop path may have just announced.
     *
     * Silent for sub-agent / helper view models: those run headless and have
     * no composer to write to, so a line there would be pure overhead. Also
     * silent below the warning threshold, which is what keeps the feature
     * invisible until it has something worth saying.
     */
    private fun publishContextUsage(crossingOnly: Boolean) {
        if (isHelper) return
        val usage = ContextUsage.from(
            usedTokens = _lastTurnContextTokens.value,
            windowTokens = effectiveContextWindowTokens(),
        )
        // The glow is derived from [contextUsage] and needs no assignment
        // here — see its KDoc. This function now decides only whether the
        // discrete placeholder LINE should appear.
        if (usage == null) return

        val now = System.currentTimeMillis()
        val crossed = contextTierTracker.observe(usage.tier, now)
        if (usage.tier == ContextUsage.Tier.NORMAL) return
        if (crossingOnly && !crossed) return
        // Loop end: skip a line the mid-loop path just showed for this tier.
        if (!crossingOnly && !crossed &&
            contextTierTracker.recentlyFired(usage.tier, now)
        ) return

        contextHintGeneration += 1
        _contextUsageHint.value = ContextUsageHint(
            generation = contextHintGeneration,
            text = "",  // resolved at render time — see composerContextUsageText
            highlights = listOf(
                "${usage.percent}%",
                TokenCountFormatter.sizePair(usage),
            ),
            tier = usage.tier,
        )
        contextTierTracker.markFired(usage.tier, now)
        AppLogger.info(
            TAG,
            "[ContextUsage] hint gen=$contextHintGeneration tier=${usage.tier} " +
                "${usage.percent}% (${usage.usedTokens}/${usage.windowTokens}) " +
                "crossingOnly=$crossingOnly crossed=$crossed",
        )
    }

    /**
     * [T-ctx-usage-after-compact] Replace the placeholder line after a
     * successful compaction (manual, long-press or automatic in-loop — all
     * finish in [compactAllImpl]'s `finally`) with the post-compaction
     * estimate. Mirrors
     * iOS `announceContextUsageAfterCompaction`.
     *
     * The composer keeps showing the last presented line until the user
     * types, and ignores a null hint, so without a NEW generation the
     * pre-compaction figure (often 80%+) stayed on screen and compaction
     * looked like it had done nothing. Unlike [publishContextUsage] this is
     * shown at any tier: the point is to report the drop. The glow already
     * follows via [publishMeasuredContextUsage] → [_lastTurnContextTokens].
     *
     * Records the (usually lower) tier so a later climb back over 70% / 80%
     * counts as a fresh crossing, but deliberately does not `markFired`: the
     * next loop-end line carries the real reported size and must not be
     * deduplicated against this estimate.
     */
    private fun announceContextUsageAfterCompaction() {
        if (isHelper) return
        val usage = ContextUsage.from(
            usedTokens = _lastTurnContextTokens.value,
            windowTokens = effectiveContextWindowTokens(),
        ) ?: return
        contextTierTracker.observe(usage.tier, System.currentTimeMillis())
        contextHintGeneration += 1
        _contextUsageHint.value = ContextUsageHint(
            generation = contextHintGeneration,
            text = "",  // resolved at render time — see composerContextUsageText
            highlights = listOf(
                "${usage.percent}%",
                TokenCountFormatter.sizePair(usage),
            ),
            tier = usage.tier,
        )
        AppLogger.info(
            TAG,
            "[ContextUsage] post-compact hint gen=$contextHintGeneration tier=${usage.tier} " +
                "${usage.percent}% (${usage.usedTokens}/${usage.windowTokens})",
        )
    }

    /**
     * Latest compact summary for the current session, loaded from the DB on
     * [loadSession] and re-populated after [compactAll] finishes. When non-null,
     * [effectiveAgentHistory] prepends it as a `<context-summary>` user message
     * so the model sees a condensed recap of the turns we folded away while
     * keeping the full [agentHistory] on disk as an audit trail. Mirrors iOS
     * Phase-B compact semantics (summary synthesized at inference time, never
     * baked back into agentHistory).
     */
    private val _compactSummary = MutableStateFlow<String?>(null)
    val compactSummary: StateFlow<String?> = _compactSummary.asStateFlow()

    /** True when a compact-summary LLM call is in flight (UI disables further sends). */
    private val _isCompacting = MutableStateFlow(false)
    val isCompacting: StateFlow<Boolean> = _isCompacting.asStateFlow()

    /**
     * [T-android-compact-progress] Live progress of the in-flight compaction.
     *
     * Compaction could previously run for many minutes behind a single
     * unchanging "compacting" flag, which is indistinguishable from a hang —
     * the reported symptom was users staring at it for 15-20 minutes with no
     * way to tell whether it was working or wedged. This carries enough state
     * for the UI to show real movement: elapsed seconds, which segment of a
     * split is running, and how deep the split went.
     */
    data class CompactProgress(
        /** When the whole compaction started, for elapsed-time display. */
        val startedAtMs: Long,
        /** Recursion depth currently executing (0 = whole history, >0 = a split half). */
        val depth: Int = 0,
        /** Leaf LLM calls issued so far, across all segments. */
        val callsIssued: Int = 0,
        /** Total leaf calls allowed before the budget aborts the run. */
        val callBudget: Int = MAX_COMPACT_LLM_CALLS,
        /** Seconds the whole run is allowed before it is cancelled. */
        val timeoutSeconds: Int = 0,
        /**
         * [T-android-compact-fallback] Model actually executing the summary
         * right now — it changes mid-run when fallback switches providers.
         * Without this the UI kept showing the session's model while a
         * different one was doing the work, which is how "it didn't switch"
         * looks from the outside even when it did.
         */
        val modelName: String? = null,
    )

    private val _compactProgress = MutableStateFlow<CompactProgress?>(null)
    val compactProgress: StateFlow<CompactProgress?> = _compactProgress.asStateFlow()

    /**
     * Leaf LLM calls issued by the current compaction. Reset at the start of
     * each run; read/incremented from the split recursion, which can interleave
     * across suspension points, hence atomic.
     */
    private val compactCallsIssued = java.util.concurrent.atomic.AtomicInteger(0)

    /**
     * The running compaction's job, so the UI can offer a Cancel affordance.
     * Cancelling routes through the same `finally` that clears the lock, so a
     * user-cancelled compaction leaves no state behind.
     */
    private val compactJob: Job? get() = compactionRuntime.job

    /** Cancel an in-flight compaction. No-op when nothing is running. */
    fun cancelCompact() {
        val job = compactJob ?: return
        if (!job.isActive) return
        AppLogger.info(TAG, "[Compact] cancelled by user")
        job.cancel(CancellationException("compact cancelled by user"))
    }

    /** Current auto-retry attempt number (0 = not retrying, 1..MAX = nth retry in flight). */
    private val _autoRetryAttempt = MutableStateFlow(0)
    val autoRetryAttempt: StateFlow<Int> = _autoRetryAttempt.asStateFlow()

    /** Seconds remaining in the current auto-retry countdown (0 = not counting down). */
    private val _autoRetryCountdown = MutableStateFlow(0)
    val autoRetryCountdown: StateFlow<Int> = _autoRetryCountdown.asStateFlow()

    private val runCoordinator = com.openminis.app.agent.AgentRunCoordinator()
    private val cancellationCoordinator = com.openminis.app.agent.AgentCancellationCoordinator()
    private val stopRuntime = com.openminis.app.agent.AgentStopRuntime(runCoordinator, cancellationCoordinator)
    private val streamJob: Job? get() = runCoordinator.job
    private val cancellationPending get() = streamJob?.let { it.isCancelled && !it.isCompleted } == true
    internal suspend fun awaitAgentRunSettlement() { streamJob?.join() }

    private fun afterCancelledRun(action: () -> Unit): Boolean {
        val previous = streamJob?.takeIf { it.isCancelled && !it.isCompleted } ?: return false
        val owner = activeSessionId
        viewModelScope.launch {
            previous.join()
            if (activeSessionId == owner && streamJob === previous && !_isStreaming.value) action()
        }
        return true
    }

    private fun launchAgentRun(
        scope: kotlinx.coroutines.CoroutineScope,
        label: String,
        bypassSlot: Boolean = false,
        markFailure: Boolean = true,
        failure: (Exception) -> Unit = { setInlineError(it.message ?: "Unknown error") },
        prepareSession: (suspend () -> String)? = null,
        prepare: suspend () -> Unit = {},
        body: suspend () -> Unit,
    ): Job {
        var sessionId = activeSessionId
        var dispatched = false
        return runCoordinator.launch(scope, sessionId, label, bypassSlot, markFailure,
            title = ::overlaySessionTitle, stop = ::cancelStream,
            beforeInactive = { if (activeSessionId == sessionId) publishOverlayReplyExcerpt(sessionId) },
            failed = { error -> if (activeSessionId == sessionId) {
                if (dispatched) {
                    failure(error)
                    errorJournal.terminal(sessionId,
                        _messages.value.lastOrNull { it.role == "assistant" }?.error ?: error.message ?: "Unknown error")
                } else _error.value = error.message ?: "Unknown error"
            } },
            settled = { _isStreaming.value = false },
            prepareSession = {
                historyLoadJob?.join()
                historyRestoreFailure?.let { throw IllegalStateException("History restore failed; reopen the conversation before sending", it) }
                (prepareSession?.invoke() ?: sessionId).also { sessionId = it }
            }, prepare = prepare, body = {
                try {
                    if (activeSessionId != sessionId) throw CancellationException("run branch changed before dispatch")
                    dispatched = true
                    body()
                } finally {
                    cancellationCoordinator.settle(sessionId, agentHistory, { activeSessionId }, subagentJournal) { committed ->
                        committed.assistantRow?.let { messagePublication.stamp(committed.sessionId, committed.bubbleId, it) }
                    }
                }
            })
    }
    private val providerPreparation by lazy {
        com.openminis.app.agent.AgentProviderPreparation(context, providerRepository)
    }
    private var currentProvider: LLMProvider? = null
    private var currentModel: LLMModel? = null


    private val currentModelHasNativeVision: Boolean
        get() = currentModel?.hasImageInput == true

    /**
     * [T-token-attribution-snapshot] Which model actually served the turn being
     * persisted, for the message's attribution columns.
     *
     * Built from `currentModel` + `_activeEntryId` — the live request context —
     * and deliberately NOT from the session row. Automatic failover rewrites
     * `sessions.model_id` mid-turn (see the fallback path that reassigns
     * `_activeEntryId` / `currentModel` when a candidate fails), so a session
     * read at persist time can name a model that never produced this message.
     * Both fields here are updated by that same fallback path, so they always
     * describe the model that actually responded.
     */
    private fun currentModelSnapshot(): com.openminis.app.data.model.ModelAttributionSnapshot? {
        val model = currentModel ?: return null
        return modelSnapshotFor(model, _activeEntryId.value)
    }

    private fun modelSnapshotFor(model: LLMModel, entryId: String?): com.openminis.app.data.model.ModelAttributionSnapshot {
        val entry = entryId?.let { id ->
            providerRepository.config.value.modelEntries.find { it.id == id }
        }
        val instance = entry?.let { providerRepository.instance(it.providerInstanceId) }
        return com.openminis.app.data.model.ModelAttributionSnapshot(
            modelId = model.id,
            displayName = model.displayName,
            // `.name` is the enum's stable rawValue, never the localized
            // displayName — grouping on display strings is what produced the
            // duplicate "Google" / "Gemini" / "Google Gemini" sections.
            providerTypeRaw = instance?.providerType?.name ?: "",
            providerInstanceId = entry?.providerInstanceId,
        )
    }

    /** Structured agent history for the agent loop (contentParts-based). */
    private val agentHistory = mutableListOf<LLMMessage>()
    private val subagentJournal by lazy {
        com.openminis.app.agent.AgentSubagentJournal(chatRepository, agentHistory) { activeSessionId }
    }
    private val subagentRuntime by lazy {
        com.openminis.app.agent.AgentSubagentRuntime(context, chatRepository, providerRepository, viewModelScope, subagentJournal)
    }
    private val subagentControls by lazy { com.openminis.app.agent.AgentSubagentControls(subagentRuntime) }

    private val compactionSummarizer = CompactionSummarizer(COMPACT_IDLE_TIMEOUT_MS, com.openminis.app.agent.CompactionFailurePolicy::canSplit)
    private val compactionCoordinator = com.openminis.app.agent.CompactionCoordinator(
        compactionSummarizer, compactCallsIssued, MAX_COMPACT_LLM_CALLS, com.openminis.app.agent.CompactionFailurePolicy::canSplit,
    ) { depth, issued ->
        _compactProgress.value = _compactProgress.value?.copy(depth = depth, callsIssued = issued)
    }

    private val compactionRuntime = com.openminis.app.agent.AgentCompactionRuntime(
        compactionCoordinator, compactCallsIssued, COMPACT_MAX_TOTAL_MS)

    /**
     * Agent tool definitions are rebuilt so capability switches take effect immediately.
     * The cost is negligible — [AgentTools.makeAgentTools] just builds a
     * fixed list of definition objects, no I/O.
     */
    private val callableAgentTools: List<AgentToolDefinition>
        get() = AgentTools.makeAgentTools(
            isHelper = isHelper,
            delegateEnabled = com.openminis.app.tools.AgentToolSwitch.AGENTS.isEnabled(context),
            browserEnabled = com.openminis.app.tools.AgentToolSwitch.BROWSER.isEnabled(context),
            // [T-sub-agents-v1] Rebuilt on every schema build (this property is
            // not cached), so renaming a sub agent takes effect on the next
            // request and the enum can never advertise a name the resolver
            // would reject.
            rosterNames = providerRepository.subAgents.map { it.name },
            rosterSection = subAgentRosterSection(),
        )

    private fun subagentParent(toolId: String = "", prior: Int = 0): com.openminis.app.agent.AgentSubagentRuntime.Parent {
        val owner = activeSessionId
        return com.openminis.app.agent.AgentSubagentRuntime.Parent(owner, toolId, currentModel?.id,
            _activeEntryId.value, isHelper, prior, checkBranch = {
                if (activeSessionId != owner) throw CancellationException("subagent parent branch changed")
            })
    }

    private fun subagentBlocks(): List<com.openminis.app.agent.AgentSubagentControls.Block> =
        mergeStreamingOverlay(_messages.value, _streamingById.value).filter { it.role == "assistant" }
            .flatMap { it.toolBlocks }.filter {
                it.kind == "tool_use" && com.openminis.app.agent.jobs.HelperRunner.isSubAgentToolName(it.toolName)
            }.map { subagentBlock(it) }

    private fun subagentBlock(block: AssistantBlock) = com.openminis.app.agent.AgentSubagentControls.Block(
        block.id, block.toolTitle, block.toolArgs, block.content)

    private fun executeResumeAgents(argsJson: String): ToolExecutionResult {
        val parent = subagentParent()
        return subagentControls.resumeTool(argsJson, parent, subagentBlocks()) { id -> subagentEffects(parent.copy(toolId = id)) }
    }

    internal fun countInterruptedChildren(parentSessionId: String): Int =
        if (parentSessionId.isEmpty() || activeSessionId != parentSessionId) 0 else subagentControls.interrupted(subagentBlocks()).size

    fun startNeverStartedDelegation(block: AssistantBlock): Boolean {
        val parent = subagentParent()
        return subagentControls.neverStarted(subagentBlock(block), parent, subagentBlocks()) { id -> subagentEffects(parent.copy(toolId = id)) }
    }

    enum class CardResume { STARTED, QUEUED, REFUSED }

    fun resumeInterruptedFromCard(block: AssistantBlock): CardResume {
        val child = com.openminis.app.agent.jobs.HelperRunner.childSessionIdFrom(block.content) ?: return CardResume.REFUSED
        val parent = subagentParent()
        return when (subagentControls.resumeCard(child, parent, subagentBlocks()) { id -> subagentEffects(parent.copy(toolId = id)) }) {
            com.openminis.app.agent.AgentSubagentControls.Resume.STARTED -> CardResume.STARTED
            com.openminis.app.agent.AgentSubagentControls.Resume.QUEUED -> CardResume.QUEUED
            com.openminis.app.agent.AgentSubagentControls.Resume.REFUSED -> CardResume.REFUSED
        }
    }

    fun startQueuedDelegation(argsJson: String, toolUseId: String): Boolean {
        val parent = subagentParent()
        return subagentControls.queued(argsJson, toolUseId, parent, subagentBlocks()) { id -> subagentEffects(parent.copy(toolId = id)) }
    }

    private fun subagentEffects(parent: com.openminis.app.agent.AgentSubagentRuntime.Parent,
        toolBlocks: MutableList<AssistantBlock> = mutableListOf(), assistantId: String = "", currentText: String = "",
        inline: java.util.concurrent.atomic.AtomicBoolean? = null, run: Job? = streamJob): com.openminis.app.agent.AgentSubagentRuntime.Effects {
        val owner = parent.sessionId
        val toolId = parent.toolId
        return com.openminis.app.agent.AgentSubagentRuntime.Effects(
            createChild = { childId, config -> withContext(Dispatchers.Main) {
                parent.checkBranch()
                val pool = browserTabPool
                val vm = com.openminis.app.debug.HeadlessChatRunner.viewModelFor(context, childId,
                    com.openminis.app.ui.chat.ChatViewModelStore.PoolKind.CHILD)
                check(!vm.isStreaming.value && !vm.isCompacting.value) { "child already has an active run" }
                vm.helperConfig = config
                vm.adoptBrowserTabPool(pool)
                ChatSubagentChild(vm, childId, pool)
            } },
            queuedStarter = { item ->
                activeSessionId == owner && item.parentSessionId == owner && startQueuedDelegation(item.argsJson, item.toolUseId)
            }, interruptedCount = { countInterruptedChildren(owner) },
            publish = { content, state -> withContext(Dispatchers.Main) {
                if (activeSessionId == owner) {
                    val status = when (state) {
                        com.openminis.app.agent.jobs.AgentJobState.DONE -> ToolBlockStatus.SUCCESS
                        com.openminis.app.agent.jobs.AgentJobState.CANCELLED -> ToolBlockStatus.CANCELLED
                        com.openminis.app.agent.jobs.AgentJobState.TIMEOUT -> ToolBlockStatus.TIMEOUT
                        com.openminis.app.agent.jobs.AgentJobState.FAILED -> ToolBlockStatus.FAILED
                        com.openminis.app.agent.jobs.AgentJobState.RUNNING, com.openminis.app.agent.jobs.AgentJobState.PENDING -> ToolBlockStatus.RUNNING
                        null -> null
                    }
                    writeDelegateBlock(toolId, content, status)
                    if (inline?.get() == true && assistantId.isNotEmpty() && streamJob === run && run?.isActive == true) {
                        val i = toolBlocks.indexOfFirst { it.id == toolId }
                        if (i >= 0) {
                            toolBlocks[i] = toolBlocks[i].copy(content = content)
                            updateAssistantMessage(assistantId, currentText, true, toolBlocks)
                        }
                    }
                }
            } }, clear = { withContext(Dispatchers.Main) {
                if (activeSessionId == owner) clearDelegateOverride(toolId)
            } }, userWaiting = { activeSessionId == owner && _promptQueue.value.any { it.origin == QueuedPromptOrigin.USER } },
            progress = { text, previous -> withContext(Dispatchers.Main) {
                if (activeSessionId != owner) null else {
                    previous?.let { id -> if (_promptQueue.value.any { it.id == id }) removeQueuedPrompt(id) }
                    if (submitPrompt(text) is SubmitOutcome.Queued) _promptQueue.value.lastOrNull()?.id else null
                }
            } })
    }

    /**
     * [T-sub-agents-v1] The roster block for the system prompt, or "" when the
     * tool is not offered.
     *
     * Empty for a helper (depth 1: a sub agent cannot delegate, so naming the
     * roster to it is pure cost) and when the Agents switch is off.
     */
    private fun subAgentRosterSection(): String {
        if (isHelper) return ""
        if (!com.openminis.app.tools.AgentToolSwitch.AGENTS.isEnabled(context)) return ""
        return com.openminis.app.agent.jobs.HelperRunner.subAgentRosterSection(
            roster = providerRepository.subAgents,
            groupName = { id -> providerRepository.config.value.modelEntries.find { it.id == id }?.model?.displayName },
        )
    }

    /**
     * Per-session loop detector. Reset alongside [agentHistory] whenever the
     * conversation is rewound (edit/regenerate) so a stale tool-call window
     * can't bleed warnings into a fresh prompt.
     */
    private val toolLoopDetector = ToolLoopDetector()

    /**
     * Cached reference to the lazily-created [BrowserTabPool] so
     * [ensureSession] can re-point it at the real session id after a rename.
     * Read only through [browserTabPool]; the backing `by lazy` fills this in.
     */
    @Volatile
    private var _browserTabPoolRef: BrowserTabPool? = null

    /** Browser tab pool for browser tool. Lazily created on first access. */
    val browserTabPool: BrowserTabPool
        get() = _browserTabPoolRef ?: synchronized(this) {
            _browserTabPoolRef ?: BrowserTabPool(context).also {
                it.setSession(activeSessionId)
                // Surface download start/finish/failure as system-info notices in
                // this chat. May fire from the pool's IO scope — hop to Main since
                // appendSystemInfo does a read-modify-write on _messages.
                it.onDownloadEvent = { text ->
                    viewModelScope.launch(kotlinx.coroutines.Dispatchers.Main) {
                        appendSystemInfo(text, "info")
                    }
                }
                _browserTabPoolRef = it
            }
        }

    /** [T-p2-shared-workspace] A helper shares its PARENT's browser tab set:
     *  the same pool instance, never re-stamped with the child's id and never
     *  released by the child (see the helperConfig guards). */
    internal fun adoptBrowserTabPool(pool: BrowserTabPool) { _browserTabPoolRef = pool }

    internal val _showBrowserSheet = MutableStateFlow(false)
    val showBrowserSheet: StateFlow<Boolean> = _showBrowserSheet.asStateFlow()

    // [T-android-split-chat] toggleBrowserSheet / dismissBrowserSheet /
    // openBrowserSheetForUrl moved to ChatViewModelUiStateExt.kt.


    /** Set true by the slash-command "/clear" handler so ChatScreen can mirror
     *  it into the local Compose state that drives the existing
     *  showClearChatDialog confirmation. ChatScreen calls
     *  [ackClearChatConfirmRequest] after observing to reset back to false. */
    private val _clearChatConfirmRequested = MutableStateFlow(false)
    val clearChatConfirmRequested: StateFlow<Boolean> = _clearChatConfirmRequested.asStateFlow()

    fun ackClearChatConfirmRequest() {
        _clearChatConfirmRequested.value = false
    }

    // ── Slash commands (mirrors iOS AIChatViewModel) ────────────────────


    /**
     * [T-android-usage-capsule-style] Message ids whose token-usage capsule is
     * revealed. Default empty: the capsule is an easter egg, not a permanent
     * footer — iOS keeps it hidden until the user taps the blank area at the
     * bottom of the reply, and taps again to put it away.
     *
     * Held here rather than in a composable because the flat renderer emits
     * the capsule as its own list item, with no shared parent to remember it
     * in. Keying by message id also means a row scrolled out and back keeps
     * whatever the user chose. Session-scoped, so it resets with the ViewModel.
     */
    internal val _revealedUsageIds = MutableStateFlow<Set<String>>(emptySet())
    val revealedUsageIds: StateFlow<Set<String>> = _revealedUsageIds.asStateFlow()

    fun toggleUsageCapsule(messageId: String) {
        val cur = _revealedUsageIds.value
        _revealedUsageIds.value = if (messageId in cur) cur - messageId else cur + messageId
    }

    internal val _thinkingLevel = MutableStateFlow(ThinkingLevel.OFF)
    val thinkingLevel: StateFlow<ThinkingLevel> = _thinkingLevel.asStateFlow()

    /**
     * [T-android-enhanced-cache] Enhanced Cache (1-hour Anthropic cache TTL)
     * toggle. Per-VM memory state, NOT persisted — mirrors iOS
     * `AIChatViewModel.enhancedCacheEnabled`. When true, the active turn's
     * AnthropicProvider is stamped with `enhancedCache = true` just before the
     * request (see the streamMessage choke point).
     */
    internal val _enhancedCacheEnabled = MutableStateFlow(false)
    val enhancedCacheEnabled: StateFlow<Boolean> = _enhancedCacheEnabled.asStateFlow()

    /**
     * [T-android-enhanced-cache] Whether the Enhanced Cache menu item is shown.
     * Mirrors iOS `showEnhancedCacheToggle` (commit 57aaf122): only visible when
     * the current session's resolved provider instance is the *official*
     * Anthropic API (`providerType == anthropic` AND `customBaseURL` is
     * blank) — relays / other providers hide it because they don't honor the
     * 1-hour cache TTL. Recomputes whenever the active entry or provider config
     * changes so switching model/provider updates visibility instantly.
     */
    val showEnhancedCacheToggle: StateFlow<Boolean> =
        kotlinx.coroutines.flow.combine(
            _activeEntryId,
            providerRepository.config,
        ) { entryId, config ->
            val entry = entryId?.let { id -> config.modelEntries.find { it.id == id } }
            val instance = entry?.let { e -> config.instances.find { it.id == e.providerInstanceId } }
            instance != null &&
                instance.providerType == com.openminis.app.data.model.ProviderType.anthropic &&
                instance.customBaseURL.isNullOrBlank()
        }.stateIn(
            viewModelScope,
            kotlinx.coroutines.flow.SharingStarted.Eagerly,
            false,
        )

    /** [T-android-enhanced-cache] True once the user accepted the one-time warning. */
    fun isEnhancedCacheConfirmed(): Boolean =
        com.openminis.app.data.EnhancedCachePrefs.isConfirmed(context)

    /**
     * [T-android-enhanced-cache] Enable Enhanced Cache after the confirmation
     * dialog was accepted (records the durable acknowledgement) and flips the
     * in-memory toggle on.
     */
    fun confirmAndEnableEnhancedCache() {
        com.openminis.app.data.EnhancedCachePrefs.setConfirmed(context)
        _enhancedCacheEnabled.value = true
    }

    /**
     * [T-android-enhanced-cache] Toggle the switch when confirmation is not
     * required (turning it OFF, or turning it ON after the user already
     * acknowledged). The confirmation-gated first enable is handled in the UI.
     */
    fun setEnhancedCacheEnabled(enabled: Boolean) {
        _enhancedCacheEnabled.value = enabled
    }

    /**
     * [T-codex-fast-mode] Fast Mode toggle state. APP-LEVEL and persisted
     * (FastModePrefs / iOS UserDefaults "codexFastModeEnabled") — unlike
     * Enhanced Cache it survives across sessions and process restarts; every
     * chat reads the same flag. The provider reads FastModePrefs directly at
     * request-build time, so this flow only drives the menu row + nav badge.
     */
    internal val _fastModeEnabled =
        MutableStateFlow(com.openminis.app.data.FastModePrefs.isEnabled())
    val fastModeEnabled: StateFlow<Boolean> = _fastModeEnabled.asStateFlow()

    fun setFastModeEnabled(enabled: Boolean) {
        com.openminis.app.data.FastModePrefs.setEnabled(context, enabled)
        _fastModeEnabled.value = enabled
    }

    /**
     * Auto-compact toggle state. APP-LEVEL and persisted
     * (AutoCompactPrefs / iOS UserDefaults "autoCompactOnThreshold").
     *
     * When on, crossing the compact threshold before a send compacts silently
     * and then sends; when off, the user is asked first. Mirrors iOS
     * `AIChatViewModel.autoCompactEnabled`.
     */
    internal val _autoCompactEnabled =
        MutableStateFlow(com.openminis.app.data.AutoCompactPrefs.isEnabled())
    val autoCompactEnabled: StateFlow<Boolean> = _autoCompactEnabled.asStateFlow()

    fun setAutoCompactEnabled(enabled: Boolean) {
        com.openminis.app.data.AutoCompactPrefs.setEnabled(context, enabled)
        _autoCompactEnabled.value = enabled
    }

    /**
     * [T-codex-fast-mode] Whether the Fast Mode menu row (and, when enabled,
     * the nav ⚡ badge) is shown. Mirrors iOS activeModelSupportsFastMode
     * (838ba929): the active model id contains "gpt" (case-insensitive —
     * matches the official fast catalog gpt-5.6-sol/terra/luna, gpt-5.5,
     * gpt-5.4) AND the request travels the Responses path — the instance has
     * useResponsesAPI on (any credential/base; Responses relays like sub2api
     * pass the tier through) OR it's the Codex OAuth route (OpenAI type +
     * oauth credential + no custom base URL). Chat-completions providers stay
     * excluded. Recomputes on entry/config changes like the Enhanced Cache
     * gate above.
     *
     * [T-android-xai-priority] xAI is a second, independent branch: xAI's
     * Priority Processing is the same `service_tier: "priority"` wire field
     * and the same user-facing promise (lower latency, higher price), so it
     * reuses this one global toggle rather than adding a competing per-provider
     * switch. The "gpt" model-id test deliberately does NOT apply — xAI's
     * models are the grok family — and neither does the Responses-path test,
     * because xAI serves Priority Processing on its Chat Completions endpoint,
     * which is the path ProviderFactory always resolves xAI to.
     */
    val showFastModeToggle: StateFlow<Boolean> =
        kotlinx.coroutines.flow.combine(
            _activeEntryId,
            providerRepository.config,
        ) { entryId, config ->
            val entry = entryId?.let { id -> config.modelEntries.find { it.id == id } }
            val instance = entry?.let { e -> config.instances.find { it.id == e.providerInstanceId } }
            val isCodexOAuth = instance != null &&
                instance.providerType == com.openminis.app.data.model.ProviderType.openAI &&
                instance.credentialType == com.openminis.app.data.model.ProviderCredential.oauth &&
                instance.customBaseURL.isNullOrBlank()
            val isXAI = instance?.providerType == com.openminis.app.data.model.ProviderType.xAI
            entry != null && instance != null &&
                (
                    isXAI ||
                        (
                            entry.model.id.contains("gpt", ignoreCase = true) &&
                                (instance.useResponsesAPI || isCodexOAuth)
                            )
                    )
        }.stateIn(
            viewModelScope,
            kotlinx.coroutines.flow.SharingStarted.Eagerly,
            false,
        )

    internal val _showSlashMenu = MutableStateFlow(false)
    val showSlashMenu: StateFlow<Boolean> = _showSlashMenu.asStateFlow()

    internal val _slashFilter = MutableStateFlow("")
    val slashFilter: StateFlow<String> = _slashFilter.asStateFlow()

    internal val _slashMenuSelectedIndex = MutableStateFlow(-1)
    val slashMenuSelectedIndex: StateFlow<Int> = _slashMenuSelectedIndex.asStateFlow()

    /**
     * [T-android-slash-menu-align-ios-prepend] The user's ORIGINAL composer
     * text, saved when the slash menu is opened via the "/" button over
     * existing content. Non-null ⇒ "over-content" mode; null ⇒ the menu was
     * opened by typing a leading "/" (the input itself is the slash query).
     *
     * Mirrors iOS `savedInputBeforeSlash`. On open we PREPEND "/ " to the
     * composer so it reads `/ <original>`; the user's subsequent typing edits
     * only the `/<filter>` token (see [updateSlashMenuState]), while
     * `<original>` is preserved here. Every exit path restores/uses this saved
     * original — never the live `/ <original>` string — so the injected "/ "
     * prefix is always stripped and the body text is never lost.
     *
     * This is the iOS-parity replacement for the earlier boolean marker. It
     * does NOT regress e48fe7a0 ("don't clear input"): the original body is
     * saved and faithfully restored on dismiss / prepended on skill select; it
     * is never discarded. The only behavioral change is that the body now sits
     * AFTER the slash token (iOS semantics) instead of being edited live.
     */
    internal var savedInputBeforeSlash: String? = null

    // ── @ file-mention picker (mirrors iOS AIChatViewModel mention*) ─────
    /**
     * Per-app singleton — scans /var/minis/{workspace,attachments,shared,
     * skills}/<sessionId>/ on demand, ranks matches by basename
     * fuzzy score + scope priority. The composer hooks update*MentionMenu*
     * on every keystroke; the popup composes against [mentionEntries].
     */
    val fileMentionIndex: FileMentionIndex by lazy {
        // T219: provide the SAF-mounted external folders so `@<mountName>`
        // resolves to /var/minis/mounts/<name>/... in the chat composer.
        // PRootKernel holds the MountedFoldersStore reference (set at app
        // launch by MinisApp); reading via a closure means the index sees
        // an up-to-date snapshot on every rescan without a manual refresh.
        FileMentionIndex(
            filesDir = java.io.File(context.applicationContext.filesDir, "minis-global"),
            mountsProvider = {
                com.openminis.app.sandbox.PRootKernel
                    .mountEntriesForIndex(context.applicationContext)
            },
        )
    }

    internal val _showMentionMenu = MutableStateFlow(false)
    val showMentionMenu: StateFlow<Boolean> = _showMentionMenu.asStateFlow()

    internal val _mentionFilter = MutableStateFlow("")
    val mentionFilter: StateFlow<String> = _mentionFilter.asStateFlow()

    /** Caret index of the active `@` in [inputText], or -1 when no token is open. */
    internal val _mentionAnchor = MutableStateFlow(-1)

    /** Live-filtered candidate list. Combines the index's [FileMentionIndex.entries]
     * with [mentionFilter] so matches refresh as the user types and as the
     * background scan emits more entries. Capped at 50 like iOS. */
    val mentionEntries: StateFlow<List<FileMentionIndex.Entry>> = combine(
        fileMentionIndex.entries,
        _mentionFilter,
    ) { _, filter -> fileMentionIndex.matches(filter, limit = 50) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val isMentionScanning: StateFlow<Boolean>
        get() = fileMentionIndex.isScanning

    /**
     * T-at-filepicker-keyboard: highlighted row in the @-mention picker. -1 when
     * the menu is closed or the filtered list is empty. Mirrors iOS
     * `mentionSelectedIndex` so a hardware-keyboard user can Up/Down through
     * candidates and hit Return to commit the highlighted entry. Touch users
     * still tap rows directly — the highlight just shows which row Return
     * would land on.
     */
    internal val _mentionSelectedIndex = MutableStateFlow(-1)
    val mentionSelectedIndex: StateFlow<Int> = _mentionSelectedIndex.asStateFlow()

    val currentModelSupportsReasoning: Boolean
        get() = currentModel?.supportsReasoning == true

    /**
     * [T-android-thinking-level-arch] The thinking ceiling the currently-bound
     * model actually supports. Prefers the active ModelEntry's
     * effectiveMaxThinkingLevel (so a user override on the entry is honored);
     * falls back to the resolved model's catalog default when no entry is
     * pinned (e.g. a group-resolved turn) or the model isn't known.
     */
    private val currentModelMaxThinkingLevel: ThinkingLevel
        get() {
            val entry = _activeEntryId.value?.let { id ->
                providerRepository.config.value.modelEntries.find { it.id == id }
            }
            if (entry != null) {
                return entry.effectiveMaxThinkingLevel
            }
            val model = currentModel ?: return ThinkingLevel.XHIGH
            return model.catalogMaxThinkingLevel
        }

    /**
     * [T-android-thinking-level-arch] Levels the chat composer picker should
     * offer: everything up to the current model's ceiling, EXCLUDING OFF —
     * mirrors iOS availableThinkingLevels (`filter { $0 != .off && $0 <= max }`).
     * There is no standalone "Off" capsule; tapping the already-selected level
     * toggles thinking off (see ThinkingLevelPicker). setThinkingLevel
     * additionally clamps as a belt-and-suspenders defense.
     */
    val availableThinkingLevels: List<ThinkingLevel>
        get() {
            val ceiling = currentModelMaxThinkingLevel
            return ThinkingLevel.entries.filter { it != ThinkingLevel.OFF && it.rank <= ceiling.rank }
        }

    // [T-anthropic-context-window] Token Usage sheet's context-window row.
    // Route through contextWindowTokens (heuristic-backed) so models without an
    // explicit contextWindow — e.g. heuristic-only Claude/Gemini — still report
    // their real 1M window instead of showing blank.
    val currentModelContextWindow: Int?
        get() = effectiveContextWindowTokens()

    /**
     * [T-context-window-live-read] Effective context window for capacity
     * judgment (compaction warnings, tool-output offload, empty-response
     * heuristic, Token Usage sheet). Reads LIVE state on every call instead of
     * the `currentModel` snapshot, so editing the model's context window or
     * the bound group's `contextLimitTokens` takes effect on the very next
     * judgment without re-picking the model/group (mirrors iOS fcc22b66):
     *   1. the active entry's model is re-resolved from the current repository
     *      config (folds ModelOverrides live), falling back to the snapshot
     *      only when the entry can't be found (e.g. synced sessions before
     *      config finished loading);
     *   2. the result is clamped by the bound group's `contextLimitTokens`
     *      (null / <=0 = unlimited). Pre-fix that group field was write-only
     *      on Android — persisted by the group editor but never consulted at
     *      runtime.
     */
    private fun effectiveContextWindowTokens(): Int? = resolvedContextWindow()?.first

    /**
     * [T-ctx-user-cap] The effective window plus whether it came from a
     * user-chosen group cap that actually binds (i.e. is smaller than the
     * model's native window). [ContextPolicy] needs the flag to pick
     * proportional thresholds instead of the native-window tier table.
     */
    private fun resolvedContextWindow(forModel: LLMModel? = null): Pair<Int, Boolean>? {
        val config = providerRepository.config.value
        val liveModel = forModel ?: _activeEntryId.value
            ?.let { id -> config.modelEntries.find { it.id == id }?.model }
            ?: currentModel
        val window = liveModel?.contextWindowTokens ?: return null
        val groupLimit = sessionContextLimitTokens
            ?.takeIf { it > 0 }
            ?: return window to false
        // A cap above the model's own window is not a licence to overflow the
        // model — it just means "no practical limit".
        return if (groupLimit < window) groupLimit to true else window to false
    }

    /** [T-ctx-user-cap] Policy for the current window, honouring a user cap. */
    private fun currentContextPolicy(): Pair<ContextPolicy, Int>? {
        val (window, isUserCap) = resolvedContextWindow() ?: return null
        val policy = if (isUserCap) {
            ContextPolicy.forUserCap(window)
        } else {
            ContextPolicy.forContextWindow(window)
        }
        return policy to window
    }

    val currentModelMaxOutputTokens: Int?
        get() = currentModel?.maxOutputTokens

    // ── Session token usage (iOS parity: TokenUsageSheet data) ─────────────

    /**
     * Aggregated token usage for this session, computed from all persisted
     * `token_usage` JSON rows. Mirrors iOS [sessionTokenStats].
     *
     * @param context the most recent [LLMUsage.latestContextTokens] — reflects
     * how much of the model's context window was consumed at the last turn.
     * @param loopCount number of agent loop iterations (approximated by
     * max(tool_use blocks, assistant message count), matching iOS).
     */
    data class ThinkingInfo(
        val supported: Boolean,
        val enabled: Boolean,
        val level: String,
    )

    /** Read-only view of the current thinking configuration for the model. */
    fun thinkingInfo(): ThinkingInfo? {
        val model = currentModel ?: return null
        val supported = model.supportsReasoning == true
        val level = _thinkingLevel.value
        val enabled = supported && level.isEnabled
        val levelText = if (enabled) level.displayName else "—"
        return ThinkingInfo(supported, enabled, levelText)
    }

    /**
     * Load session-level token aggregates from the database. Suspend so the
     * Token Usage sheet can fetch on demand without keeping a live subscription
     * — token data rarely changes mid-view, and we want to avoid reactive
     * overhead per token chunk.
     */
    suspend fun loadSessionTokenStats(): SessionTokenStats {
        val sid = realSessionId.ifEmpty { sessionId }
        if (sid.isEmpty()) return SessionTokenStats(0, 0, 0, 0, 0, 0)
        val usages = chatRepository.sessionTokenUsages(sid)
        val snapshot = _messages.value
        val assistantCount = snapshot.count { it.role == "assistant" }
        val toolCalls = snapshot.filter { it.role == "assistant" }
            .sumOf { msg -> msg.toolBlocks.count { it.kind != "text" && it.kind != "info" } }
        val loops = maxOf(toolCalls, assistantCount)
        return SessionTokenStats.fromUsageRecords(usages, loops)
    }


    // ── Slash command API (mirrors iOS AIChatViewModel) ─────────────────

    /** Static catalogue of available slash commands, in display order.
     *  Subtitles are placeholders here — [filteredSlashCommands] always
     *  rebuilds them with the current localized state. */
    internal val availableSlashCommands: List<SlashCommand> = listOf(
        SlashCommand(
            id = "clear",
            icon = Icons.Default.Delete,
            title = "Clear",
            subtitle = "",
        ),
        SlashCommand(
            id = "compact",
            icon = Icons.Default.Compress,
            title = "Compact",
            subtitle = "",
        ),
        SlashCommand(
            id = "thinking",
            icon = Icons.Default.Lightbulb,
            title = "Thinking",
            subtitle = "",
        ),
    )

    // [T-android-split-chat] filteredSlashCommands / updateSlashMenuState /
    // showSlashMenuOverInput / dismissSlashMenu / slashMenuSetSelectedIndex moved
    // to ChatViewModelSlashExt.kt as ChatViewModel extension functions.

    // ── @ file-mention picker driver ──────────────────────────────────────
    // [T-android-split-chat] updateMentionMenuState / dismissMentionMenu /
    // mentionMenuUp / mentionMenuDown / executeSelectedMention / selectMention
    // moved to ChatViewModelMentionExt.kt as ChatViewModel extension functions.

    /**
     * Execute a slash command. Returns the text the composer should hold
     * afterward (caret via [pendingCaret] when relevant).
     *
     * [T-android-slash-menu-align-ios-prepend] Over-content (the menu was
     * opened via the "/" button, so [savedInputBeforeSlash] holds the user's
     * original text): a skill row prepends "/<skill> " to the original; an
     * action command (clear/compact/…) runs as a side effect and restores the
     * original (stripping the injected "/ "). Typed-"/" (no saved original):
     * a skill fills "/<skill> ", an action clears the input. The original body
     * is always preserved — never discarded (no regression of e48fe7a0).
     *
     * [T-android-mcp-slash-dispatch] "Skill row" above means every
     * COMPOSER-FILL row: both [SlashCommand.isSkill] and [SlashCommand.isMcp]
     * take that path. Only rows carrying NEITHER flag are executable commands
     * dispatched by id. GH#372.
     *
     * [currentInput] is retained for call-site compatibility; the body text is
     * sourced from [savedInputBeforeSlash], not the live string.
     */
    fun executeSlashCommand(cmd: SlashCommand, currentInput: String = ""): String {
        val saved = savedInputBeforeSlash
        // [T-skill-slash a88ea8f9] Skill rows aren't directly executable —
        // they're a typing aid. Fill the composer with the literal slash
        // command; the user then taps Send and the model handles the skill via
        // the existing SKILL.md fragment injection in runAgentLoop.
        //
        // [T-android-mcp-slash-dispatch] MCP rows are the same kind of row, and
        // this guard used to test `isSkill` ALONE. MCP rows carry [isMcp] — a
        // flag deliberately kept distinct from [isSkill] so the picker can tag
        // them "[mcp]" with a wrench — so an MCP tap fell straight through to
        // the `when (cmd.id)` below, matched none of the four built-in ids, hit
        // its `else` branch and returned "" — which the caller assigns back
        // into the composer (ChatScreen: setInputText(executeSlashCommand(…))).
        // The user saw the menu close and their input vanish, with only
        // "[Slash] unrecognized id=mcp:<name> — no dispatch" in the log. GH#372.
        //
        // iOS has gated on `cmd.isSkill || cmd.isMCP` since 3c048b909; the
        // Android MCP commit (04aed37a8, same day) added the flag and the rows
        // but never the dispatch, leaving [isMcp] write-only for three months.
        // The branch body already implements the exact iOS semantics, so MCP
        // inherits prefix-prepend, caret placement and menu dismissal for free.
        if (cmd.isSkill || cmd.isMcp) {
            val kind = if (cmd.isMcp) "mcp" else "skill"
            AppLogger.info(TAG, "[Slash] tap $kind id=${cmd.id} title=${cmd.title} → composer fill only")
            savedInputBeforeSlash = null
            _showSlashMenu.value = false
            _slashMenuSelectedIndex.value = -1
            val prefix = "/${cmd.title} "
            // [T-android-slash-menu-align-ios-prepend] iOS parity: over-content
            // (saved != null) → PREPEND "/<skill> " to the original, so the
            // composer reads "/<skill> <original>" with the original as args,
            // caret right after the prefix (before the original). Typed-"/"
            // (saved == null) → just "/<skill> " (the input WAS the partial
            // command). Trailing space lets the user type "/<skill> <args>".
            return if (saved != null) {
                _pendingCaret.value = prefix.length
                prefix + saved
            } else {
                prefix
            }
        }
        AppLogger.info(TAG, "[Slash] tap id=${cmd.id} title=${cmd.title} streaming=${_isStreaming.value} compacting=${_isCompacting.value}")
        savedInputBeforeSlash = null
        _showSlashMenu.value = false
        _slashMenuSelectedIndex.value = -1

        when (cmd.id) {
            "compact" -> compactAll()
            "thinking" -> toggleThinking()
            "clear" -> _clearChatConfirmRequested.value = true
            else -> AppLogger.info(TAG, "[Slash] unrecognized id=${cmd.id} — no dispatch")
        }
        // [T-android-slash-menu-align-ios-prepend] Action command: restore the
        // saved ORIGINAL (stripping the injected "/ " prefix) so the body text
        // survives — never the live "/ <original>". Typed-"/" path → clear.
        if (saved != null) {
            _pendingCaret.value = saved.length
            return saved
        }
        return ""
    }

    /** Toggle thinking between OFF and MEDIUM (matches iOS default toggle semantics). */
    private fun toggleThinking() {
        if (!currentModelSupportsReasoning) {
            appendSystemInfo(
                text = "The current model does not support deep thinking.",
                iconKind = "thinking",
            )
            return
        }
        val newLevel = if (_thinkingLevel.value.isEnabled) ThinkingLevel.OFF else ThinkingLevel.MEDIUM
        _thinkingLevel.value = newLevel
        persistThinkingOverride(newLevel)
        appendSystemInfo(
            text = "Thinking set to ${newLevel.displayName.lowercase()}.",
            iconKind = "thinking",
        )
    }

    /**
     * Set thinking level explicitly. Used by the inline level picker in the
     * `/thinking` slash row. Mirrors iOS `setThinkingLevel(_:)` — silently
     * ignored when the current model doesn't support reasoning.
     */
    fun setThinkingLevel(level: ThinkingLevel) {
        if (!currentModelSupportsReasoning) return
        // [T-android-thinking-level-arch] Double-safety clamp: the composer UI
        // already filters to availableThinkingLevels, but never fully trust the
        // caller — cap to the current model's ceiling so a stale/over-range
        // request can't persist a level the model can't reach.
        val ceiling = currentModelMaxThinkingLevel
        val clamped = if (level.rank > ceiling.rank) ceiling else level
        // Even reselecting the current level is an explicit choice for future chats.
        _thinkingLevel.value = clamped
        persistThinkingOverride(clamped)
    }

    /**
     * T239: write the user's explicit thinking-level choice back to the
     * sessions row so it survives cold-start. Stored as enum name; null
     * means "no override" (legacy behaviour). We always store a non-null
     * value here — including OFF — because the user's explicit "turn it
     * off for this session" must persist as distinct from "never set".
     *
     * Also remembers the choice globally for new chats, including on drafts.
     * The session override is written only if a row already exists; otherwise
     * [ensureSession] flushes the in-memory level on first send.
     */
    private fun persistThinkingOverride(level: ThinkingLevel) {
        providerRepository.lastUsedThinkingLevel = level
        // [T-android-draft-no-row-on-open] Same rule as
        // `applyGroupSessionDefaults`: write only when a row exists, never
        // create one. Merely opening the thinking picker on a fresh chat and
        // choosing a level is not "start a conversation", and `ensureSession()`
        // here turned it into one.
        //
        // Mirrors iOS `setThinkingLevel`, whose doc comment states the rule
        // outright — "persists if session exists, otherwise holds in memory" —
        // and whose else-branch parks the value in `pendingThinkingLevel`.
        // Android's in-memory hold is `_thinkingLevel` itself, which the caller
        // has already set; `ensureSession()` flushes it on first send.
        val sid = realSessionId
        if (sid.isEmpty()) return
        viewModelScope.launch {
            chatRepository.dao.updateThinkingOverride(sid, level.name)
        }
    }

    /**
     * If `text` is a slash command literal (e.g. "/compact"), run it and
     * return true so the caller can skip the normal send path. Mirrors iOS
     * `tryExecuteInputAsSlashCommand()`. Recognized titles are matched
     * case-insensitively against [availableSlashCommands].
     *
     * Accepts both ASCII `/` and the full-width `／` (U+FF0F): some Chinese/
     * Japanese IMEs auto-substitute the full-width form when the user types
     * `/` while a CJK keyboard layout is active. We treat them identically.
     */
    fun tryExecuteInputAsSlashCommand(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return false
        val first = trimmed[0]
        if (first != '/' && first != '／') return false
        val name = trimmed.drop(1).lowercase()
        val cmd = availableSlashCommands.firstOrNull { it.title.lowercase() == name }
            ?: return false
        executeSlashCommand(cmd)
        return true
    }

    /**
     * Append a system-info block to the conversation. Not persisted — matches the
     * iOS `appendSystemInfo` behavior which surfaces a local notice in the chat
     * stream. Future work: wire real conversation compaction through the LLM.
     */
    private fun appendSystemInfo(text: String, iconKind: String, payload: String? = null) {
        val block = AssistantBlock(
            id = "sysinfo_${System.currentTimeMillis()}",
            kind = "info",
            content = text,
            toolName = iconKind,
            // Reuse toolArgs as a freeform payload slot — for `iconKind="compact"`
            // this carries the full summary text so the UI can show an info-icon
            // affordance opening a detail sheet (mirrors iOS CompactSummarySheet).
            toolArgs = payload.orEmpty(),
        )
        _messages.value = _messages.value + ChatMessage(
            id = "sysinfo_${System.currentTimeMillis()}",
            role = "system",
            content = "",
            toolBlocks = listOf(block),
        )
    }

    /**
     * Fold the current session history into a single summary stored in
     * `compact_markers`. Mirrors iOS `compactAll()` + Phase-B semantics:
     *
     *   1. Build a compact conversation transcript (role + parts preview).
     *   2. Call the **current provider's non-streaming `sendMessage`** with a
     *      hardcoded summarization system prompt that emphasises preserving
     *      paths/commands/IDs/decisions/errors/open tasks.
     *   3. Persist a `CompactMarkerEntity` via the DAO; publish via
     *      [_compactSummary] so [effectiveAgentHistory] starts injecting it.
     *   4. agentHistory itself is NOT truncated — the audit trail stays.
     *
     * Concurrency: gated by [_isCompacting] so the slash command can't
     * overlap with an in-flight streaming turn (`_isStreaming`) or another
     * compact. Runs on [Dispatchers.IO].
     */
    /**
     * Public entrypoint used by the debug RPC (`chat.session.compact`) to
     * trigger compaction without going through the ChatScreen slash-command
     * UI path. Mirrors what [executeSlashCommand]("compact") does — just
     * calls [compactAll]. RPC callers can then observe [isCompacting] flipping
     * back to false to know the run finished, and read [compactSummary] for
     * the resulting summary text.
     */
    fun runCompactNow() {
        compactAll()
    }

    /**
     * Public entrypoint for "compact up through this message" (mirrors iOS
     * AIChatViewModel.compactBefore). The chat list's long-press menu and
     * the debug RPC `chat.compact.before` route through here.
     *
     * @param dbMessageId the DB message id to use as the new marker's
     *   anchor. agentHistory range to compact = `[prevAnchor+1, anchorIdx]`
     *   where anchorIdx is the agentHistory position of this id.
     * @param includesBoundary accepted for ABI compatibility with iOS, but
     *   in v2 the anchor IS the caller-supplied message regardless — the
     *   flag is logged and ignored. (iOS made the same simplification.)
     *
     * If the id can't be resolved to an agentHistory entry, this falls
     * back to compactAll() behaviour so the user's gesture isn't lost.
     */
    fun compactBefore(dbMessageId: String, includesBoundary: Boolean = false) {
        AppLogger.info(
            TAG,
            "[Compact] compactBefore() id=${dbMessageId.take(8)} includesBoundary=$includesBoundary " +
                "(v2: includesBoundary ignored — caller-supplied id becomes the anchor)",
        )
        val history = agentHistory.toList()
        val idx = history.indexOfLast { it.dbMessageId == dbMessageId }
        if (idx < 0) {
            AppLogger.warning(
                TAG,
                "[Compact] compactBefore: id=${dbMessageId.take(8)} not in agentHistory — falling back to compactAll()",
            )
            compactAll(anchorIdxOverride = null)
            return
        }
        compactAll(anchorIdxOverride = idx)
    }

    /**
     * [T-android-auto-compact-inloop] Compact the session.
     *
     * [allowDuringProcessing] lets the in-loop guard in [runAgentLoop] compact
     * BETWEEN agent iterations, where `_isStreaming` is legitimately true. All
     * user-initiated paths keep the default (false) so the "can't compact while
     * a turn is running" guard is unchanged for them. Re-entrancy is still
     * covered by [_isCompacting]. Mirrors iOS f70ac173.
     *
     * [onFinished] fires on the IO coroutine once the compaction attempt has
     * settled (success or failure), so the loop can await it before issuing the
     * next API call — the function itself is fire-and-forget.
     */
    /**
     * Public entry: guarantees [onFinished] is invoked exactly once even when a
     * precondition rejects the request before any work is launched. The inner
     * implementation has many early returns; wrapping it here is safer than
     * threading a callback through each one, and it means an in-loop caller can
     * never hang waiting for a callback that was skipped.
     */
    private fun compactAll(
        anchorIdxOverride: Int? = null,
        allowDuringProcessing: Boolean = false,
        onFinished: ((Boolean) -> Unit)? = null,
    ): Job? {
        var started = false
        compactAllImpl(anchorIdxOverride, allowDuringProcessing, onFinished) { started = true }
        if (!started) onFinished?.invoke(false)
        return if (started) compactJob else null
    }

    private inline fun compactAllImpl(
        anchorIdxOverride: Int?,
        allowDuringProcessing: Boolean,
        noinline onFinished: ((Boolean) -> Unit)?,
        markStarted: () -> Unit,
    ) {
        if (runCoordinator.mutating) return
        AppLogger.info(TAG, "[Compact] compactAll() invoked streaming=${_isStreaming.value} compacting=${_isCompacting.value} historySize=${agentHistory.size} anchorOverride=$anchorIdxOverride inLoop=$allowDuringProcessing")
        if (_isStreaming.value && !allowDuringProcessing) {
            AppLogger.info(TAG, "[Compact] aborted: stream in progress")
            appendSystemInfo(
                text = "Cannot compact while a turn is in progress. Stop the current response first.",
                iconKind = "compact",
            )
            return
        }
        if (_isCompacting.value) {
            AppLogger.info(TAG, "[Compact] aborted: another compact already in flight")
            appendSystemInfo(
                text = "A compact is already in progress. Please wait for it to finish.",
                iconKind = "compact",
            )
            return
        }
        if (currentProvider == null) {
            appendSystemInfo("No provider configured. Cannot compact.", "compact")
            return
        }
        val compactSessionId = activeSessionId
        val preparation = com.openminis.app.agent.AgentCompactionJournal.prepare(
            agentHistory, _cachedLatestMarker, anchorIdxOverride)
        val plan = when (preparation) {
            is com.openminis.app.agent.AgentCompactionJournal.Preparation.Ready -> preparation.plan
            is com.openminis.app.agent.AgentCompactionJournal.Preparation.Rejected -> {
                val text = when (preparation.reason) {
                    com.openminis.app.agent.AgentCompactionJournal.Rejection.EMPTY -> "Nothing to compact — the session is empty."
                    com.openminis.app.agent.AgentCompactionJournal.Rejection.NO_PERSISTED_ANCHOR -> "Cannot compact: no persisted messages yet."
                    com.openminis.app.agent.AgentCompactionJournal.Rejection.ALREADY_COMPACTED -> "Already compacted up to this point."
                }
                appendSystemInfo(text, "compact")
                return
            }
        }
        val compactionJournal = com.openminis.app.agent.AgentCompactionJournal(
            chatRepository, compactSessionId, plan) { activeSessionId }
        val toCompact = plan.messages
        // Past every precondition — from here the launch below owns the
        // onFinished callback.
        markStarted()
        val compactMeasuredBefore = runCatching { measureOutboundContextTokens() }.getOrDefault(-1)
        _isCompacting.value = true
        // [T-compact-idle-timeout] The working limit is the per-stream idle
        // timer inside generateCompactSummary; this is only the runaway
        // backstop. Transcript length no longer scales it — under an idle timer
        // a big transcript just takes longer, which is correct, and sizing a
        // total budget off it was what cancelled healthy streams mid-flight.
        val transcriptChars = compactionCoordinator.transcript(toCompact).length
        val timeoutMs = COMPACT_MAX_TOTAL_MS
        _compactProgress.value = CompactProgress(
            startedAtMs = System.currentTimeMillis(),
            depth = 0,
            callsIssued = 0,
            callBudget = MAX_COMPACT_LLM_CALLS,
            timeoutSeconds = (timeoutMs / 1000L).toInt(),
            modelName = currentModel?.displayName,
        )
        AppLogger.info(
            TAG,
            "[Compact] starting: ${toCompact.size} entries, ${transcriptChars} transcript chars, " +
                "idleTimeout=${COMPACT_IDLE_TIMEOUT_MS / 1000}s, " +
                "maxTotal=${timeoutMs / 1000}s, callBudget=$MAX_COMPACT_LLM_CALLS",
        )
        compactionRuntime.launch(viewModelScope, compactionJournal,
            currentSession = { activeSessionId }, entryId = _activeEntryId.value,
            previousSummary = _compactSummary.value, provider = { currentProvider },
            prepare = { prepareCompactionInput(compactSessionId) }, candidates = ::buildFallbackProviders,
            identity = { it.entryId }, adopted = { next ->
                adoptFallbackCandidate(next)
                _compactProgress.value = _compactProgress.value?.copy(modelName = next.provider.model.displayName)
            }, publish = { marker ->
                    val summary = marker.summary
                    val lastCompactedDbId = requireNotNull(marker.lastCompactedMessageId)
                    _compactSummary.value = marker.summary
                    _cachedLatestMarker = marker
                    // Gray out everything in the compacted range; the kept
                    // tail (last N user turns + tool/assistant follow-ups)
                    // stays full opacity. Determined by walking _messages
                    // until we pass the row whose id == lastCompactedDbId.
                    //
                    // Also drop any prior compact-divider system rows — a
                    // session shows at most one divider (the latest marker).
                    // Those old dividers are stored as system messages with
                    // a "compact" iconKind in toolBlocks[0].toolName.
                    val cutoffId: String = lastCompactedDbId
                    var passedCutoff = false   // anchor is guaranteed non-null in v2
                    val cleaned = _messages.value
                        .filterNot { msg ->
                            // Drop prior compact-divider rows; appendSystemInfo
                            // below will re-add the new one.
                            msg.role == "system" &&
                                msg.toolBlocks.firstOrNull()?.toolName == "compact"
                        }
                        .map { msg ->
                            if (msg.role == "system") msg
                            else if (passedCutoff) msg
                            else {
                                val grayed = if (msg.isCompactedHistory) msg
                                    else msg.copy(isCompactedHistory = true)
                                if (msg.id == cutoffId) passedCutoff = true
                                grayed
                            }
                        }
                    // T84: count UI bubbles in this pass's compacted range.
                    // Filters: role != system (dividers/notices don't count).
                    // Range: everything up to and including the cutoff row,
                    // since the kept-tail starts immediately after.
                    // Falls back to "all non-system" when cutoffId is null
                    // (compact-everything path), matching iOS dividerInsertIdx
                    // == messages.count behavior.
                    //
                    // We deliberately do NOT exclude `isCompactedHistory` rows.
                    // Back-to-back compacts (or compact after restoring a prior
                    // marker on session reload) leave the in-range rows already
                    // grayed; excluding them produced "0 messages compacted"
                    // even though `toCompact.size` was nonzero. The divider's
                    // count should reflect the size of THIS pass's range, not
                    // the delta of newly-grayed rows.
                    val cutoffIdx = cleaned.indexOfLast { it.id == cutoffId }
                    val compactedUICount = if (cutoffIdx < 0) {
                        cleaned.count { it.role != "system" }
                    } else {
                        cleaned.take(cutoffIdx + 1).count { it.role != "system" }
                    }
                    // [T-android-compact-divider-position] Insert the divider
                    // immediately AFTER the anchor row — not at the end of the
                    // list.
                    //
                    // This used to call appendSystemInfo(), which appends to
                    // `_messages`. For /compact that is invisible, because the
                    // anchor IS the last message and "after the anchor" and
                    // "end of list" are the same place. For long-press
                    // "Compact Above" on an EARLIER message they are not: the
                    // divider landed below every message that follows the
                    // anchor, so the user saw active, full-opacity turns
                    // sitting ABOVE a line claiming everything above it was
                    // compacted — the boundary appeared several rows too low.
                    //
                    // The graying loop above already stops at `cutoffId`, so
                    // the gray/active split was always correct; only the
                    // divider row was misplaced. Placing it at cutoffIdx + 1
                    // matches the reload path (rebuildWithCompactMarker's
                    // `insertIdx = lcmIdx + 1`) and iOS, so the position no
                    // longer changes when the session is reopened.
                    //
                    // cutoffIdx < 0 (anchor row not in the UI list) falls back
                    // to the end, which is the old behaviour and is also what
                    // the compact-everything path wants.
                    val dividerBlock = AssistantBlock(
                        id = "sysinfo_${System.currentTimeMillis()}",
                        kind = "info",
                        content = "$compactedUICount messages compacted",
                        toolName = "compact",
                        toolArgs = summary,
                    )
                    val dividerRow = ChatMessage(
                        id = "sysinfo_${System.currentTimeMillis()}",
                        role = "system",
                        content = "",
                        toolBlocks = listOf(dividerBlock),
                    )
                    val withDivider = cleaned.toMutableList()
                    val dividerAt = if (cutoffIdx < 0) withDivider.size else cutoffIdx + 1
                    withDivider.add(dividerAt.coerceIn(0, withDivider.size), dividerRow)
                    _messages.value = withDivider
                    AppLogger.info(
                        TAG,
                        "[Compact] divider: $compactedUICount UI bubbles compacted " +
                            "(history entries: ${toCompact.size}) inserted at row $dividerAt " +
                            "of ${withDivider.size} (cutoffIdx=$cutoffIdx)",
                    )
                }, restored = { previous ->
                    _cachedLatestMarker = previous
                    _compactSummary.value = previous?.summary
                }, notice = { event, calls ->
                    val text = when (event) {
                        com.openminis.app.agent.AgentCompactionRuntime.Notice.Empty -> "Compaction produced no output — try again later."
                        com.openminis.app.agent.AgentCompactionRuntime.Notice.TotalTimeout ->
                            "Compaction stopped after ${timeoutMs / 60_000L} minutes ($calls model call(s) attempted) — the model kept producing output without finishing. Try compacting again, or start a new session if it keeps happening."
                        com.openminis.app.agent.AgentCompactionRuntime.Notice.IdleTimeout ->
                            "Compaction stalled — no response from the model for ${COMPACT_IDLE_TIMEOUT_MS / 1000}s ($calls model call(s) attempted). Check your connection and try compacting again."
                        com.openminis.app.agent.AgentCompactionRuntime.Notice.Cancelled -> "Compaction cancelled."
                        is com.openminis.app.agent.AgentCompactionRuntime.Notice.Failed -> "Compaction failed: ${event.error.message ?: event.error.javaClass.simpleName}"
                    }
                    appendSystemInfo(text, "compact")
                }, settled = { outcome ->
                    _isCompacting.value = false
                    _compactProgress.value = null
                    AppLogger.info(TAG, "[Compact] finished: success=${outcome.succeeded} timedOut=${outcome.timedOut} calls=${outcome.calls}")
                    if (outcome.succeeded && outcome.visible) runCatching {
                        publishMeasuredContextUsage()
                        announceContextUsageAfterCompaction()
                        AppLogger.info(TAG, "[CtxMeter] compacted marker=${_cachedLatestMarker?.id?.take(8)} measured $compactMeasuredBefore→${measureOutboundContextTokens()}")
                    }
                }, successful = {
                    if (_promptQueue.value.isNotEmpty()) resumeQueueAfterCancel()
                }, finished = onFinished)

    }

    /**
     * Revert the most recent compact on this session.
     *
     * Drops the latest CompactMarker (its summary is discarded), refreshes
     * [_cachedLatestMarker] / [_compactSummary] to whatever's left (or
     * null), and rebuilds the message list so the UI reflects the new (or
     * absent) divider. Effect by design:
     *   - If a previous (older) marker exists, divider snaps back to that
     *     marker's anchor; effectiveAgentHistory replays that summary.
     *   - If no previous marker exists, divider disappears, full history
     *     flows to the model again.
     *
     * Mirrors iOS `revertCompact()`. Refuses to run mid-stream.
     */
    fun revertCompact() {
        if (_isStreaming.value || streamJob?.isActive == true || compactJob?.isCompleted == false) return
        val marker = _cachedLatestMarker ?: run {
            appendSystemInfo("Nothing to revert — no compact marker on this session.", "compact")
            return
        }
        val owner = activeSessionId
        com.openminis.app.agent.AgentCompactionRevert(chatRepository, runCoordinator, subagentJournal, { activeSessionId }).launch(
            viewModelScope, owner, marker,
            failed = { if (activeSessionId == owner) _error.value = it.message ?: "Compact revert failed" },
            publish = { next ->
                _cachedLatestMarker = next
                _compactSummary.value = next?.summary
                _messages.value = _messages.value.filterNot { msg ->
                    msg.role == "system" && msg.toolBlocks.firstOrNull()?.toolName == "compact"
                }
                pendingRevertLogMarker = marker.id.take(8)
            }, reload = {
                loadSession(kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.currentCoroutineContext()))?.join()
            })
    }

    /**
     * Produce the LLM-facing view of agentHistory. Mirrors iOS
     * `effectiveAgentHistory` (AIChatViewModel.swift:3843-3876):
     *
     *   1) No marker / no summary → full agentHistory (zero-copy).
     *   2) Marker has a `firstKeptMessageId` (compactBefore at boundary) →
     *      `[summary] + agentHistory[boundaryIdx ...]`. The boundary message
     *      itself is the first kept entry.
     *   3) compactAll marker (`firstKeptMessageId = null`) → only summary +
     *      messages persisted AFTER the marker, located by
     *      `lastCompactedMessageId`. Messages inserted post-compact (the
     *      user's follow-up turn + the assistant's response) survive; the
     *      summary stands in for everything older.
     *   4) Marker present but no boundary resolvable in current history (e.g.
     *      the boundary message was deleted) → fall through to full history,
     *      same safety net iOS uses.
     *
     * Critically, we do NOT include `agentHistory[< boundaryIdx]` for case
     * (2/3) — that's how the model context stays clean after compact.
     * Earlier behaviour was [summary] + entire agentHistory, which both
     * over-stuffed the context AND duplicated tool_use/tool_result pairs the
     * marker had already replaced; that's what made follow-up turns appear
     * to lose continuity (the model got confused by the dual representation).
     */
    /**
     * Apply the request-level image-byte budget to a fully-resolved
     * message list before handing it to a provider. Images that don't
     * fit under [ImageBudget.MAX_REQUEST_BYTES] (oldest first) are
     * replaced in-place with a text placeholder that, when the original
     * bytes were offloaded to disk, points the model back to the linux
     * path so it can re-fetch via `read` if needed. Images that
     * never had a linuxPath are spilled to
     * `attachments/spillover/<sha1>.<ext>` lazily so the placeholder
     * still carries an addressable reference.
     *
     * Returns the budgeted message list. When nothing was elided this
     * is the same instance as [messages].
     *
     * Emits a one-shot [requestBudgetEvent] for the UI Snackbar so the
     * user knows older images were compacted into placeholders.
     */
    /**
     * [T-android-payload-size-audit] (GH#352) Report parts whose serialized
     * size disagrees violently with what the token estimator thinks they cost.
     *
     * OBSERVE-ONLY on purpose. It returns [messages] untouched and never edits
     * history: the root cause of the field report is not confirmed to be in
     * this app (every image emit site uses a structured `image_url` /
     * `input_image` block — see OpenAIProvider), so silently rewriting a user's
     * conversation on a heuristic would risk breaking working sessions to fix a
     * problem we cannot yet point at. What the app demonstrably lacked was any
     * way to NOTICE the disagreement, and that is what this adds.
     *
     * The log line names the message, part kind, tool id, byte size, estimated
     * tokens and the ratio, so the next report can be diagnosed from a log
     * instead of needing a session export the reporter could not produce.
     *
     * When a part is big enough to exhaust the window on its own, the line is
     * escalated and points at the self-heal path, which is where an actual
     * mutation happens — and only after the provider has confirmed the overflow.
     */
    private fun auditOutgoingPayload(messages: List<LLMMessage>): List<LLMMessage> {
        val window = effectiveContextWindowTokens() ?: 0
        for ((msgIdx, msg) in messages.withIndex()) {
            for (part in msg.contentParts) {
                val (bytes, estTokens, kind, id) = when (part) {
                    is AgentContentPart.ImageData ->
                        // Base64 is what actually leaves the device, so measure
                        // that, not the raw byte count.
                        Quad(base64Size(part.data.size), BPETokenizer.countImageTokens(part.data), "image", null)
                    is AgentContentPart.ToolResult -> {
                        val imgBytes = part.imageData?.size ?: 0
                        Quad(
                            part.content.length + base64Size(imgBytes),
                            BPETokenizer.countTokens(part.content) +
                                (part.imageData?.let { BPETokenizer.countImageTokens(it) } ?: 0),
                            "tool_result",
                            part.id,
                        )
                    }
                    is AgentContentPart.Text ->
                        Quad(part.text.length, BPETokenizer.countTokens(part.text), "text", null)
                    is AgentContentPart.ToolUse -> {
                        val raw = part.input.toString()
                        Quad(raw.length, BPETokenizer.countTokens(raw), "tool_use", part.id)
                    }
                }
                val finding = PayloadSizeAudit.audit(bytes, estTokens, window)
                if (!finding.suspicious && !finding.dangerous) continue
                AppLogger.warning(
                    TAG,
                    "[PayloadAudit] GH#352 oversized part msgIdx=$msgIdx role=${msg.role} " +
                        "kind=$kind id=${id ?: "-"} bytes=${finding.bytes} " +
                        "estTokens=${finding.estimatedTokens} bytesPerToken=${finding.bytesPerToken} " +
                        "window=$window suspicious=${finding.suspicious} dangerous=${finding.dangerous}" +
                        if (finding.dangerous) {
                            " — this part alone could exhaust the window if measured as text; " +
                                "if the provider now 400s on context length the self-heal path " +
                                "([T-android-context-overflow-selfheal]) will offload it."
                        } else "",
                )
            }
        }
        return messages
    }

    /**
     * [T-android-context-overflow-selfheal] (GH#352) Offload the single largest
     * offloadable part in history, to unwedge a session the provider has just
     * refused for length. Returns whether anything was offloaded.
     *
     * Reuses the ordinary offload machinery rather than inventing a second one:
     * bytes go to `offloads/tools/`, the part is replaced by the same
     * `[CONTEXT OFFLOADED] …` stub the regular path writes, and the model can
     * fetch it back with `read`. Nothing is deleted.
     *
     * Scope is deliberately minimal — ONE part, the biggest:
     *
     *  - The failure this addresses is one part that dwarfs the rest (a 1 MB
     *    image measured as text is ~960k tokens against a 1M window), so
     *    removing the largest is usually sufficient and is the least the app
     *    can do while still unblocking the turn.
     *  - Stripping more, or looping until the request fits, would quietly
     *    dismantle a conversation on the strength of an error string. If one
     *    part is not enough the error surfaces, which is the honest outcome.
     *  - The last 4 messages are protected exactly as in the normal offload
     *    scan: the model needs the current turn verbatim to make sense of it.
     */
    /** Base64 expands 3 bytes to 4; the wire size is what the audit cares about. */
    private fun base64Size(rawBytes: Int): Int = if (rawBytes <= 0) 0 else (rawBytes + 2) / 3 * 4

    /** Local 4-tuple so the audit's per-part extraction stays one expression. */
    private data class Quad(val a: Int, val b: Int, val c: String, val d: String?)

    private fun applyRequestImageBudget(messages: List<LLMMessage>): List<LLMMessage> {
        // Collect every image in chronological order so the planner can
        // walk in reverse and protect the most recent images.
        data class ImageRef(val msgIdx: Int, val partIdx: Int, val image: ImageBudget.BudgetImage)
        val images = mutableListOf<ImageRef>()
        messages.forEachIndexed { mi, msg ->
            msg.contentParts.forEachIndexed { pi, part ->
                when (part) {
                    is AgentContentPart.ImageData -> {
                        images.add(
                            ImageRef(
                                mi, pi,
                                ImageBudget.BudgetImage(part.data, part.linuxPath, part.mimeType),
                            )
                        )
                    }
                    is AgentContentPart.ToolResult -> {
                        val img = part.imageData
                        if (img != null) {
                            images.add(
                                ImageRef(
                                    mi, pi,
                                    ImageBudget.BudgetImage(
                                        img,
                                        part.imageLinuxPath,
                                        part.imageMimeType ?: "image/jpeg",
                                    ),
                                )
                            )
                        }
                    }
                    else -> Unit
                }
            }
        }
        if (images.isEmpty()) return messages

        val plan = ImageBudget.planRequestBudget(images.map { it.image })
        if (!plan.mutated) return messages

        // For dropped images without a linuxPath, lazily spill to disk so
        // the placeholder still gives the model an addressable reference.
        val attachmentsRoot = activeSessionId?.let { sid ->
            java.io.File(context.filesDir, "minis-sessions/$sid/attachments")
        }
        val resolvedPaths = HashMap<ImageBudget.ImagePartId, String?>()
        for (ref in images) {
            val id = ImageBudget.ImagePartId.of(ref.image.data)
            if (id !in plan.droppedIds) continue
            val existing = ref.image.linuxPath
            if (existing != null) {
                resolvedPaths[id] = existing
            } else if (attachmentsRoot != null) {
                resolvedPaths[id] = ImageBudget.ensureSpillover(
                    attachmentsRoot, ref.image.data, ref.image.mimeType,
                )
            } else {
                resolvedPaths[id] = null
            }
        }

        // Build a new message list with dropped image parts replaced by
        // text placeholders. Same-message multiple drops collapse cleanly
        // because we never touch parts whose ids weren't in droppedIds.
        val byMsg = images.groupBy { it.msgIdx }
        val mutated = messages.toMutableList()
        for ((mi, refs) in byMsg) {
            val msg = mutated[mi]
            val newParts = msg.contentParts.toMutableList()
            for (ref in refs) {
                val id = ImageBudget.ImagePartId.of(ref.image.data)
                if (id !in plan.droppedIds) continue
                val path = resolvedPaths[id]
                val placeholder = AgentContentPart.Text(ImageBudget.elidedImagePlaceholder(path))
                val originalPart = newParts[ref.partIdx]
                newParts[ref.partIdx] = when (originalPart) {
                    is AgentContentPart.ImageData -> placeholder
                    is AgentContentPart.ToolResult -> originalPart.copy(
                        // Strip the bytes but keep the structural ToolResult
                        // role; append the elision marker into content so
                        // the model sees it next to the rest of the tool
                        // output. linux path remains in the part for any
                        // subsequent diagnostic round-trip.
                        imageData = null,
                        imageMimeType = null,
                        content = originalPart.content +
                            (if (originalPart.content.isEmpty()) "" else "\n") +
                            ImageBudget.elidedImagePlaceholder(path),
                    )
                    else -> originalPart
                }
            }
            mutated[mi] = msg.copy(contentParts = newParts)
        }

        _requestBudgetEvent.tryEmit(plan)
        AppLogger.info(
            TAG,
            "applyRequestImageBudget: dropped=${plan.droppedCount}/${plan.totalCount} keptBytes=${plan.keptBytes}B elidedBytes=${plan.elidedBytes}B",
        )
        return mutated
    }

    /**
     * [T-android-compact-orphan-toolcall] The outgoing history, with tool
     * call/result pairing repaired. Every request goes through here — see
     * [com.openminis.app.agent.ToolHistorySanitizer] for why the sweep exists and what it can and
     * cannot fix.
     */
    private fun effectiveAgentHistory(): List<LLMMessage> =
        com.openminis.app.agent.ToolHistorySanitizer.repair(effectiveAgentHistoryUncounted(), activeSessionId)

    private val personaReminder = com.openminis.app.agent.AgentPersonaReminder()

    private fun effectiveAgentHistoryUncounted(): List<LLMMessage> =
        historyProjection.project(agentHistory, _compactSummary.value, _cachedLatestMarker,
            COMPACT_KEEP_RECENT_USER_TURNS) { warm, tail, summary ->
            val trim = trimWarmUpToFit(warm, tail, summary)
            com.openminis.app.agent.HistoryProjection.Trim(trim.kept, trim.decided)
        }

    /** Latest in-memory compact marker, used by [effectiveAgentHistory] to
     * resolve boundaries the same way iOS `cachedLatestMarker` does. Refreshed
     * on every compactAll write and on session reload. */
    @Volatile
    private var _cachedLatestMarker: com.openminis.app.data.db.CompactMarkerEntity? = null

    /**
     * Walk back from `anchorIdx` toward 0, deciding ONLY at user-message
     * boundaries whether to include the next round. Stops when:
     * - we've collected `maxUserTextTurns` user-text turns (success), OR
     * - including the next user round would push total messages over
     *   `maxMessages` (cap reason — don't split a user/assistant/tool round
     *   in the middle, otherwise a tool_use would be orphaned without its
     *   tool_result), OR
     * - we hit index 0 (start of history).
     *
     * Port of iOS `walkBackUserTurnsBounded` (AIChatViewModel.swift, 8b76cd74).
     */
    private fun walkBackUserTurnsBounded(
        anchorIdx: Int,
        maxUserTextTurns: Int,
        maxMessages: Int,
    ): com.openminis.app.agent.HistoryProjection.WalkBack =
        historyProjection.walkBack(agentHistory, anchorIdx, maxUserTextTurns, maxMessages)

    /** Snapshot request inputs; runtime owns summary requests, splitting and task settlement. */
    private suspend fun prepareCompactionInput(
        ownerSessionId: String,
    ): com.openminis.app.agent.AgentCompactionRuntime.Input {
        val (provider, journal, attribution) = withContext(Dispatchers.Main) {
            if (activeSessionId != ownerSessionId) throw CancellationException("compaction branch changed")
            val selected = currentProvider ?: throw IllegalStateException("No LLM provider available for compaction")
            Triple(selected, com.openminis.app.agent.AgentJournalWriter(chatRepository, ownerSessionId),
                modelSnapshotFor(selected.model, _activeEntryId.value))
        }
        val history = if (compactionSummarizer.canReuse(provider))
            applyRequestImageBudget(effectiveAgentHistory()) else emptyList()
        return com.openminis.app.agent.AgentCompactionRuntime.Input(
            CompactionSummarizer.Context(provider, history, compactSummarySystemPrompt, lastDispatchRatio,
                attribution, journal::compactionUsage),
            provider.model.contextWindow ?: 128_000)
    }

    /**
     * Consult [ContextPolicy] before sending. Returns true to proceed. The
     * Android MVP doesn't surface a "Compact before send" dialog (iOS does),
     * so we only warn via [appendSystemInfo] at the `needsCompact` /
     * `exhausted` boundaries and still allow the send. That gives the user
     * a signal to invoke `/compact` explicitly without blocking their turn.
     */
    private fun checkContextBeforeSend(): PreSendContextAction {
        // [T-ctx-measure-outbound] Judge the request about to go out, not the
        // provider's count for the previous one — which after a /compact or a
        // revert described a context that no longer exists.
        ensureContextFixedTokens()
        val m = contextMeasurement()
        val tokens = m.measured
        if (tokens <= 0) return PreSendContextAction.PROCEED
        // [T-context-window-live-read] Live window (entry re-resolved + group
        // contextLimitTokens folded in) — not the currentModel snapshot.
        // [T-ctx-user-cap] Honour a user-chosen group cap with proportional
        // thresholds; a native window keeps the legacy tier table.
        val (policy, window) = currentContextPolicy() ?: return PreSendContextAction.PROCEED
        val verdict = policy.check(tokens, window)
        logContextDecision("pre-send", m, policy.compactThreshold, window, verdict.name)
        return when (verdict) {
            ContextPolicy.CheckResult.OK -> PreSendContextAction.PROCEED

            // Mirrors iOS AIChatViewModel.swift:2224. Previously Android only
            // appended a notice here and sent anyway, which meant the very
            // request that tripped the threshold still went out over-length —
            // the warning arrived alongside the failure it was meant to avoid.
            ContextPolicy.CheckResult.NEEDS_COMPACT -> {
                if (com.openminis.app.data.AutoCompactPrefs.isEnabled()) {
                    AppLogger.info(
                        TAG,
                        "[Context] pre-send near capacity ($tokens / $window) — auto-compacting (pref on)",
                    )
                    PreSendContextAction.COMPACT_THEN_SEND
                } else {
                    AppLogger.info(
                        TAG,
                        "[Context] pre-send near capacity ($tokens / $window) — prompting user",
                    )
                    PreSendContextAction.ASK_USER
                }
            }

            // Exhausted tiers have compactThreshold = 0 by policy: the window is
            // too small for a summary to pay for itself, so compacting is not
            // on offer. Keep the existing advisory-and-proceed behaviour rather
            // than blocking the user out of their own chat.
            ContextPolicy.CheckResult.EXHAUSTED -> {
                appendSystemInfo(
                    text = "Context is near the model's limit ($tokens / $window tokens). Start a new chat or /compact to continue reliably.",
                    iconKind = "compact",
                )
                PreSendContextAction.PROCEED
            }
        }
    }

    /** What the pre-send context check decided. Mirrors iOS's send() branch. */
    private enum class PreSendContextAction {
        /** Under threshold (or nothing useful to do) — send as normal. */
        PROCEED,

        /** Auto-compact is on — compact silently, then send. */
        COMPACT_THEN_SEND,

        /** Auto-compact is off — raise the dialog and let the user choose. */
        ASK_USER,
    }

    /**
     * Text + attachments held back while the "Context Near Capacity" dialog is
     * up. Mirrors iOS `pendingSendText` / `pendingSendAttachments`.
     */
    private var pendingSendText: String? = null

    /**
     * [T-scheduled-tool-prefill] Prefilled tool calls travelling with
     * [pendingSendText] while a pre-send compaction runs, so the prompt that
     * finally goes out still runs them. Set and cleared together with the text.
     */
    private var pendingSendPrefill: List<com.openminis.app.scheduled.PrefilledToolCall> = emptyList()

    /**
     * [T-android-programmatic-prompt-keeps-draft] Whether [pendingSendText]
     * came from a headless prompt rather than the composer. It keeps the send
     * after compaction headless, and stops [cancelCompactBeforeSend] from ever
     * dropping a background prompt's text into the user's input box.
     */
    private var pendingSendHeadless: Boolean = false

    private val _showCompactBeforeSendPrompt = MutableStateFlow(false)
    val showCompactBeforeSendPrompt: StateFlow<Boolean> = _showCompactBeforeSendPrompt.asStateFlow()

    /**
     * Dialog action: compact the history, then send what the user was holding.
     * [alsoEnableAutoCompact] backs iOS's one-tap opt-in button, which compacts
     * now AND remembers the choice for every future conversation.
     */
    fun compactAndSendPending(alsoEnableAutoCompact: Boolean = false) {
        if (alsoEnableAutoCompact) setAutoCompactEnabled(true)
        _showCompactBeforeSendPrompt.value = false
        val text = pendingSendText ?: return
        pendingSendText = null
        val prefill = pendingSendPrefill
        pendingSendPrefill = emptyList()
        val headless = pendingSendHeadless
        pendingSendHeadless = false
        viewModelScope.launch {
            val ok = awaitCompaction()
            if (!ok) {
                AppLogger.warning(TAG, "[Context] pre-send compaction failed — sending anyway")
            }
            sendMessage(text, skipContextCheck = true, headless = headless, prefill = prefill)
        }
    }

    /** Dialog action: send without compacting. */
    fun sendPendingWithoutCompacting() {
        _showCompactBeforeSendPrompt.value = false
        val text = pendingSendText ?: return
        pendingSendText = null
        val prefill = pendingSendPrefill
        pendingSendPrefill = emptyList()
        val headless = pendingSendHeadless
        pendingSendHeadless = false
        sendMessage(text, skipContextCheck = true, headless = headless, prefill = prefill)
    }

    /** Dialog dismissed — restore the text to the composer so it isn't lost. */
    fun cancelCompactBeforeSend() {
        _showCompactBeforeSendPrompt.value = false
        // Only the user's own text goes back into the composer.
        if (!pendingSendHeadless) pendingSendText?.let { _inputText.value = it }
        pendingSendText = null
        pendingSendPrefill = emptyList()
        pendingSendHeadless = false
    }

    /**
     * [T-android-auto-compact-inloop] Run [compactAll] with the in-loop flag and
     * suspend until it settles. Returns whether it actually compacted.
     *
     * `compactAll` is fire-and-forget (it launches its own IO coroutine), so the
     * loop cannot simply call it and continue — the next API call would read the
     * pre-compaction history and the guard would fire again immediately.
     */
    private suspend fun awaitCompaction(): Boolean =
        com.openminis.app.agent.AgentCompactionAwait.run { finished ->
            compactAll(allowDuringProcessing = true, onFinished = finished)
        }

    /**
     * System prompt for the single-shot summarisation call. Matches iOS
     * wording so cross-device summaries stay stylistically aligned.
     */
    private val compactSummarySystemPrompt: String = """
        You are a context compaction engine. Your summary will REPLACE the original messages in the conversation context window. The agent will read your summary as past context, then proceed based on the user's NEXT message — your summary is background, not a standing work order. Write the summary in the same language the user used in the conversation.

        MUST PRESERVE (never omit or shorten):
        - All file paths, directory names, URLs, UUIDs, and identifiers — copy verbatim
        - Commands executed and their outcomes (success/failure/output)
        - What was requested and what was done (record as past events, not as ongoing goals)
        - Key decisions made and their rationale
        - Errors encountered and how they were resolved
        - Important constraints, rules, or user preferences mentioned
        - Any tool calls and their results that affect current state

        STRUCTURE:
        1. Start with a one-line description of what the conversation was about (use past tense — "User asked X, agent did Y", NOT "Goal: X").
        2. Then a concise narrative of what happened, preserving technical details.
        3. End with a "What had been done so far" section listing completed work — NOT a "todo" or "pending" list. Do not invent ongoing objectives or carry-over tasks from old turns; if the user wants to continue, they will say so in their next message.

        PRIORITIZE recent context over older history — recent decisions and recent file/path references are most useful for continuity.

        Do NOT translate or alter code snippets, file paths, identifiers, or error messages. Be concise but never lose information the agent needs.
    """.trimIndent()

    // T203 part 2: these MUST be declared before `init { loadSession() }` below.
    // viewModelScope.launch defaults to Dispatchers.Main.immediate, which runs
    // the launch body synchronously up to the first suspend point — and the
    // launch body reads `isDraft` before its first suspend. If `isDraft` is
    // declared further down the class, its property initializer hasn't run yet,
    // so the read returns the JVM default (`false`), routing every draft
    // session through the load-from-DB branch. The DB lookup misses (no row
    // for `__new__…` keys), the function returns early, and no model name /
    // group name is ever set on the draft chat — exactly the bug T203 was
    // chasing through the wrong layer.
    /** Whether this is a draft session (not yet persisted to DB). */
    private val isDraft: Boolean = sessionId.startsWith("__new__")

    /** Session-group (folder) id from the folder card's "New Chat in Group"
     *  menu item, encoded in the draft id. Filed at draft promotion — the
     *  folder_id row can only exist once the session does (iOS defers the
     *  same way via pendingFolderDraft). */
    private val initialFolderId: String? =
        sessionId.substringAfter("__fld__", "").substringBefore("__grp__")
            .takeIf { it.isNotEmpty() }

    /** The real session ID (same as sessionId for existing sessions, generated on first message for drafts). */
    internal var realSessionId: String = if (isDraft) "" else sessionId

    /**
     * [T-android-compact-fallback] Entries that failed in this session recently.
     *
     * `resolveProviderFromGroup` picks `available.first()`, with no memory of
     * what just refused. So after model A in a group ran out of quota,
     * re-selecting that same group handed A straight back — the user taps the
     * picker, sees no change, and reasonably concludes switching does nothing.
     * Recording the failure lets the pick skip A while a healthy member exists,
     * and fall back to today's behaviour when every member has failed.
     *
     * [T-android-recentlyfailed-init-order] Declared HERE, above `init`, and
     * not beside the fallback code it belongs to — same rule as `isDraft`
     * above, for the same reason. `init { loadSession() }` launches on
     * `Dispatchers.Main.immediate`, which runs synchronously up to the first
     * suspend point, and that stretch reaches `resolveProviderFromGroup`. A
     * declaration further down the class has not been initialised by then, so
     * the read returns the JVM default `null` and `it.id !in
     * recentlyFailedEntryIds` throws NullPointerException inside the
     * constructor — the ViewModel never finishes building and the app crashes
     * on launch, every launch, for any user whose session opens on a model
     * group (crash reports 2026-09-13, HUAWEI MNA-AL00 / Android 12, 1.14(26)).
     */
    private val recentlyFailedEntryIds = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    init {
        loadSession()
        // [T-android-context-usage-hint] Loop-END path for the usage line.
        //
        // Driven off the isStreaming EDGE rather than hooked into each
        // `_isStreaming.value = false` site: there are ~10 of those (send,
        // retryLast, rerun, resume, cancel, several error unwinds), and a
        // per-site call would silently miss whichever one a future change
        // adds. One collector on the transition covers all of them by
        // construction.
        //
        // A user-cancelled turn is excluded: the figure would describe a
        // request the user deliberately abandoned, shown exactly as they
        // reach for the composer to redirect.
        viewModelScope.launch {
            var wasStreaming = false
            isStreaming.collect { streaming ->
                val justFinished = wasStreaming && !streaming
                wasStreaming = streaming
                if (!justFinished) return@collect
                if (lastTurnWasCancelled) {
                    lastTurnWasCancelled = false
                    return@collect
                }
                publishContextUsage(crossingOnly = false)
            }
        }
        // [T-session-paused-badge-active-false-positive] Drive the session-list
        // PAUSED badge directly off canResume — the authoritative "this session
        // is interrupted (tap Resume)" flag. This is the single chokepoint over
        // every _canResume setter (background-suspend cleanup, cancel cleanup,
        // loadSession DB detection, …): canResume true → badge on; false
        // (resumed / new send / completed) → badge off. Replaces both the old
        // foreground heuristic AND clear-on-open, so a session the user merely
        // glanced at but didn't resume keeps its badge, and a running/resolved
        // session never shows one.
        viewModelScope.launch {
            canResume.collect { interrupted ->
                if (interrupted) {
                    // [T-android-group-pause-badge-restamp] Only a REAL
                    // interruption re-stamps the badge's entry time. This
                    // collector is the single chokepoint over every
                    // `_canResume` setter, so it ALSO fires when loadSession
                    // merely RE-DETECTS an old interrupted tail — that is not
                    // a new entry into the paused state, and re-stamping it
                    // there is what let a days-old pause keep looking "fresh"
                    // to the group card's 24h window forever (the more often
                    // the user opened the chat, the less able it was to
                    // expire). The detecting site raises a sticky generation
                    // mark before its assignment; we consume it here, once the
                    // annotated emission has actually been observed.
                    val pendingGen = redetectingInterruptedTailGen
                    val isRedetection = pendingGen != consumedRedetectGen
                    if (isRedetection) consumedRedetectGen = pendingGen
                    com.openminis.app.service.SessionBadgeStore.push(
                        sessionId,
                        com.openminis.app.service.SessionBadgeStore.SessionBadgeState.PAUSED,
                        restamp = !isRedetection,
                    )
                } else {
                    com.openminis.app.service.SessionBadgeStore.remove(
                        sessionId,
                        com.openminis.app.service.SessionBadgeStore.SessionBadgeState.PAUSED,
                    )
                }
            }
        }
        // T-android-crash-safe-mode-v2: when the user dismisses the
        // safe-mode dialog, retry the restore that we skipped during
        // cold start. loadSession() is idempotent (re-checks isSafeMode
        // on entry; sessionLoaded gate prevents double-population), so
        // this is a clean "now finish the work you skipped" hook.
        com.openminis.app.crash.CrashFrequencyDetector
            .registerSafeModeClearedListener {
                viewModelScope.launch(kotlinx.coroutines.Dispatchers.Main) {
                    runCatching { loadSession() }
                        .onFailure {
                            android.util.Log.w(
                                TAG,
                                "safe-mode-cleared retry loadSession failed: ${it.message}",
                            )
                        }
                }
            }
        // Re-resolve provider when config changes (models may load async)
        viewModelScope.launch {
            // T306: wait for loadSession to finish BEFORE observing config.
            //
            // Pre-T306 we used a "skip first replay" trick that broke under
            // a real race: loadSession suspends inside `chatRepository.getSession`,
            // so when ProviderRepository finishes its async config load and
            // emits the populated value, the collector can fire BEFORE
            // loadSession's `restoreFromBinding(session.modelBinding)` runs.
            // The collector then resolves to the default group's first
            // entry (X), `_modelName` flips to X, and seconds later
            // restoreFromBinding finds Y and re-sets `_modelName` to Y —
            // exactly the "top model picker first shows X, then flickers and switches to Y"
            // the user reported after a fallback persisted Y.
            //
            // Awaiting `sessionLoaded == true` here means loadSession has
            // already had its turn at the persisted binding (success or
            // failure). After that, the `currentProvider == null` guard
            // below correctly captures BOTH the draft case (no binding,
            // currentProvider may still be null because config hadn't
            // loaded yet during loadSession) AND the existing-session
            // case where binding restore failed, while leaving alone any
            // session whose binding successfully resolved to its target.
            sessionLoaded.first { it }
            providerRepository.config.collect { config ->
                if (currentProvider == null && config.modelEntries.isNotEmpty()) {
                    if (isDraft) applyNewChatDefaultModel()
                    else {
                        val session = chatRepository.getSession(realSessionId.ifEmpty { sessionId })
                        if (!restoreFromBinding(session?.modelBinding)) applyNewChatDefaultModel()
                    }
                }
            }
        }
    }

    /**
     * Session ID that disk/shell-bound resources must use. Until the user sends
     * the first message, `realSessionId` is empty and we fall back to the draft
     * key. After `ensureSession()` runs, this returns the persisted id so
     * `/var/minis/{attachments,workspace,...}` mounts, browser artifacts, and
     * the PersistentShell all land in a single directory that survives re-entry.
     */
    internal val activeSessionId: String
        get() = realSessionId.ifEmpty { sessionId }

    /** Public accessor used by ChatScreen to resolve session-scoped minis:// links. */
    val currentSessionId: String
        get() = activeSessionId

    /** T-chat-title-pill-edit: load the persisted [ChatSessionEntity] for the
     *  current session so the shared edit-title sheet (reused from the session
     *  list) can be opened from the in-chat title pill. Returns null for
     *  drafts that haven't been persisted yet. */
    suspend fun loadSessionEntity(): com.openminis.app.data.db.ChatSessionEntity? {
        val sid = realSessionId.ifEmpty { return null }
        return runCatching { chatRepository.getSession(sid) }.getOrNull()
    }

    /** T-chat-title-pill-edit: update title + category from the in-chat
     *  edit sheet. Mirrors SessionListViewModel.updateTitleAndCategory but
     *  also refreshes the local StateFlows so the pill updates immediately
     *  without waiting for a session reload. */
    fun updateTitleAndCategory(title: String, category: String?) {
        val sid = realSessionId.ifEmpty { return }
        viewModelScope.launch {
            chatRepository.updateSessionTitleAndCategory(sid, title, category)
            _sessionTitle.value = title.ifBlank { UNTITLED_SESSION_TITLE }
            _sessionCategory.value = category
        }
    }

    /** Ensure the session exists in the database. Called before first message. */
    private suspend fun ensureSession(): String = withContext(NonCancellable + Dispatchers.Main) {
        if (realSessionId.isNotEmpty()) return@withContext realSessionId
        val modelId = currentModel?.id ?: providerRepository.allVisibleEntries().firstOrNull()?.model?.id ?: "unknown"
        val session = chatRepository.createSession(
            modelId = modelId,
        )
        realSessionId = session.id
        // [T-android-opencode-session-header] Hand the just-minted real id to
        // the provider built while this chat was still a draft. Without it the
        // FIRST turn of every new conversation would reach OpenCode Go with no
        // `x-opencode-session` (the draft key is a `__new__…` placeholder the
        // header gate rejects) and later turns with one — the exact cross-turn
        // instability the header exists to prevent. Same re-point-onto-the-real-
        // id idea as the resource hops below, and a no-op for every provider
        // that is not OpenCode-bound.
        (currentProvider as? com.openminis.app.provider.openai.OpenAIProvider)
            ?.sessionId = session.id
        // "New Chat in Group": file the just-promoted draft into its folder.
        // Unconditional (vs iOS setFolderIfUnfiled) — the session is seconds
        // old and nothing else can have filed it yet.
        initialFolderId?.let { chatRepository.setFolderForSessions(it, listOf(session.id)) }
        // Move our cached VM from the draft key ("__new__...") to the real
        // sessionId so re-entering the session reuses the same instance.
        if (isDraft) {
            ChatViewModelStore.rename(sessionId, session.id)
            // Bring every disk/shell resource that was opened with the draft
            // id over to the real id *before* agent tools start running against
            // the persisted session — otherwise the first tool call (e.g.
            // yt-dlp writing into /var/minis/attachments) would land in
            // minis-sessions/__new__*/… and be orphaned when the user
            // re-enters the session and everything is resolved via the real
            // id. See debug report 2026-04-21 (TikTok Chinese filename).
            migrateDraftResources(fromDraft = sessionId, toReal = session.id)
            // [T-android-session-skill-override-init-timing] Re-point any
            // session_skill_overrides / mcp_session_overrides rows written
            // pre-first-message (against `__new__<uuid>`) onto the real
            // session id, mirroring the disk-resource hop above. Without
            // this, a skill or MCP server the user toggled on the draft
            // session sheet vanishes the next time the same chat is opened
            // (the prop carries the real id by then, but the override row
            // is still stranded under the draft key). Aligns with iOS
            // ed861471 (T-ios-session-skill-override-init-timing). Cheap
            // no-op when no rows match.
            skillRepository?.renameSessionOverrides(fromDraft = sessionId, toReal = session.id)
            mcpRepository?.renameSessionOverrides(fromDraft = sessionId, toReal = session.id)
            // Re-point the lazily-created BrowserTabPool if it was already
            // instantiated against the draft key (e.g. user opened the browser
            // sheet before sending a message). Without this, cookies and
            // downloads keep flowing into the draft directory.
            if (helperConfig == null) _browserTabPoolRef?.setSession(session.id)
        }
        // Persist the current model binding so it survives re-entry
        val entryId = _activeEntryId.value
        val binding = when {
            entryId != null -> """{"type":"entry","entryId":"$entryId"}"""
            else -> null
        }
        if (binding != null) {
            val bindingWithLimit = org.json.JSONObject(binding).apply {
                sessionContextLimitTokens?.let { put("contextLimitTokens", it) }
            }.toString()
            chatRepository.updateSessionBinding(realSessionId, bindingWithLimit, modelId)
        }
        // [T-android-draft-no-row-on-open] Flush the thinking level the draft
        // was carrying in memory. `applyGroupSessionDefaults` (group default)
        // and the user's own pre-send pick both land in `_thinkingLevel` and
        // deliberately skip the DB while there is no row; this is the single
        // point where that becomes persistent, mirroring iOS's
        // `flushPendingThinkingLevel()` call inside `ensureSessionReturningId`.
        // Without it, a level chosen before the first message would be lost on
        // re-entry — the regression that would otherwise come with not writing
        // eagerly above.
        chatRepository.dao.updateThinkingOverride(realSessionId, _thinkingLevel.value.name)
        realSessionId
    }

    /**
     * Move every per-session disk resource from the draft directory to the
     * real one, and tear down any shell that was started against the draft id.
     *
     * The draft key leaks into persistent shells (`ExecutionCoordinator`),
     * browser artifacts (`AgentBrowserExecutor`), and the `BrowserTabPool`'s
     * cookie/state store. Before this migration ran, a tool invocation that
     * happened before the user's first message would write into the draft's
     * `minis-sessions/__new__{uuid}` directory and become invisible the
     * moment the VM was recreated under the real id — exactly the symptom
     * observed with the Chinese-named TikTok download that appeared to
     * "disappear" after `yt-dlp` reported success.
     */
    private fun migrateDraftResources(fromDraft: String, toReal: String) {
        // Stop any shell that was already spun up against the draft id; its
        // -b mount arguments were frozen to the draft directory at launch, so
        // we can't reuse it after the migration.
        runCatching { ExecutionCoordinator.sessionDidTerminate(fromDraft) }

        val base = java.io.File(context.filesDir, "minis-sessions")
        val draftBase = java.io.File(base, fromDraft)
        if (!draftBase.isDirectory) return
        val realBase = java.io.File(base, toReal).apply { mkdirs() }

        listOf("attachments", "offloads", "workspace", "browser").forEach { subdir ->
            val src = java.io.File(draftBase, subdir)
            if (!src.isDirectory) return@forEach
            val dst = java.io.File(realBase, subdir).apply { mkdirs() }
            src.listFiles()?.forEach { child ->
                val target = java.io.File(dst, child.name)
                runCatching {
                    if (!target.exists() && !child.renameTo(target)) {
                        copyRecursive(child, target)
                    }
                }.onFailure {
                    android.util.Log.w("ChatViewModel",
                        "migrateDraftResources: failed to move ${child.absolutePath} -> ${target.absolutePath}: ${it.message}")
                }
            }
        }
        runCatching { draftBase.deleteRecursively() }

        // Also rename the BrowserTabPool saved-state file (filesDir/browser_tabs/<sid>.json).
        // Otherwise the pool will load empty state on the next re-entry and the
        // user loses their open tabs even though the URLs never truly "went away".
        val tabsDir = java.io.File(context.filesDir, "browser_tabs")
        val draftTabs = java.io.File(tabsDir, "$fromDraft.json")
        if (draftTabs.exists()) {
            val realTabs = java.io.File(tabsDir, "$toReal.json")
            runCatching {
                if (!realTabs.exists()) {
                    if (!draftTabs.renameTo(realTabs)) {
                        draftTabs.copyTo(realTabs, overwrite = false)
                        draftTabs.delete()
                    }
                }
            }
        }
    }

    private fun copyRecursive(src: java.io.File, dst: java.io.File): Boolean = runCatching {
        if (src.isDirectory) {
            dst.mkdirs()
            src.listFiles()?.all { copyRecursive(it, java.io.File(dst, it.name)) } ?: true
        } else {
            src.copyTo(dst, overwrite = false)
            src.delete()
            true
        }
    }.getOrDefault(false)

    private fun loadSession(scope: kotlinx.coroutines.CoroutineScope = viewModelScope): Job? {
        if (scope === viewModelScope && sessionLoaded.value && streamJob?.isActive == true) return null
        // T-android-crash-detected-halt: when CrashFrequencyDetector
        // tripped (#459, ≥3 crashes in last hour), skip the heavy
        // session-restore path entirely. Re-running the same persisted
        // state is exactly what produced the burst, so we'd just feed
        // a re-crash loop while the user is staring at the share dialog.
        // The flag clears the moment the dialog closes (share / dismiss /
        // cancel) — see CrashFrequencyDetector.maybeShowOnActivity.
        if (com.openminis.app.crash.CrashFrequencyDetector.isSafeMode()) {
            android.util.Log.w(TAG, "loadSession: safe-mode active, skipping session restore")
            // [T-android-perf-logging] Surface the skip on the Perf timeline
            // too — when a crash_or_stall recovery loop is suspected, this
            // distinguishes "loadSession ran and was slow" from "loadSession
            // was skipped (safe-mode), so the stall is elsewhere".
            com.openminis.app.diagnostics.PerfLongCtx.step(
                sessionId,
                "loadSession.skipped",
                "reason=safeMode",
            )
            return null
        }
        val previous = historyLoadJob
        val loading = scope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            withContext(NonCancellable) { previous?.join() }
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            // [T-HANG-DIAG] timing markers to localise where session entry
            // stalls. Sentinel-tagged so a single grep -v can strip them
            // when this diagnostic is removed. Declared OUTSIDE the try
            // block so the EXIT log in `finally` can still read it after
            // an early-return / exception path.
            val tHangDiagStart = System.currentTimeMillis()
            println("[T-HANG-DIAG] loadSession ENTER session=$sessionId isDraft=$isDraft")
            com.openminis.app.diagnostics.PerfLongCtx.step(sessionId, "loadSession.enter", "isDraft=$isDraft")
            try {
            val config = providerRepository.config.value

            if (isDraft) {
                // Draft session: just set up provider using default group or first entry
                _sessionTitle.value = UNTITLED_SESSION_TITLE
                _sessionCategory.value = null
                sessionContextLimitTokens = config.defaultContextLimitTokens
                // Explicit defaults win; otherwise reuse the user's last choice.
                // Only new chats inherit this — existing sessions keep their override.
                _thinkingLevel.value = config.defaultThinkingLevel
                    ?: providerRepository.lastUsedThinkingLevel
                    ?: ThinkingLevel.OFF
                applyNewChatDefaultModel()
                return@launch
            }

            // Existing session: load from DB
            val session = chatRepository.getSession(sessionId) ?: return@launch
            _sessionTitle.value = session.title ?: UNTITLED_SESSION_TITLE
            _sessionCategory.value = session.category
            // T239: hydrate persisted thinking-mode override. null = unset
            // (use OFF as the legacy default); non-null = explicit user
            // choice persisted across cold-start. runCatching guards against
            // a stale enum name from a future rename — fall back silently
            // rather than crashing the session load.
            // [T-android-restore-thinking-level] A session restored from an iOS
            // backup carries the iOS raw value ("high"), which valueOf rejected
            // — the session silently came back with thinking off.
            val persistedThinking = session.thinkingOverride
                ?.let { ThinkingLevel.parseOrNull(it) }
            _thinkingLevel.value = persistedThinking ?: ThinkingLevel.OFF

            // Priority 1: restore from persisted model_binding (group or entry)
            var resolved = restoreFromBinding(session.modelBinding)

            // [T-android-subagent-inherit-group-thinking] A group's
            // `defaultThinkingLevel` only ever reached a session through
            // `selectGroup` / `selectGroupEntry` (user taps) or the DRAFT
            // branch above. A sub-agent child session is neither: it is
            // created as a real DB row with its binding written directly
            // (executeDelegateTask -> updateSessionBinding), so it took the
            // "existing session" path, found `thinking_override` NULL — nothing
            // had ever written one — and fell back to OFF. The child then ran
            // every turn with no reasoning effort even though the group it was
            // bound to specified one, which is the reported "子代理不携带分组
            // 指定的思考强度 / API 后台请求没有思考强度".
            //
            // Gated on the override being ABSENT, so this is a default, not an
            // override: a session where the user explicitly chose a level
            // (including deliberately choosing OFF, which persists as "OFF")
            // keeps that choice. Applies to any session restoring onto a group
            // without one, so a normal chat created by a non-UI path gets the
            // same inheritance rather than this being a delegate-only special
            // case.
            if (persistedThinking == null) {
                providerRepository.config.value.defaultThinkingLevel?.let { _thinkingLevel.value = it }
            }

            // Priority 2: fall back to stored model_id
            if (!resolved) {
                val entry = findModelEntry(session.modelId)
                if (entry != null) {
                    currentModel = entry.model
                    _modelName.value = entry.model.displayName
                    _activeEntryId.value = entry.id
                    val instance = providerRepository.instance(entry.providerInstanceId)
                    if (instance != null) {
                        // [T-android-group-resolve-skip-uncredentialed] Gate on
                        // hasAnyCredential — keying off the API key alone left a
                        // session whose model lives on an OAuth provider unable
                        // to restore, despite being signed in.
                        val apiKey = providerRepository.usableApiKey(instance) ?: ""
                        if (providerRepository.hasAnyCredential(instance)) {
                            currentProvider = ProviderFactory.create(instance, apiKey, entry.model, context, sessionId = activeSessionId, overrides = entry.overrides)
                            _providerName.value = instance.label.ifEmpty { entry.model.provider }
                            resolved = true

                        }
                    }
                }
            }

            if (!resolved) applyNewChatDefaultModel()

            // [T-HANG-DIAG] measure DB load + transform separately so a long
            // load on one stage is obvious in the trace.
            //
            // T-android-gc-storm-hang-crash (P0, issue #17): on a 405-message
            // session with one 397KB user row, loadMessages + toChatMessages
            // + the agentHistory rebuild below ran on Main and triggered a
            // GC storm (34MB freed, repeated) that blocked the frame loop for
            // 58s → crash_or_stall restart. Hoist the heavy DB + JSON-parse
            // work off Main so the UI thread stays responsive even when one
            // row is large. Stays inside the existing safe-mode guard above
            // (#466/#470) — we only move work, not gating.
            val tHangDiagBeforeLoad = System.currentTimeMillis()
            val loaded = com.openminis.app.agent.AgentHistoryRestore(chatRepository, agentHistory,
                { activeSessionId }).restore(sessionId, { it.toLLMMessage() },
                project = { rows -> rows.toChatMessages() },
                links = { rows -> rows.map { com.openminis.app.agent.AgentHistoryRestore.Link(it.id, it.sourceDbIds.toSet()) } })
            historyRestoreFailure = null
            val messages = loaded.messages
            val ordered = loaded.ordered
            val tHangDiagAfterLoad = tHangDiagBeforeLoad + loaded.loadMs
            val tHangDiagAfterTransform = tHangDiagAfterLoad + loaded.transformMs
            println(
                "[T-HANG-DIAG] loadMessages session=$sessionId count=${messages.size} " +
                    "tookMs=${loaded.loadMs}",
            )
            println(
                "[T-HANG-DIAG] toChatMessages session=$sessionId tookMs=${loaded.transformMs}",
            )
            // Per-message size sketch + oversize-row scan. Pure diagnostics —
            // does a full second pass over partsJson with several substring
            // searches per row, so on a 405-row session with 1MB total it
            // adds material main-thread time. Fire-and-forget on the IO
            // dispatcher so it can't contribute to the GC-storm hang the
            // rest of this task is trying to fix.
            viewModelScope.launch(Dispatchers.IO) {
                var totalChars = 0L
                var maxChars = 0
                var withTools = 0
                var withAttachments = 0
                for (m in messages) {
                    val len = m.partsJson.length
                    totalChars += len
                    if (len > maxChars) maxChars = len
                    // ContentPart serialises its discriminator in camelCase
                    // ("toolUse" / "toolResult" — see ContentPart.PartType), so
                    // the snake_case probe this used to run matched NOTHING and
                    // reported toolMessages=0 on every session, including ones
                    // whose history is almost entirely tool traffic. That is
                    // the opposite of the signal this diagnostic exists to give
                    // — it is here to finger oversized tool_result inlines as
                    // the GC-storm culprit, and it was reporting them absent.
                    if (m.partsJson.contains("\"toolUse\"") || m.partsJson.contains("\"toolResult\"")) {
                        withTools++
                    }
                    // Same casing trap: attachments serialise as "mediaRef",
                    // never as "image"/"attachment".
                    if (m.partsJson.contains("\"mediaRef\"")) {
                        withAttachments++
                    }
                }
                println(
                    "[T-HANG-DIAG] messages-shape session=$sessionId total=${messages.size} " +
                        "totalChars=$totalChars maxChars=$maxChars toolMessages=$withTools " +
                        "attachmentMessages=$withAttachments",
                )

                // [T-HANG-DIAG] for any message ≥ 50_000 chars, log size /
                // role / createdAt / structural type markers only — NEVER
                // the partsJson content (or any prefix/suffix of it). Earlier
                // versions echoed head500/tail500 to localise the culprit;
                // now that the cause is known (oversized tool_result inlines)
                // and ReadTool / AIChatViewModel.executeFileRead enforce
                // an 80 KB hard cap upstream, only metadata is needed for
                // future audits.
                val OVERSIZE_THRESHOLD = 50_000
                val oversized = messages.filter { it.partsJson.length >= OVERSIZE_THRESHOLD }
                if (oversized.isNotEmpty()) {
                    println(
                        "[T-HANG-DIAG] oversized-messages session=$sessionId " +
                            "count=${oversized.size} threshold=${OVERSIZE_THRESHOLD}",
                    )
                    for (m in oversized) {
                        val raw = m.partsJson
                        val len = raw.length
                        val hasToolUse = raw.contains("\"toolUse\"")
                        val hasToolResult = raw.contains("\"toolResult\"")
                        val hasImage = raw.contains("\"image\"") || raw.contains("\"image_url\"")
                        val hasBase64 = raw.contains("data:image") || raw.contains(";base64,")
                        println(
                            "[T-HANG-DIAG] oversized id=${m.id} role=${m.role} " +
                                "createdAt=${m.createdAt} len=$len " +
                                "hasToolUse=$hasToolUse hasToolResult=$hasToolResult " +
                                "hasImage=$hasImage hasBase64=$hasBase64 " +
                                "streamInterrupts=${m.streamInterruptCount}",
                        )
                    }
                }
            }

            // Rebuild agentHistory from persisted messages.
            // Pre-built off-Main inside the withContext(Dispatchers.IO) block
            // above to avoid re-parsing partsJson on the UI thread.
            //
            // [T-android-revert-history-duplication] REPLACE, don't append.
            // This used to be a bare addAll, justified by "loadSession runs
            // once at init before any sender writes into agentHistory". That
            // stopped being true when revertCompact() started calling
            // reloadSessionFromDb() -> loadSession() on a session that is
            // already open and whose agentHistory is fully populated: the
            // addAll then stacked a SECOND copy of every persisted message
            // onto the first. Measured on device — same session, same 20 DB
            // rows: after a revert the next request carried 31 rows with the
            // first 12 duplicated, while a cold restart of the same session
            // sent 21. The DB was never wrong; only the in-memory list was.
            //
            // The model saw the user say everything twice, and the duplicate
            // prefix also guarantees a prompt-cache miss for the whole
            // request. Clearing first makes the rebuild idempotent, so any
            // future re-entry into loadSession() is safe by construction
            // rather than by a comment nobody can enforce.
            compactionSummarizer.clear()
            // [T-ctx-measure-outbound] Re-seed the size meter from this session's
            // usage rows — the calibration RATIO only, never a raw size, so a
            // compaction or revert done since the last response cannot leave the
            // next decision judging a context that no longer exists. revertCompact
            // reloads through here, which is what lets the restored history be
            // measured at its full size.
            runCatching {
                seedContextCalibration(loaded.usages)
            }
            // [T-android-context-usage-hint] Adopt this session's pressure
            // WITHOUT announcing it. Opening an already-long conversation must
            // not fire a line for a threshold crossed hours ago in a different
            // one; only a crossing that happens while the user is here counts.
            // The glow still paints from `contextUsage`, so a heavy session
            // looks heavy on open — it just does not interrupt.
            withContext(Dispatchers.Main) {
                _contextUsageHint.value = null
                // [T-ctx-measure-outbound] The glow's source was never reset on
                // load, so it could show the previous state's number (or, after
                // a revert, the compacted context's). Show the measurement.
                runCatching { publishMeasuredContextUsage() }
                pendingRevertLogMarker?.let { marker ->
                    pendingRevertLogMarker = null
                    AppLogger.info(TAG, "[CtxMeter] reverted marker=$marker measured=${runCatching { measureOutboundContextTokens() }.getOrDefault(-1)}")
                }
                // The glow re-derives itself from _lastTurnContextTokens; only
                // the crossing tracker needs seeding, so an already-heavy
                // session shows its glow on open without announcing a line.
                val restored = ContextUsage.from(
                    usedTokens = _lastTurnContextTokens.value,
                    windowTokens = effectiveContextWindowTokens(),
                )
                contextTierTracker.reset(restored?.tier ?: ContextUsage.Tier.NORMAL)
            }
            val tHangDiagAfterAgentHistory = System.currentTimeMillis()
            println(
                "[T-HANG-DIAG] agentHistory rebuilt session=$sessionId tookMs=${tHangDiagAfterAgentHistory - tHangDiagAfterTransform}",
            )

            // Restore the most-recent compact summary, if any, so the first
            // outgoing turn after reopening a compacted session still sees
            // the folded-away context via [effectiveAgentHistory]. Also gray
            // out every UI message that falls before the marker's boundary —
            // mirrors iOS Phase 2.5 restore (AIChatViewModel.swift:3360+).
            val marker = loaded.marker
            _compactSummary.value = marker?.summary
            _cachedLatestMarker = marker

            com.openminis.app.diagnostics.PerfLongCtx.step(
                sessionId,
                "stateflow.emit.begin",
                "count=${ordered.size}",
            )
            // [T-android-larky-longsession-followup] Reset the tail
            // window to its initial cap on every session (re)load. Without
            // this a freshly opened session would inherit the previous
            // session's enlarged cap (set via loadOlderMessages), defeating
            // the windowing intent on the first paint of every new session.
            _visibleMessageCap.value = INITIAL_VISIBLE_MESSAGE_CAP
            _messages.value = if (marker == null) {
                ordered
            } else {
                applyCompactMarkerGraying(ordered, marker, loaded.dividerIndex ?: 0)
            }

            if (loaded.interrupted != null && !_isStreaming.value && !SessionActivityTracker.isActive(activeSessionId)) {
                markRedetectingInterruptedTail()
                _canResume.value = true
                Log.i(TAG, "loadSession: interrupted shape=${loaded.interrupted}")
            } else if (!_isStreaming.value) _canResume.value = false
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                historyRestoreFailure = failure
                _error.value = "History restore failed: ${failure.message ?: failure.javaClass.simpleName}"
                if (scope !== viewModelScope) throw failure
            } finally {
                // T201: open the gate even on early `return@launch` (draft path,
                // missing-session path) and on exception, so the init-time
                // config.collect can never deadlock waiting for us.
                sessionLoaded.value = true
                // [T-HANG-DIAG] total time spent in loadSession from ENTER to
                // either successful completion or early return. tHangDiagStart
                // was captured just inside `try` so this covers the whole
                // body the user perceives as "loading".
                println(
                    "[T-HANG-DIAG] loadSession EXIT session=$sessionId " +
                        "totalMs=${System.currentTimeMillis() - tHangDiagStart}",
                )
                com.openminis.app.diagnostics.PerfLongCtx.step(
                    sessionId,
                    "loadSession.exit",
                    "totalMs=${System.currentTimeMillis() - tHangDiagStart}",
                )
            }
        }
        historyLoadJob = loading
        loading.start()
        return loading
    }

    /** Display-only application of the runtime's resolved compact boundary. */
    private fun applyCompactMarkerGraying(messages: List<ChatMessage>,
        marker: com.openminis.app.data.db.CompactMarkerEntity, boundary: Int): List<ChatMessage> {
        val insertIdx = boundary.coerceIn(0, messages.size)
        val grayed = messages.mapIndexed { index, message ->
            message.copy(isCompactedHistory = message.role != "system" && index < insertIdx)
        }
        val count = grayed.take(insertIdx).count { it.role != "system" }
        val divider = ChatMessage(id = "compact-divider-msg-${marker.id}", role = "system", content = "",
            toolBlocks = listOf(AssistantBlock(id = "compact-divider-${marker.id}", kind = "info",
                content = "$count messages compacted", toolName = "compact", toolArgs = marker.summary)))
        return grayed.toMutableList().also { it.add(insertIdx, divider) }
    }

    /** Restore provider state from a JSON binding string. Returns true if successfully resolved. */
    private fun restoreFromBinding(bindingJson: String?): Boolean {
        bindingJson ?: return false
        return try {
            val obj = org.json.JSONObject(bindingJson)
            sessionContextLimitTokens = obj.optInt("contextLimitTokens").takeIf { it > 0 }
            when (obj.optString("type")) {

                "entry" -> {
                    val entryId = obj.optString("entryId").takeIf { it.isNotEmpty() } ?: return false
                    val entry = providerRepository.config.value.modelEntries.find { it.id == entryId } ?: return false
                    val instance = providerRepository.instance(entry.providerInstanceId) ?: return false
                    // [T-android-group-resolve-skip-uncredentialed] An explicit
                    // entry pin on an OAuth provider must restore too.
                    if (!providerRepository.hasAnyCredential(instance)) return false
                    val apiKey = providerRepository.usableApiKey(instance) ?: ""
                    currentModel = entry.model
                    _modelName.value = entry.model.displayName
                    _providerName.value = instance.label.ifEmpty { entry.model.provider }
                    _activeEntryId.value = entry.id
                    currentProvider = ProviderFactory.create(instance, apiKey, entry.model, context, sessionId = activeSessionId, overrides = entry.overrides)
                    true
                }
                else -> false
            }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * [T-newchat-default-model-fallback-android] Resolve and apply the default
     * model for a NEW chat when no default group produced a model. Fallback
     * chain tiers 2→3 (tier 1, the default group, is handled by the caller
     * before this runs):
     *
     *   2) last-used model — the entry the user last actively selected / used,
     *      if it still exists, is visible, and its provider is enabled.
     *   3) newest provider's newest text-output model — the final catch-all so
     *      a first-ever chat with providers but no group/last-used still gets a
     *      sensible, text-capable default (image/audio-only models excluded).
     *
     * Sets currentModel / currentProvider / the name + activeEntry state flows.
     * Returns true when a model was applied. Mirrors iOS #636. The legacy
     * behaviour here was `allVisibleEntries().firstOrNull()` (the FIRST entry),
     * which ignored both last-used and add-order — replaced by this chain.
     */
    private fun applyNewChatDefaultModel(): Boolean {
        val cfg = providerRepository.config.value
        val entry = cfg.modelEntries.firstOrNull {
            it.id == cfg.defaultModelEntryId && !it.isHidden && providerRepository.isEntryProviderEnabled(it.id)
        } ?: providerRepository.lastUsedVisibleEntry()
            ?: providerRepository.newestProviderNewestTextEntry()
            ?: return false
        val instance = providerRepository.instance(entry.providerInstanceId) ?: return false
        currentModel = entry.model
        _modelName.value = entry.model.displayName
        _activeEntryId.value = entry.id
        _providerName.value = instance.label.ifEmpty { entry.model.provider }
        // [T-android-group-resolve-skip-uncredentialed] Build the provider for
        // an OAuth instance too — otherwise this tier set the model name in the
        // UI but left currentProvider null, and the first send failed.
        if (providerRepository.hasAnyCredential(instance)) {
            val apiKey = providerRepository.usableApiKey(instance) ?: ""
            currentProvider = ProviderFactory.create(instance, apiKey, entry.model, context, sessionId = activeSessionId, overrides = entry.overrides)
        }
        return true
    }

    /** Select a specific model entry (bypasses group selection). */
    private fun cancelWorkBoundToPreviousModel(reason: String) {
        if (runCoordinator.mutating) return
        if (!_isStreaming.value && streamJob?.isActive != true) return
        val leaving = currentProvider?.model?.displayName ?: "(unresolved)"
        if (!isBoundToAbandonedModel()) {
            // [T-android-switch-model-let-stream-finish] The live turn is
            // producing output right now, so it is not interrupted.
            // [T-android-switch-model-next-request] But it no longer runs the
            // rest of the turn on the old model either: the caller rebinds the
            // class-level provider right after this, and runAgentLoop swaps to
            // it before its NEXT request (iOS 86a045284). A tool-driven task
            // picks up the new model after the current request or tool call,
            // not after its last one.
            pendingModelSwitch = true
            AppLogger.info(
                TAG_STREAM,
                "[SwitchModel] '$leaving' is streaming normally ($reason) — " +
                    "letting this request finish; the NEXT request uses the new model",
            )
            return
        }
        // The loop is ending: no next request to apply a switch to, and the
        // next turn resolves the new binding anyway.
        pendingModelSwitch = false
        AppLogger.info(
            TAG_STREAM,
            "[SwitchModel] cancelling retry work bound to '$leaving' ($reason) " +
                "attempt=${_autoRetryAttempt.value}",
        )
        // Treat it as a user-initiated stop of THIS turn: the error handler
        // then marks the message interrupted/resumable instead of reporting
        // the old model's failure.
        val stopped = stopRuntime.stop(activeSessionId, sessionId.takeIf { isDraft && it != activeSessionId },
            com.openminis.app.agent.AgentStopRuntime.Reason.MODEL_RETRY_SWITCH)
        if (stopped !is com.openminis.app.agent.AgentStopRuntime.Result.Run) return
        lastTurnWasCancelled = true
        _isStreaming.value = false
        _autoRetryAttempt.value = 0
        _autoRetryCountdown.value = 0
        clearInlineError()
        // [T-android-switch-model-let-stream-finish] Deliberately NOT
        // `handleUserCancelledCleanup()`. That is Stop's per-turn closer: its
        // tool branch flips in-flight tool blocks to CANCELLED, persists
        // cancelled tool_results and raises `_canResume` — i.e. it PAUSES the
        // conversation. Calling it here (T-android-switch-model-seam-cleanup)
        // turned an ordinary model switch into "task stopped, tap Resume",
        // reported on a Pixel 6: GPT-5.6 Terra was streaming a tool call, the
        // user switched model, and the run halted into the resumable state.
        // iOS's seam (05d654128) never did this — it cancels the task, clears
        // the retry UI, and stops. The cleanup is unnecessary in this path
        // anyway: we now only reach it from the retry ladder, where there is
        // no half-streamed tool block to reconcile.
        //
        // Prompts the user queued behind the cancelled retry must still drain
        // under the NEW model rather than sit as dashed bubbles.
        // resumeQueueAfterCancel re-resolves the provider after a 200ms delay,
        // by which time the caller has rebound it.
        if (_promptQueue.value.isNotEmpty()) {
            AppLogger.info(TAG_STREAM, "[SwitchModel] ${_promptQueue.value.size} queued prompt(s) remain, restarting drain")
            resumeQueueAfterCancel()
        }
    }

    /**
     * [T-android-switch-model-let-stream-finish] Whether the in-flight turn is
     * work aimed at the model being switched AWAY from, rather than a healthy
     * turn that merely happens to be running.
     *
     * The distinction is the whole point of the seam. `currentProvider` is
     * captured once per attempt, so the work that outlives a model switch is
     * the auto-retry ladder: it sleeps through a 1s/2s/4s countdown still
     * holding the abandoned provider and, on waking, issues the next attempt
     * against it. That is the ghost retry the fix exists for, and cancelling
     * costs nothing because the attempt it would make is unwanted by
     * definition.
     *
     * A turn that is streaming normally is the opposite: it is producing the
     * answer the user is reading. Cancelling it throws away real output (on the
     * reported Pixel 6 run, 33.8k tokens and a tool call that never finished
     * assembling) to no benefit — the model binding is already updated, so the
     * NEXT turn runs on the new model either way.
     */
    /**
     * [T-android-switch-model-next-request] The user picked another model
     * while a healthy turn was running. Set by [cancelWorkBoundToPreviousModel]
     * (every picker goes through it, before it rebinds), read and cleared by
     * runAgentLoop at the top of its next iteration. An explicit flag rather
     * than "the class-level provider changed", like iOS: a group fallback also
     * moves the provider, and must not be read as a user switch. Main-thread
     * only.
     */
    private var pendingModelSwitch = false

    /**
     * [T-android-switch-model-next-request] [prompt] for [provider]: with the
     * Claude Code prefix Anthropic OAuth requires, without it for anything
     * else (it is that login's identity string, not ours to send elsewhere).
     * The rest of the prompt is not model-specific on Android.
     */
    private fun systemPromptFor(provider: LLMProvider, prompt: String?): String? {
        val prefix = com.openminis.app.auth.ClaudeOAuthManager.ANTHROPIC_OAUTH_IDENTIFIER_PROMPT
        if (prompt == null || prefix.isEmpty()) return prompt
        val bare = if (prompt.startsWith(prefix)) prompt.removePrefix(prefix).trimStart('\n') else prompt
        val oauth = (provider as? com.openminis.app.provider.anthropic.AnthropicProvider)?.isOAuth == true
        return if (oauth) "$prefix\n\n$bare" else bare
    }

    private fun isBoundToAbandonedModel(): Boolean =
        _autoRetryAttempt.value > 0 || _autoRetryCountdown.value > 0

    /**
     * [T-android-vm-evict-busy] Whether evicting this view model from
     * [ChatViewModelStore] would destroy work in flight. See [EvictionGuard]
     * for why `SessionActivityTracker` alone is not enough: it only turns on
     * after the concurrency slot is acquired, and never for a parent whose
     * background sub agents are still running.
     *
     * Both session ids are consulted because [activeSessionId] is the draft
     * id before the first persist and the real id afterwards, and agent jobs
     * may be registered under either.
     */
    fun hasWorkInFlight(): Boolean {
        val registry = com.openminis.app.agent.jobs.AgentJobRegistry
        val agentWork = setOf(activeSessionId, sessionId, realSessionId)
            .filter { it.isNotEmpty() }
            .any { registry.hasAgentWork(it) }
        return EvictionGuard.hasWorkInFlight(
            isStreaming = _isStreaming.value,
            streamJobActive = streamJob?.isActive == true,
            hasAgentWork = agentWork,
            hasQueuedPrompts = _promptQueue.value.isNotEmpty(),
        )
    }

    fun selectEntry(entryId: String) {
        // [T-android-switch-model-hang] Same teardown [selectGroup] and
        // [selectGroupEntry] do — this selector was missed when
        // a43ad4134 added the guard, and it is the one the header dropdown
        // uses for a plain (non-group) model, i.e. the most common switch.
        //
        // Without it, switching model mid-turn left `_isStreaming = true` with
        // the old model's stream job still parked in the auto-retry ladder's
        // 1s/2s/4s countdown. The session then looked FROZEN: `sendMessage`
        // routes to `enqueuePrompt` whenever `_isStreaming` is set, so the
        // user's next message silently became a queued prompt waiting on a
        // turn that would never finish under a model they had already left.
        // Nothing visibly happened — no reply, no error — which is exactly
        // the "切换模型后卡死停滞" the report describes.
        //
        // [T-android-switch-model-seam-cleanup] Placed AFTER the early returns:
        // those returns mean "this entry is unusable" (unknown id, missing
        // instance, no credential) and the pick does not take effect. The
        // first version cancelled before them, so a pick that resolved to
        // nothing killed the running turn AND left the old model bound — the
        // user saw a dead turn on a model they never actually left. A pick
        // that changes nothing must cost nothing; the old model keeps
        // retrying exactly as if the user had not tapped. The helper is a
        // no-op when nothing is streaming, so an idle pick still costs nothing.
        val config = providerRepository.config.value
        val entry = config.modelEntries.find { it.id == entryId } ?: return
        val instance = providerRepository.instance(entry.providerInstanceId) ?: return
        // [T-android-group-resolve-skip-uncredentialed] The user explicitly
        // tapped this model; refusing it because the API-key slot is empty
        // made OAuth models unselectable from the picker.
        if (!providerRepository.hasAnyCredential(instance)) return
        val apiKey = providerRepository.usableApiKey(instance) ?: ""
        cancelWorkBoundToPreviousModel("selectEntry")

        currentModel = entry.model
        // An explicit user pick is a statement that this model should be tried
        // again — clear any failure mark so the group pick stops avoiding it.
        recentlyFailedEntryIds.remove(entry.id)
        _modelName.value = entry.model.displayName
        _providerName.value = instance.label.ifEmpty { entry.model.provider }
        _activeEntryId.value = entry.id
        currentProvider = ProviderFactory.create(instance, apiKey, entry.model, context, sessionId = activeSessionId, overrides = entry.overrides)
        persistBinding("""{"type":"entry","entryId":"$entryId"}""")
        // [T-newchat-default-model-fallback-android] Remember this as the
        // global last-used model so the NEXT new chat (when no default group
        // is set) defaults back to it. Tier 2 of the new-chat fallback chain.
        providerRepository.lastUsedEntryId = entryId
    }

    /** Persist the model binding to the DB session (no-op for draft sessions). */
    private fun persistBinding(bindingJson: String) {
        val sid = realSessionId.takeIf { it.isNotEmpty() } ?: return
        val modelId = currentModel?.id ?: return
        val directBinding = org.json.JSONObject(bindingJson).apply {
            sessionContextLimitTokens?.let { put("contextLimitTokens", it) }
        }.toString()
        viewModelScope.launch {
            chatRepository.updateSessionBinding(sid, directBinding, modelId)
        }
    }

    private fun findModelEntry(modelId: String) =
        providerRepository.allVisibleEntries().find { it.model.id == modelId }

    /**
     * Build the ordered list of fallback providers for the current group,
     * starting AFTER the primary provider in the member list and cycling around.
     * This ensures that models already tried (before the primary) are at the end,
     * not the beginning — so retry doesn't re-trigger the same fallback chain.
     */
    /**
     * [T-android-fallback-entry-identity] A fallback candidate, carrying the
     * ENTRY it was built from.
     *
     * The entry id is the only unambiguous identity: two different provider
     * instances can expose the SAME `model.id` (observed in the field:
     * `deepseek-v4-flash` exists under both "DeekSeak" — api.deepseek.com — and
     * "Bailian OpenAI" — dashscope.aliyuncs.com). Recovering the entry after the
     * fact by matching `model.id` therefore picks whichever entry happens to
     * come first in `modelEntries`, which is not necessarily the one that served
     * the request.
     */
    private data class FallbackCandidate(
        val provider: LLMProvider,
        val entryId: String,
    )

    private fun buildFallbackProviders(primaryProvider: LLMProvider): List<FallbackCandidate> = emptyList()

    /**
     * Group members that fallback skipped (disabled instance / missing
     * credential / hidden entry), with reasons. Mirrors iOS
     * ModelGroupRouter.unavailableMembers: when fallback exhausts, the user
     * needs to know WHY the other group members never got tried — e.g. the
     * Claude subscription was logged out, so every Anthropic entry was
     * silently filtered and fallback kept cycling OpenAI-only.
     */
    private fun unavailableGroupMembers(): List<String> = emptyList()

    internal fun noteEntryFailed(entryId: String?) {
        if (!entryId.isNullOrEmpty()) recentlyFailedEntryIds.add(entryId)
    }

    private fun adoptFallbackCandidate(candidate: FallbackCandidate) {
        noteEntryFailed(_activeEntryId.value)
        recentlyFailedEntryIds.remove(candidate.entryId)
        currentProvider = candidate.provider
        _modelName.value = candidate.provider.model.displayName
        val newEntry = providerRepository.config.value.modelEntries
            .find { it.id == candidate.entryId }
        if (newEntry != null) {
            _activeEntryId.value = newEntry.id
            currentModel = newEntry.model
            providerRepository.instance(newEntry.providerInstanceId)?.let { inst ->
                _providerName.value = inst.label.ifEmpty { newEntry.model.provider }
            }
            persistBinding("""{"type":"entry","entryId":"${newEntry.id}"}""")
        }
        _fallbackTrigger.value++
    }

    private fun resolveNextFallbackProvider(): LLMProvider? = null

    // [T-android-split-chat] addAttachment / removeAttachment / clearAttachments
    // moved to ChatViewModelUiStateExt.kt (extension functions).

    /** Runtime serializes cancellation, compaction rollback, child retirement and the durable wipe. */
    fun clearChat() {
        val owner = activeSessionId
        _promptQueue.value = emptyList() // suppress cancel-time queue restart
        if (_isStreaming.value || streamJob?.isActive == true) cancelStream()
        com.openminis.app.agent.AgentHistoryReset(chatRepository, agentHistory, runCoordinator,
            subagentJournal, { activeSessionId }).launch(viewModelScope, owner, compactJob,
            failed = { if (activeSessionId == owner) _error.value = it.message ?: "Clear chat failed" }) {
            clearAllStreamFlushStates()
            _messages.value = emptyList()
            _error.value = null
            _cachedLatestMarker = null
            _compactSummary.value = null
            toolLoopDetector.reset()
            _canResume.value = false
            _attachments.value = emptyList()
            _hasInjectedShareContent.value = false
            _selectedToolDetailId.value = null
            if (helperConfig == null) _browserTabPoolRef?.destroyAllTabs()
            runCatching { java.io.File(context.filesDir, "browser_tabs/$owner.json").delete() }
            AppLogger.info(TAG, "clearChat: session=$owner wiped (files preserved)")
        }
    }

    // ─── Share Injection (T51) ────────────────────────────────────────────

    /**
     * Whether the current input was seeded from a system share intent.
     * The "Move to…" capsule above the chat list is gated on this — once
     * the user starts a new turn or moves the share elsewhere we flip it
     * back to false. Mirrors iOS AIChatView.hasInjectedShareContent.
     */
    private val _hasInjectedShareContent = kotlinx.coroutines.flow.MutableStateFlow(false)
    val hasInjectedShareContent: kotlinx.coroutines.flow.StateFlow<Boolean> =
        _hasInjectedShareContent.asStateFlow()

    fun markShareInjected() { _hasInjectedShareContent.value = true }
    fun clearShareInjectedFlag() { _hasInjectedShareContent.value = false }

    /**
     * Convert a staged share file (under filesDir/share_extension/) into
     * an [InputAttachment] and add it to the composer. Called by
     * ChatScreen when draining a [com.openminis.app.share.PendingShare].
     */
    fun addAttachmentFromStagedShare(file: java.io.File): InputAttachment? {
        if (!file.exists()) return null
        val ext = file.extension.lowercase()
        val mime = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
            ?: "application/octet-stream"
        val kind = if (mime.startsWith("image/")) InputAttachment.Kind.IMAGE
                   else InputAttachment.Kind.DOCUMENT
        // T185 fix: ChatScreen wipes the share-extension directory right
        // after this call returns (`SharedShareStore.cleanSharedFiles`),
        // so a `Uri.fromFile(<staged file>)` would dangle by the time the
        // user actually sends — the byte-read in prepareUserAttachments
        // then fails to open the stream and the image never makes it into
        // the LLM payload, leaving the model staring at "what is this?" with no
        // picture. Copy the staged bytes into our own private dir so the
        // attachment outlives the share-extension cleanup.
        val durableDir = java.io.File(context.cacheDir, "share_inbound").apply { mkdirs() }
        val durable = java.io.File(durableDir, "${java.util.UUID.randomUUID()}-${file.name}")
        try {
            file.inputStream().use { input ->
                durable.outputStream().use { output -> input.copyTo(output) }
            }
        } catch (e: Exception) {
            Log.w(TAG, "failed to copy staged share file ${file.name}: ${e.message}")
            return null
        }
        val attachment = InputAttachment(
            fileName = file.name,
            uri = android.net.Uri.fromFile(durable),
            mimeType = mime,
            kind = kind,
        )
        addAttachment(attachment)
        return attachment
    }

    // ─── Message Sending & Agent Loop ─────────────────────────────────────

    /**
     * [T-android-rerun-from-tool-block-position] Resolve the live UI assistant
     * bubble id that currently owns the tool block with [blockId] (== its
     * tool_use id). Returns null when no live bubble holds it. Used by the
     * debug RPC ([com.openminis.app.debug.HeadlessChatRunner.rerunFromToolBlock])
     * because the in-memory bubble id is a volatile `assistant_<ts>` runtime id
     * (not the DB row id a caller would read from `chat.messages.list`), so the
     * harness can't supply it directly.
     */
    fun assistantMessageIdForToolBlock(blockId: String): String? =
        _messages.value.firstOrNull { m ->
            m.role == "assistant" && m.toolBlocks.any { it.id == blockId }
        }?.id

    /** Explicit block rerun: capture the display target; runtime validates and commits the exact cut. */
    fun rerunFromToolBlock(assistantMessageId: String, blockId: String): Boolean {
        if (afterCancelledRun { rerunFromToolBlock(assistantMessageId, blockId) }) return true
        if (_isStreaming.value) return false
        val snapshot = _messages.value
        val index = snapshot.indexOfFirst { it.id == assistantMessageId }
        if (index < 0) return false
        val message = snapshot[index]
        val part = message.toolBlocks.indexOfFirst { it.id == blockId && it.kind == "tool_use" }
        if (part < 0 || blockId.isBlank() || currentProvider == null) return false
        return launchRewind(com.openminis.app.agent.AgentRewindJournal.Target.Tool(blockId), "rerunFromToolBlock") {
            val blocks = message.toolBlocks.take(part)
            _messages.value = snapshot.take(index) + if (blocks.isEmpty()) emptyList() else listOf(message.copy(
                content = blocks.filter { it.isText }.joinToString("") { it.content }, toolBlocks = blocks,
                isStreaming = false, isAwaitingModelResponse = false, error = null))
        }
    }

    fun retryFromMessage(messageId: String) {
        if (afterCancelledRun { retryFromMessage(messageId) }) return
        if (_isStreaming.value) return
        val snapshot = _messages.value
        val index = snapshot.indexOfFirst { it.id == messageId }
        if (index < 0 || snapshot[index].role != "user") return
        val message = snapshot[index]
        if (message.content.isBlank() && message.attachmentUris.isEmpty() && message.imageUris.isEmpty()) return
        val keepThrough = BubbleRowLocator.groupSpan(snapshot, index).last
        val ids = (message.sourceDbIds + message.id).toSet()
        launchRewind(com.openminis.app.agent.AgentRewindJournal.Target.User(ids), "retryFromMessage") {
            _messages.value = snapshot.take(keepThrough + 1).map { bubble ->
                if (bubble.id == messageId && bubble.isQueued) {
                    bubble.queuedPromptId?.let { id -> _promptQueue.value = _promptQueue.value.filterNot { it.id == id } }
                    bubble.copy(isQueued = false, queuedPromptId = null)
                } else bubble
            }
        }
    }

    /** UI captures the target and renders the committed cut; runtime owns the durable rewind and dispatch. */
    private fun launchRewind(target: com.openminis.app.agent.AgentRewindJournal.Target,
        label: String, publish: () -> Unit): Boolean {
        val initial = currentProvider ?: return false
        val owner = activeSessionId
        val entry = _activeEntryId.value
        var provider = initial
        var prompt: String? = null
        com.openminis.app.agent.jobs.AgentJobRegistry.clearDelegationMute(owner)
        _forceScrollToBottom.tryEmit(Unit)
        _canResume.value = false
        _error.value = null
        _isStreaming.value = true
        fun checkOwner() { if (activeSessionId != owner) throw CancellationException("rewind branch changed") }
        launchAgentRun(viewModelScope, label, prepare = {
            checkOwner()
            com.openminis.app.agent.AgentRewindJournal(chatRepository, owner, agentHistory,
                { activeSessionId }, { it.toLLMMessage() }, subagentJournal).prepare(target) { _, marker ->
                checkOwner()
                publish()
                _cachedLatestMarker = marker
                _compactSummary.value = marker?.summary
                if (marker == null) _messages.value = _messages.value.map { it.copy(isCompactedHistory = false) }
                retainStreamFlushStates(_messages.value.mapTo(mutableSetOf()) { it.id })
                toolLoopDetector.reset()
            }
            checkOwner()
            provider = providerPreparation.prepare(provider, entry, owner) { expected, refreshed ->
                if (activeSessionId == owner && currentProvider === expected) currentProvider = refreshed
            }
            checkOwner()
            prompt = providerPreparation.prompt(provider, buildSystemPrompt())
        }) {
            runAgentLoop(provider, prompt, buildFallbackProviders(provider))
        }
        return true
    }

    /** Display captures an exact user-turn boundary; runtime owns deletion and settlement. */
    fun deleteFromMessage(messageId: String) {
        if (_isStreaming.value || streamJob?.isActive == true || compactJob?.isCompleted == false) return
        val snapshot = _messages.value
        val index = snapshot.indexOfFirst { it.id == messageId }
        if (index < 0) return
        val selected = snapshot[index]
        val anchorIndex = if (selected.role == "user") index
            else if (selected.role == "assistant") snapshot.take(index + 1).indexOfLast { it.role == "user" } else -1
        if (anchorIndex < 0) { _error.value = "No persisted user-turn boundary for deletion"; return }
        val anchor = snapshot[anchorIndex]
        val keepAnchor = selected.role == "assistant"
        val span = BubbleRowLocator.groupSpan(snapshot, anchorIndex)
        val cutFrom = if (keepAnchor) span.last + 1 else span.first
        val owner = activeSessionId
        val target = com.openminis.app.agent.AgentRewindJournal.Target.User(
            (anchor.sourceDbIds + anchor.id).toSet(), keepAnchor)
        com.openminis.app.agent.AgentRewindJournal(chatRepository, owner, agentHistory,
            { activeSessionId }, { it.toLLMMessage() }, subagentJournal).delete(viewModelScope, runCoordinator, target,
            failed = { if (activeSessionId == owner) _error.value = it.message ?: "History deletion failed" }) { _, marker ->
            val removedQueue = snapshot.drop(cutFrom).mapNotNull { it.queuedPromptId }.toSet()
            _promptQueue.value = _promptQueue.value.filterNot { it.id in removedQueue }
            _messages.value = snapshot.take(cutFrom).map { if (marker == null) it.copy(isCompactedHistory = false) else it }
            _cachedLatestMarker = marker
            _compactSummary.value = marker?.summary
            _canResume.value = false
            retainStreamFlushStates(_messages.value.mapTo(mutableSetOf()) { it.id })
            toolLoopDetector.reset()
        }
    }

    /**
     * T187: enter edit mode for [messageId]. Returns the cleaned text the
     * caller should drop into the composer (with any
     * `<user-attached-files>` XML stripped), or null when the message
     * cannot be edited (streaming in progress, message missing, or not
     * a user turn). Setting `_editingMessageId` is what flips the
     * composer into edit-mode UI; the next sendMessage call sees the
     * non-null id and truncates the conversation from that point.
     * Mirrors iOS AIChatViewModel.editMessage(_:) (L2468).
     */
    fun editMessage(messageId: String): String? {
        if (_isStreaming.value) return null
        val msg = _messages.value.firstOrNull { it.id == messageId } ?: return null
        if (msg.role != "user") return null
        var text = msg.content
        val startIdx = text.indexOf("<user-attached-files>")
        if (startIdx >= 0) {
            val endTag = "</user-attached-files>"
            val endIdx = text.indexOf(endTag, startIdx)
            text = if (endIdx >= 0) {
                (text.substring(0, startIdx) + text.substring(endIdx + endTag.length)).trim()
            } else {
                text.substring(0, startIdx).trim()
            }
        }
        // [T-android-edit-loses-attachments] Restore the message's attachments
        // into the composer alongside its text.
        //
        // Without this, editing a message that carried an image silently
        // dropped it: the composer showed only the text, and re-sending
        // produced a turn the model could no longer see the picture in. The
        // XML strip above is what makes the loss invisible — the
        // `<user-attached-files>` block naming the files is removed from the
        // text, so nothing on screen hints that anything was attached.
        //
        // iOS has done this since AIChatViewModel.editMessage (L3891-3920);
        // this side only ever returned the text. Android needs no copy step,
        // unlike iOS: `imageUris`/`attachmentUris` on a restored message
        // already point at files inside the app's own media store (see the
        // `mediaRef` branch of loadSessionMessages, which resolves them
        // against mediaStore.mediaBaseDir and skips any that no longer
        // exist), and that is exactly what the send path re-reads.
        //
        // Ordering matches ChatMessage's own convention — images first, then
        // files — so the composer's preview row shows them the same way the
        // sent bubble did. Names come from `attachmentNames`, which is built
        // image-first to align with these two lists; it is indexed
        // defensively anyway, since a row persisted by an older build could
        // be short.
        val restored = mutableListOf<InputAttachment>()
        msg.imageUris.forEachIndexed { i, uri ->
            val name = msg.attachmentNames.getOrNull(i) ?: uri.lastPathSegment ?: "image"
            restored.add(
                InputAttachment(
                    fileName = name,
                    uri = uri,
                    mimeType = guessMimeType(name, fallback = "image/*"),
                    kind = InputAttachment.Kind.IMAGE,
                ),
            )
        }
        msg.attachmentUris.forEachIndexed { i, uri ->
            // The non-image names occupy the suffix of attachmentNames, after
            // the imageUris-many image entries.
            val name = msg.attachmentNames.getOrNull(msg.imageUris.size + i)
                ?: uri.lastPathSegment ?: "file"
            restored.add(
                InputAttachment(
                    fileName = name,
                    uri = uri,
                    mimeType = guessMimeType(name, fallback = "application/octet-stream"),
                    kind = InputAttachment.Kind.DOCUMENT,
                ),
            )
        }
        // Replace rather than append: edit mode reloads a specific message, so
        // whatever the composer held was a different draft. Assigning even when
        // empty keeps that true for a message that genuinely had no files.
        _attachments.value = restored

        _editingMessageId.value = messageId
        AppLogger.info(
            TAG_STREAM,
            "✏️ editMessage id=${messageId.take(8)} text=${text.length}ch " +
                "attachments=${restored.size}",
        )
        return text
    }

    /**
     * [T-android-edit-attachments] Best-effort MIME for a restored attachment,
     * derived from its file extension.
     *
     * The exact type only has to be good enough for the composer chip and the
     * send path's image/non-image split; the kind is already decided by which
     * list the URI came out of, so a miss here cannot misroute an attachment.
     */
    private fun guessMimeType(fileName: String, fallback: String): String {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        if (ext.isEmpty()) return fallback
        return android.webkit.MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(ext) ?: fallback
    }

    /**
     * T187: leave edit mode without sending. Just clears the id flag —
     * caller (ChatScreen) is responsible for clearing inputText. iOS
     * parity: AIChatViewModel.cancelEdit (L2522).
     */
    fun cancelEdit() {
        if (_editingMessageId.value != null) {
            AppLogger.info(TAG_STREAM, "✏️ cancelEdit")
            // [T-android-edit-loses-attachments] Drop the attachments
            // editMessage restored, mirroring how the caller clears the text.
            //
            // Symmetry with editMessage is the whole point: it REPLACES the
            // composer's attachments with the edited message's, so leaving
            // them behind on cancel would strand files the user never picked
            // in a composer they thought they had backed out of — and the next
            // ordinary send would silently attach them.
            //
            // Guarded by the same non-null check as the log so a stray call
            // outside edit mode cannot wipe a draft's real attachments.
            _attachments.value = emptyList()
        }
        _editingMessageId.value = null
    }

    private suspend fun commitEditedUser(messageId: String, owner: String,
        input: com.openminis.app.agent.AgentQueuedUserInput, accepted: (MessageEntity) -> Unit) {
        val snapshot = _messages.value
        val index = snapshot.indexOfFirst { it.id == messageId && it.role == "user" }
        require(index >= 0) { "The edited message is no longer on this branch" }
        val message = snapshot[index]
        val cutFrom = BubbleRowLocator.groupSpan(snapshot, index).first
        val target = com.openminis.app.agent.AgentRewindJournal.Target.User(
            (message.sourceDbIds + message.id).toSet(), keepAnchor = false)
        com.openminis.app.agent.AgentRewindJournal(chatRepository, owner, agentHistory,
            { activeSessionId }, { it.toLLMMessage() }, subagentJournal).prepare(target, input, accepted) { _, marker ->
            _messages.value = snapshot.take(cutFrom).map { if (marker == null) it.copy(isCompactedHistory = false) else it }
            _cachedLatestMarker = marker
            _compactSummary.value = marker?.summary
            retainStreamFlushStates(_messages.value.mapTo(mutableSetOf()) { it.id })
            toolLoopDetector.reset()
        }
    }

    /**
     * Enqueue a prompt to be injected into the currently running agent loop.
     * The message appears immediately in the chat with isQueued=true; when the
     * current agent loop finishes, drainQueuedPrompts() consumes the queue.
     * Mirrors iOS AIChatViewModel.enqueuePrompt().
     */
    fun enqueuePrompt(
        text: String,
        origin: QueuedPromptOrigin = QueuedPromptOrigin.USER,
        // [T-scheduled-tool-prefill] Runs as the first turn of whichever loop
        // eventually carries this prompt (drain or mid-loop injection).
        prefill: List<com.openminis.app.scheduled.PrefilledToolCall> = emptyList(),
    ) {
        val trimmed = text.trim()
        // [T-p2-gentle-injection] Only a USER prompt owns the composer's staged
        // attachments. A programmatic prompt arriving while the user has files
        // staged must not walk off with them (and must not clear them below).
        val pendingAttachments = if (origin == QueuedPromptOrigin.USER) _attachments.value else emptyList()
        if ((trimmed.isBlank() && pendingAttachments.isEmpty()) || !_isStreaming.value) return
        // [T-android-image-input-preflight] Same gate as sendMessage: a queued
        // prompt drains against the same model, so an image the model cannot
        // take would fail just as surely a turn later.

        val prompt = QueuedPrompt(
            id = "queued_${System.currentTimeMillis()}_${(Math.random() * 1_000_000).toInt()}",
            text = trimmed,
            attachments = pendingAttachments,
            origin = origin,
            prefill = prefill,
            // [T-scheduled-preemptive-insert] Recognise a scheduled fire by its
            // envelope, and only when it did not come from the composer.
            scheduledTaskId = if (origin == QueuedPromptOrigin.PROGRAMMATIC) {
                com.openminis.app.scheduled.ScheduledTaskMarker.parse(trimmed)?.taskId
            } else null,
        )
        // [T-scheduled-preemptive-insert] A newer undelivered fire of the same
        // task replaces the older one (queue entry and its dashed bubble).
        prompt.scheduledTaskId?.let { taskId ->
            val superseded = QueuedPromptBatching.supersededBy(_promptQueue.value, taskId).toSet()
            if (superseded.isNotEmpty()) {
                _promptQueue.value = _promptQueue.value.filterNot { it.id in superseded }
                _messages.value = _messages.value.filterNot { it.queuedPromptId in superseded }
                Log.i(TAG, "Scheduled task $taskId fired again before delivery — replaced ${superseded.size} older queued fire(s)")
            }
        }
        _promptQueue.value = _promptQueue.value + prompt

        val attachmentNames = pendingAttachments.map { it.fileName }
        val imageUris = pendingAttachments.filter { it.isImage }.map { it.uri }
        val attachmentUris = pendingAttachments.filterNot { it.isImage }.map { it.uri }
        val chatMsg = ChatMessage(
            id = "queued_msg_${prompt.id}",
            role = "user",
            content = trimmed,
            imageUris = imageUris,
            attachmentNames = attachmentNames,
            attachmentUris = attachmentUris,
            isQueued = true,
            queuedPromptId = prompt.id,
        )
        _messages.value = _messages.value + chatMsg
        if (origin == QueuedPromptOrigin.USER) clearAttachments()
        Log.i(TAG, "Enqueued prompt (${trimmed.length}ch, ${pendingAttachments.size} attachments, origin=$origin), queue=${_promptQueue.value.size}")
    }

    /** Remove a queued prompt and its chat message by prompt id. */
    fun removeQueuedPrompt(promptId: String) {
        _promptQueue.value = _promptQueue.value.filterNot { it.id == promptId }
        _messages.value = _messages.value.filterNot { it.queuedPromptId == promptId }
    }

    /** Withdraw a queued message before it gets injected into the agent loop. */
    fun withdrawQueuedMessage(messageId: String) {
        val msg = _messages.value.firstOrNull { it.id == messageId } ?: return
        if (!msg.isQueued) return
        val pid = msg.queuedPromptId ?: return
        _promptQueue.value = _promptQueue.value.filterNot { it.id == pid }
        _messages.value = _messages.value.filterNot { it.id == messageId }
        Log.i(TAG, "Withdrew queued message, queue=${_promptQueue.value.size}")
    }

    /**
     * [T-android-queued-message-interrupt-on-toolclose] Mid-tool-loop
     * interrupt: take everything in [_promptQueue] right now, finalize the
     * just-finished assistant bubble in the UI, persist a fresh user
     * message carrying the queued text + attachments, append an assistant
     * "bridge" entry into [agentHistory] (so Anthropic's
     * mergeConsecutiveSameRole doesn't fold the queued user msg into the
     * preceding tool_result), and spawn a new assistant placeholder for
     * the next iteration's response.
     *
     * Returns an [InjectedTurn] carrying the new assistantId (which the
     * caller swaps into its loop-scope `assistantId` before `continue`-ing
     * the agent loop), or `null` if every queued prompt was empty after
     * attachment processing (caller falls through to a normal next-turn
     * dispatch in that case).
     *
     * Mirrors iOS `injectQueuedPromptsAsNewTurn`
     * (AIChatViewModel.swift:2794). Unlike iOS we don't persist the bridge
     * entry — its sole purpose is to break up the consecutive-user run for
     * the next API call; chat history reconstruction would just hide it.
     */
    /**
     * [T-scheduled-preemptive-insert] [envelopeText] rewritten as a mid-loop
     * insertion; unchanged if it is not a scheduled envelope.
     */
    private fun insertedScheduledEnvelope(envelopeText: String): String =
        com.openminis.app.scheduled.ScheduledTaskMarker.parse(envelopeText)
            ?.copy(insertedMidTask = true)?.xml
            ?: envelopeText

    private data class InjectedTurn(
        val newAssistantId: String,
        /** [T-scheduled-tool-prefill] Prefills of the injected prompts; the
         *  loop runs them as its next turn instead of asking the model. */
        val prefill: List<com.openminis.app.scheduled.PrefilledToolCall> = emptyList(),
    )

    private suspend fun injectQueuedPromptsAsNewTurn(
        conversation: com.openminis.app.agent.AgentConversationJournal,
        finishedAssistantId: String,
        finishedAccumulatedText: String,
        finishedAllToolBlocks: List<AssistantBlock>,
        // [T-scheduled-preemptive-insert] The prompts to inject, chosen by
        // QueuedPromptBatching.nextInsertBatch: either one scheduled fire, or
        // the user follow-up(s) merged with the other non-scheduled prompts.
        // Anything not in the batch stays queued.
        batch: List<QueuedPrompt>,
    ): InjectedTurn? {
        // [T-android-mute-reset-on-drain] The injected prompt starts a new turn
        // inside this loop; a sub-agent stop's mute was for the turn before it.
        conversation.checkBranch()
        com.openminis.app.agent.jobs.AgentJobRegistry.clearDelegationMute(conversation.sessionId)
        if (batch.isEmpty()) return null
        val queued = batch
        // A scheduled fire travels alone (see nextInsertBatch), so the batch
        // is either exactly one of those or has none.
        val scheduledInsert = queued.size == 1 && queued[0].isScheduledFire
        // [T-queue-dequeue-after-persist] The prompts stay ON the queue until
        // their user row is persisted (see the dequeue after appendMessage
        // below). This used to empty the queue here, ahead of four suspension
        // points; a Stop inside that window made cancelStream find an empty
        // queue, so resumeQueueAfterCancel never ran and the message was
        // neither sent nor saved (its dashed bubble vanished on reopen).

        // [T-android-queued-message-duplicated-on-inject] REMOVE the queued
        // placeholder bubbles (the ones enqueuePrompt added with
        // id="queued_msg_…") for the prompts we're injecting. Step (c) below
        // appends a single combined user bubble (id=userEntity.id) for the same
        // text — so flipping isQueued=false and KEEPING the placeholders (the
        // old behaviour) rendered the message TWICE: once as the un-queued
        // placeholder, once as the injected bubble. drainQueuedPrompts reuses
        // its placeholders and never re-appends, so it didn't dupe; this mid-
        // loop inject path appends a fresh bubble, so the placeholders must go.
        val queuedIds = queued.map { it.id }.toSet()
        // [T-android-midtask-msg-vanishes] Only the ID SET is captured here.
        //
        // This used to also snapshot `_messages.value` into `msgsAfterUnqueue`
        // and assign that snapshot back on Main ~100 lines below. Between the
        // two points this function suspends four times — ensureSession(),
        // prepareUserAttachments() (which copies attachments), buildPastedParts()
        // and chatRepository.appendMessage() (a DB write) — and that gap is
        // seconds wide in a real session (measured 4.8s in the reported repro).
        //
        // Anything appended to _messages during that gap was silently
        // overwritten by the stale snapshot: a read-modify-write race. The
        // user-visible symptom was the reported bug — you type a message while
        // a task is running, it IS accepted and IS persisted (the model even
        // answers it), but its bubble vanishes from the chat until you leave
        // the session and come back. enqueuePrompt() appends the queued bubble
        // to _messages immediately, so a message sent inside the window had its
        // bubble erased milliseconds later.
        //
        // Agent callbacks make the window easy to hit: they enqueue
        // PROGRAMMATIC prompts on the same queue, so in the repro log a user
        // prompt landed as queue=2 next to a 9070-char callback twice within
        // four minutes.
        //
        // The filter is now applied to the LIVE list at assignment time
        // (below), which is what drainQueuedPrompts already does
        // (`_messages.value.map { … }`) and why that path never showed the bug.

        // Build the combined user message from all queued prompts.
        val sid = conversation.sessionId
        val combinedAttachments = queued.flatMap { it.attachments }
        val prepared = prepareUserAttachments(combinedAttachments, sid)
        conversation.checkBranch()

        val combinedParts = mutableListOf<AgentContentPart>()
        val combinedText = StringBuilder()
        for (prompt in queued) {
            // [T-scheduled-preemptive-insert] A scheduled fire landing mid-loop
            // is re-wrapped as an INSERTED envelope, whose reminder tells the
            // model where this turn came from and to resume its plan after.
            // Persisted as such, so a reloaded session shows the model the
            // same thing it saw live.
            val text = if (scheduledInsert) insertedScheduledEnvelope(prompt.text) else prompt.text
            if (text.isNotEmpty()) {
                if (combinedText.isNotEmpty()) combinedText.append("\n\n")
                combinedText.append(text)
                combinedParts.add(AgentContentPart.Text(text))
            }
        }
        prepared.imageParts.forEachIndexed { idx, part ->
            combinedParts.addModelImage(part, prepared.imageUploadPaths.getOrNull(idx), prepared.imageModelNotes.getOrNull(idx))
        }
        prepared.attachedFilesXml?.let { combinedParts.add(AgentContentPart.Text(it)) }

        // Guard: every queued prompt produced no content (no text, no
        // image). An empty user msg is a 400 from every provider. Skip —
        // the caller falls through to a normal next-turn dispatch so the
        // loop doesn't spin.
        if (combinedParts.isEmpty()) {
            AppLogger.warning(
                TAG_STREAM,
                "injectQueuedPromptsAsNewTurn: ${queued.size} queued prompt(s) produced no content, skipping",
            )
            _promptQueue.value = _promptQueue.value.filterNot { it.id in queuedIds }
            return null
        }

        // Bridge entry into agentHistory ONLY (not persisted). The tail
        // before this call is user(tool_result); without the bridge the
        // queued user message becomes two consecutive user roles and the
        // provider merges them — exactly the regression iOS hit at #579.
        // Empty/whitespace-only bridge text would itself be merged out by
        // some sanitizers; keep a small visible string for parity with iOS.
        // [T-scheduled-preemptive-insert] A scheduled fire is not the user
        // changing course, so it gets its own bridge: a pause to handle the
        // task, then back to the plan. A user follow-up keeps the original
        // wording, which leaves "should the prior task continue?" open.
        // Both texts live in ChatMessage, whose isInternalBridge keeps them
        // out of the chat UI.
        val bridgeText = if (scheduledInsert) ChatMessage.SCHEDULED_INSERT_BRIDGE_TEXT else ChatMessage.INTERNAL_BRIDGE_TEXT

        // Persist the queued user message as its own DB row + append to
        // agentHistory so the next API call carries it.
        val userText = combinedText.toString()
        // [T-android-paste-mediaref] Queued prompts carry markers too.
        //
        // sendMessage hands off to enqueuePrompt whenever a turn is already
        // streaming, and it no longer expands markers before doing so — so
        // without this the queued row would persist a literal `[Pasted#3]` and
        // the model would receive the marker instead of the text.
        val queuedPaste = buildPastedParts(userText, sid)
        val userPartsJson = buildUserPartsJson(
            userText,
            prepared.mediaRefPartsJson,
            prepared.attachedFilesXml,
            bodyPartsJson = queuedPaste?.partsJson,
        )
        val input = com.openminis.app.agent.AgentQueuedUserInput(userPartsJson, userText,
            combinedParts.toList(), prepared.imageParts, queuedPaste?.modelText)
        val newAssistantId = "assistant_${System.currentTimeMillis()}"
        conversation.commitQueued(input, bridgeText) { userEntity ->
            // The runtime invokes this on Main only after durable commit and branch validation.
            _promptQueue.value = _promptQueue.value.filterNot { it.id in queuedIds }
            queuedPaste?.let { paste -> _pastedTexts.value = _pastedTexts.value.filterNot { it.id in paste.consumedIds } }
            // (a) + (b) one emit: build the post-finalize list.
            // Filter the LIVE list, not a snapshot taken before the suspends —
            // see [T-android-midtask-msg-vanishes] above. Any bubble appended
            // while we were persisting (another enqueue, a system notice) is
            // preserved; only the placeholders we are replacing are removed.
            _messages.value = _messages.value.filterNot { m ->
                m.queuedPromptId != null && queuedIds.contains(m.queuedPromptId)
            }
            updateAssistantMessage(
                finishedAssistantId,
                finishedAccumulatedText,
                false,
                finishedAllToolBlocks,
                isAwaitingModelResponse = false,
            )
            // (c) — append the queued user bubble + the new assistant
            // placeholder. Mirrors sendMessage's user-bubble append shape so
            // attachments / images / file chips render the same.
            val queuedUserMsg = ChatMessage(
                id = userEntity.id,
                role = "user",
                content = userText,
                imageUris = prepared.imageUris,
                attachmentNames = prepared.attachmentNames,
                attachmentUris = prepared.nonImageUris,
            )
            val stillRunning = streamJob?.isCancelled != true
            val nextAssistantMsg = ChatMessage(
                id = newAssistantId,
                role = "assistant",
                content = "",
                isStreaming = stillRunning,
                isAwaitingModelResponse = stillRunning,
                thinkingLevel = _thinkingLevel.value,
            )
            if (!stillRunning) _canResume.value = true
            _messages.value = _messages.value + queuedUserMsg + nextAssistantMsg
            // Note: ChatScreen's `lastUserAppendMs` (the trailing-row
            // ScrollPin send-grace window) is updated reactively by
            // ChatScreen's `LaunchedEffect(messages.size)` user-send hook
            // when messages.size grows — appending the queuedUserMsg above
            // bumps the size, so the pin window opens just like a normal
            // send. No direct write needed from here (and we couldn't —
            // `lastUserAppendMs` lives in ChatScreen's composition scope).
        }

        AppLogger.info(
            TAG_STREAM,
            "injectQueuedPromptsAsNewTurn: injected ${queued.size} queued prompt(s) as new turn, " +
                "finishedId=$finishedAssistantId newId=$newAssistantId",
        )
        return InjectedTurn(newAssistantId, queued.flatMap { it.prefill })
    }

    /**
     * Drain queued prompts after an agent loop finishes. Each queued prompt is
     * appended to agentHistory, persisted, and re-runs the agent loop.
     * Mirrors iOS AIChatViewModel.drainQueuedPrompts().
     */
    private suspend fun drainQueuedPrompts(
        provider: LLMProvider,
        systemPrompt: String?,
        fallbackProviders: List<FallbackCandidate>,
        fallbackStrategy: com.openminis.app.data.model.FallbackStrategy,
    ) {
        if (_promptQueue.value.isEmpty()) return
        val sid = ensureSession()
        val conversation = com.openminis.app.agent.AgentConversationJournal(
            com.openminis.app.agent.AgentJournalWriter(chatRepository, sid), agentHistory) { activeSessionId }
        while (_promptQueue.value.isNotEmpty()) {
            conversation.checkBranch()
            // [T-android-mute-reset-on-drain] A drained batch is a new turn.
            // Stopping a sub agent mutes the parent's CURRENT turn (a sibling
            // that finishes afterwards must not re-wake it); the mute used to
            // be lifted only by the public sendMessage, so a prompt drained
            // after a Stop ran muted and any sub agent it started delivered
            // its result to nobody. iOS f1f4f23ba resets it here too.
            com.openminis.app.agent.jobs.AgentJobRegistry.clearDelegationMute(activeSessionId)
            // [T-scheduled-preemptive-insert] One scheduled fire per round,
            // never merged; everything else merges as before.
            val queued = QueuedPromptBatching.nextDrainBatch(_promptQueue.value)
            // [T-queue-dequeue-after-persist] Same as injectQueuedPromptsAsNewTurn:
            // the prompts leave the queue (and their bubbles flip to "sent") only
            // after appendMessage below, so a Stop during the suspending persist
            // leaves them queued for cancelStream's resumeQueueAfterCancel instead
            // of losing them.
            Log.i(TAG, "📨[DRAIN] Draining ${queued.size} queued prompt(s): " +
                queued.joinToString(", ") { "${it.id}=\"${it.text.take(20)}...\"" })
            val queuedIds = queued.map { it.id }.toSet()

            // Build a combined user message (text + images from all queued prompts).
            // Persist as a single row.
            val combinedAttachments = queued.flatMap { it.attachments }
            val prepared = prepareUserAttachments(combinedAttachments, sid)
            conversation.checkBranch()

            // T132: same shape as sendMessage — caption(s) first, then for each
            // image emit "[attached image: <path>]" + ImageData, finally the
            // <user-attached-files> XML. Keeps caption adjacent to image and
            // lets the agent re-read the file via read.
            val combinedParts = mutableListOf<AgentContentPart>()
            val combinedText = StringBuilder()
            for (prompt in queued) {
                if (prompt.text.isNotEmpty()) {
                    if (combinedText.isNotEmpty()) combinedText.append("\n\n")
                    combinedText.append(prompt.text)
                    combinedParts.add(AgentContentPart.Text(prompt.text))
                }
            }
            prepared.imageParts.forEachIndexed { idx, part ->
                combinedParts.addModelImage(part, prepared.imageUploadPaths.getOrNull(idx), prepared.imageModelNotes.getOrNull(idx))
            }
            prepared.attachedFilesXml?.let { combinedParts.add(AgentContentPart.Text(it)) }

            val userText = combinedText.toString()
            // [T-android-paste-mediaref] Same marker handling as the mid-loop
            // inject path above — see the note there for why queued prompts
            // need it at all.
            val drainPaste = buildPastedParts(userText, sid)
            val userPartsJson = buildUserPartsJson(
                userText,
                prepared.mediaRefPartsJson,
                prepared.attachedFilesXml,
                bodyPartsJson = drainPaste?.partsJson,
            )
            val input = com.openminis.app.agent.AgentQueuedUserInput(userPartsJson, userText,
                combinedParts.toList(), prepared.imageParts, drainPaste?.modelText)
            conversation.commitQueued(input) { drainedRow ->
                _promptQueue.value = _promptQueue.value.filterNot { it.id in queuedIds }
                drainPaste?.let { paste -> _pastedTexts.value = _pastedTexts.value.filterNot { it.id in paste.consumedIds } }
                if (streamJob?.isCancelled == true) _canResume.value = true
                // Every merged placeholder points at the same committed row for retry/delete/edit.
                _messages.value = _messages.value.map { m ->
                    if (m.queuedPromptId != null && queuedIds.contains(m.queuedPromptId)) {
                        m.copy(isQueued = false, queuedPromptId = null, sourceDbIds = listOf(drainedRow.id))
                    } else m
                }
            }

            try {
                // A previous loop may have applied a pick/fallback; queued work starts on that binding.
                val (drainProvider, drainPrompt, drainFallbacks) = withContext(Dispatchers.Main) {
                    conversation.checkBranch()
                    val selected = currentProvider ?: provider
                    Triple(selected, if (selected === provider) systemPrompt else systemPromptFor(selected, systemPrompt),
                        if (selected === provider) fallbackProviders else buildFallbackProviders(selected))
                }
                runAgentLoop(
                    provider = drainProvider,
                    systemPrompt = drainPrompt,
                    fallbackProviders = drainFallbacks,
                    fallbackStrategy = fallbackStrategy,
                    // [T-scheduled-tool-prefill] A queued scheduled prompt keeps
                    // its prefilled calls: they run as this loop's first turn.
                    prefill = queued.flatMap { it.prefill },
                )
            } catch (e: CancellationException) {
                Log.d(TAG, "Agent loop (queued-drain) cancelled")
                // Cancel mid-drain: cancelStream() will check _promptQueue
                // and call resumeQueueAfterCancel() if anything's still pending,
                // so just propagate.
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Agent loop (queued-drain) error", e)
                reportTurnError(e.message ?: "Unknown error")
                break
            }
        }
    }

    fun sendMessage(text: String) {
        // [T-android-stop-sibling-subagent] New work in this session lifts the
        // stop: delegations from here on may drive the turn again.
        com.openminis.app.agent.jobs.AgentJobRegistry.clearDelegationMute(activeSessionId)
        sendMessage(text, skipContextCheck = false, headless = false)
    }

    /**
     * [T-android-submit-outcome] What happened to a prompt handed to the send
     * funnel. The Android equivalent of iOS P0's `ProgrammaticSubmitOutcome`,
     * deliberately NOT a separate entry point: Android's funnel already checks
     * `_isStreaming` and hands off in one synchronous Main-thread step, so the
     * iOS check-then-act window does not exist here (EnqueuePromptIdleWindowTest,
     * 465 prompts / 0 lost on device). What Android lacked was the TRUTH coming
     * back — `sendMessage` returned Unit through six silent exits, and every
     * headless caller reported "Running" for prompts that were queued,
     * refused, or parked behind a UI dialog nobody would ever answer.
     */
    sealed class SubmitOutcome {
        /** No loop was running; a new turn started. */
        object Sent : SubmitOutcome()
        /** A loop was running; the prompt is queued and drains when it ends. */
        object Queued : SubmitOutcome()
        /** Auto-compact is on and the context was near capacity: compaction
         *  runs first, then the prompt is sent. */
        object Compacting : SubmitOutcome()
        /** Refused. `reason` is a stable snake_case token for scripts/RPC. */
        data class Rejected(val reason: String) : SubmitOutcome()
    }

    /**
     * [T-android-submit-outcome] Headless entry point for CLI / RPC / scheduled
     * jobs. Same funnel as [sendMessage] — one code path, one set of guards —
     * but (a) returns the outcome instead of swallowing it, and (b) never parks
     * the prompt behind the "compact before sending?" dialog: with no user to
     * answer it, that dialog would hold the text forever while the caller was
     * told the prompt was running. A headless prompt that trips that threshold
     * is rejected with `context_near_capacity` so the caller can compact and
     * retry, or surface the refusal.
     */
    internal fun submitPrompt(
        text: String,
        // [T-scheduled-tool-prefill] Tool calls executed as the loop's first
        // turn, before any model request (a scheduled task's prefilled call).
        prefill: List<com.openminis.app.scheduled.PrefilledToolCall> = emptyList(),
    ): SubmitOutcome =
        sendMessage(text, skipContextCheck = false, headless = true, prefill = prefill)

    /**
     * @param skipContextCheck set by the pre-send context dialog's own actions,
     *   which have already made the compact decision. Without it the re-entrant
     *   send would re-evaluate the same (still stale until the next usage
     *   chunk) token count and pop the dialog again — iOS guards the identical
     *   re-entry with `skipCompactCheck`.
     */
    private fun sendMessage(
        text: String,
        skipContextCheck: Boolean,
        headless: Boolean = false,
        prefill: List<com.openminis.app.scheduled.PrefilledToolCall> = emptyList(),
    ): SubmitOutcome {
        // [T-android-mute-reset-on-drain] Headless submits (submitPrompt, a
        // scheduled fire into an idle chat) reach this funnel without the
        // public sendMessage, and are new work too.
        com.openminis.app.agent.jobs.AgentJobRegistry.clearDelegationMute(activeSessionId)
        // [T-android-paste-mediaref] `[Pasted#N]` markers are NOT expanded here
        // any more.
        //
        // They used to be: this funnel substituted the full text inline, so the
        // persisted message held one enormous `text` part. That is the same
        // shape that made huge tool results freeze the app — every time the
        // bubble scrolled into view, TextKit had to lay out the whole block on
        // the main thread. The markers now survive down to the parts-building
        // step below, where each becomes its own `text/plain` mediaRef; the full
        // content is re-attached to the REQUEST from disk (see toLLMMessage and
        // the fresh-send contentParts), so the model still sees everything while
        // the bubble stays small.
        //
        // The buffer is cleared where the mediaRefs are actually written, not
        // here — clearing at this point would strip the content out from under
        // a send that then bails on the context-check paths below.
        val trimmed = text.trim()
        // Queue until the stopped run's journal is settled as well as while streaming.
        if (_isStreaming.value || cancellationPending) {
            // enqueuePrompt has its own blank/attachment guard and returns Unit;
            // the queue length is the acceptance signal (iOS does the same).
            val before = _promptQueue.value.size
            // [T-p2-gentle-injection] A headless send is PROGRAMMATIC: it waits
            // for the running loop to finish instead of interrupting it.
            enqueuePrompt(text, if (headless) QueuedPromptOrigin.PROGRAMMATIC else QueuedPromptOrigin.USER, prefill)
            if (cancellationPending) resumeQueueAfterCancel()
            return if (_promptQueue.value.size == before + 1) SubmitOutcome.Queued
            else SubmitOutcome.Rejected("enqueue_declined")
        }
        // T180: allow attachments-only sends (no caption). Mirrors iOS, where
        // an empty text + non-empty attachments still produces a valid user
        // message. Without this an image-only "look at this" send dropped.
        if (trimmed.isBlank() && _attachments.value.isEmpty()) return SubmitOutcome.Rejected("empty_prompt")
        // [T-android-image-input-preflight] Refuse to send images to a model
        // that does not accept them — the request would only come back as an
        // upstream 400 (or have its pixels silently swapped for a placeholder).
        // The composer text is put back because ChatScreen clears it before
        // calling in; attachments are still in place (cleared further down).
        if (_isCompacting.value) {
            appendSystemInfo(
                text = "Wait for the current compact to finish before sending.",
                iconKind = "compact",
            )
            return SubmitOutcome.Rejected("session_compacting")
        }
        // Context pressure check. Unlike before, needsCompact now HOLDS the
        // send: either compact silently (auto-compact on) or ask first. The
        // whole point is that the request which tripped the threshold must not
        // be the one that goes out over-length.
        if (!skipContextCheck) {
            when (checkContextBeforeSend()) {
                PreSendContextAction.PROCEED -> {}
                PreSendContextAction.COMPACT_THEN_SEND -> {
                    pendingSendText = text
                    pendingSendPrefill = prefill
                    pendingSendHeadless = headless
                    // [T-android-programmatic-prompt-keeps-draft] Only the
                    // user's own send owns the composer. A headless prompt (a
                    // scheduled fire, a sub-agent callback, the sessions CLI)
                    // carries its text in `text`; clearing here erased whatever
                    // the user was typing whenever such a prompt tripped the
                    // auto-compact threshold (iOS 2a06de66a).
                    if (!headless) _inputText.value = ""
                    compactAndSendPending()
                    return SubmitOutcome.Compacting
                }
                PreSendContextAction.ASK_USER -> {
                    // [T-android-submit-outcome] No user behind a headless
                    // prompt → nobody to answer the dialog. Refuse loudly
                    // rather than park the text and report it as running.
                    if (headless) return SubmitOutcome.Rejected("context_near_capacity")
                    // Park the text on the VM (not the composer) so the dialog
                    // owns it; cancelCompactBeforeSend puts it back.
                    pendingSendText = text
                    pendingSendHeadless = false
                    _inputText.value = ""
                    _showCompactBeforeSendPrompt.value = true
                    return SubmitOutcome.Rejected("awaiting_user_compact_decision")
                }
            }
        }
        // A fresh send supersedes any pending resume — mirror iOS which clears
        // canResume at the top of send().
        _canResume.value = false
        // T185: clear the share-injected flag the moment the user actually
        // sends. Without this, the "Move to…" capsule (gated on
        // hasInjectedShareContent) keeps floating over the user-message row
        // after the share content has been committed — it then visually
        // collides with the user-attachment chips, which renders as the
        // "image attachment shows up as Move to" symptom in T185. Mirrors
        // iOS AIChatView.swift:2255 (`hasInjectedShareContent = false`
        // inside the send button's tap closure).
        if (_hasInjectedShareContent.value) _hasInjectedShareContent.value = false

        val initialProvider = currentProvider
        if (initialProvider == null) {
            _error.value = "No provider configured"
            return SubmitOutcome.Rejected("no_provider")
        }
        var provider: LLMProvider = initialProvider

        _error.value = null

        val currentAttachments = _attachments.value
        clearAttachments()

        // T145: claim _isStreaming synchronously so a rapid second tap can't
        // slip past the entry guard during DB/OAuth setup. See retryFromMessage.
        AppLogger.info(TAG_STREAM, "send _isStreaming=true (sync, sid=$activeSessionId)")
        _isStreaming.value = true

        // [T-android-thinking-indicator-linger] Invariant sweep: a fresh send
        // only reaches here when no turn is streaming (the _isStreaming guard
        // at the top routes mid-stream sends to enqueuePrompt). So any residual
        // _streamingById entry is an orphan stranded by a prior turn that
        // exited without draining it (e.g. a late delta re-added the entry
        // after finalizeAtTurnLimit / cancel cleared it). mergeStreamingOverlay
        // forces isStreaming=true on any message holding such an entry, so an
        // orphan would render a second "thinking" row alongside the new turn's.
        // Flush them into the canonical messages (isStreaming=false) before the
        // new streaming message is created — no two messages ever stream at once.
        if (_streamingById.value.isNotEmpty()) {
            AppLogger.warning(TAG_STREAM, "send: sweeping ${_streamingById.value.size} orphan streaming delta(s) before new turn")
            flushAllStreamingDeltas()
        }

        // T187: when the user is editing a previous message, truncate the
        // conversation from that message (inclusive) before persisting the
        // edited text as a fresh user turn. Snapshot + clear the id here so
        // any error in the truncate path doesn't leave the composer stuck
        // in edit mode.
        val editingId = _editingMessageId.value
        if (editingId != null) _editingMessageId.value = null

        val sendOwner = activeSessionId
        val sendEntry = _activeEntryId.value
        var sendTarget = sendOwner
        var imageCount = 0
        var preparedSystemPrompt: String? = null
        fun checkSendBranch(owner: String) {
            if (activeSessionId != owner) throw CancellationException("send branch changed during preparation")
        }
        launchAgentRun(viewModelScope, "send", bypassSlot = helperConfig != null,
            failure = { error ->
                val imageHint = if (ImageInputPreflight.isLikelyImageRejection(error, imageCount)) {
                    "\n" + context.getString(R.string.image_input_rejected_hint, imageCount)
                } else ""
                setInlineError((error.message ?: "Unknown error") + imageHint)
            }, prepareSession = {
            checkSendBranch(sendOwner)
            // [T-android-send-before-load] Never write this turn into the
            // transcript while loadSession is still reading it. loadSession
            // snapshots the DB, then REPLACES agentHistory and _messages with
            // that snapshot; a send landing in between (a session opened and
            // prompted at once: debug prompts, scheduled fires, sub agents, a
            // quick tap on a long chat) had its user message wiped from the
            // screen - the chat showed replies with no question - or from
            // agentHistory, so the model never saw it. Title generation then
            // found no user message and the chat stayed "New Chat". Safe mode
            // skips the load entirely, so there is nothing to wait for there.
            if (!sessionLoaded.value && !com.openminis.app.crash.CrashFrequencyDetector.isSafeMode()) {
                AppLogger.info(TAG_STREAM, "send waiting for session load (sid=${this@ChatViewModel.activeSessionId})")
                sessionLoaded.first { it }
            }
            checkSendBranch(sendOwner)
            // Ensure session exists in DB (creates on first message for draft sessions)
            val promoted = ensureSession()
            checkSendBranch(promoted)
            sendTarget = promoted
            promoted
        }, prepare = {
            val activeSessionId = sendTarget
            checkSendBranch(activeSessionId)
            val prepared = prepareUserAttachments(currentAttachments, activeSessionId)

            // [T-android-paste-mediaref] Fold `[Pasted#N]` markers out to disk
            // BEFORE persisting, so the stored message carries a mediaRef per
            // paste instead of one huge text part.
            val pasted = buildPastedParts(trimmed, activeSessionId)

            // Save user message — text + persisted mediaRef parts so images survive
            // a session reload (T128). Non-image attachments still only contribute
            // their name (rendered as a file tile) and are not persisted.
            val userPartsJson = buildUserPartsJson(
                trimmed,
                prepared.mediaRefPartsJson,
                prepared.attachedFilesXml,
                bodyPartsJson = pasted?.partsJson,
            )
            val imageParts = prepared.imageParts
            imageCount = imageParts.size

            // T132: build the user contentParts in iOS order — caption first
            // (only if non-empty), then per image emit
            //   text("[attached image: /var/minis/attachments/uploads/<f>]")
            //   ImageData(<bytes>, <mime>)
            // so the caption sits adjacent to the image in the wire payload,
            // and the agent's read tool can resolve the same path back
            // to bytes. Trailing <user-attached-files> XML block lets the
            // model see filenames/sizes without needing tool calls.
            // [T-android-paste-mediaref] The MODEL gets the fully expanded body
            // even though the bubble and the DB row do not. This is the whole
            // point of the split: local rendering stays cheap, the prompt is
            // unchanged from what it used to be.
            //
            // On later turns the same expansion is rebuilt from disk by
            // toLLMMessage's mediaRef branch, so history replay (retry, rerun,
            // session reload, compaction) sees the identical text.
            val modelBody = pasted?.modelText ?: trimmed

            val userContentParts = mutableListOf<AgentContentPart>()
            if (modelBody.isNotEmpty()) userContentParts.add(AgentContentPart.Text(modelBody))
            imageParts.forEachIndexed { idx, part ->
                userContentParts.addModelImage(part, prepared.imageUploadPaths.getOrNull(idx), prepared.imageModelNotes.getOrNull(idx))
            }
            prepared.attachedFilesXml?.let { userContentParts.add(AgentContentPart.Text(it)) }

            val journal = com.openminis.app.agent.AgentConversationJournal(
                com.openminis.app.agent.AgentJournalWriter(chatRepository, activeSessionId), agentHistory,
                currentSession = { this@ChatViewModel.activeSessionId })
            val input = com.openminis.app.agent.AgentQueuedUserInput(userPartsJson, modelBody, userContentParts, imageParts)
            val accepted: (MessageEntity) -> Unit = { persistedUser ->
                if (pasted != null) {
                    // Safe to clear now: the content is on disk and the parts JSON
                    // below references it, so nothing depends on the buffer any more.
                    _pastedTexts.value = _pastedTexts.value.filterNot { it.id in pasted.consumedIds }
                }
                val userMsg = ChatMessage(
                    id = persistedUser.id,
                    role = "user",
                    // The bubble shows the SHORT body with markers removed; the
                    // pasted blocks appear beside it as file cards (below).
                    // Strip the consumed markers from the visible caption — the
                    // file cards now stand for them. Only ids that actually
                    // resolved are removed, so a literal the user typed for an
                    // unknown id survives as text, matching how it is persisted.
                    content = pasted?.let { p ->
                        p.consumedIds.fold(trimmed) { acc, id ->
                            acc.replace(PastedText.placeholderFor(id), "")
                        }.trim()
                    } ?: trimmed,
                    imageUris = prepared.imageUris,
                    // Pasted blocks render exactly like attached documents: append
                    // them to the non-image suffix, preserving the
                    // images-first/files-after ordering that
                    // ChatMessage.attachmentNames depends on.
                    attachmentNames = prepared.attachmentNames + (pasted?.uiNames ?: emptyList()),
                    attachmentUris = prepared.nonImageUris + (pasted?.uiUris ?: emptyList()),
                )
                _messages.value = _messages.value + userMsg
            }
            if (editingId != null) commitEditedUser(editingId, activeSessionId, input, accepted)
            else journal.commitQueued(input, accepted = accepted)

            checkSendBranch(activeSessionId)
            provider = providerPreparation.prepare(provider, sendEntry, activeSessionId) { expected, refreshed ->
                if (this@ChatViewModel.activeSessionId == activeSessionId && currentProvider === expected) currentProvider = refreshed
            }
            checkSendBranch(activeSessionId)
            preparedSystemPrompt = providerPreparation.prompt(provider, buildSystemPrompt())
        }) {
            val strategy = com.openminis.app.data.model.FallbackStrategy.default
            val fallbacks = buildFallbackProviders(provider)
            runAgentLoop(provider, preparedSystemPrompt, fallbacks, strategy, prefill)
            drainQueuedPrompts(provider, preparedSystemPrompt, fallbacks, strategy)
        }
        return SubmitOutcome.Sent
    }

    /** Set error inline on the last assistant message (iOS: message.error).
     *
     *  Also clears [ChatMessage.isAwaitingModelResponse] — without this, an
     *  exception thrown after a tool turn (which sets isAwaitingModelResponse=
     *  true at runAgentLoop ~4015) leaves the "Minis is thinking" indicator
     *  on screen even though streaming is over. The flag is per-message and
     *  is not implicitly cleared by isStreaming=false. */
    private fun setInlineError(errorText: String) {
        // [T-error-persist-android] Never let an empty/blank error string reach
        // the banner. The UI gate is `message.error?.let { … }` — a non-null ""
        // would render an EMPTY error banner, and (now that errors persist) it
        // would stick across reloads. An exception with a blank `message`
        // (`e.message ?: "Unknown error"` only guards null, not "") is the
        // realistic source. Coalesce to a generic non-empty message.
        val safeError = errorText.ifBlank { context.getString(R.string.error_empty_response_generic) }
        // T-streaming-side-channel: before mutating the canonical message,
        // drain any in-flight streaming delta so the error frame carries
        // the actual accumulated content (otherwise the user sees content
        // snap back to a pre-stream prefix when the error banner appears).
        flushAllStreamingDeltas()
        val msgs = _messages.value.toMutableList()
        val lastAssistantIdx = msgs.indexOfLast { it.role == "assistant" }
        if (lastAssistantIdx >= 0) {
            val msg = msgs[lastAssistantIdx]
            msgs[lastAssistantIdx] = msg.copy(
                error = safeError,
                isStreaming = false,
                isAwaitingModelResponse = false,
            )
            _messages.value = msgs
        } else {
            // No assistant message yet — fall back to top-level error
            _error.value = safeError
        }
    }

    /**
     * Show a transient error on the last assistant message while keeping isStreaming=true
     * so the "thinking" indicator and streaming UI stay intact during auto-retry countdowns.
     * Mirrors iOS streamWithAutoRetry: `chatMessage?.error = desc` without dropping the loop.
     */
    private fun setTransientInlineError(errorText: String) {
        val msgs = _messages.value.toMutableList()
        val lastAssistantIdx = msgs.indexOfLast { it.role == "assistant" }
        if (lastAssistantIdx < 0) return
        val msg = msgs[lastAssistantIdx]
        msgs[lastAssistantIdx] = msg.copy(error = errorText)
        _messages.value = msgs
    }

    /** Clear any inline error on the last assistant message (used after successful retry). */
    private fun clearInlineError() {
        val msgs = _messages.value.toMutableList()
        val lastAssistantIdx = msgs.indexOfLast { it.role == "assistant" }
        if (lastAssistantIdx < 0) return
        val msg = msgs[lastAssistantIdx]
        if (msg.error == null) return
        msgs[lastAssistantIdx] = msg.copy(error = null)
        _messages.value = msgs
    }

    private val errorJournal by lazy { com.openminis.app.agent.AgentErrorJournal(chatRepository) { activeSessionId } }

    private suspend fun reportTurnError(errorText: String) {
        val owner = activeSessionId
        setInlineError(errorText)
        errorJournal.terminal(owner, _messages.value.lastOrNull { it.role == "assistant" }?.error ?: errorText)
    }

    /** Retry preserves completed tool cards; the runtime retires only the exact failed response.
     * Paid usage survives as a hidden receipt, and preparation is owned by the cancellable run. */
    fun retryLast() {
        if (afterCancelledRun { retryLast() }) return
        if (_isStreaming.value) return
        val initialProvider = currentProvider ?: return
        val owner = activeSessionId
        val entry = _activeEntryId.value
        com.openminis.app.agent.jobs.AgentJobRegistry.clearDelegationMute(owner)
        flushAllStreamingDeltas()
        val msgs = _messages.value.toMutableList()
        val index = msgs.indexOfLast { it.role == "assistant" }
        if (index < 0) return
        _forceScrollToBottom.tryEmit(Unit)
        msgs[index] = msgs[index].copy(error = null, isStreaming = false, isAwaitingModelResponse = false,
            toolBlocks = msgs[index].toolBlocks.filter { it.toolStatus !in IN_FLIGHT_TOOL_STATUSES })
        _messages.value = msgs
        _error.value = null
        _isStreaming.value = true
        var provider = initialProvider
        var prompt: String? = null
        fun checkOwner() { if (activeSessionId != owner) throw CancellationException("retry branch changed") }
        launchAgentRun(viewModelScope, "retryLast", prepareSession = {
            checkOwner()
            com.openminis.app.agent.AgentRetryJournal(chatRepository, owner, agentHistory) { activeSessionId }.prepare()
            checkOwner()
            provider = providerPreparation.prepare(provider, entry, owner) { expected, refreshed ->
                if (activeSessionId == owner && currentProvider === expected) currentProvider = refreshed
            }
            checkOwner()
            prompt = providerPreparation.prompt(provider, buildSystemPrompt())
            owner
        }) {
            val strategy = com.openminis.app.data.model.FallbackStrategy.default
            val fallbacks = buildFallbackProviders(provider)
            runAgentLoop(provider, prompt, fallbacks, strategy)
            drainQueuedPrompts(provider, prompt, fallbacks, strategy)
        }
    }

    private fun unwrapFlowException(e: Throwable): Throwable {
        var cause: Throwable? = e
        while (cause != null) {
            if (cause is com.openminis.app.data.model.LLMError) return cause
            cause = cause.cause
        }
        return e
    }

    private val contextOffloader by lazy {
        com.openminis.app.agent.AgentContextOffloader(context, agentHistory, historyProjection,
            COMPACT_KEEP_RECENT_USER_TURNS) { activeSessionId }
    }

    private fun scriptedTurnFor(
        prefill: List<com.openminis.app.scheduled.PrefilledToolCall>,
    ): com.openminis.app.scheduled.ScriptedToolTurn? {
        if (prefill.isEmpty()) return null
        val available = callableAgentTools.map { it.name }.toSet()
        val turn = com.openminis.app.scheduled.ScriptedToolTurn.from(prefill, available)
        if (turn == null || turn.calls.size < prefill.size) {
            AppLogger.warning(
                TAG_STREAM,
                "[ScheduledPrefill] dropped ${prefill.size - (turn?.calls?.size ?: 0)} of ${prefill.size} " +
                    "prefilled call(s) [${prefill.joinToString { it.toolName }}] — tool unavailable or invalid; " +
                    "the model decides for itself",
            )
        }
        return turn
    }

    private suspend fun runAgentLoop(
        provider: LLMProvider,
        systemPrompt: String?,
        fallbackProviders: List<FallbackCandidate> = emptyList(),
        fallbackStrategy: com.openminis.app.data.model.FallbackStrategy = com.openminis.app.data.model.FallbackStrategy.default,
        // Tool calls already decided for this run's first turn.
        prefill: List<com.openminis.app.scheduled.PrefilledToolCall> = emptyList(),
        resumePrevious: Boolean = false,
    ) {
        val runJournal = com.openminis.app.agent.AgentJournalWriter(chatRepository, activeSessionId)
        val initialSelection = AgentConversationRuntime.Selection(provider, fallbackProviders, fallbackStrategy,
            _activeEntryId.value, modelSnapshotFor(provider.model, _activeEntryId.value))
        AppLogger.info(TAG_STREAM, "runAgentLoop ENTER provider=${provider.javaClass.simpleName} historySize=${agentHistory.size}")
        // [T-android-mem-probe-trust] Send-path context shape. The existing
        // `messages-shape` probe only runs on session LOAD, so the 2026-08-15
        // log described the session as it was opened, never as it was sent —
        // and the send is where the memory goes. `historySize` alone says
        // nothing about payload: 17 messages carrying a 100 KB tool_result each
        // is a very different request from 1500 short ones. Logged once per
        // agent loop (not per turn) to stay cheap; the walk is O(parts) over
        // already-resident strings.
        runCatching {
            var chars = 0L
            var maxOne = 0
            var toolResults = 0
            var images = 0
            var imageBytes = 0L
            var audioChars = 0L
            var biggestRole = ""
            for (m in agentHistory) {
                var perMsg = m.content.length
                for (p in m.contentParts) {
                    when (p) {
                        is AgentContentPart.ToolResult -> {
                            perMsg += p.content.length
                            toolResults++
                            // Inline image bytes never reach the char count, so
                            // track them separately — an image-heavy request is
                            // a different failure shape from a text-heavy one.
                            p.imageData?.let { images++; imageBytes += it.size }
                        }
                        is AgentContentPart.Text -> perMsg += p.text.length
                        is AgentContentPart.ImageData -> { images++; imageBytes += p.data.size }
                        else -> {}
                    }
                }
                for (a in m.audioParts) audioChars += a.base64Data.length
                images += m.imageParts.size
                chars += perMsg
                if (perMsg > maxOne) { maxOne = perMsg; biggestRole = m.role.name }
            }
            AppLogger.info(
                TAG_STREAM,
                "[CtxShape] historySize=${agentHistory.size} totalChars=$chars " +
                    "maxMsgChars=$maxOne maxMsgRole=$biggestRole toolResultParts=$toolResults " +
                    "imageParts=$images imageBytes=$imageBytes audioB64Chars=$audioChars " +
                    "approxTokens=${chars / 4} " +
                    "${com.openminis.app.diagnostics.MemorySnapshot.capture().toLogString()}",
            )
        }
        // [T-android-queued-message-interrupt-on-toolclose] `assistantId` is
        // normally a single message id for the whole agent loop (iOS-parity:
        // multiple tool/text turns folded into one bubble). It is reassigned
        // ONLY when a queued mid-loop prompt is injected as a new turn: the
        // just-finished bubble is sealed and a fresh assistantId starts so the
        // queued user message renders BETWEEN them. `allToolBlocks` and
        // `accumulatedText` are also reset at that point so the new bubble
        // starts empty and `buildTurnParts(allToolBlocks, turnStartBlockIndex,
        // toolInputMap)` continues to slice only the current turn's blocks
        // (turnStartBlockIndex is captured at iteration start to 0 after reset).
        var assistantId = "assistant_${System.currentTimeMillis()}"
        // [T-android-concurrent-tools] Synchronized because the tool-dispatch
        // loop now runs its calls concurrently on Dispatchers.IO, and each of
        // them updates ITS OWN element here (located by tool_use id, set by
        // index — no slot is shared and the list is never resized during the
        // batch). Distinct indices make the writes non-conflicting, but a plain
        // ArrayList still offers no happens-before edge between a write on one
        // IO thread and the read that `updateAssistantMessage` performs on
        // another, so a block could render with a stale status. The wrapper is
        // the cheap, obviously-correct fix at this list's write rate; the
        // alternative (hand-rolled barriers around every mutation site) would
        // be easy to get subtly wrong for no measurable gain.
        val allToolBlocks = java.util.Collections.synchronizedList(mutableListOf<AssistantBlock>())
        var accumulatedText = ""
        val turnCap = helperConfig?.maxTurns ?: MAX_AGENT_TURNS
        val runtime = AgentConversationRuntime(runJournal, agentHistory, { activeSessionId }, initialSelection,
            systemPrompt, turnCap, helperConfig != null, contextPlanner, cancellationCoordinator,
            compactionSummarizer, toolLoopDetector, TOOL_INPUT_CHUNK_RING_MAX, CANCELLED_MARKER, scriptedTurnFor(prefill), resumePrevious,
            subagentResults = subagentJournal, personaReminder = personaReminder)
        val conversationJournal = runtime.conversation


        // Add placeholder assistant message (once). Mark as awaiting so the
        // "Minis is thinking" indicator shows during the initial request gap
        // before the first stream chunk arrives. Mirrors iOS isAwaitingModelResponse.
        // T300: snapshot the user's current thinking level at message
        // creation so the renderer can hide Deep Thinking blocks for
        // turns the user explicitly asked not to surface, even when a
        // forced-reasoning model still streams reasoning_content.
        val turnThinkingLevel = _thinkingLevel.value
        // [T-android-readaloud-stop-stale] One-shot per REPLY (not per turn):
        // the first text delta stops any Read Aloud still playing from the
        // previous reply. Scoped outside the turn loop so a tool-loop reply
        // that emits text across several turns doesn't re-fire it and cut off
        // its own speech mid-sentence.
        var didStopStaleReadAloud = false
        val outcome = runtime.run(
            models = AgentConversationRuntime.Models(
                begin = { launched -> withContext(Dispatchers.Main) {
                    conversationJournal.checkBranch()
                    if (currentProvider === launched) pendingModelSwitch = false
                    currentProvider
                } },
                takeSelection = { running -> withContext(Dispatchers.Main) {
                    conversationJournal.checkBranch()
                    val chosen = currentProvider
                    if (chosen != null && modelSwitchToApply(pendingModelSwitch, chosen, running)) {
                        pendingModelSwitch = false
                        AgentConversationRuntime.Selection(chosen, buildFallbackProviders(chosen),
                            com.openminis.app.data.model.FallbackStrategy.default, _activeEntryId.value,
                            modelSnapshotFor(chosen.model, _activeEntryId.value))
                    } else null
                } }, identity = { it.entryId }, candidates = ::buildFallbackProviders,
                provider = { it.provider }, attribution = { modelSnapshotFor(it.provider.model, it.entryId) },
                prompt = ::systemPromptFor,
                bindFallback = { expected, candidate, realChange -> withContext(Dispatchers.Main) {
                    conversationJournal.checkBranch()
                    if (!fallbackMayRebindSession(currentProvider, expected)) false else {
                        currentProvider = candidate.provider
                        _modelName.value = candidate.provider.model.displayName
                        val entry = providerRepository.config.value.modelEntries.find { it.id == candidate.entryId }
                        if (entry != null) {
                            _activeEntryId.value = entry.id
                            currentModel = entry.model
                            providerRepository.instance(entry.providerInstanceId)?.let {
                                _providerName.value = it.label.ifEmpty { entry.model.provider }
                            }
                            persistBinding("""{"type":"entry","entryId":"${entry.id}"}""")
                        }
                        if (realChange) _fallbackTrigger.value++
                        true
                    }
                } }),
            context = AgentConversationRuntime.Context(
                snapshotFacts = ::runtimeContextFacts,
                effectiveHistory = ::effectiveAgentHistory,
                tools = { callableAgentTools }, window = ::effectiveContextWindowTokens,
                offload = { window, tokens -> contextOffloader.offload(runJournal.sessionId, window, tokens,
                    _compactSummary.value, _cachedLatestMarker,
                    currentContextPolicy()?.takeIf { it.second == window }?.first, contextCalibrationRatio(runtime.provider.model.id)) },
                measurement = ::contextMeasurement, measured = ::measureOutboundContextTokens,
                policy = ::currentContextPolicy, compact = ::awaitCompaction,
                compacting = { tokens, window ->
                    appendSystemInfo("Context is filling up ($tokens / $window tokens) — compacting to continue.", "compact")
                }),
            wrapUpRequested = { helperWrapUpRequested },
            steers = { synchronized(pendingSteerMessages) {
                pendingSteerMessages.toList().also { pendingSteerMessages.clear() }
            } },
            presentation = AgentConversationRuntime.Presentation(
                started = { withContext(Dispatchers.Main) {
                    conversationJournal.checkBranch()
                    _messages.value = _messages.value + ChatMessage(
                        id = assistantId, role = "assistant", content = "", isStreaming = true,
                        isAwaitingModelResponse = true, thinkingLevel = turnThinkingLevel)
                    assistantId
                } },
                steer = { steer, frame ->
                    // Durable steer is already committed; display it above the reply it steers.
                    val steerUi = ChatMessage(
                        id = steer.dbMessageId ?: "steer_${System.currentTimeMillis()}",
                        role = "user",
                        content = steer.content,
                    )
                    // Insert BEFORE the assistant bubble this turn streams into,
                    // not at the end. runAgentLoop appends that bubble before the
                    // loop starts, so `messages` already ends with it — appending
                    // would put the correction visually AFTER the reply it was
                    // meant to steer, reading as though it arrived too late to
                    // matter. It belongs where a person's interjection would go:
                    // above the turn that answers it.
                    withContext(Dispatchers.Main) {
                        conversationJournal.checkBranch()
                        val cur = _messages.value
                        val at = cur.indexOfLast { it.id == assistantId }
                        _messages.value = if (at >= 0) {
                            cur.subList(0, at) + steerUi + cur.subList(at, cur.size)
                        } else {
                            cur + steerUi
                        }
                    }
                    AppLogger.info(TAG, "[subagent] steer delivered turn=${frame.index + 1}/$turnCap")
                },

                compacted = {
                    // [T-android-inloop-compact-divider-order / GH#235] Seal the
                    // bubble this run has been writing into and continue in a
                    // FRESH one below the divider.
                    //
                    // `assistantId` is normally ONE bubble for the whole agent
                    // loop, appended before the loop starts. compactAll() then
                    // tail-appends the "N messages compacted" divider, which
                    // lands AFTER that still-streaming bubble — and the loop
                    // `continue`s and keeps appending thinking / tool blocks
                    // into it. So every token produced after the compaction
                    // rendered ABOVE the divider, reading as if fresh output had
                    // been filed into already-compacted history. That is the
                    // reported symptom.
                    //
                    // Starting a new bubble rather than moving the divider is
                    // plan B, matching the iOS fix (e65540b69) so both platforms
                    // carry the same semantics: output produced BEFORE the
                    // compaction genuinely is pre-compaction history and belongs
                    // above the line; output after it belongs below. It also
                    // leaves compactAll()/appendSystemInfo untouched, so the
                    // user-initiated `/compact` path — which anchors on a
                    // finished message and is already correct — takes zero
                    // regression risk.
                    //
                    // Reuses the seal+swap contract the queued-prompt injection
                    // path already established (see injectQueuedPromptsAsNewTurn
                    // and its call site): flush the finished bubble, clear the
                    // per-turn accumulators, and point `assistantId` at a fresh
                    // placeholder so subsequent writes target it.
                    val sealedId = assistantId
                    val freshAssistantId = "assistant_${System.currentTimeMillis()}"
                    withContext(Dispatchers.Main) {
                        conversationJournal.checkBranch()
                        // Flush whatever the sealed bubble accumulated and stop
                        // it streaming, so it renders as finished history.
                        updateAssistantMessage(
                            sealedId,
                            accumulatedText,
                            false,
                            allToolBlocks,
                            isAwaitingModelResponse = false,
                        )
                        // If compaction fired before the bubble produced
                        // anything, it would render as an empty row stranded
                        // above the divider — drop it. Mirrors the iOS branch.
                        val sealed = _messages.value.firstOrNull { it.id == sealedId }
                        if (sealed != null &&
                            sealed.content.isEmpty() &&
                            sealed.toolBlocks.isEmpty()
                        ) {
                            _messages.value = _messages.value.filterNot { it.id == sealedId }
                            AppLogger.info(
                                TAG,
                                "[Compact] in-loop: dropped empty sealed bubble $sealedId",
                            )
                        }
                        _messages.value = _messages.value + ChatMessage(
                            id = freshAssistantId,
                            role = "assistant",
                            content = "",
                            isStreaming = true,
                            isAwaitingModelResponse = true,
                            thinkingLevel = turnThinkingLevel,
                        )
                    }
                    clearStreamFlushState(sealedId)
                    // Same loop-scope reset the queued-prompt swap performs, so
                    // the new bubble starts empty and buildTurnParts slices only
                    // the new turn's blocks.
                    assistantId = freshAssistantId
                    accumulatedText = ""
                    allToolBlocks.clear()
                    AppLogger.info(
                        TAG,
                        "[Compact] in-loop: sealed $sealedId, continuing in $freshAssistantId below the divider",
                    )
                    freshAssistantId
                },
                contextStopped = { withContext(Dispatchers.Main) {
                    conversationJournal.checkBranch()
                    // The loop cannot present a modal mid-flight, so stop
                    // safely: user-visible notice + resumable, without the
                    // turn-limit error overwrite.
                    AppLogger.warning(
                        TAG,
                        "[AutoCompact] stopping turn: context exhausted and compaction cannot recover",
                    )
                    // [T-android-inloop-stop-thinking-orphan] Finalize the
                    // assistant message before leaving the loop.
                    //
                    // The placeholder was created with isStreaming = true /
                    // isAwaitingModelResponse = true. Only updateAssistantMessage
                    // (isStreaming = false) or finalizeAtTurnLimit ever clears
                    // those, and this branch reaches NEITHER: appendSystemInfo
                    // appends a SEPARATE system row and never touches the
                    // placeholder, while `loopExitedNormally = true` below
                    // deliberately skips finalizeAtTurnLimit at the loop tail.
                    //
                    // Without this the bubble stays on "Minis is thinking"
                    // forever — the streamJob's finally only clears the GLOBAL
                    // _isStreaming, not the per-message flags. Reachable with no
                    // failure at all: ContextPolicy gives every model with a
                    // context window under 64K `exhaustedOnly = true`, so
                    // crossing the exhaust line lands here directly.
                    withContext(Dispatchers.Main) {
                        updateAssistantMessage(
                            assistantId, accumulatedText, false, allToolBlocks,
                            isAwaitingModelResponse = false,
                        )
                        // Same orphan guard finalizeAtTurnLimit carries: the loop
                        // ran on IO while this hops to Main, so a late delta can
                        // re-add the side-channel entry after the drain, and
                        // mergeStreamingOverlay would then force isStreaming=true
                        // again with no further writer left to clear it.
                        clearStreamFlushState(assistantId)
                    }
                    // No persistAssistantTurn here: this guard runs BEFORE the
                    // turn body, so nothing new has been produced yet and the
                    // per-turn accumulators it would need
                    // (turnStartBlockIndex / lastUsage / turnReasoningContent)
                    // are not in scope. Everything from previous turns was
                    // already persisted by those turns.
                    appendSystemInfo(
                        text = "Context is full and could not be reduced further. " +
                            "Tap Continue to resume, or start a new chat.",
                        iconKind = "compact",
                    )
                    // [T-android-group-pause-badge-restamp] A LIVE interruption just
                    // happened: this is a real entry into the paused state, so the
                    // badge's 24h freshness stamp must be refreshed. Cancel any
                    // unconsumed re-detection mark left by a prior load so it cannot
                    // suppress the re-stamp here.
                    markLiveInterruption()
                    _canResume.value = true
                    // Android's equivalent of iOS's `hitTurnLimit = false`: this
                    // is a deliberate stop, NOT the runaway-ceiling path, so the
                    // post-loop tail must not slap a fake "hit 200 turns" error
                    // on it. finalizeAtTurnLimit is skipped; the notice above is
                    // the user-visible explanation.
                } },
                converged = { decision, frame -> withContext(Dispatchers.Main) {
                    conversationJournal.checkBranch()
                    decision.hint?.let { hint ->
                        val resource = when (hint) {
                            com.openminis.app.agent.AgentTurnContinuation.Hint.EMPTY_AFTER_REMINDER -> R.string.error_empty_response_after_tool
                            com.openminis.app.agent.AgentTurnContinuation.Hint.EMPTY_CONTEXT_LARGE -> R.string.error_empty_response_context_large
                            com.openminis.app.agent.AgentTurnContinuation.Hint.EMPTY_GENERIC -> R.string.error_empty_response_generic
                        }
                        withContext(Dispatchers.Main) { reportTurnError(context.getString(resource)) }
                    }
                    when (val action = decision.action) {
                        AgentLoopEngine.Action.Next -> {
                            withContext(Dispatchers.Main) { updateAssistantMessage(assistantId, accumulatedText, true, allToolBlocks) }
                            _canResume.value = false
                        }
                        is AgentLoopEngine.Action.Stop -> if (action.reason == AgentLoopEngine.StopReason.INTERRUPTED) {
                            withContext(Dispatchers.Main) { reportTurnError(context.getString(R.string.chat_error_stream_dropped_partial)) }
                            markLiveInterruption()
                            _canResume.value = true
                        } else if (frame.index == 0) generateSessionTitleIfNeeded()
                    }
                } },
                limitReached = { withContext(Dispatchers.Main) {
                    conversationJournal.checkBranch()
                    finalizeAtTurnLimit(assistantId, accumulatedText, allToolBlocks)
                } }),
            turnEffects = { frame, reportedContextTokens ->
            val turn = frame.index
            // Presentation and retry rollback slice only the current turn's blocks.
            val turnStartBlockIndex = allToolBlocks.size
            val projection = ChatStreamProjection(turn, turnStartBlockIndex, allToolBlocks, runJournal.sessionId,
                prefix = { accumulatedText }, firstText = {
                    if (!didStopStaleReadAloud) {
                        didStopStaleReadAloud = true
                        _stopStaleReadAloud.tryEmit(Unit)
                    }
                }, publish = { snapshot ->
                    withContext(Dispatchers.Main) {
                        if (activeSessionId == runJournal.sessionId) {
                            snapshot.reason?.let { com.openminis.app.diagnostics.StreamJitterProbe.publish(it, snapshot.content.length) }
                            updateAssistantMessage(assistantId, snapshot.content, true, snapshot.blocks)
                        }
                    }
                })
            // Runtime sequences requests, tool dispatch and journal commits; these callbacks project effects.
            var boundaryQueue: List<QueuedPrompt> = emptyList()
            var insertedReply: AgentConversationRuntime.InsertedReply? = null
            AgentConversationRuntime.TurnEffects(
                request = AgentConversationRuntime.RequestEffects(
                    enhancedCache = { _enhancedCacheEnabled.value },
                    configure = { projection.configure(it.streamTextIsMonolithic, it.model.id) },
                        source = {
                            val model = runtime.provider.model
                            AgentConversationRuntime.InputSource(effectiveAgentHistory(),
                                resolvedContextWindow(model)?.first ?: model.contextWindowTokens, _thinkingLevel.value)
                        }, wireHistory = { auditOutgoingPayload(applyRequestImageBudget(it)) },
                        firstChunk = { _autoRetryAttempt.value = 0; _autoRetryCountdown.value = 0 },
                        consume = { chunk ->
                conversationJournal.checkBranch()
                when (chunk) {
                    is LLMStreamChunk.ThinkingDelta,
                    is LLMStreamChunk.Text,
                    is LLMStreamChunk.ToolUseStart,
                    is LLMStreamChunk.ToolInputDelta,
                    is LLMStreamChunk.ToolCallComplete -> projection.accept(chunk)
                    is LLMStreamChunk.Usage -> {
                        val reportedContext = reportedContextTokens()
                        if (reportedContext > 0) {
                            _lastTurnContextTokens.value = reportedContext
                            // [T-android-context-usage-hint] Mid-loop path: a
                            // turn that runs tools for minutes should not stay
                            // silent until it finishes, so a genuine upward
                            // crossing surfaces immediately. `crossingOnly`
                            // keeps this from re-announcing a tier the user is
                            // merely sitting in. This also refreshes the glow
                            // on every usage chunk, which is why the border
                            // colour tracks pressure continuously.
                            withContext(Dispatchers.Main) {
                                publishContextUsage(crossingOnly = true)
                            }
                        }
                    }
                    is LLMStreamChunk.ReasoningContent -> Unit
                    is LLMStreamChunk.Finished -> Unit
                    is LLMStreamChunk.Started -> { /* no-op */ }
                    is LLMStreamChunk.MediaAttachment -> {
                        // [T-codex-gpt-image2-oauth-android] Model-generated
                        // media (gpt-image-2 image). Inline chat display is out
                        // of scope for this change — the image is delivered via
                        // sendMessage→LLMResponse.mediaAttachments for the
                        // minis-model-use CLI path. No-op here so the chat agent
                        // loop compiles with the new chunk variant.
                    }
                }
                    }, completed = {
                    // T94 fix 2: flush any text that landed in the throttle
                    // window after the last UI tick. The retry-rollback /
                    // turn-finalize paths below assume _messages reflects all
                    // accumulated text-deltas, so we must not leave the last
                    // 0-50ms worth on the floor.
                    projection.finish()
                    }),
                recovery = com.openminis.app.agent.AgentTurnRuntime.Recovery(retrying = { actual, retry ->
                val errDesc = actual.message ?: actual.javaClass.simpleName
                Log.w(TAG, "🔁 Transient error on ${runtime.provider.model.displayName}, retry ${retry.attempt}/${retry.limit} in ${retry.delaySeconds}s: $errDesc")
                withContext(Dispatchers.Main) {
                    _autoRetryAttempt.value = retry.attempt
                    setTransientInlineError("$errDesc — retrying (${retry.attempt}/${retry.limit})…")
                }
            }, countdown = { _autoRetryCountdown.value = it },
                retryCancelled = { _autoRetryAttempt.value = 0 },
                clearRetry = {
                    _autoRetryAttempt.value = 0
                    _autoRetryCountdown.value = 0
                    withContext(Dispatchers.Main) { clearInlineError() }
                }, rollbackPresentation = { discardPartialBlocks ->
                    if (discardPartialBlocks) withContext(Dispatchers.Main) {
                        while (allToolBlocks.size > turnStartBlockIndex) allToolBlocks.removeAt(allToolBlocks.size - 1)
                        updateAssistantMessage(assistantId, accumulatedText + projection.text(), true, allToolBlocks)
                    }
                    projection.resetAttempt()
                }, healOverflow = {
                    val healed = contextOffloader.healOverflow(runJournal.sessionId)
                    if (healed) {
                        AppLogger.warning(TAG, "[OverflowSelfHeal] context-length rejection — offloaded largest history part; retrying")
                        withContext(Dispatchers.Main) {
                            appendSystemInfo(context.getString(R.string.context_overflow_selfheal_notice), "compact")
                        }
                    } else AppLogger.warning(TAG, "[OverflowSelfHeal] nothing offloadable — surfacing original error")
                    healed
                }, adopted = { nextCandidate, reason, isRealModelChange ->
                        Log.i(TAG, "🔀 $reason, switched to ${nextCandidate.provider.model.displayName} (realModelChange=$isRealModelChange)")
                        val infoText = runtime.failureTrail.joinToString("\n") + "\n🔄 Switched to ${runtime.provider.model.displayName}"
                        allToolBlocks.removeAll { it.kind == "info" }
                        allToolBlocks.add(0, AssistantBlock(
                            id = "fallback_info_$turn",
                            kind = "info",
                            content = infoText,
                            toolTitle = "Switched model",
                            toolStatus = ToolBlockStatus.SUCCESS,
                        ))
                        // [T-android-fallback-text-rewind] Same as the retry-
                        // rollback path above: preserve this turn's streamed text
                        // (`turnTextSb`) on screen while we switch providers.
                        // `accumulatedText` hasn't folded it in yet, so bare
                        // `accumulatedText` would rewind the visible reply. The new
                        // provider streams into a fresh `turnTextSb` (reset just
                        // below) and re-publishes `accumulatedText + newTurnText`.
                        withContext(Dispatchers.Main) {
                            updateAssistantMessage(assistantId, accumulatedText + projection.text(), true, allToolBlocks)
                        }
                }, skippedCandidates = ::unavailableGroupMembers),
                tools = com.openminis.app.agent.AgentTurnRuntime.Tools(
                    definitions = { callableAgentTools }, concurrency = MAX_CONCURRENT_TOOLS,
                starting = { name, args ->
                    SessionActivityTracker.updateToolStatus(status = "Running: $name", toolName = name,
                        isRunning = true, toolTitle = runCatching { providedToolTitle(name, args) }.getOrNull())
                },
                running = { id ->
                    withContext(Dispatchers.Main) {
                        val index = allToolBlocks.indexOfFirst { it.id == id }
                        if (index >= 0 && allToolBlocks[index].toolStatus == ToolBlockStatus.PENDING) {
                            allToolBlocks[index] = allToolBlocks[index].copy(toolStatus = ToolBlockStatus.RUNNING)
                            updateAssistantMessage(assistantId, accumulatedText, true, allToolBlocks)
                        }
                    }
                },
                blocked = { id, message ->
                    withContext(Dispatchers.Main) {
                        val index = allToolBlocks.indexOfFirst { it.id == id }
                        if (index >= 0) {
                            allToolBlocks[index] = allToolBlocks[index].copy(toolStatus = ToolBlockStatus.FAILED,
                                content = message, durationMs = System.currentTimeMillis() - allToolBlocks[index].startTimeMs)
                            updateAssistantMessage(assistantId, accumulatedText, true, allToolBlocks)
                        }
                    }
                },
                finished = { id, name, result, truncated ->
                    withContext(Dispatchers.Main) {

                        val blockIdx = allToolBlocks.indexOfFirst { it.id == id }
                        if (blockIdx >= 0) {
                            val elapsed = System.currentTimeMillis() - allToolBlocks[blockIdx].startTimeMs
                            // Completed tools own their truncation and continuation hints.
                            val finalContent = result.output
                            // [T-truncated-args-visibility #119] A call built from
                            // truncated args must not render as a clean success — that
                            // silence is the reported bug. Show it with the same weight
                            // as the blocked path. Mirrors iOS ConcurrentTools.
                            val finalStatus = when {
                                // [T-p2-background-helper] A background delegation returned
                                // `status: running`: the block stays RUNNING so the tool bar
                                // treats it as active; the completion hook flips it later.
                                name == com.openminis.app.agent.jobs.HelperRunner.TOOL_NAME &&
                                    com.openminis.app.agent.jobs.HelperRunner.isRunningPayload(result.output) -> ToolBlockStatus.RUNNING
                                result.success && truncated -> ToolBlockStatus.FAILED
                                result.success -> ToolBlockStatus.SUCCESS
                                result.timedOut -> ToolBlockStatus.TIMEOUT
                                else -> ToolBlockStatus.FAILED
                            }
                            // T-bg-overlay phase 1: tool finished — drop the
                            // notification's indeterminate progress bar so the
                            // user can tell streaming has paused (LLM step) vs
                            // a tool is in flight.
                            // [T-overlay-glyph-typed-outcome] Pass the typed
                            // outcome so the bg overlay glyph reflects the real
                            // SUCCESS / TIMEOUT / FAILED result instead of
                            // text-sniffing the stale "Running: foo" status.
                            val toolOutcome = when (finalStatus) {
                                ToolBlockStatus.SUCCESS -> com.openminis.app.service.ToolOutcome.Success
                                ToolBlockStatus.TIMEOUT -> com.openminis.app.service.ToolOutcome.Timeout
                                ToolBlockStatus.FAILED -> com.openminis.app.service.ToolOutcome.Error
                                else -> com.openminis.app.service.ToolOutcome.Unknown
                            }
                            SessionActivityTracker.clearToolRunning(toolOutcome)
                            android.util.Log.d("ToolChain[VM]", "[turn=$turn] block[$blockIdx] status→$finalStatus title=${result.toolTitle} contentLen=${finalContent.length}")
                            allToolBlocks[blockIdx] = allToolBlocks[blockIdx].copy(
                                toolStatus = finalStatus,
                                content = finalContent,
                                toolTitle = result.toolTitle.ifEmpty { allToolBlocks[blockIdx].toolTitle },
                                durationMs = elapsed,
                                browserURL = result.pageURL ?: allToolBlocks[blockIdx].browserURL,
                                imageFilePath = result.imageFilePath ?: allToolBlocks[blockIdx].imageFilePath,
                            )
                        }

                    }
                },
                invoke = { name, args, id ->
                    executeTool(name, args, id, allToolBlocks, assistantId, accumulatedText)
                }),
                presentation = com.openminis.app.agent.AgentTurnRuntime.Presentation(
                    modelFinished = { text, hasCalls ->
                        accumulatedText += text
                        if (!hasCalls) withContext(Dispatchers.Main) {
                            updateAssistantMessage(assistantId, accumulatedText, false, allToolBlocks)
                        }
                    }, metadata = {
                        journalMetadata(allToolBlocks.filter { it.kind == "tool_use" }.associateBy { it.id })
                    }, awaiting = {
                        withContext(Dispatchers.Main) {
                            updateAssistantMessage(assistantId, accumulatedText, true, allToolBlocks,
                                isAwaitingModelResponse = true)
                        }
                    }, committed = { entity ->
                        withContext(Dispatchers.Main) { messagePublication.stamp(runJournal.sessionId, assistantId, entity) }
                    }),
                completion = {
                    com.openminis.app.agent.AgentTurnRuntime.Completion(
                        effectiveContextWindowTokens(),
                        _promptQueue.value.any { it.origin == QueuedPromptOrigin.USER },
                        ChatMessage.SCHEDULED_RESUME_NUDGE_TEXT, helperConfig != null, turnCap, pendingSteerMessages.size)
                }, boundary = com.openminis.app.agent.AgentTurnRuntime.Boundary(
                    queue = {
                        boundaryQueue = _promptQueue.value.toList()
                        boundaryQueue.map { prompt -> com.openminis.app.agent.AgentToolBoundary.Prompt(
                            prompt.id, prompt.isScheduledFire, prompt.origin == QueuedPromptOrigin.USER,
                            prompt.text.contains("<agent_callback")) }
                    }, exchangeCompleted = { if (turn == 0) generateSessionTitleIfNeeded() },
                    stopped = { dropIds ->
                        withContext(Dispatchers.Main) {
                            dropIds.forEach(::removeQueuedPrompt)
                            updateAssistantMessage(assistantId, accumulatedText, false, allToolBlocks,
                                isAwaitingModelResponse = false)
                        }
                    }, insert = { ids ->
                        val byId = boundaryQueue.associateBy { it.id }
                        val batch = ids.mapNotNull(byId::get)
                        val handled = try {
                            injectQueuedPromptsAsNewTurn(conversationJournal, assistantId, accumulatedText, allToolBlocks, batch)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (failure: Exception) {
                            Log.e(TAG, "injectQueuedPromptsAsNewTurn failed", failure)
                            null
                        }
                        if (handled != null) {
                            assistantId = handled.newAssistantId
                            insertedReply = AgentConversationRuntime.InsertedReply(handled.newAssistantId, scriptedTurnFor(handled.prefill))
                            accumulatedText = ""
                            allToolBlocks.clear()
                            _canResume.value = streamJob?.isCancelled == true
                        }
                        handled != null
                    }), insertedReply = { insertedReply },
            )
        })
        if (outcome is AgentLoopEngine.Outcome.LimitReached) {
            AppLogger.warning(
                TAG_STREAM,
                "runAgentLoop EXIT — hit turn cap=$turnCap${if (helperConfig != null) " (helper)" else ""}, finalizing as resumable",
            )
        } else {
            AppLogger.info(TAG_STREAM, "runAgentLoop EXIT (loop body ended naturally)")
        }
    }

    /**
     * Finalize the current assistant message when [runAgentLoop] hits the
     * MAX_AGENT_TURNS ceiling. Drops the streaming/awaiting flags so the
     * "thinking" indicator clears, writes an inline error explaining *why*
     * we stopped, and arms canResume so the user can continue from here.
     * Mirrors iOS AIChatViewModel.swift:4922-4929 pattern (canResume + error).
     */
    private suspend fun finalizeAtTurnLimit(
        assistantId: String,
        text: String,
        blocks: List<AssistantBlock>,
    ) {
        updateAssistantMessage(
            assistantId, text, false, blocks,
            isAwaitingModelResponse = false,
        )
        // [T-android-thinking-indicator-linger] updateAssistantMessage drains
        // _streamingById[assistantId] above, but the agent loop ran on
        // Dispatchers.IO while this finalize hops to Main — a late streaming
        // delta can re-add the side-channel entry AFTER the drain, and since
        // the loop has now exited no further isStreaming=false write will ever
        // clear it. mergeStreamingOverlay (ChatScreen) forces isStreaming=true
        // on any message with a side-channel entry, so that orphan keeps the
        // "thinking" row alive forever. Defensively drop the entry here as the
        // last Main-thread write of this turn.
        // [T-android-stream-flush-review] Cancel the trailing flush too, so it
        // can't re-add this orphan entry after we drop it on the error path.
        clearStreamFlushState(assistantId)
        // [T-agent-wrapup-turn] Name the cap that applied: a helper stops at
        // helperConfig.maxTurns, not the chat's MAX_AGENT_TURNS.
        val cap = helperConfig?.maxTurns ?: MAX_AGENT_TURNS
        reportTurnError(
            if (helperConfig != null)
                "The agent stopped after its $cap tool rounds without a final answer."
            else
                "Stopped after $cap agent turns to prevent runaway " +
                    "tool use. The model kept calling tools without finishing — tap " +
                    "Resume to continue from here, or send a new message to start over.",
        )
        // [T-android-group-pause-badge-restamp] A LIVE interruption just
        // happened: this is a real entry into the paused state, so the
        // badge's 24h freshness stamp must be refreshed. Cancel any
        // unconsumed re-detection mark left by a prior load so it cannot
        // suppress the re-stamp here.
        markLiveInterruption()
        _canResume.value = true
    }

    private suspend fun executeTool(
        name: String,
        argsJson: String,
        toolId: String,
        toolBlocks: MutableList<AssistantBlock>,
        assistantId: String,
        currentText: String,
    ): ToolExecutionResult {
        val ownerSession = activeSessionId
        val ownerRun = streamJob
        fun active() = activeSessionId == ownerSession && streamJob === ownerRun && ownerRun?.isActive == true
        fun effects(id: String, blocks: MutableList<AssistantBlock>): com.openminis.app.agent.AgentToolExecutor.Effects {
            val bash = ChatBashProjection(viewModelScope, id, blocks, active = ::active,
                publish = { updateAssistantMessage(assistantId, currentText, true, blocks) })
            return com.openminis.app.agent.AgentToolExecutor.Effects(
                subagent = { args, action -> withContext(Dispatchers.Main) { when (action) {
                    "resume" -> executeResumeAgents(args)
                    "status", "steer", "cancel" -> executeAgentStatus(args)
                    else -> executeDelegateTask(args, id, blocks, assistantId, currentText)
                } } }, disabled = ::toolDisabledResult, bashLine = bash::line,
                openUrl = { url -> if (active()) MinisOpenUrlBroker.offer(url) })
        }
        val input = com.openminis.app.agent.AgentToolExecutor.InputContext(fsSessionId, currentModelHasNativeVision,
            currentModel?.inputLimits?.images?.resize, ownerSession, checkBranch = {
                if (activeSessionId != ownerSession) throw CancellationException("tool branch changed")
            })
        val executor = com.openminis.app.agent.AgentToolExecutor(context, skillRepository,
            browser = { browserTabPool })
        return executor.execute(name, argsJson, toolId, input, effects(toolId, toolBlocks))
    }

    private suspend fun executeDelegateTask(argsJson: String, toolId: String,
        toolBlocks: MutableList<AssistantBlock>, assistantId: String, currentText: String): ToolExecutionResult {
        val hr = com.openminis.app.agent.jobs.HelperRunner
        val index = toolBlocks.indexOfFirst { it.id == toolId }
        val prior = toolBlocks.take(index.coerceAtLeast(0)).count {
            it.kind == "tool_use" && hr.isSubAgentToolName(it.toolName) &&
                !hr.isControlOnly(it.toolArgs.ifEmpty { null }, it.content.ifEmpty { null })
        }
        val parent = subagentParent(toolId, prior)
        val inline = java.util.concurrent.atomic.AtomicBoolean(true)
        return try {
            subagentRuntime.execute(argsJson, parent, subagentEffects(parent, toolBlocks, assistantId, currentText, inline))
        } finally { inline.set(false) }
    }

    private fun executeAgentStatus(argsJson: String): ToolExecutionResult =
        subagentControls.status(argsJson, subagentParent()) { childId ->
            runCatching { com.openminis.app.debug.HeadlessChatRunner.existingViewModel(childId) }.getOrNull()
                ?.let { ChatSubagentChild.snapshotOf(it) }
        }

    private fun visionPlaceholder(): String? {
        if (currentModelHasNativeVision) return null
        return ImageReader.NON_VISION_NOTE
    }


    /**
     * [T-tools-granular-switches] Result for a tool the user has switched off.
     *
     * Phrased for the model, not the user: it states the fact and tells it not
     * to retry, so a refusal ends the attempt instead of provoking a loop. The
     * user is not shown an error — they turned the tool off on purpose.
     */
    private fun toolDisabledResult(name: String): ToolExecutionResult =
        ToolExecutionResult(
            "The $name tool is disabled in this app's settings and cannot be used. " +
                "Do not try it again in this conversation; continue without it, " +
                "or tell the user it is turned off if the task requires it.",
            false,
        )

    // ─── UI Helpers ──────────────────────────────────────────────────────

    private fun mergeDelegateOverrides(blocks: List<AssistantBlock>): List<AssistantBlock> {
        if (delegateBlockOverrides.isEmpty()) return blocks
        return blocks.map { b ->
            delegateBlockOverrides[b.id]?.let { o ->
                // [T-android-orphaned-running-tool-spin] An override is a live
                // claim, and this map is never cleared on session load — so a
                // delegate whose completion never landed would re-stamp
                // RUNNING over the DB-derived status on EVERY reload, forever.
                // A RUNNING block draws an infinite shimmer, which invalidates
                // every frame: measured at 91 fps and 85-130% of a core on an
                // idle Pixel 6 until the session was closed. Drop a spinner
                // override whose job is gone and keep what the DB derived.
                val status = if (OrphanedRunningToolGate.overrideMayApply(
                        o.toolStatus, isLiveRun = _isStreaming.value, jobIsAlive = delegateJobIsAlive(o.content),
                    )
                ) {
                    o.toolStatus ?: b.toolStatus
                } else {
                    b.toolStatus
                }
                b.copy(content = o.content, toolStatus = status)
            } ?: b
        }
    }

    /**
     * [T-android-orphaned-running-tool-spin] Whether the sub-agent job named by
     * a delegate block's JSON payload is still live.
     *
     * The payload carries `job_id` (see HelperRunner.progressJson); an id that
     * the registry no longer holds as active means the run is over, however the
     * block was last stamped. Unparseable payload → treat as NOT alive: the
     * cost of being wrong is a spinner that stops, versus one that never does.
     */
    private fun delegateJobIsAlive(content: String): Boolean {
        val jobId = runCatching { org.json.JSONObject(content).optString("job_id", "") }.getOrNull()
        if (jobId.isNullOrEmpty()) return false
        return com.openminis.app.agent.jobs.AgentJobRegistry.job(jobId)?.isActive == true
    }

    /**
     * [T-p2-background-helper] Write a delegate block by tool_use id — the
     * block index is not stable across the parent's later turns and reloads.
     * Records a live override for the running loop and patches the canonical
     * list (and the streaming overlay) directly. Main thread.
     */
    internal fun writeDelegateBlock(toolId: String, content: String, status: ToolBlockStatus?) {
        val prev = delegateBlockOverrides[toolId]
        delegateBlockOverrides[toolId] = AssistantBlock(
            id = toolId, kind = "tool_use", content = content,
            toolStatus = status ?: prev?.toolStatus, toolName = com.openminis.app.agent.jobs.HelperRunner.TOOL_NAME,
        )
        fun patch(blocks: List<AssistantBlock>): List<AssistantBlock>? {
            if (blocks.none { it.id == toolId }) return null
            return blocks.map { if (it.id == toolId) it.copy(content = content, toolStatus = status ?: it.toolStatus) else it }
        }
        _messages.value = _messages.value.map { m ->
            if (m.role != "assistant") m else patch(m.toolBlocks)?.let { m.copy(toolBlocks = it) } ?: m
        }
        messagePublication.patch(::patch)
    }

    internal fun clearDelegateOverride(toolId: String) { delegateBlockOverrides.remove(toolId) }

    private fun updateAssistantMessage(id: String, content: String, isStreaming: Boolean,
        toolBlocksIn: List<AssistantBlock>, isAwaitingModelResponse: Boolean = false) =
        messagePublication.update(id, content, isStreaming, toolBlocksIn, isAwaitingModelResponse)

    internal fun effectiveContent(id: String): String? =
        _streamingById.value[id]?.content ?: _messages.value.firstOrNull { it.id == id }?.content

    private fun flushAllStreamingDeltas() = messagePublication.flushAll()

    private fun journalMetadata(toolBlockMeta: Map<String, AssistantBlock>) = toolBlockMeta.mapValues { (_, block) ->
        com.openminis.app.agent.AgentJournalWriter.ToolPresentation(block.toolTitle,
            block.browserURL.orEmpty(), block.imageFilePath.orEmpty())
    }

    private fun buildSystemPrompt(): String? {
        // Keep policy/capability text at the head. Changing runtime facts are owned,
        // persisted snapshots appended to history, never a rewritten system suffix.

        // Main agents get the SOUL identity/personality; helpers get their own
        // task/deliverable preamble, without inheriting the parent's persona.
        // Browser guidance must follow the same switch as the tool schema.
        val browserToolEnabled = com.openminis.app.tools.AgentToolSwitch.BROWSER.isEnabled(context)
        val identitySection = helperConfig?.let {
            com.openminis.app.agent.jobs.HelperRunner.identitySection(it, browserEnabled = browserToolEnabled)
        }
            ?: (com.openminis.app.agent.SystemPromptBuilder.identitySection(context) +
                "\n\n" + com.openminis.app.agent.UserProfileStore.promptFragment(context))
        // The browser, shell and delegation facts now ride their own tool schemas;
        // the system prompt keeps only rules that belong to no single tool.
        val base = com.openminis.app.agent.AndroidSystemPrompt.build(identitySection = identitySection)

        // Append optional capability fragments after the stable base.
        // [T-android-skill-scan-parity] No disk access here — this runs on the
        // main thread, twice per send (checkContextBeforeSend and the send
        // coroutine). Like iOS SkillStore.skillPromptFragment(), the fragment
        // reads only the in-memory list loaded from skills.db. Out-of-band
        // installs (shell `git clone`, write of a SKILL.md, backup
        // restore, returning to the app) queue a background rescan instead;
        // see SkillRepository.requestReload. The old per-send
        // reloadFromDisk() cost ~2 s per send for a user with 490 skills.
        val skillFragment = skillRepository?.skillPromptFragment(activeSessionId)
        // [T-mcp-integration-android] Re-read servers.json (the CLI / file
        // browser may have changed it out-of-band) then build the Top-20
        // enabled-MCP disclosure, injected right after the skills fragment.
        mcpRepository?.reloadFromDisk()
        val mcpFragment = mcpRepository?.mcpPromptFragment(activeSessionId)

        return buildString {
            append(base)
            if (skillFragment != null) {
                append("\n\n")
                append(skillFragment)
            }
            if (mcpFragment != null) {
                append("\n\n")
                append(mcpFragment)
            }
        }
    }

    private suspend fun runtimeContextFacts(): com.openminis.app.agent.RuntimeContextSnapshot.Facts =
        com.openminis.app.agent.RuntimeContextSnapshot.Facts(
            java.time.LocalDate.now().toString(), java.util.TimeZone.getDefault().id,
            context.resources.configuration.locales[0].toLanguageTag(),
            try { providerRepository.resolvedAgentLoopEntries().size }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { 0 },
        )

    // ─── Tool execution methods ───────────

    suspend fun executeBrowser(argsJson: String): BrowserToolResult {
        val input = BrowserActionInput.parse(argsJson)
            ?: return BrowserToolResult(text = "Error: Invalid browser input. Required: 'action' parameter.", success = false)

        return try {
            val result = browserTabPool.execute(input, owner = browserOwnerId)
            BrowserToolResult(
                text = result.text,
                success = result.success,
                base64Image = result.base64Image,
                imageFilePath = result.imageFilePath,
                pageURL = result.pageURL,
            )
        } catch (e: Exception) {
            BrowserToolResult(text = "Error: ${e.message}", success = false)
        }
    }

    data class BrowserToolResult(
        val text: String,
        val success: Boolean,
        val base64Image: String? = null,
        val imageFilePath: String? = null,
        val pageURL: String? = null,
    )

    // ─── Misc Helpers ────────────────────────────────────────────────────

    /**
     * T209: resize image bytes for the LLM inference payload only — the
     * full-resolution original is preserved on disk (mediaStore + uploads
     * dir) so chat history fullscreen view, agent shell `cat`, and
     * `read` all see the user's original picture, matching iOS.
     *
     * Returns null when the source already fits within [maxEdge] (caller
     * should fall back to [rawBytes]) or on any decode/compress failure.
     */
    /**
     * [T-android-image-downscale-parity] The model-facing shape of one attached
     * image, shared by every send path: the "[attached image: <path>]" caption,
     * the pixels, then — only when the pixels were downscaled — the note giving
     * the original size, so the model knows it is looking at a reduced copy.
     */
    private fun MutableList<AgentContentPart>.addModelImage(
        part: LLMMessage.ImagePart,
        path: String?,
        note: String?,
    ) {
        if (path != null) add(AgentContentPart.Text("[attached image: $path]"))
        add(AgentContentPart.ImageData(part.data, part.mimeType, linuxPath = path, noVisionPlaceholder = visionPlaceholder()))
        if (note != null) add(AgentContentPart.Text(note))
    }

    /**
     * Bundle of everything derived from a user-message's input attachments:
     * the resized in-memory image bytes for the LLM, file:// URIs of the
     * persisted copies (for stable rendering across app restarts), the
     * filenames in original attachment order (images first, then non-image
     * files — matches the rendering convention in UserAttachmentList), and
     * the mediaRef JSON parts that need to be embedded in parts_json so the
     * attachments survive a session reload (T128).
     */
    private data class PreparedAttachments(
        val imageParts: List<LLMMessage.ImagePart>,
        val imageUris: List<Uri>,
        val attachmentNames: List<String>,
        val mediaRefPartsJson: List<String>,
        // T132: iOS-parity additions so the model sees the attachment as
        // a real file in the agent's sandbox (read / bash can
        // open these paths).
        //   imageUploadPaths: one /var/minis/attachments/uploads/<safe> per
        //     inlined image, in the same order as `imageParts`.
        //   attachedFilesXml:  null when no attachments, otherwise the
        //     <user-attached-files> XML block iOS appends to the user turn.
        val imageUploadPaths: List<String>,
        val attachedFilesXml: String?,
        // T150: file:// URIs of persisted non-image attachments, in the same
        // order as the non-image suffix of `attachmentNames`. Carried into
        // ChatMessage so the user-bubble file chip can route a tap directly
        // to FilePreviewScreen without re-resolving by filename.
        val nonImageUris: List<Uri>,
        /** [T-android-image-downscale-parity] Parallel to [imageParts]; see downscaleNote. */
        val imageModelNotes: List<String?> = emptyList(),
    )

    /**
     * Resize each image attachment, copy the bytes into MediaStore (private
     * filesDir/media/<date>/<sessionId>/<id>.<ext>), and return both the
     * in-memory bytes (for the LLM) and a stable file:// URI + mediaRef JSON
     * part (for persistence + reload). T150: non-image attachments take the
     * same persistence + uploadsHostDir path so they survive session reload
     * and remain visible to the agent's shell tools — but their content is
     * NOT inlined into the LLM payload (parity with iOS processAttachments,
     * AIChatViewModel.swift L1552-1645).
     */
    private fun prepareUserAttachments(
        attachments: List<InputAttachment>,
        sessionId: String,
    ): PreparedAttachments {
        val imageParts = mutableListOf<LLMMessage.ImagePart>()
        val imageUris = mutableListOf<Uri>()
        val imageNames = mutableListOf<String>()
        val nonImageNames = mutableListOf<String>()
        val nonImageUris = mutableListOf<Uri>()
        // T150: separate buffers so the persisted mediaRefPartsJson is
        // image-first, matching the on-screen UserAttachmentList ordering
        // and `attachmentNames = imageNames + nonImageNames`. On restore,
        // `loadSessionMessages` walks parts_json in array order — keeping
        // the persisted order image-first means restoredAttachmentNames
        // and restoredAttachmentUris also come out image-first/non-image-suffix.
        val imageMediaRefPartsJson = mutableListOf<String>()
        val nonImageMediaRefPartsJson = mutableListOf<String>()
        val imageUploadPaths = mutableListOf<String>()
        // [T-android-image-downscale-parity] One entry per imageParts entry: the
        // "downscaled from W×H" note, or null when the image was sent as-is.
        val imageModelNotes = mutableListOf<String?>()
        // T132: also write the resized bytes into the session's iSH-bound
        // attachments dir (filesDir/minis-sessions/<sid>/attachments/uploads/),
        // which is mounted at /var/minis/attachments/ inside iSH. This makes
        // the same image accessible to the agent via shell tools (read
        // / cat / file) and matches the iOS uploads-directory convention.
        val uploadsHostDir = java.io.File(
            context.filesDir,
            "minis-sessions/$sessionId/attachments/uploads",
        ).apply { mkdirs() }
        // Metadata captured per attachment for the <user-attached-files> XML.
        data class UploadMeta(val linuxPath: String, val size: Long, val modifiedIso: String)
        val metas = mutableListOf<UploadMeta>()
        val nowMs = System.currentTimeMillis()
        val isoFormatter = java.text.SimpleDateFormat(
            "yyyy-MM-dd'T'HH:mm:ss'Z'",
            java.util.Locale.US,
        ).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
        val nowStr = isoFormatter.format(java.util.Date(nowMs))

        for (attachment in attachments) {
            if (attachment.isImage) {
                // T209: read the original image bytes once and reuse them
                // for storage + uploads dir; only the LLM inference payload
                // gets the resized copy. Pre-T209 the resized JPEG was used
                // for all three, so chat history fullscreen view and agent
                // shell tools (read / cat) saw a 1024px JPEG instead
                // of the user's original picture. Matches iOS canonical
                // (AIChatViewModel.swift L1595-1617).
                val rawBytes = try {
                    context.contentResolver.openInputStream(attachment.uri)?.use { it.readBytes() }
                } catch (e: Exception) {
                    Log.w(TAG, "image read failed for ${attachment.fileName}: ${e.message}")
                    null
                } ?: continue
                val ref = try {
                    mediaStore.saveMedia(
                        data = rawBytes,
                        mimeType = attachment.mimeType,
                        sessionId = sessionId,
                        originalFileName = attachment.fileName,
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to persist image attachment ${attachment.fileName}", e)
                    continue
                }
                // Resize only for the LLM payload — token-efficient and a
                // close-enough sketch of the picture for the model. Falls
                // back to raw bytes if the source is already small or the
                // decode/compress step fails.
                // [T-android-image-downscale-parity] The same function rebuilds
                // this copy from the saved original on reload (toLLMMessage), so
                // every turn sends the same ≤2000 px image, as iOS does.
                val downscaled = ImageBudget.downscaleForModel(rawBytes)
                val inferenceBytes = downscaled?.bytes ?: rawBytes

                // Mirror ORIGINAL bytes into the iSH uploads dir under a
                // unique safe name so agent shell tools see the full-res
                // image. Don't fail the send if this write fails —
                // image_url in the request still carries (resized) bytes;
                // the model just won't be able to ask the agent to re-read
                // the same file from shell.
                //
                // Done BEFORE ImagePart construction so the linuxPath is
                // attached to the part — request-level image budgeting
                // uses it to emit a re-fetchable text placeholder when
                // the cumulative payload would exceed the per-request cap.
                val safeName = uniqueUploadFileName(uploadsHostDir, attachment.fileName)
                val dest = java.io.File(uploadsHostDir, safeName)
                val uploadOk = try { dest.writeBytes(rawBytes); true } catch (e: Exception) {
                    Log.w(TAG, "uploads write failed for ${attachment.fileName}: ${e.message}")
                    false
                }
                val linuxPath = if (uploadOk) "/var/minis/attachments/uploads/$safeName" else null
                if (linuxPath != null) {
                    imageUploadPaths.add(linuxPath)
                    metas.add(UploadMeta(linuxPath = linuxPath, size = rawBytes.size.toLong(), modifiedIso = nowStr))
                }

                // [T-android-image-upload-format] Label the part with what
                // inferenceBytes ARE, not what the attachment was. downscaleForModel
                // re-encodes anything over 2000 px (a HEIC photo comes out JPEG),
                // and the old code kept "image/heic" on those JPEG bytes — which
                // DeepSeek rejects on the label alone. The provider boundary
                // re-derives the MIME too; this keeps the in-memory part honest
                val inferenceMime = ImageBudget.sniffFormat(inferenceBytes).mimeType ?: attachment.mimeType
                imageParts.add(LLMMessage.ImagePart(inferenceBytes, inferenceMime, linuxPath = linuxPath))
                imageModelNotes.add(downscaled?.let { ImageBudget.downscaleNote(it, linuxPath) })
                val savedFile = java.io.File(mediaStore.mediaBaseDir, ref.relativePath)
                imageUris.add(Uri.fromFile(savedFile))
                imageNames.add(attachment.fileName)
                imageMediaRefPartsJson.add(buildMediaRefPartJson(ref, linuxPath = linuxPath))
                continue
            }

            // T150: non-image attachment — stream-copy to disk (no
            // resize), persist a mediaRef so the chip survives session
            // reload (T151), and put a copy in the iSH uploads dir so
            // the agent can `cat` it via shell tools. iOS parity: the
            // file content is NOT inlined into the LLM payload — it
            // only appears in <user-attached-files> XML metadata, the
            // model fetches content on demand.
            //
            // CRITICAL: we deliberately do NOT `readBytes()` the
            // attachment here. A 400MB APK shared in by the user would
            // OOM on a low-RAM device (heap growth limit ~500MB on
            // Pixel 4a); the file's not even going into the LLM
            // payload, so loading the full byte array is pointless.
            // Stream-copy to the uploads dest first, then hand that
            // file to MediaStore.saveMediaStreamed so a second
            // streaming pass produces the durable mediaRef.
            nonImageNames.add(attachment.fileName)
            val safeName = uniqueUploadFileName(uploadsHostDir, attachment.fileName)
            val dest = java.io.File(uploadsHostDir, safeName)
            val uploadOk = try {
                context.contentResolver.openInputStream(attachment.uri)?.use { input ->
                    dest.outputStream().use { output -> input.copyTo(output) }
                } != null
            } catch (e: Exception) {
                Log.w(TAG, "non-image upload write failed for ${attachment.fileName}: ${e.message}")
                runCatching { dest.delete() }
                false
            }
            if (!uploadOk) continue

            val ref = try {
                dest.inputStream().use { input ->
                    mediaStore.saveMediaStreamed(
                        source = input,
                        mimeType = attachment.mimeType,
                        sessionId = sessionId,
                        originalFileName = attachment.fileName,
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to persist non-image attachment ${attachment.fileName}", e)
                null
            }
            if (ref != null) {
                nonImageMediaRefPartsJson.add(buildMediaRefPartJson(ref))
                nonImageUris.add(Uri.fromFile(java.io.File(mediaStore.mediaBaseDir, ref.relativePath)))
            }

            val linuxPath = "/var/minis/attachments/uploads/$safeName"
            metas.add(UploadMeta(linuxPath = linuxPath, size = dest.length(), modifiedIso = nowStr))
        }

        // T-imgsize: byte-level budget enforcement. The downscaleForModel pass
        // above caps *resolution* at 2000px but does nothing for the JPEG byte
        // size when the source is a 12-megapixel photo — Anthropic 413s once
        // cumulative inline image payload crosses ~30MB. ImageBudget walks
        // every image part, re-encodes oversize ones via the quality ladder,
        // and drops the tail when cumulative bytes would exceed 20MB. Result
        // is surfaced to the UI through _imageBudgetEvent so the Snackbar can
        // tell the user we touched their attachments.
        if (imageParts.isNotEmpty()) {
            val budgetResult = ImageBudget.applyMessageBudget(imageParts.map { it.data })
            // budgetResult.keptBytes.size <= imageParts.size; tail-drop the
            // parallel image-only lists symmetrically. Re-encoded bytes always
            // come out as JPEG so flip the mimeType on any part whose bytes
            // changed size (cheap proxy — never a false positive that hurts
            // semantics because the byte stream itself is the JPEG header).
            val newImageParts = budgetResult.keptBytes.mapIndexed { idx, kept ->
                val orig = imageParts[idx]
                if (kept === orig.data) orig
                else LLMMessage.ImagePart(kept, "image/jpeg", linuxPath = orig.linuxPath)
            }
            val newSize = newImageParts.size
            imageParts.clear()
            imageParts.addAll(newImageParts)
            while (imageUris.size > newSize) imageUris.removeAt(imageUris.size - 1)
            while (imageNames.size > newSize) imageNames.removeAt(imageNames.size - 1)
            while (imageMediaRefPartsJson.size > newSize) imageMediaRefPartsJson.removeAt(imageMediaRefPartsJson.size - 1)
            while (imageUploadPaths.size > newSize) imageUploadPaths.removeAt(imageUploadPaths.size - 1)
            while (imageModelNotes.size > newSize) imageModelNotes.removeAt(imageModelNotes.size - 1)
            if (budgetResult.mutated) {
                AppLogger.info(
                    TAG,
                    "[ImageBudget] compose: in=${budgetResult.keptBytes.size + budgetResult.droppedCount} kept=${budgetResult.keptBytes.size} compressed=${budgetResult.compressedCount} dropped=${budgetResult.droppedCount} totalBytes=${budgetResult.totalBytes}",
                )
                _imageBudgetEvent.tryEmit(budgetResult)
            }
        }

        // Build the <user-attached-files> XML block (iOS parity). One <file>
        // per attachment (image and non-image) that successfully landed in
        // the iSH uploads dir — gives the model a metadata-only inventory
        // it can resolve via shell tools when content is needed.
        val xml = if (metas.isEmpty()) null else buildString {
            append("<user-attached-files>\n")
            for (m in metas) {
                val urlPath = m.linuxPath.removePrefix("/var/minis/")
                append("  <file path=\"")
                append(m.linuxPath)
                append("\" url=\"minis://")
                append(urlPath)
                append("\" size=\"")
                append(m.size)
                append("\" modified=\"")
                append(m.modifiedIso)
                append("\" />\n")
            }
            append("</user-attached-files>")
        }

        // Order matches UserAttachmentList convention: images first, then files.
        return PreparedAttachments(
            imageParts = imageParts,
            imageUris = imageUris,
            attachmentNames = imageNames + nonImageNames,
            mediaRefPartsJson = imageMediaRefPartsJson + nonImageMediaRefPartsJson,
            imageUploadPaths = imageUploadPaths,
            imageModelNotes = imageModelNotes,
            attachedFilesXml = xml,
            nonImageUris = nonImageUris,
        )
    }

    /**
     * Compute a unique-on-disk filename inside [dir] for [original]. Strips
     * path separators, falls back to "image.jpg" if the input is empty, and
     * appends `_N` before the extension when the target already exists.
     */
    private fun uniqueUploadFileName(dir: java.io.File, original: String): String {
        val raw = original.substringAfterLast('/').substringAfterLast('\\').ifBlank { "image.jpg" }
        // Sanitize control / path-hostile chars without going overboard;
        // safe POSIX path chars are kept.
        val sanitized = raw.replace(Regex("[^A-Za-z0-9._-]"), "_")
        if (!java.io.File(dir, sanitized).exists()) return sanitized
        val dot = sanitized.lastIndexOf('.')
        val base = if (dot > 0) sanitized.substring(0, dot) else sanitized
        val ext = if (dot > 0) sanitized.substring(dot) else ""
        var n = 1
        while (true) {
            val candidate = "${base}_$n$ext"
            if (!java.io.File(dir, candidate).exists()) return candidate
            n++
        }
    }

    private fun buildMediaRefPartJson(
        ref: com.openminis.app.data.model.MediaRef,
        linuxPath: String? = null,
    ): String {
        val value = JSONObject()
            .put("id", ref.id)
            .put("relativePath", ref.relativePath)
            .put("mimeType", ref.mimeType)
        if (ref.originalFileName != null) value.put("originalFileName", ref.originalFileName)
        // Carry the iSH-visible uploads path through persistence so that
        // restored history can reconstruct AgentContentPart.ImageData with
        // its original linuxPath. Restored images that miss this field
        // (older rows written before this column existed) get linuxPath=null
        // and fall back to spillover at budget-elide time.
        if (linuxPath != null) value.put("linuxPath", linuxPath)
        return JSONObject().put("type", "mediaRef").put("value", value).toString()
    }

    /**
     * Build the parts_json array for a user message: a `text` part (omitted
     * when the user only sent attachments with no caption) followed by one
     * `mediaRef` part per persisted image. Mirrors the existing single-part
     * shape when there are no attachments.
     */
    /**
     * [T-android-paste-mediaref] What a message's `[Pasted#N]` markers turned
     * into once each was written to disk.
     *
     * @param partsJson the message's parts, in order, with each marker replaced
     *   by a `text/plain` mediaRef part and the surrounding prose kept as
     *   separate text parts.
     * @param modelText the same body with every marker expanded back to its full
     *   text — what the model must see on THIS turn. (Later turns rebuild it
     *   from disk via toLLMMessage.)
     * @param uiNames / [uiUris] the pasted blocks as attachment-style entries so
     *   the sent bubble shows a file card, exactly like a picked document.
     * @param consumedIds buffer entries actually referenced, for the caller to
     *   clear once the send is committed.
     */
    private data class PastedParts(
        val partsJson: List<String>,
        val modelText: String,
        val uiNames: List<String>,
        val uiUris: List<Uri>,
        val consumedIds: Set<Int>,
    )

    /**
     * [T-android-paste-mediaref] Write each `[Pasted#N]` in [text] to its own
     * `text/plain` media file and return the pieces the send path needs.
     *
     * This is the crux of the change. Previously the marker was substituted
     * inline and the whole block was persisted as one `text` part; now the block
     * becomes a mediaRef — the same mechanism images and documents already use —
     * so the stored message and its bubble stay small while the file on disk
     * holds the content.
     *
     * Returns null when [text] contains no live marker, letting every caller
     * keep its existing straight-line path untouched.
     */
    private fun buildPastedParts(text: String, sessionId: String): PastedParts? {
        val (chunks, consumed) = splitPastePlaceholders(text, _pastedTexts.value)
        if (consumed.isEmpty()) return null
        val byId = _pastedTexts.value.associateBy { it.id }

        val parts = mutableListOf<String>()
        val model = StringBuilder()
        val names = mutableListOf<String>()
        val uris = mutableListOf<Uri>()
        for (chunk in chunks) {
            when (chunk) {
                is PasteChunk.Text -> {
                    parts.add("""{"type":"text","value":${escapeJson(chunk.value)}}""")
                    model.append(chunk.value)
                }
                is PasteChunk.Pasted -> {
                    val entry = byId[chunk.id] ?: continue
                    val ref = try {
                        mediaStore.saveMedia(
                            data = entry.text.toByteArray(Charsets.UTF_8),
                            mimeType = PastedMedia.MIME,
                            sessionId = sessionId,
                            originalFileName = PastedMedia.fileNameFor(chunk.id),
                        )
                    } catch (e: Exception) {
                        // Disk full / unwritable: fall back to inlining this one
                        // block as text. The message is then shaped like the old
                        // behaviour — big, but complete. Losing the paste
                        // silently would be far worse than a heavy bubble.
                        AppLogger.warning(
                            TAG,
                            "[Paste] saveMedia failed for #${chunk.id}, inlining: ${e.message}",
                        )
                        parts.add("""{"type":"text","value":${escapeJson(entry.text)}}""")
                        model.append(entry.text)
                        continue
                    }
                    parts.add(buildMediaRefPartJson(ref))
                    model.append(entry.text)
                    names.add(ref.originalFileName ?: PastedMedia.fileNameFor(chunk.id))
                    uris.add(Uri.fromFile(java.io.File(mediaStore.mediaBaseDir, ref.relativePath)))
                }
            }
        }
        AppLogger.info(
            TAG,
            "[Paste] ${consumed.size} placeholder(s) -> mediaRef: " +
                "${text.length} chars in bubble, ${model.length} chars to model",
        )
        return PastedParts(parts, model.toString(), names, uris, consumed)
    }

    private fun buildUserPartsJson(
        text: String,
        mediaRefPartsJson: List<String>,
        // [T-android-retry-attachment-loss] The <user-attached-files> XML
        // inventory (non-image file paths/sizes the model uses to `cat` the
        // file). iOS persists this same XML as a trailing text part so it
        // round-trips through retry / rerun / session-reload unchanged — the
        // model keeps seeing the /var/minis/attachments/uploads/... paths.
        // Android previously only added it to the in-memory agentHistory and
        // never persisted it, so a retry silently dropped the file inventory.
        // Persist it here as a text part (iOS parity); toLLMMessage restores
        // it via the plain "text" case with zero special-casing.
        attachedFilesXml: String? = null,
        /**
         * [T-android-paste-mediaref] Pre-split body parts from
         * [buildPastedParts], used INSTEAD of the single `text` part when the
         * message contained `[Pasted#N]` markers. Already an interleaved
         * text/mediaRef sequence, so it is spliced in at the position the plain
         * text part would have occupied — order is what keeps the pasted block
         * where the user put it, between the words around it.
         */
        bodyPartsJson: List<String>? = null,
    ): String {
        val parts = mutableListOf<String>()
        if (bodyPartsJson != null) {
            parts.addAll(bodyPartsJson)
        } else if (text.isNotEmpty() || mediaRefPartsJson.isEmpty()) {
            parts.add("""{"type":"text","value":${escapeJson(text)}}""")
        }
        parts.addAll(mediaRefPartsJson)
        attachedFilesXml?.let { parts.add("""{"type":"text","value":${escapeJson(it)}}""") }
        return parts.joinToString(prefix = "[", postfix = "]", separator = ",")
    }

    private val titleRuntime by lazy {
        com.openminis.app.agent.AgentTitleRuntime(context, chatRepository, providerRepository, viewModelScope)
    }

    private fun generateSessionTitleIfNeeded() {
        val owner = activeSessionId
        val title = _sessionTitle.value
        val category = _sessionCategory.value
        val input = com.openminis.app.agent.AgentTitleRuntime.Input(owner, title, titleLanguageDirective(),
            _messages.value.map { com.openminis.app.agent.AgentTitleRuntime.Message(it.role, it.content) },
            _activeEntryId.value, currentModel?.id, com.openminis.app.ui.settings.autoGroupingEnabled(context),
            currentProvider, currentModelSnapshot())
        titleRuntime.automatic(input) { result ->
            if (activeSessionId == owner && _sessionTitle.value == title && _sessionCategory.value == category) {
                _sessionTitle.value = result.title
                _sessionCategory.value = result.category ?: category
            }
        }
    }

    /**
     * [T-android-overlay-reply-status-34599] Pull the most recent
     * assistant text out of `_messages` and hand it to
     * [SessionActivityTracker.publishLastReply]. The tracker truncates
     * to a fixed-width excerpt and pairs it with [sessionId] so the
     * floating overlay can render a "tap to open this chat" capsule
     * after the stream completes. No-op when no assistant message has
     * content yet (e.g. fail during the very first turn).
     */
    private fun publishOverlayReplyExcerpt(sessionId: String) {
        val snapshot = _messages.value
        val text = snapshot.asReversed().firstOrNull { msg ->
            msg.role == "assistant" && msg.content.isNotBlank()
        }?.content
        SessionActivityTracker.publishLastReply(sessionId, text)
    }

    fun cancelStream() {
        val owner = activeSessionId
        val alias = sessionId.takeIf { isDraft && it != owner }
        val stopped = stopRuntime.stop(owner, alias)
        if (stopped !is com.openminis.app.agent.AgentStopRuntime.Result.Run) return
        lastTurnWasCancelled = true
        _isStreaming.value = false
        flushAllStreamingDeltas()
        publishOverlayReplyExcerpt(owner)
        SessionActivityTracker.clearToolRunning(com.openminis.app.service.ToolOutcome.Cancelled)
        if (_autoRetryAttempt.value != 0 || _autoRetryCountdown.value != 0) {
            _autoRetryAttempt.value = 0
            _autoRetryCountdown.value = 0
            clearInlineError()
        }
        handleUserCancelledCleanup(stopped.view)
        if (_promptQueue.value.isNotEmpty()) resumeQueueAfterCancel()
    }

    /**
     * T189: spawn a fresh agent loop to drain whatever the user queued during
     * the cancelled stream. 200ms delay matches iOS resumeQueueAfterCancel
     * (Task.sleep(200_000_000)) — gives the cancelled streamJob's finally block
     * room to release the concurrency slot + write back state. Race-guards on
     * entry: empty queue (user withdrew) or already streaming (user manually
     * retried) → noop return.
     *
     * Provider / systemPrompt / fallback resolution mirrors [sendMessage]
     * verbatim (incl. OAuth token refresh + Claude Code prefix), so a queued
     * prompt drain after cancel uses the same plumbing as a fresh send.
     */
    private val queueRestart by lazy { com.openminis.app.agent.AgentQueueRestart(viewModelScope) }

    private fun resumeQueueAfterCancel() {
        val owner = activeSessionId
        queueRestart.kick(owner, streamJob?.takeIf { it.isCancelled }, com.openminis.app.agent.AgentQueueRestart.Effects(
            currentSession = { activeSessionId }, queued = { _promptQueue.value.isNotEmpty() },
            busy = { _isStreaming.value }, compacting = { _isCompacting.value },
            providerAvailable = { currentProvider != null },
            unavailable = { _error.value = "No provider configured; queued prompts are retained" }, launch = {
                val initial = currentProvider
                if (initial != null && activeSessionId == owner) {
                    val entry = _activeEntryId.value
                    var provider: LLMProvider = initial
                    var prompt: String? = null
                    _isStreaming.value = true
                    _canResume.value = false
                    _error.value = null
                    launchAgentRun(viewModelScope, "resumeQueueAfterCancel", markFailure = false, prepare = {
                        if (activeSessionId != owner) throw CancellationException("queue restart branch changed")
                        provider = providerPreparation.prepare(provider, entry, owner) { expected, refreshed ->
                            if (activeSessionId == owner && currentProvider === expected) currentProvider = refreshed
                        }
                        if (activeSessionId != owner) throw CancellationException("queue restart branch changed")
                        prompt = providerPreparation.prompt(provider, buildSystemPrompt())
                    }) {
                        drainQueuedPrompts(provider, prompt, buildFallbackProviders(provider),
                            com.openminis.app.data.model.FallbackStrategy.default)
                    }
                }
            }))
    }

    /**
     * Project the runtime's interruption snapshot onto the live bubble.
     * Persistence completes in the cancelled run before a successor can start.
     */
    private fun handleUserCancelledCleanup(stop: com.openminis.app.agent.AgentTurnJournal.StopView?) {
        val msgs = _messages.value.toMutableList()
        val lastIdx = if (stop != null) msgs.indexOfLast { it.id == stop.bubbleId }
            else msgs.indexOfLast { it.role == "assistant" && (it.isStreaming || it.isAwaitingModelResponse) }
        if (lastIdx < 0) return
        var last = msgs[lastIdx]

        // T73: clear "Minis is thinking…" the moment the user taps Stop.
        // isAwaitingModelResponse is set true at runAgentLoop entry (≈ line
        // 2785) so the typing indicator shows during the initial request
        // gap before the first stream chunk. The cancel paths below didn't
        // reset it, so after Stop the indicator stayed live forever even
        // though the streamJob was already torn down. Reset before either
        // case runs so both tool-cancel and text-cancel paths benefit.
        if (last.isAwaitingModelResponse) {
            last = last.copy(isAwaitingModelResponse = false)
            msgs[lastIdx] = last
            _messages.value = msgs
        }

        // Projection only: the runtime commits interrupted model content and tool results.
        val updatedBlocks = last.toolBlocks.map { block ->
            if (block.id in stop?.pendingIds.orEmpty()) block.copy(toolStatus = ToolBlockStatus.CANCELLED) else block
        }
        val hasVisibleContent = stop?.resumable == true || last.content.isNotEmpty() ||
            updatedBlocks.any { it.kind == "tool_use" || (it.kind == "text" && it.content.isNotEmpty()) }
        if (!hasVisibleContent) {
            msgs.removeAt(lastIdx)
        } else {
            msgs[lastIdx] = last.copy(toolBlocks = updatedBlocks, isStreaming = false, isAwaitingModelResponse = false)
            markLiveInterruption()
            _canResume.value = true
        }
        _messages.value = msgs
    }

    /**
     * Resume an interrupted agent loop in a fresh [streamJob]. The runtime
     * durably commits any required continuation reminder before dispatch. Mirrors iOS
     * AIChatViewModel.resume().
     *
     * Safe to call only when [canResume] is true and [isStreaming] is false.
     * Clears [_canResume] on entry so repeated taps don't stack.
     */
    fun resume() {
        if (afterCancelledRun { resume() }) return
        if (_isStreaming.value || !_canResume.value) return
        // [T-android-mute-reset-on-drain] Resume is new work, as on iOS.
        com.openminis.app.agent.jobs.AgentJobRegistry.clearDelegationMute(activeSessionId)
        val provider = currentProvider ?: run {
            _error.value = "No provider configured"
            return
        }
        _canResume.value = false
        _error.value = null
        // [T-error-persist-android] resume() follows finalizeAtTurnLimit's
        // setInlineError (which persisted an error sticker on the last assistant
        // row). Clear it now so a successful resume doesn't merge-resurrect the
        // turn-limit banner on the next reload.
        val resumeOwner = activeSessionId
        val resumeRows = _messages.value.lastOrNull { it.role == "assistant" }?.sourceDbIds.orEmpty().toSet()
        clearInlineError()
        AppLogger.info(TAG, "▶️ resume: continuing partial assistant message (no new header emitted)")
        // [T-android-tool-autoscroll] Start-of-turn snap. The thinking
        // placeholder is the only visible delta until the model's first
        // token, and the auto-follow tuple won't advance until content
        // streams — ChatScreen would otherwise leave the placeholder
        // behind the input bar.
        _forceScrollToBottom.tryEmit(Unit)

        // Claim the run synchronously; cancellation owns preparation as well as the stream.
        _isStreaming.value = true
        launchAgentRun(viewModelScope, "resume", markFailure = false, prepare = {
            errorJournal.clear(resumeOwner, resumeRows)
        }) {
            val baseSystemPrompt = buildSystemPrompt()
            val systemPrompt =
                if ((provider as? com.openminis.app.provider.anthropic.AnthropicProvider)?.isOAuth == true) {
                    val prefix = com.openminis.app.auth.ClaudeOAuthManager.ANTHROPIC_OAUTH_IDENTIFIER_PROMPT
                    if (baseSystemPrompt?.startsWith(prefix) == true) baseSystemPrompt
                    else "$prefix\n\n${baseSystemPrompt ?: ""}"
                } else baseSystemPrompt

            val strategy = com.openminis.app.data.model.FallbackStrategy.default
            val fallbacks = buildFallbackProviders(provider)
            runAgentLoop(provider, systemPrompt, fallbacks, strategy, resumePrevious = true)
            drainQueuedPrompts(provider, systemPrompt, fallbacks, strategy)
        }
    }

    override fun onCleared() {
        // [T-sub-agents-queue] Nothing can re-enter a queued delegation once
        // this view model is gone; leaving the starter installed would let the
        // registry call into a cleared VM.
        //
        // Both ids are cleared, not just the current one: `activeSessionId`
        // returns the DRAFT id before the session is persisted and the real id
        // afterwards, so a hook registered under one key would otherwise be
        // unregistered under the other and leak — a stale entry pointing at a
        // cleared view model.
        for (key in setOf(activeSessionId, sessionId, realSessionId)) {
            if (key.isEmpty()) continue
            com.openminis.app.agent.jobs.AgentJobRegistry.unregisterQueuedStarter(key)
            com.openminis.app.agent.jobs.AgentJobRegistry.unregisterInterruptedCounter(key)
        }
        super.onCleared()
        // Tear down whichever shell was actually serving this VM. Terminate
        // both ids when the rename happened, since a draft shell may still
        // linger if the agent ran a tool before `ensureSession()`.
        ExecutionCoordinator.sessionDidTerminate(activeSessionId)
        if (activeSessionId != sessionId) {
            ExecutionCoordinator.sessionDidTerminate(sessionId)
        }
    }

    /**
     * T-android-new-chat-empty-residue: when the user leaves the chat screen,
     * drop sessions that were materialised in the DB (e.g. via a thinking /
     * settings toggle in `ensureSession()`) but never received a real message.
     * Without this hook, tapping "New chat" → toggling a session-scoped
     * setting → exiting leaves an empty row at the top of the session list.
     *
     * Called from ChatScreen's onDispose. Gates:
     *   - realSessionId must be non-empty (a row was actually inserted)
     *   - not currently streaming (background agent work would be lost)
     *   - persisted message count == 0 (authoritative DB check — `_messages`
     *     also contains ephemeral system-info bubbles that aren't persisted,
     *     so a state-only check would over-count).
     *
     * Safe to call multiple times; the row-existence + count gates make it
     * idempotent. After deletion we release the cached VM so a stale entry
     * doesn't linger in `ChatViewModelStore`.
     */
    fun cleanupIfEmptyOnExit() {
        val sid = realSessionId
        if (sid.isEmpty()) return
        if (_isStreaming.value) return
        if (_attachments.value.isNotEmpty()) return
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val count = chatRepository.messageCount(sid)
                if (count > 0) return@launch
                AppLogger.info(
                    TAG,
                    "cleanupIfEmptyOnExit: deleting empty session $sid (no persisted messages)",
                )
                // [T-android-child-session-delete-storage] Same funnel as every
                // other delete: rows + files + ViewModel + badges, whole tree.
                com.openminis.app.data.session.SessionDeleter.deleteTree(context, chatRepository, sid, "empty-on-exit")
            } catch (t: Throwable) {
                AppLogger.warning(TAG, "cleanupIfEmptyOnExit failed for $sid: ${t.message}")
            }
        }
    }

    fun clearError() {
        _error.value = null
    }

    private fun escapeJson(text: String): String {
        val sb = StringBuilder("\"")
        for (c in text) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> {
                    if (c.code < 0x20) sb.append("\\u%04x".format(c.code))
                    else sb.append(c)
                }
            }
        }
        sb.append("\"")
        return sb.toString()
    }

    /**
     * Convert a flat list of MessageEntity into ChatMessages, merging toolResult
     * data from user-role messages back into their corresponding AssistantBlocks.
     * This mirrors iOS's toChatMessage() which reads both toolUse and toolResult parts.
     */
    /**
     * Matches a `<system-reminder>...</system-reminder>` block, including any
     * surrounding whitespace / newlines, so a part that is *only* a reminder
     * collapses to empty text instead of leaving a blank gap. DOTALL so `.`
     * spans newlines (reminders run multi-line in the cancel/resume paths).
     *
     * Only applied at the UI-render transform — agentHistory + DB rows keep
     * the raw text so the LLM continues to see the reminder on subsequent
     * turns (matches iOS, where system-reminder text is appended to
     * agentHistory/AgentMessage parts but never to the chat-list ChatMessage).
     */
    private val systemReminderRegex =
        Regex("\\s*<system-reminder>.*?</system-reminder>\\s*", RegexOption.DOT_MATCHES_ALL)

    private fun stripSystemReminders(text: String): String =
        if (!text.contains("<system-reminder>")) text
        else systemReminderRegex.replace(text, "")

    /**
     * [T-android-retry-attachment-loss] Remove the `<user-attached-files>` XML
     * inventory from a persisted text part for DISPLAY only. The XML is now
     * persisted (iOS parity) so the model keeps the file paths across retry /
     * reload, but it must never render in the user bubble — the file chips are
     * rebuilt from the mediaRef parts instead. Mirrors the index-based strip
     * already used by editMessage / the title-fallback path.
     */
    private fun stripAttachedFilesXml(text: String): String {
        val startIdx = text.indexOf("<user-attached-files>")
        if (startIdx < 0) return text
        val endTag = "</user-attached-files>"
        val endIdx = text.indexOf(endTag, startIdx)
        return if (endIdx >= 0) {
            text.substring(0, startIdx) + text.substring(endIdx + endTag.length)
        } else {
            text.substring(0, startIdx)
        }
    }

    private fun List<MessageEntity>.toChatMessages(): List<ChatMessage> {
        // First pass: extract all toolResult data keyed by toolUseId
        val toolResultMap = mutableMapOf<String, ToolResultData>()
        for (entity in this) {
            if (entity.role != "user") continue
            try {
                val array = org.json.JSONArray(entity.partsJson)
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    if (obj.optString("type") == "toolResult") {
                        val value = obj.getJSONObject("value")
                        val toolUseId = value.optString("toolUseId", "")
                        if (toolUseId.isNotEmpty()) {
                            toolResultMap[toolUseId] = ToolResultData(
                                output = value.optString("output", ""),
                                success = value.optBoolean("success", true),
                            )
                        }
                    }
                }
            } catch (_: Exception) { /* skip malformed */ }
        }

        // Second pass: convert messages, merging tool results into blocks
        // Filter out user messages that only contain toolResult parts (no visible text)
        return mapNotNull { entity ->
            if (JournalProjection.isHidden(entity.partsJson)) return@mapNotNull null
            var text = ""
            val blocks = mutableListOf<AssistantBlock>()
            // T128: media attachments persisted under user messages as `mediaRef`
            // parts. Restored to file:// URIs (stable across app restarts) and
            // their original filenames so UserAttachmentList renders the same
            // tiles after a session reload.
            val restoredImageUris = mutableListOf<Uri>()
            // [T-android-paste-mediaref] Names are collected PER COLUMN and
            // concatenated image-first at the end, instead of appended to one
            // list in part order.
            //
            // UserAttachmentList splits with `allFileNames.drop(imageUris.size)`,
            // so the names list must be images-then-files regardless of the
            // order the parts appear in. That used to be automatic: attachment
            // mediaRefs were always written images-first. A pasted block breaks
            // it — it lives in the BODY, so it can precede an image part, and a
            // single in-order list would then start with a file name and shift
            // every image caption onto the wrong tile.
            val restoredImageNames = mutableListOf<String>()
            val restoredFileNames = mutableListOf<String>()
            // T150: file:// URIs of restored non-image attachments, in the
            // same order as the non-image suffix of the joined name list.
            // Powers the user-bubble file chip → FilePreviewScreen tap after
            // a session reload.
            val restoredAttachmentUris = mutableListOf<Uri>()

            if (entity.role == "assistant" && !entity.reasoningContent.isNullOrEmpty()) {
                blocks.add(AssistantBlock(
                    id = "thinking_restored_${entity.id}",
                    kind = "thinking",
                    content = entity.reasoningContent,
                    toolTitle = "Thinking",
                    toolStatus = ToolBlockStatus.SUCCESS,
                ))
            }

            try {
                val array = org.json.JSONArray(entity.partsJson)
                var textBlockCounter = 0
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    when (obj.optString("type")) {
                        "text" -> {
                            val raw = obj.optString("value", "")
                            // Strip <system-reminder>...</system-reminder> blocks
                            // here only — agentHistory in memory and the DB row
                            // both keep the raw text, so the LLM still sees the
                            // reminder on subsequent turns. UI just hides it.
                            // If a part was *only* a reminder, the cleaned
                            // string is empty and we skip it so we don't render
                            // a phantom blank text block.
                            // [T-android-retry-attachment-loss] Also strip the
                            // now-persisted <user-attached-files> XML so it
                            // doesn't render in the user bubble (file chips come
                            // from mediaRef parts). The DB row + agentHistory
                            // keep the raw XML so the model still sees paths.
                            val t = stripAttachedFilesXml(stripSystemReminders(raw)).let {
                                if (it != raw) it.trim() else it
                            }
                            if (t.isEmpty()) continue
                            text += t
                            // For assistant messages, also push the text as a block so
                            // the renderer can preserve the original text↔tool ordering.
                            // For user messages we keep using the `text` field only.
                            if (entity.role == "assistant") {
                                blocks.add(AssistantBlock(
                                    id = "text_restored_${entity.id}_${textBlockCounter++}",
                                    kind = "text",
                                    content = t,
                                ))
                            }
                        }
                        "toolUse" -> {
                            val value = obj.getJSONObject("value")
                            val toolId = value.optString("toolUseId", "")
                            if (toolId.startsWith("thinking_")) continue
                            val toolInput = value.optString("input", "")
                            // Merge tool result output (iOS: block.content = tr.output)
                            val result = toolResultMap[toolId]
                            val pageURL = value.optString("pageURL", "").ifEmpty { null }
                            val imgPath = value.optString("imageFilePath", "").ifEmpty { null }
                            blocks.add(AssistantBlock(
                                id = toolId,
                                kind = "tool_use",
                                toolName = value.optString("name", ""),
                                toolTitle = value.optString("description", ""),
                                toolArgs = toolInput,
                                content = result?.output?.lines()?.takeLast(80)?.joinToString("\n") ?: "",
                                toolStatus = when {
                                    result == null -> ToolBlockStatus.SUCCESS
                                    !result.success && (
                                        result.output.startsWith(CANCELLED_MARKER) ||
                                            result.output.startsWith(LEGACY_CANCELLED_MARKER)
                                    ) -> ToolBlockStatus.CANCELLED
                                    result.success -> ToolBlockStatus.SUCCESS
                                    else -> ToolBlockStatus.FAILED
                                },
                                browserURL = pageURL,
                                imageFilePath = imgPath,
                                // [T-android-gemini3-thoughtsig / #179] Restore the
                                // persisted signature onto the rebuilt block.
                                thoughtSignature = value.optString("thoughtSignature", "").ifEmpty { null },
                            ))
                        }
                        "mediaRef" -> {
                            if (entity.role != "user") continue
                            val value = obj.optJSONObject("value") ?: continue
                            val rel = value.optString("relativePath", "")
                            if (rel.isEmpty()) continue
                            val file = java.io.File(mediaStore.mediaBaseDir, rel)
                            if (!file.exists()) continue
                            val mime = value.optString("mimeType", "")
                            val name = value.optString("originalFileName", "").ifEmpty { file.name }
                            // T150: branch on mime so non-image mediaRefs land
                            // in the file-chip column instead of polluting
                            // imageUris (which feeds the image gallery).
                            //
                            // [T-android-paste-mediaref] A pasted block needs no
                            // case of its own: it is text/plain, so it takes the
                            // non-image branch and renders as the same file card
                            // as an attached document — which is exactly the
                            // requested behaviour. Note this ALSO relies on
                            // parts being written images-first; a pasted ref
                            // sits in the body (possibly before an image part),
                            // so the names/uris pairing here is positional per
                            // COLUMN, not per part index, and stays consistent
                            // because each column is appended in part order.
                            if (mime.startsWith("image/")) {
                                restoredImageUris.add(Uri.fromFile(file))
                                restoredImageNames.add(name)
                            } else {
                                restoredAttachmentUris.add(Uri.fromFile(file))
                                restoredFileNames.add(name)
                            }
                        }
                        // toolResult in user messages handled in first pass above
                    }
                }
            } catch (e: Exception) {
                // T-PARTS-FALLBACK: previously this catch dumped the entire
                // partsJson into `text` as a degraded fallback. That meant
                // any malformed (or unexpectedly large) row rendered its
                // raw JSON — including any inlined base64 — as a plain
                // user/assistant bubble, which then locked up Compose's
                // StaticLayout for tens of seconds (see HangDetector report
                // for session e84882d7 / 820 KB partsJson). Replace with a
                // short, fixed-size placeholder so the row still appears
                // (so the user can delete or scroll past it) but no longer
                // pulls megabytes through the layout pass.
                Log.w(
                    TAG,
                    "toChatMessages: failed to parse partsJson for id=${entity.id} " +
                        "len=${entity.partsJson.length} role=${entity.role}: ${e.javaClass.simpleName}: ${e.message}",
                )
                text = "(message could not be parsed: ${e.javaClass.simpleName}, " +
                    "${entity.partsJson.length} bytes)"
            }

            // Skip user messages with no visible content (toolResult-only internal messages,
            // or messages that were entirely a system-reminder). A user message that is
            // *only* an image attachment (no caption) still has visible content and must
            // not be skipped — restoredImageUris carries it.
            if (entity.role == "user" && text.isBlank() && restoredImageUris.isEmpty()) return@mapNotNull null
            // Skip assistant messages that became empty after stripping system-reminders
            // and have no tool / thinking blocks to fall back on — would otherwise
            // render as a phantom blank assistant bubble.
            //
            // [T-android-error-persist-current-turn] ...unless the row carries an
            // error: that is the carrier persistTurnError inserts for a turn that
            // failed before any output, and the banner + Retry are its whole
            // point. Once retry clears the error the carrier is skipped again,
            // so a recovered turn leaves no empty bubble.
            if (entity.role == "assistant" && text.isBlank() && blocks.isEmpty() &&
                entity.errorInfo.isNullOrBlank()
            ) return@mapNotNull null
            ChatMessage(
                id = entity.id,
                role = entity.role,
                content = text,
                imageUris = restoredImageUris,
                // Image names first, then file names — the invariant
                // UserAttachmentList's `drop(imageUris.size)` relies on.
                attachmentNames = restoredImageNames + restoredFileNames,
                attachmentUris = restoredAttachmentUris,
                toolBlocks = blocks,
                sourceDbIds = listOf(entity.id),
                // [T-android-usage-capsule-time] Only assistant rows carry
                // usage. created_at is the completion instant on this path —
                // the row is written after the turn produced its usage.
                tokenUsage = if (entity.role == "assistant") {
                    ChatTokenUsage.parse(entity.tokenUsage)
                } else null,
                completedAt = if (entity.role == "assistant") entity.createdAt else null,
                // [T-error-persist-android] Restore the persisted terminal error
                // so the inline error banner + Retry button survive a reload.
                // Coalesce a blank value to null: the UI gate is `error?.let`, so
                // a non-null "" would render an empty banner. Defends against any
                // legacy/other-writer "" row.
                error = entity.errorInfo?.takeIf { it.isNotBlank() },
            )
        }.let { messages ->
            // Merge consecutive assistant messages into one:
            // agent loop persists each turn separately, but UI should show them as a single message.
            val merged = mutableListOf<ChatMessage>()
            for (msg in messages) {
                val prev = merged.lastOrNull()
                if (msg.role == "assistant" && prev?.role == "assistant") {
                    // Merge: combine tool blocks, append text, keep the last id.
                    // Deduplicate by block.id — the agent loop may persist the same tool
                    // use in multiple consecutive turns (as it carries tool state across),
                    // and duplicated ids would crash LazyColumn's key uniqueness check.
                    // Keep the LAST occurrence so the most recent status (e.g. SUCCESS with
                    // output) wins over an earlier STREAMING placeholder.
                    val seen = mutableSetOf<String>()
                    val combinedBlocks = (prev.toolBlocks + msg.toolBlocks)
                        .asReversed()
                        .filter { seen.add(it.id) }
                        .asReversed()
                    val combinedText = when {
                        prev.content.isBlank() -> msg.content
                        msg.content.isBlank() -> prev.content
                        else -> prev.content + "\n\n" + msg.content
                    }
                    merged[merged.lastIndex] = prev.copy(
                        id = msg.id,
                        content = combinedText,
                        toolBlocks = combinedBlocks,
                        // T126-marker: keep every source dbId so Phase 2.5
                        // can resolve markers that point at any of the
                        // pre-merge rows (lastCompactedMessageId is often
                        // an assistant row that gets folded into a later
                        // assistant turn).
                        sourceDbIds = prev.sourceDbIds + msg.sourceDbIds,
                        // [T-error-persist-android] The error sticker is written
                        // to the LAST assistant row of the turn, so the later row
                        // (`msg`) wins; fall back to `prev` if only it carried one.
                        error = msg.error ?: prev.error,
                        // [T-android-usage-capsule-time] Same rule as `id` and
                        // `error` above: the merged bubble represents the whole
                        // run, so it reports the LAST turn's usage and finish
                        // time. Summing the turns would double-count — each
                        // turn's `latestContextTokens` already includes the
                        // previous ones, so a sum would report a context far
                        // larger than any single request carried.
                        tokenUsage = msg.tokenUsage ?: prev.tokenUsage,
                        completedAt = msg.completedAt ?: prev.completedAt,
                    )
                } else {
                    merged.add(msg)
                }
            }
            merged
        }
    }

    private data class ToolResultData(val output: String, val success: Boolean)

    private fun MessageEntity.toLLMMessage(): LLMMessage? =
        com.openminis.app.agent.HistoryMessageDecoder(
            com.openminis.app.agent.AndroidHistoryMedia(context, fsSessionId, mediaStore.mediaBaseDir, visionPlaceholder()),
        ).decode(this)

    private fun providedToolTitle(toolName: String, args: JSONObject): String? =
        StreamToolPresentation.provided(toolName, args)

}

/**
 * [T-ctx-warmup-trim-undecided] Result of [ChatViewModel]'s warm-up trim.
 * [decided] is false when no budget could be computed, in which case [kept] is
 * the warm-up unchanged and the per-marker cache must NOT record it.
 */
internal data class WarmUpTrim(val kept: List<LLMMessage>, val decided: Boolean)

/**
 * [T-ctx-warmup-trim-undecided] The raw-estimate token budget a compaction's
 * warm-up must fit under, or null when it cannot be known yet:
 *  - [window] unknown (null / <= 0),
 *  - [compactThreshold] null (no active entry, so no context policy),
 *  - [fixedTokens] not yet seeded (<= 0): system prompt + tool schemas are
 *    counted on every request, so a budget without them over-promises and
 *    would decide "fits" for a warm-up that does not.
 * A threshold of 0 means "compact at the window" (same as before).
 */
internal fun warmUpBudget(window: Int?, compactThreshold: Int?, ratio: Double, fixedTokens: Int): Int? {
    val w = window?.takeIf { it > 0 } ?: return null
    val threshold = compactThreshold ?: return null
    if (fixedTokens <= 0) return null
    val line = if (threshold > 0) threshold else w
    return (line / ratio).toInt() - fixedTokens
}
