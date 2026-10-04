# Android agent boundaries refactor

Baseline: `99f259a` (pushed before starting this refactor).

## First structural slice

- `agent/CompactionSummarizer.kt` owns streaming summary execution, idle detection, usage-confirmed request affinity, structured prefix reuse and fail-closed summary tool handling. It has no ViewModel, database or tool dispatcher dependency.
- `agent/CompactionCoordinator.kt` owns bounded transcript rendering, recursive splitting, call-budget enforcement, sibling affordability and textual merging. The caller supplies the existing run counter and receives progress values, not stream chunks. Each summarize attempt captures one provider/context; outer model fallback reconstructs the context for the next candidate.
- `agent/JournalProjection.kt` centralizes hidden journal recognition, owned runtime-message decoding and user-turn classification. UI visibility and model replay now use the same ownership rule.
- `data/model/SessionTokenStats.kt` owns normalized usage aggregation and derived rates, independent of Compose/ViewModel. Current database ordering, weighted cache denominator, legacy stream-speed matching and visibility semantics are preserved.

The ViewModel still owns model selection/fallback, the outer deadline, cancellation, branch/marker commits and UI progress. No database migration, provider wire schema change, extra paid warming, tool API change or destructive data operation was introduced.

## Validation

- 11 JVM tests passed (7 existing plus 4 boundary regressions).
- Boundary tests cover journal/task distinctions, malformed usage records, weighted/latest cache rates, legacy stream-speed matching, structured summary prefix replay, tool-request rejection, warm-state reset, bounded splitting and no extra merge call.
- Debug and Release builds passed; native 16 KB alignment checks passed.
- Three manual device tests passed, including a disposable Room database accounting row across close/reopen, session duplication and rewind. Model attribution, rates, preview stability and exclusion from model input were checked without clearing the personal database.
- Live requested-model `gpt-6.1-sol` regression passed: six leading serialized input items, system instructions, tools and key matched the preceding request. Summary usage **450 fresh + 4608 cached = 5058 input (91.1% hit rate)** was persisted exactly once with purpose `compaction`. A follow-up and a force-stop/restart reload both recalled `REFACTOR_LEDGER_PROBE` without tools; actual model input contained no accounting metadata and the journal still had one runtime snapshot and one accounting record. This is a measured request, not a promise of uniform cache hits.
- Evidence: `/tmp/openminis-history-ledger-device.log`, `/tmp/openminis-history-ledger-live-after.json`, `/tmp/openminis-history-ledger-live-continuity.json`, `/tmp/openminis-history-ledger-live-restarted.json`, `/tmp/openminis-history-ledger-release.log`. Final Release was cover-installed without uninstalling or clearing personal data.

## Second structural slice

- `agent/HistoryProjection.kt` owns effective-history reconstruction: v1/v2 anchors, bounded user-turn lookback, paired large-result pruning, fixed-per-marker warm-up decisions and summary injection into both text representations. Unresolvable anchors preserve full branch history. The ViewModel supplies the live token-fit decision, not the history algorithm.
- `agent/ToolHistorySanitizer.kt` owns normalized call/output pairing repair and the final in-flight assistant exemption, without mutating stored history.
- `data/model/RequestUsageRecord.kt` defines normalized usage plus request purpose. Conversation records use the same encoder as compaction. Auxiliary usage is stored as hidden branch-owned `request-usage` journal records with request-time model attribution; Room allocates sort order and inserts atomically. These records do not change session previews, become chat bubbles, or replay into model input.
- Summary streams retain the last usage report and account once per request, not once per preliminary/final usage chunk. Accounting observes reported usage even on failed streams; it is not a complete provider billing invoice. Old unrecorded summaries are not retroactively estimated.
- Current-session totals and latest-request rate now include recorded compaction requests. The sheet labels included auxiliary requests. Conversation context pressure is not overwritten by the summary's input size. Subagents continue to account in their own sessions, not again in the parent.

## Remaining work

This is not a complete agent architecture rewrite. Media-aware message decoding still belongs to the ViewModel. Provider protocol/authentication concerns remain combined. The full lifecycle/fallback coordinator and historical billing-scope questions remain separate work; do not duplicate child-session usage into parent totals without defining lineage semantics. Preserve restart/fork/rewind behavior and verify serialized requests, not just Kotlin object equality.
