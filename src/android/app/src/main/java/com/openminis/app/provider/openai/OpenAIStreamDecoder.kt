package com.openminis.app.provider.openai

import com.openminis.app.data.model.LLMError
import com.openminis.app.data.model.LLMStreamChunk
import com.openminis.app.provider.safeOptString
import org.json.JSONObject
import java.io.BufferedReader

internal class OpenAIStreamDecoder(
    private val reader: BufferedReader,
    private val isResponsesAPI: Boolean,
    private val modelId: String,
    private val emit: suspend (LLMStreamChunk) -> Unit,
) {
    private suspend fun send(chunk: LLMStreamChunk) = emit(chunk)
    private fun combineResponsesAPIIds(callId: String, itemId: String) =
        if (itemId.isEmpty()) callId else "$callId|$itemId"
    private data class ToolCallAccumulator(var id: String = "", var name: String = "", val args: StringBuilder = StringBuilder(), var started: Boolean = false)
    private val toolCallAccumulators = mutableMapOf<Int, ToolCallAccumulator>()
    private data class ResponsesToolCallAccumulator(var callId: String = "", var name: String = "", val args: StringBuilder = StringBuilder(), var started: Boolean = false)
    private val responsesToolCalls = mutableMapOf<String, ResponsesToolCallAccumulator>()
    private var sawReasoningDelta = false
    private val reasoningAccum = StringBuilder()
    private var sawReasoningField = false
    private val thinkParser = ThinkPrefixStreamParser()
    private var sseEventCount = 0
    private var contentLen = 0
    private var sawNonBlankText = false
    private var reasoningLen = 0
    private var encryptedReasoningChunks = 0
    private var toolCallEventCount = 0
    private var sawFinishReason = false
    private var sawUsageBlock = false
    private var finishReason: String? = null
    private var sentFinished = false

    suspend fun decode() {
        try {
            send(LLMStreamChunk.Started)
            var line: String?

            while (reader.readLine().also { line = it } != null) {
                val l = line ?: continue
                // Tolerate `data:` with or without the optional space — the
                // HTML5 SSE spec only treats one leading space as ignorable,
                // and some OpenAI-compatible servers (e.g. China Telecom's
                // eaichat.ctyun.cn deepseek-v4-oc endpoint) emit `data:{...}`
                // with no space. Strict `data: ` matching dropped every
                // chunk on those providers, surfacing as empty-stream errors.
                if (!l.startsWith("data:")) continue
                val payload = l.removePrefix("data:").let {
                    if (it.startsWith(" ")) it.removePrefix(" ") else it
                }
                if (payload == "[DONE]") {
                    // [T-android-think-prefix-stream] Flush whatever the parser
                    // still holds (a cross-chunk tag tail, or an unterminated
                    // <think>). Idempotent, so the finish_reason path may also
                    // call it. Withheld trailing whitespace is dropped by design.
                    thinkParser.finishTurn().let { fin ->
                        if (fin.thinking.isNotEmpty()) {
                            reasoningAccum.append(fin.thinking)
                            send(LLMStreamChunk.ThinkingDelta(fin.thinking))
                        }
                        if (fin.visible.isNotEmpty()) send(LLMStreamChunk.Text(fin.visible))
                    }
                    // [T-android-think-prefix-stream] Persist reasoning captured
                    // EITHER from the `reasoning_content` field or from a
                    // `<think>` prefix. Gating solely on sawReasoningField would
                    // stream think-tag reasoning live and then drop it — the
                    // thinking bubble would vanish on session reload.
                    if (sawReasoningField || reasoningAccum.isNotEmpty()) {
                        send(LLMStreamChunk.ReasoningContent(reasoningAccum.toString()))
                    }
                    send(LLMStreamChunk.Finished(finishReason))
                    sentFinished = true
                    break
                }

                val event = try { JSONObject(payload) } catch (e: Exception) {
                    com.openminis.app.logging.AppLogger.warning(
                        "OpenAIProvider",
                        "[T321] SSE JSON parse failed: ${e.message} payload=${payload.take(300)}"
                    )
                    continue
                }
                // [T-android-vm-store-leak] Gated on DEBUG. This dumps the ENTIRE
                // raw SSE payload for every event — ~8.5k lines and 1.4 MB in a
                // 3-hour device log, 20% of the whole file. The `$payload`
                // interpolation builds that string BEFORE logcat decides whether
                // anything is listening, so a release build paid the allocation
                // and the I/O in full. Note the T321 summary right below this
                // deliberately logs only counts and lengths "never the actual
                // delta text — keeps log volume bounded"; this line was quietly
                // undoing that.
                //
                // NOT the cause of the stutter this was filed under (the log
                // shows the heaviest SSE hour had ZERO of the blocking GCs), but
                // it is real per-token overhead on the hot streaming path and
                // does not belong in a shipped build.
                if (com.openminis.app.BuildConfig.DEBUG) {
                    android.util.Log.d("ToolChain[Provider]", "RAW SSE: $payload")
                }
                sseEventCount++

                // T321: per-event delta-field summary. Only counts/lengths,
                // never the actual delta text — keeps log volume bounded.
                run {
                    val ev = event
                    val delta = ev.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("delta")
                    val type = ev.optString("type", "")
                    if (delta != null) {
                        val cLen = delta.optString("content", "").length
                        val rcLen = delta.optString("reasoning_content", "").length
                        val rLen = delta.optString("reasoning", "").length
                            .coerceAtLeast(delta.optString("reasoning_text", "").length)
                        val tcLen = delta.optJSONArray("tool_calls")?.length() ?: 0
                        val role = delta.optString("role", "")
                        if (cLen + rcLen + rLen + tcLen > 0 || delta.has("role")) {
                            // [T-android-log-hotpath] Per SSE event: built only
                            // when someone can read it (debug build / Verbose).
                            com.openminis.app.logging.AppLogger.trace("OpenAIProvider") {
                                "[T321] SSE delta: contentLen=$cLen rcLen=$rcLen rLen=$rLen toolCalls=$tcLen role='$role'"
                            }
                        }
                        contentLen += cLen
                        reasoningLen += rcLen + rLen
                        if (tcLen > 0) toolCallEventCount += tcLen
                    } else if (type.isNotEmpty()) {
                        // Responses API event-typed diagnostics
                        val dLen = ev.optString("delta", "").length
                        if (type.contains("delta") || type == "response.completed" || type == "response.output_item.added" || type == "response.output_item.done") {
                            com.openminis.app.logging.AppLogger.trace("OpenAIProvider") {
                                "[T321] SSE responses type=$type deltaLen=$dLen"
                            }
                        }
                        if (type == "response.output_text.delta") {
                            contentLen += dLen
                            // [T-android-incomplete-keep-partial] contentLen alone
                            // can't distinguish " " from real text, and the
                            // response.incomplete handler needs that difference to
                            // decide between keeping a truncated answer and failing
                            // the turn. Track it here, where the delta text is in
                            // hand, rather than buffering the whole response.
                            if (!sawNonBlankText && ev.optString("delta", "").isNotBlank()) {
                                sawNonBlankText = true
                            }
                        }
                        if (type.startsWith("response.reasoning_")) reasoningLen += dLen
                    }
                }

                if (isResponsesAPI) {
                    // Responses API SSE parsing
                    val type = event.optString("type", "")
                    when {
                        // Reasoning text deltas — both event variants the API emits.
                        // For Codex OAuth the actual content is encrypted (echoed via
                        // include=reasoning.encrypted_content), so the .delta value
                        // is typically empty; for non-Codex Responses (forceResponsesAPI
                        // or custom base) it streams plaintext we can render.
                        // Mirrors iOS OpenAIAgentProvider.swift:374-382.
                        type == "response.reasoning_text.delta" ||
                            type == "response.reasoning_summary_text.delta" -> {
                            val delta = event.optString("delta", "")
                            if (delta.isNotEmpty()) {
                                if (!sawReasoningDelta) {
                                    com.openminis.app.logging.AppLogger.info(
                                        "OpenAIProvider",
                                        "Responses API: first reasoning delta arrived (type=$type) — streaming Thinking content"
                                    )
                                    sawReasoningDelta = true
                                }
                                send(LLMStreamChunk.ThinkingDelta(delta))
                            }
                        }
                        type == "response.output_text.delta" -> {
                            val delta = event.optString("delta", "")
                            if (delta.isNotEmpty()) send(LLMStreamChunk.Text(delta))
                        }
                        // function_call item announced — capture call_id + name, start accumulator.
                        type == "response.output_item.added" -> {
                            val item = event.optJSONObject("item") ?: continue
                            val itemType = item.optString("type", "")
                            if (itemType == "function_call" || itemType == "custom_tool_call") {
                                val itemId = item.optString("id", "")
                                val callId = item.optString("call_id", "")
                                val name = item.optString("name", "")
                                if (itemId.isNotEmpty() && callId.isNotEmpty() && name.isNotEmpty()) {
                                    responsesToolCalls[itemId] = ResponsesToolCallAccumulator(callId = callId, name = name)
                                    val combined = combineResponsesAPIIds(callId, itemId)
                                    android.util.Log.d("ToolChain[Provider]", "→ ToolUseStart (Responses) id=$combined name=$name")
                                    send(LLMStreamChunk.ToolUseStart(combined, name))
                                    responsesToolCalls[itemId]?.started = true
                                }
                            }
                        }
                        type == "response.function_call_arguments.delta" || type == "response.custom_tool_call_input.delta" -> {
                            val itemId = event.optString("item_id", "")
                            val delta = event.optString("delta", "")
                            val acc = responsesToolCalls[itemId]
                            if (acc != null && delta.isNotEmpty()) {
                                acc.args.append(delta)
                                val combined = combineResponsesAPIIds(acc.callId, itemId)
                                val input = if (type == "response.custom_tool_call_input.delta")
                                    // Keep the string/object open until output_item.done. Otherwise a
                                    // truncated raw script looks like complete, executable JSON to repair.
                                    JSONObject().put("code", acc.args.toString()).toString().dropLast(2) else acc.args.toString()
                                send(LLMStreamChunk.ToolInputDelta(combined, input))
                            } else if (acc == null) {
                                // Pre-T107 this branch silently dropped the entire tool call
                                // because no accumulator was set up — leaving the model with
                                // no real tool channel and provoking <tool_call>{...} text
                                // hallucinations. Keep a warn so any future regression here
                                // surfaces in the daily log instead of a silent failure.
                                com.openminis.app.logging.AppLogger.warning(
                                    "OpenAIProvider",
                                    "Responses API: function_call_arguments.delta for unknown item_id=$itemId — dropping"
                                )
                            }
                        }
                        // The accumulator is finalized at response.output_item.done, when the
                        // arguments stream has flushed. The completed item carries `arguments`
                        // as a JSON string — we prefer that authoritative value over our own
                        // streamed buffer in case the API ever emits a corrected payload.
                        type == "response.output_item.done" -> {
                            val item = event.optJSONObject("item") ?: continue
                            val itemType = item.optString("type", "")
                            if (itemType == "function_call" || itemType == "custom_tool_call") {
                                val itemId = item.optString("id", "")
                                val acc = responsesToolCalls.remove(itemId) ?: continue
                                val argsStr = item.optString(if (itemType == "custom_tool_call") "input" else "arguments", acc.args.toString())
                                val args = if (itemType == "custom_tool_call") JSONObject().put("code", argsStr)
                                    else try { JSONObject(argsStr) } catch (_: Exception) { JSONObject() }
                                val combined = combineResponsesAPIIds(acc.callId, itemId)
                                android.util.Log.d("ToolChain[Provider]", "→ ToolCallComplete (Responses) id=$combined name=${acc.name} args=${args.toString().take(300)}")
                                send(LLMStreamChunk.ToolCallComplete(combined, acc.name, args))
                            }
                        }
                        type == "response.failed" -> {
                            // [T-responses-terminal-events] Explicit terminal
                            // handling instead of the generic fallthrough: pull
                            // the structured error off the response object so
                            // the thrown LLMError carries the real reason (and
                            // so retry/fallback classification can act on it).
                            // Official shape: response.status == "failed",
                            // response.error = {code, message}. Mirrors iOS 637cd890.
                            val resp = event.optJSONObject("response")
                            val err = resp?.optJSONObject("error")
                            val code = err?.optString("code")?.takeIf { it.isNotEmpty() } ?: "unknown"
                            val message = err?.optString("message")?.takeIf { it.isNotEmpty() }
                                ?: "response.failed with no error detail"
                            com.openminis.app.logging.AppLogger.error(
                                "OpenAIProvider",
                                "Responses API response.failed — code=$code message=$message"
                            )
                            if (code == "server_error" || code == "rate_limit_exceeded") {
                                // Transient family: retry on the same model
                                // rather than falling back through the group.
                                throw LLMError.TransientError("[$code] $message")
                            }
                            // [T-responses-overflow-status] A streamed failure has no
                            // HTTP status, and ContextOverflowGuard deliberately refuses
                            // status-less errors — so a structured context_length_exceeded
                            // was never treated as an overflow (no ratio raise, no GH#352
                            // self-heal, silent cross-model fallback on a pinned session).
                            // That code is the request-too-big 400 in all but transport.
                            val status = if (code == "context_length_exceeded") 400 else null
                            throw LLMError.ProviderError("[$code] $message", httpStatus = status)
                        }
                        type == "response.incomplete" -> {
                            // [T-responses-terminal-events] The server ended the
                            // response early; incomplete_details.reason is
                            // "max_output_tokens" or "content_filter".
                            val reason = event.optJSONObject("response")
                                ?.optJSONObject("incomplete_details")
                                ?.optString("reason")?.takeIf { it.isNotEmpty() }
                                ?: "unknown"

                            // [T-android-incomplete-keep-partial] Only fail the
                            // turn when there is genuinely nothing to show.
                            //
                            // The old code threw unconditionally, and the comment
                            // above it ("Partial output has already been streamed
                            // — surface WHY") described an intent the code did not
                            // implement: throwing here discards the streamed text,
                            // so a truncated-but-useful answer was reported to the
                            // user as a hard failure with nothing rendered.
                            //
                            // Reported against a Responses-format relay proxying
                            // Claude: the model spent its whole budget in the
                            // reasoning phase and emitted one space of visible
                            // text, then `response.incomplete
                            // reason=max_output_tokens` with
                            // `usage.output_tokens=0`. Raising the setting could
                            // not help (the budget went to reasoning, and the
                            // relay reports output_tokens=0 regardless), so every
                            // retry failed the same way and the turn was lost.
                            //
                            // Truncation is a normal terminal condition, not an
                            // error: Chat Completions already models it as
                            // `finish_reason=length` and ends the stream
                            // normally. Treating the Responses spelling the same
                            // way keeps the two API flavours consistent and lets
                            // the agent loop persist what did arrive.
                            //
                            // `contentLen` counts text deltas actually forwarded
                            // downstream, so it is the honest test for "does the
                            // user have something to read". Whitespace-only output
                            // (the reported case) counts as nothing, since a bubble
                            // containing one space is indistinguishable from a bug.
                            val hasUsableOutput = sawNonBlankText
                            if (hasUsableOutput) {
                                com.openminis.app.logging.AppLogger.warning(
                                    "OpenAIProvider",
                                    "Responses API response.incomplete — reason=$reason; " +
                                        "keeping ${contentLen}ch of partial output (finish_reason=length)"
                                )
                                // Same terminal shape Chat Completions uses for a
                                // budget-truncated answer, so downstream code needs
                                // no new branch: the loop stops, the text persists,
                                // and the UI can mark it truncated.
                                finishReason = "length"
                                sawFinishReason = true
                                break
                            }

                            com.openminis.app.logging.AppLogger.error(
                                "OpenAIProvider",
                                "Responses API response.incomplete — reason=$reason (no usable output)"
                            )
                            throw LLMError.ProviderError(
                                "Response ended incomplete (reason: $reason)" +
                                    if (reason == "max_output_tokens") {
                                        // The old text told the user to raise Max
                                        // Output Tokens. When reasoning consumed
                                        // the budget that advice is actively
                                        // misleading — this user tried 128k, 32k
                                        // and 16k, all identical — so name the
                                        // real lever too.
                                        " — the model used its entire output budget before producing a reply" +
                                            " (often the thinking phase on a reasoning model). Try turning off or" +
                                            " lowering Deep Thinking, shortening the request, or raising the model's" +
                                            " Max Output Tokens."
                                    } else ""
                            )
                        }
                        type == "response.completed" -> {
                            val resp = event.optJSONObject("response")
                            val status = resp?.optString("status", "")
                            // When the model emitted tool calls the API returns status=completed
                            // with no stop_reason; surface "tool_use" so the agent loop knows to
                            // dispatch the calls instead of treating the turn as final.
                            val sawToolCalls = responsesToolCalls.isNotEmpty() ||
                                (resp?.optJSONArray("output")?.let { out ->
                                    var found = false
                                    for (i in 0 until out.length()) {
                                        if (out.optJSONObject(i)?.optString("type") in listOf("function_call", "custom_tool_call")) { found = true; break }
                                    }
                                    found
                                } ?: false)
                            finishReason = when {
                                sawToolCalls -> "tool_use"
                                status == "completed" -> "stop"
                                else -> status
                            }
                            if (!sawFinishReason) {
                                sawFinishReason = true
                                // [T-codex-fast-mode] The response object inside
                                // response.completed echoes the EFFECTIVE
                                // service_tier — "priority" here is definitive
                                // proof Fast Mode was honored; "default"/absent
                                // means requested-but-downgraded (OpenAI silently
                                // downgrades ineligible accounts). Mirrors iOS
                                // 63a71146.
                                val serviceTier = resp?.optString("service_tier", "")
                                    ?.takeIf { it.isNotEmpty() } ?: "n/a"
                                com.openminis.app.logging.AppLogger.info(
                                    "OpenAIProvider",
                                    "[T321] Responses finish_reason=$finishReason status=$status service_tier=$serviceTier contentLen=$contentLen reasoningLen=$reasoningLen toolCallAccumulators=${responsesToolCalls.size}"
                                )
                            }
                            resp?.optJSONObject("usage")?.let { usage ->
                                sawUsageBlock = true
                                com.openminis.app.logging.AppLogger.info(
                                    "OpenAIProvider",
                                    "[T321] Responses usage block: $usage"
                                )
                                send(LLMStreamChunk.Usage(OpenAIResponseDecoder.responsesUsage(usage)))
                            }
                        }
                        type == "response.output_text.done" -> {
                            // Text output complete, no action needed
                        }
                    }
                } else {
                    // Chat Completions API SSE parsing
                    // Check for inline error (OpenRouter sends error inside SSE with empty choices)
                    val inlineError = event.optJSONObject("error")
                    if (inlineError != null) {
                        val code = inlineError.optInt("code", 0)
                        val msg = inlineError.optString("message", "Unknown SSE error")
                        val err = OpenAIResponseDecoder.httpError(code, event.toString())
                        throw err
                    }
                    val choices = event.optJSONArray("choices")
                    if (choices != null && choices.length() > 0) {
                        val choice = choices.getJSONObject(0)
                        val delta = choice.optJSONObject("delta")

                        // Reasoning / thinking content (DeepSeek, Kimi, etc.)
                        delta?.let { d ->
                            // Track presence of either field — even an empty string
                            // counts so we can round-trip DeepSeek V4's `reasoning_content: ""`.
                            val hasRcKey = d.has("reasoning_content")
                            val hasReasoningKey = d.has("reasoning")
                            // [T-android-copilot-reasoning-text] GitHub Copilot
                            // streams reasoning as `delta.reasoning_text`, a
                            // third spelling this parser did not know. Captured
                            // stream, claude-sonnet-5 via api.githubcopilot.com:
                            //
                            //   "delta":{"content":null,"role":"assistant",
                            //            "reasoning_text":" this is a standard mod"}
                            //
                            // 274 such deltas arrived and every one was dropped —
                            // `reasoningLen=0` at stream end — so Deep Thinking
                            // was on, the model really did reason, and the user
                            // saw no thinking text. The name already existed in
                            // this file for the Responses API
                            // (`response.reasoning_text.delta`); only the
                            // chat-completions branch had never seen it.
                            val hasReasoningTextKey = d.has("reasoning_text")
                            if (hasRcKey || hasReasoningKey || hasReasoningTextKey) {
                                sawReasoningField = true
                            }
                            val rc = d.safeOptString("reasoning_content", "")
                                .ifEmpty { d.safeOptString("reasoning", "") }
                                .ifEmpty { d.safeOptString("reasoning_text", "") }
                            if (rc.isNotEmpty()) {
                                reasoningAccum.append(rc)
                                if (!sawReasoningDelta) {
                                    sawReasoningDelta = true
                                    com.openminis.app.logging.AppLogger.info(
                                        "OpenAIProvider",
                                        "Chat Completions: first reasoning_content delta arrived on $modelId — streaming Thinking content"
                                    )
                                }
                                send(LLMStreamChunk.ThinkingDelta(rc))
                            }
                            // [T-android-openrouter-reasoning-details] OpenRouter's
                            // structured `reasoning_details` array, which some
                            // models send INSTEAD of the string fields above.
                            // Skipped when this chunk already had string
                            // reasoning (same text twice); see ReasoningDetails.
                            ReasoningDetails.parse(d)?.let { rd ->
                                sawReasoningField = true
                                encryptedReasoningChunks += rd.encryptedCount
                                if (rd.text.isNotEmpty()) {
                                    reasoningAccum.append(rd.text)
                                    reasoningLen += rd.text.length
                                    if (!sawReasoningDelta) {
                                        sawReasoningDelta = true
                                        com.openminis.app.logging.AppLogger.info(
                                            "OpenAIProvider",
                                            "Chat Completions: first reasoning_details delta arrived on $modelId — streaming Thinking content"
                                        )
                                    }
                                    send(LLMStreamChunk.ThinkingDelta(rd.text))
                                }
                            }
                        }

                        // [T-android-think-prefix-stream] Text content. Models that
                        // embed reasoning as a `<think>…</think>` PREFIX of
                        // `content` (MiniMax M3, some Qwen/DeepSeek deployments)
                        // are split by ThinkPrefixStreamParser, which replaced the
                        // old extractThinkTags scanner. That scanner searched for
                        // `<think>` at ANY offset, so a reply merely explaining the
                        // tag had its prose swallowed into the thinking bubble, and
                        // it passed M3's post-`</think>` "\n\n" straight through so
                        // every such body began with a blank line.
                        delta?.safeOptString("content", "")?.let { text ->
                            if (text.isNotEmpty()) {
                                val out = thinkParser.feed(text)
                                if (out.thinking.isNotEmpty()) {
                                    reasoningAccum.append(out.thinking)
                                    send(LLMStreamChunk.ThinkingDelta(out.thinking))
                                }
                                if (out.visible.isNotEmpty()) send(LLMStreamChunk.Text(out.visible))
                            }
                        }

                        // Tool calls (parallel: keyed by index)
                        val toolCalls = delta?.optJSONArray("tool_calls")
                        if (toolCalls != null) {
                            for (i in 0 until toolCalls.length()) {
                                val tc = toolCalls.getJSONObject(i)
                                val idx = tc.optInt("index", 0)
                                val acc = toolCallAccumulators.getOrPut(idx) { ToolCallAccumulator() }

                                tc.safeOptString("id", "").let { if (it.isNotEmpty()) acc.id = it }
                                tc.optJSONObject("function")?.let { fn ->
                                    fn.safeOptString("name", "").let { if (it.isNotEmpty()) acc.name = it }
                                    fn.safeOptString("arguments", "").let { if (it.isNotEmpty()) acc.args.append(it) }
                                }

                                // Emit start exactly once per tool call
                                if (!acc.started && acc.id.isNotEmpty() && acc.name.isNotEmpty()) {
                                    acc.started = true
                                    android.util.Log.d("ToolChain[Provider]", "→ ToolUseStart id=${acc.id} name=${acc.name}")
                                    send(LLMStreamChunk.ToolUseStart(acc.id, acc.name))
                                }
                                // Emit input delta
                                if (acc.id.isNotEmpty() && acc.args.isNotEmpty()) {
                                    // [T-android-log-hotpath] Fires per tool-argument chunk.
                                    if (com.openminis.app.logging.AppLogger.traceEnabled) {
                                        android.util.Log.d("ToolChain[Provider]", "→ ToolInputDelta id=${acc.id} accumulated=${acc.args.length}chars")
                                    }
                                    send(LLMStreamChunk.ToolInputDelta(acc.id, acc.args.toString()))
                                }
                            }
                        }

                        // Finish reason
                        choice.safeOptString("finish_reason", "").let {
                            if (it.isNotEmpty()) {
                                finishReason = it
                                if (!sawFinishReason) {
                                    sawFinishReason = true
                                    com.openminis.app.logging.AppLogger.info(
                                        "OpenAIProvider",
                                        "[T321] finish_reason=$it contentLen=$contentLen reasoningLen=$reasoningLen encryptedReasoningChunks=$encryptedReasoningChunks toolCallEvents=$toolCallEventCount accumulators=${toolCallAccumulators.size}"
                                    )
                                }
                            }
                        }
                    }

                    event.optJSONObject("usage")?.let { usage ->
                        sawUsageBlock = true
                        com.openminis.app.logging.AppLogger.info(
                            "OpenAIProvider",
                            "[T321] usage block: $usage"
                        )
                        send(LLMStreamChunk.Usage(OpenAIResponseDecoder.chatUsage(usage)))
                    }
                }
            }

            // [T-android-think-prefix-stream] Stream-end flush, for streams that
            // end without a `[DONE]` sentinel. finishTurn() is idempotent, so
            // running after the [DONE] path already flushed is a no-op.
            thinkParser.finishTurn().let { fin ->
                if (fin.thinking.isNotEmpty()) {
                    reasoningAccum.append(fin.thinking)
                    send(LLMStreamChunk.ThinkingDelta(fin.thinking))
                }
                if (fin.visible.isNotEmpty()) send(LLMStreamChunk.Text(fin.visible))
            }

            // Emit ToolCallComplete for all accumulated tool calls
            for ((_, acc) in toolCallAccumulators) {
                if (acc.id.isNotEmpty() && acc.name.isNotEmpty()) {
                    val args = try { JSONObject(acc.args.toString()) } catch (_: Exception) { JSONObject() }
                    android.util.Log.d("ToolChain[Provider]", "→ ToolCallComplete id=${acc.id} name=${acc.name} args=${args.toString().take(300)}")
                    send(LLMStreamChunk.ToolCallComplete(acc.id, acc.name, args))
                }
            }
            // Drain Responses-API tool accumulators that didn't get an output_item.done
            // before the stream closed. Without this, mid-tool-call truncation (server
            // closes connection while function_call_arguments is still streaming) leaves
            // ChatViewModel.toolCalls empty: the agent loop sees no tool calls, exits,
            // and the UI hangs with the tool thumbnail spinning while the stop button
            // disappears (T247 root cause; same path hit by T237 DeepSeek truncation).
            for ((itemId, acc) in responsesToolCalls) {
                if (acc.callId.isNotEmpty() && acc.name.isNotEmpty()) {
                    val args = try { JSONObject(acc.args.toString()) } catch (_: Exception) { JSONObject() }
                    val combined = combineResponsesAPIIds(acc.callId, itemId)
                    com.openminis.app.logging.AppLogger.warning(
                        "OpenAIProvider",
                        "Stream ended mid-tool-call id=$combined name=${acc.name} argsLen=${acc.args.length} — flushing as ToolCallComplete (T248)",
                    )
                    send(LLMStreamChunk.ToolCallComplete(combined, acc.name, args))
                }
            }
            responsesToolCalls.clear()

            // T321: stream ended — final tally + warning if we never saw a
            // finish_reason. The latter is the strongest signal of a server-
            // side truncation / connection-dropped scenario.
            // [T-android-responses-missing-finished] Emit the terminal chunk for
            // streams that end without a `data: [DONE]` sentinel.
            //
            // Finished was only ever sent from the [DONE] branch. Chat
            // Completions always sends that sentinel, but the Responses API
            // terminates with `response.completed` and many relays simply close
            // the socket afterwards — no [DONE] ever arrives. The read loop then
            // exits normally, the channel closes, and no Finished is emitted.
            //
            // Downstream, ChatViewModel.runAgentLoop only ever assigns
            // turnFinishReason from a Finished chunk, so it stayed null and the
            // turn was reported as "stream closed without a finish reason" —
            // the red "连接中断，此回复可能不完整" banner on a reply that was in
            // fact complete. It looked intermittent because it depends on the
            // relay: those that do append [DONE] worked, the rest did not, which
            // is why the same model on the same account failed only sometimes,
            // and why Chat Completions models (deepseek) never showed it.
            //
            // Gated on sawFinishReason: reaching here WITHOUT one is a genuine
            // truncation, and must keep falling through to the warning below so
            // the interrupted-reply UI still fires for real drops.
            if (sawFinishReason && !sentFinished) {
                // Mirror the [DONE] branch's ordering: flush the think-tag
                // parser and reasoning blob before the terminal chunk, or a
                // trailing <think> tail would be dropped and reasoning would
                // vanish on reload.
                thinkParser.finishTurn().let { fin ->
                    if (fin.thinking.isNotEmpty()) {
                        reasoningAccum.append(fin.thinking)
                        send(LLMStreamChunk.ThinkingDelta(fin.thinking))
                    }
                    if (fin.visible.isNotEmpty()) send(LLMStreamChunk.Text(fin.visible))
                }
                if (sawReasoningField || reasoningAccum.isNotEmpty()) {
                    send(LLMStreamChunk.ReasoningContent(reasoningAccum.toString()))
                }
                send(LLMStreamChunk.Finished(finishReason))
                sentFinished = true
                com.openminis.app.logging.AppLogger.info(
                    "OpenAIProvider",
                    "[T321] stream ended without [DONE] — emitted Finished(finishReason=$finishReason) from tail"
                )
            }

            if (!sawFinishReason) {
                com.openminis.app.logging.AppLogger.warning(
                    "OpenAIProvider",
                    "[T321] stream ended WITHOUT finish_reason: events=$sseEventCount " +
                        "contentLen=$contentLen reasoningLen=$reasoningLen " +
                        "encryptedReasoningChunks=$encryptedReasoningChunks " +
                        "toolCallEvents=$toolCallEventCount sawUsage=$sawUsageBlock model=$modelId"
                )
                // [T-android-fallback-providererror] A stream that produced
                // NOTHING AT ALL — no SSE event, no content, no tool call, no
                // usage — did not "finish early", it never started. Completing
                // the flow normally reports an empty successful turn, so the
                // group-fallback decision is never reached and a dead endpoint
                // keeps its turn instead of handing off to the next model.
                // TransientError (no httpStatus: there was no HTTP failure, the
                // body was simply empty) puts it on the same-provider retry
                // ladder first, and group fallback picks it up if that is
                // exhausted.
                //
                // Deliberately NARROW. The two richer cases keep their existing,
                // better-suited recovery and must NOT be converted to throws:
                //   * partial content then a drop  -> ChatViewModel's
                //     [T-android-silent-stream-drop] interrupted-reply banner,
                //     which preserves the text the user already read;
                //   * an empty turn that still had events -> the
                //     <system-reminder> one-round retry + empty-response hint.
                // Throwing for either would discard those recoveries.
                if (sseEventCount == 0 && contentLen == 0 && reasoningLen == 0 &&
                    toolCallEventCount == 0 && !sawUsageBlock
                ) {
                    throw LLMError.TransientError(
                        "stream closed without producing any data (model=$modelId)"
                    )
                }
            } else {
                com.openminis.app.logging.AppLogger.info(
                    "OpenAIProvider",
                    "[T321] stream complete: events=$sseEventCount contentLen=$contentLen " +
                        "reasoningLen=$reasoningLen toolCallEvents=$toolCallEventCount sawUsage=$sawUsageBlock"
                )
            }
        } catch (e: Exception) {
            // T321: never silently swallow — log message + top-3 stack frames.
            val frames = e.stackTrace.take(3).joinToString(" | ") { "${it.className}.${it.methodName}:${it.lineNumber}" }
            com.openminis.app.logging.AppLogger.error(
                "OpenAIProvider",
                "[T321] stream parse exception: ${e.javaClass.simpleName}: ${e.message} @ $frames " +
                    "(events=$sseEventCount contentLen=$contentLen reasoningLen=$reasoningLen)"
            )
            throw e
        }
    }
}
