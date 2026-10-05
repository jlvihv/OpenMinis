package com.openminis.app.provider.openai

import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.ModelOverrides
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.data.model.hasImageInput
import com.openminis.app.provider.thinking.ThinkingResolveContext
import com.openminis.app.provider.thinking.ThinkingRuleResolver
import org.json.JSONArray
import org.json.JSONObject

internal class OpenAIConversationEncoder(
    private val model: LLMModel,
    private val endpoint: Endpoint,
    private val settings: Settings,
) {
    data class Endpoint(
        val basePath: String,
        val oauth: Boolean,
        val forceChat: Boolean,
        val azure: Boolean,
        val openRouter: Boolean,
        val dashScope: Boolean,
        val mistral: Boolean,
        val cerebras: Boolean,
        val xai: Boolean,
        val unifiedEffort: Boolean,
    )
    data class Settings(
        val cacheKey: String,
        val serviceTier: String?,
        val fastMode: Boolean,
        val offEffort: String?,
        val ruleInstanceId: String?,
        val overrides: ModelOverrides?,
        val extraBody: Map<String, Any?>,
    )
    private val basePath get() = endpoint.basePath
    private val isOAuth get() = endpoint.oauth
    private val forceChatCompletions get() = endpoint.forceChat
    private val isAzure get() = endpoint.azure
    private val isOpenRouter get() = endpoint.openRouter
    private val isDashScope get() = endpoint.dashScope
    private val isMistral get() = endpoint.mistral
    private val isCerebras get() = endpoint.cerebras
    private val isXAI get() = endpoint.xai
    private val usesUnifiedReasoningEffort get() = endpoint.unifiedEffort
    private val thinkingRuleInstanceId get() = settings.ruleInstanceId
    private val modelOverrides get() = settings.overrides
    private val chatExtraBody get() = settings.extraBody
    private val needsOpenRouterAnthropicCacheControl get() = isOpenRouter && model.id.lowercase().startsWith("anthropic/")
    private fun resolvedServiceTier() = settings.serviceTier
    private fun explicitOffEffort() = settings.offEffort

    internal fun buildRequestBody(
        messages: List<LLMMessage>,
        systemPrompt: String?,
        maxTokens: Int,
        stream: Boolean,
        temperature: Double?,
        imageParts: List<LLMMessage.ImagePart>,
        tools: List<AgentToolDefinition> = emptyList(),
        thinkingLevel: ThinkingLevel = ThinkingLevel.OFF,
    ): JSONObject {
        // T264: cross-provider image sanitization, mirrors iOS
        // OpenAIAgentProvider.swift:744-768 / 900-918. When the target model
        // doesn't declare "image" in inputModalities (e.g. DeepSeek V4 after
        // user sent image to GPT-5.5 then switched provider), serialize a
        // text placeholder instead of an image_url block — otherwise the
        // server returns "400 unknown variant `image_url`". Decided once
        // here so the structured-contentParts loop and the legacy
        // imageParts loop below stay consistent.
        // [T-android-vision-native-check-misses-image_input] hasImageInput, not a
        // raw membership test: this compared the catalog string EXACTLY, so
        // "image_input" (OpenAI/OpenRouter) and "Image" both read as "no vision"
        // and the pixels below were swapped for a text placeholder.
        val supportsImages = model.hasImageInput
        val body = JSONObject()
        body.put("model", model.id)
        if (isOpenRouter) {
            body.put("max_tokens", maxTokens)
        } else {
            body.put("max_completion_tokens", maxTokens)
        }
        body.put("stream", stream)

        // [T-android-xai-priority] xAI Priority Processing, driven by the same
        // app-level Fast Mode toggle as Codex (FastModePrefs), read here at
        // request-build time so a flip applies to the very next request.
        // Emitted only for xAI-capable providers, so every other provider's
        // body is unchanged — `service_tier` is an xAI extension and a strict
        // OpenAI-compatible relay would 400 on the unknown key.
        resolvedServiceTier()?.let { body.put("service_tier", it) }

        if (temperature != null) {
            body.put("temperature", temperature)
        }

        if (stream && !isOpenRouter) {
            body.put("stream_options", JSONObject().put("include_usage", true))
        }

        // Provider-specific thinking params. We always call this — some
        // models (e.g. DeepSeek V4) reason by default and need an explicit
        // `disabled` signal when the user toggles thinking off.
        //
        // [T-android-mistral-reasoning-422] …EXCEPT on Mistral, which rejects
        // the thinking request parameters outright with
        // `422 extra_forbidden body.reasoning`. Mirrors iOS
        // OpenAIAgentProvider.swift's `if !provider.isMistral` gate around this
        // same call (4592ca9b). Until now [isMistral] only suppressed the
        // message-level echo ([forbidReasoningField], 0839f019 / GH
        // OpenMinis#87) — the request-parameter half of that fix was never
        // ported, so an enabled thinking level still put `reasoning_effort` on
        // the wire to api.mistral.ai.
        if (!isMistral) {
            injectThinkingParams(body, thinkingLevel, maxTokens)
        }

        // [OpenMinis#191] Opt this request into Anthropic prompt caching.
        // OpenRouter passes the field through to Anthropic but never injects it
        // for us, so without it Claude requests cache nothing at all.
        //
        // Top-level "automatic" form: the breakpoint advances to the last
        // cacheable block on its own as the conversation grows, which is what an
        // agent loop wants — the per-block form would need us to hand-manage a
        // 4-breakpoint budget across a mutating history.
        //
        // Gated on host + `anthropic/` prefix, so no other model's body changes.
        if (needsOpenRouterAnthropicCacheControl) {
            body.put("cache_control", JSONObject().put("type", "ephemeral"))
        }

        // Tools
        if (tools.isNotEmpty()) {
            val toolsArray = JSONArray()
            for (tool in tools) {
                toolsArray.put(tool.toOpenAIJson())
            }
            body.put("tools", toolsArray)
            body.put("tool_choice", "auto")
        }

        // Mirror iOS OpenAIAgentProvider.flattenChatCompletionsMessages —
        // echo reasoning_content on prior assistant turns when:
        //   - user requested thinking this turn, OR the model always reasons (forced); AND
        //   - the model isn't explicitly known to reject reasoning.
        // Prevents 400s from Kimi / DeepSeek / GLM / QwQ that reject
        // multi-turn history missing reasoning_content once thinking is on.
        val modelAlwaysReasons = model.supportsReasoning == true
        val modelMayReason = model.supportsReasoning ?: true
        // [T-android-mistral-reasoning-422] Mistral forbids reasoning_content on
        // assistant messages entirely (closed schema → HTTP 422
        // extra_forbidden), so suppress BOTH the captured echo and the ""
        // placeholder for that endpoint. This cannot be driven by capability
        // metadata: MiMo/DeepSeek require the field's PRESENCE on multi-turn
        // history while Mistral forbids it, and neither advertises
        // supportsReasoning via /v1/models — opposite requirements on the same
        // generic openAI provider path. Hence a spec-driven vendor flag.
        // [T-android-cerebras-reasoning-400] Cerebras (OpenMinis#361) has the
        // same closed assistant schema: echoing a captured reasoning_content in
        // history answers `400 … property
        // 'messages.N.assistant.reasoning_content' is unsupported`, so turn 1
        // works and turn 2 onwards always fails. Endpoint-scoped so MiMo /
        // DeepSeek — which require the field's PRESENCE — are untouched.
        val forbidReasoningField = isMistral || isCerebras
        val includeReasoning =
            (thinkingLevel.isEnabled || modelAlwaysReasons) && modelMayReason && !forbidReasoningField
        body.put("messages", ChatMessagesEncoder.encode(messages, systemPrompt, imageParts, supportsImages, includeReasoning))

        // [T-android-model-use-passthrough-mode GH#72] Merge user-supplied extra
        // body fields verbatim (no OpenAI→native conversion — callers own the
        // shape). User keys win over our defaults, but `model` is force-kept so a
        // stray override can't misroute. Mirrors generateImage's merge + iOS.
        mergeModelOverridesBody(body, temperature)
        mergeChatExtraBody(body)

        return body
    }

    /**
     * [T-android-model-use-passthrough-mode GH#72] Shared verbatim merge of
     * [chatExtraBody] into a request body, applied by BOTH the chat/completions
     * and responses builders so no endpoint can forget the passthrough. User
     * keys overwrite; `model` is force-restored last. Skipped for Codex OAuth
     * (its body is part of the client fingerprint and must stay untouched).
     */
    /**
     * [T-android-model-custom-params] Apply the user's per-model overrides to a
     * request body. Called by BOTH builders, mirroring [mergeChatExtraBody].
     *
     * @param explicitTemperature the temperature the CALLER passed. Non-null
     *   means the caller forced a specific value (today only
     *   ModelUseOffloadHandler does, from `minis-model-use`'s own argument), and
     *   an explicit per-call value must beat a stored per-model default — so the
     *   override is applied only when this is null. Every ordinary chat path
     *   passes null, which is precisely the case the user's setting exists for.
     *
     * Ordering: this runs BEFORE [mergeChatExtraBody], so an explicit per-call
     * `extra_body` still wins over a stored override on the same key.
     *
     * Codex OAuth is exempt for the same reason [mergeChatExtraBody] exempts it:
     * that body is part of the client fingerprint the ChatGPT backend validates,
     * and injecting user keys into it risks the whole path 400-ing.
     */
    private fun mergeModelOverridesBody(body: JSONObject, explicitTemperature: Double?) {
        val o = modelOverrides ?: return
        if (isOAuth && !forceChatCompletions) return  // Codex OAuth exemption

        // null means "inherit / send nothing", never 0.0 — 0.0 is a legitimate
        // fully-deterministic temperature, which is why the field is nullable.
        if (explicitTemperature == null) {
            o.temperature?.let { body.put("temperature", it) }
        }
        o.topP?.let { body.put("top_p", it) }

        // extraBodyParams is a kotlinx JsonObject while the body is org.json,
        // so round-trip through the serialized form — the same idiom the export
        // path already uses (ProviderRepository ~2768) — which preserves nested
        // structure instead of flattening it to a toString().
        o.extraBodyParams?.let { extra ->
            runCatching { JSONObject(extra.toString()) }.getOrNull()?.let { parsed ->
                for (k in parsed.keys()) body.put(k, parsed.get(k))
            }
        }
        // `model` is ours to decide, exactly as mergeChatExtraBody restores it.
        body.put("model", model.id)
    }

    private fun mergeChatExtraBody(body: JSONObject) {
        if (chatExtraBody.isEmpty()) return
        if (isOAuth && !forceChatCompletions) return  // Codex OAuth exemption
        for ((k, v) in chatExtraBody) body.put(k, v ?: JSONObject.NULL)
        body.put("model", model.id)
    }

    /**
     * Walk every assistant.tool_calls entry and every role:"tool"
     * tool_call_id in order, renaming any duplicate id to `{id}-{N}`.
     * The first occurrence keeps the raw id; subsequent collisions get
     * a numeric suffix starting at 2. Renames propagate to each pair's
     * matching role:"tool" reply by remembering the latest rename per
     * raw id (the reply is required to immediately follow its claiming
     * assistant tool_calls on this provider).
     */
    /**
     * T302: takes the pre-serialized body string instead of the JSONObject so
     * the caller can serialize once and reuse the result for the debug log,
     * the OAuth byte build, and the OkHttp RequestBody. Per-call peak heap
     * dropped by ~2× the body size (often tens of MB on long agent loops).
     */
    private fun clampEffortForModel(effort: String): String {
        val lid = model.id.lowercase()
        // [T-fallback-thinking-preclamp] Match the FAMILY substring, not one
        // spelling: catalog docs say "MiMo-2.5" but the live API returns
        // "mimo-v2.5" / "mimo-v2.5-pro", which the old "mimo-2.5" match missed
        // (mirrors iOS 72968c4f).
        return if (effort == "xhigh" && (lid.contains("mimo") || lid.contains("agnes"))) "high" else effort
    }

    /**
     * Inject the provider-specific thinking parameters for one request.
     *
     * [T-thinking-rules-phase1] The body of this function used to be an if-return chain
     * keyed on model-id substrings. That logic now lives in [ThinkingRuleResolver] as a
     * data-driven rule registry (design §4/§5); this remains as the call-site-compatible
     * entry point so every caller — and the golden snapshot that pins this exact
     * behaviour — is unchanged.
     *
     * Behaviour is byte-for-byte identical to the pre-refactor chain, enforced by
     * ThinkingWireGoldenSnapshotTest (119 rows generated against the old code and
     * committed before the refactor, fdc28e2b).
     *
     * PHASE 1 SCOPE: OpenAI-compatible endpoints only. Gemini and Anthropic keep their
     * own emitters and are not routed through the resolver yet.
     */
    private fun injectThinkingParams(body: JSONObject, level: ThinkingLevel, maxTokens: Int) {
        // [T-android-thinking-level-arch] `level` is already clamped to the model ceiling
        // by LLMProvider.streamMessage/sendMessage — do NOT re-clamp here.
        val ctx = ThinkingResolveContext(
            modelId = model.id,
            instanceId = thinkingRuleInstanceId,
            supportsReasoning = model.supportsReasoning,
            declaredEffortValues = model.reasoningEffortValues,
            // [OpenMinis#163] null (catalog silent) must read as false here —
            // only an affirmative declaration may suppress the field.
            declaresNoEffortTiers = model.declaresNoEffortTiers == true,
            level = level,
            maxTokens = maxTokens,
            isOpenRouter = isOpenRouter,
            usesUnifiedReasoningEffort = usesUnifiedReasoningEffort,
            isMistral = isMistral,
            isDashScope = isDashScope,
            isCerebras = isCerebras,
            isXAI = isXAI,
            offEffort = explicitOffEffort(),
        )
        val trace = ThinkingRuleResolver.apply(body, ctx)
        // [T-thinking-rules-observability] Design §8 / GH OpenMinis#100: which rule
        // actually won must be inspectable, or a rule layer just replaces one hidden
        // variable with a more complicated one. minis-config exposure is Phase 2.
        com.openminis.app.logging.AppLogger.info(
            "Thinking",
            "[resolve] model=${model.id} level=${level.name} ${trace.logLine}",
        )
    }

    /**
     * [T-reasoning-effort-data-driven] Snap an effort string onto the tiers the
     * model actually declares. Mirrors iOS
     * `OpenAIAgentProvider.clampEffort(_:to:)` — keep both in sync.
     *
     * Necessary because the catalog's effort sets are far from uniform
     * (["low","medium","high"], ["high","max"], ["high","xhigh"], …). Sending an
     * undeclared tier is the same class of failure the MiMo/Agnes xhigh clamp
     * already guards against ("Invalid reasoning_effort: xhigh" 400s).
     *
     * Nearest-tier semantics: step down to the closest declared tier at or below
     * the request; only if none exists step up to the lowest declared one.
     * Downgrading is preferred because overshooting costs money and latency the
     * user did not ask for. Null/empty values pass the string through unchanged.
     */
    internal fun buildResponsesAPIBody(
        messages: List<LLMMessage>,
        systemPrompt: String?,
        maxTokens: Int,
        stream: Boolean,

        imageParts: List<LLMMessage.ImagePart> = emptyList(),
        tools: List<AgentToolDefinition> = emptyList(),
        thinkingLevel: ThinkingLevel = ThinkingLevel.OFF,
    ): JSONObject {
        // T264: same vision-capability gate as buildRequestBody. Responses API
        // path (Codex OAuth) is currently always wired to a vision-capable
        // GPT-5.x so this branch is defensive rather than load-bearing, but
        // keeping the two paths symmetric prevents future regressions when
        // a non-vision model gets routed through Responses (e.g. via
        // forceResponsesAPI on a custom provider).
        // [T-android-vision-native-check-misses-image_input] hasImageInput, not a
        // raw membership test: this compared the catalog string EXACTLY, so
        // "image_input" (OpenAI/OpenRouter) and "Image" both read as "no vision"
        // and the pixels below were swapped for a text placeholder.
        val supportsImages = model.hasImageInput
        val body = JSONObject()
        body.put("model", model.id)
        body.put("stream", stream)
        // [T-android-xai-priority] xAI documents service_tier for both text
        // inference endpoints. xAI currently always resolves to the Chat
        // Completions path (forceChatCompletions), so this is belt-and-braces
        // — but keeping the two builders in step means a future routing change
        // does not silently drop the user's Fast Mode choice.
        resolvedServiceTier()?.let { body.put("service_tier", it) }
        body.put("store", false)
        body.put("parallel_tool_calls", true)
        // Pi-style real conversation identity survives edits, compaction and image-only
        // input. This is an affinity hint, not a guarantee of a provider cache hit.
        body.put("prompt_cache_key", settings.cacheKey)
        // T-responses-include: `include: ["reasoning.encrypted_content"]` is a
        // ChatGPT-backend-only field. Third-party Responses-API-compatible
        // proxies (non-OpenAI) don't recognize it and reject the request with
        // 400. Mirrors iOS OpenAIAgentProvider.swift:404 which gates this
        // strictly behind isCodexOAuth. OpenAI's first-party Responses API
        // also accepts the field, so we keep it on for OAuth (Codex) only —
        // the encrypted reasoning content is what lets the ChatGPT backend
        // re-attach prior reasoning across turns without store=true.
        if (isOAuth) {
            body.put("include", JSONArray().put("reasoning.encrypted_content"))
        }
        // Thinking level → Responses API `reasoning.effort`. Mirrors iOS
        // OpenAIAgentProvider.swift:327-338. Pre-T119 this was hardcoded to
        // "low" regardless of the user's setting, so toggling Thinking
        // High/Medium/Off had no effect on GPT-5.x via the Responses path.
        // - When the user has thinking enabled → map their level to the
        //   matching effort string.
        // - When off but using Codex OAuth → fall back to "low" because the
        //   ChatGPT backend rejects requests without a `reasoning` object.
        // - Else → omit the field so the upstream applies its own default.
        // [T-android-codex-thinking-summary] `summary: "auto"` opts in to
        // streaming the human-readable reasoning SUMMARY (delivered as
        // `response.reasoning_summary_text.delta` SSE events with non-empty
        // `delta`). Without it the Responses API / Codex backend returns ONLY
        // `encrypted_content` — the reasoning deltas arrive empty, so the
        // Thinking region never renders even though the model reasoned (token
        // usage shows it did). This was the Codex-OAuth "thinking on but UI
        // shows nothing" bug (user report). Mirrors iOS OpenAIAgentProvider.swift:415
        // (`["effort": effort, "summary": "auto"]`). OpenAI ignores the variant
        // it doesn't support and falls back to an auto-equivalent, so it's safe
        // on every Responses-flavor endpoint.
        // [T-android-xhigh-effort-clamp] Also clamp on the Responses API path:
        // the reasoning.effort field is the same name/values as Chat Completions
        // and would send xhigh too. MiMo-2.5/Agnes normally use Chat Completions
        // (the reported 400/422), but a user could flip useResponsesAPI on, so
        // guard it here as well — only xhigh for those two families is affected.
        // [T-android-thinking-level-arch] `thinkingLevel` is already clamped by
        // LLMProvider.streamMessage/sendMessage before reaching here.
        val effort = if (thinkingLevel.isEnabled) {
            mapThinkingLevelToResponsesEffort(thinkingLevel)?.let { clampEffortForModel(it) }
        } else null
        when {
            // [T-android-mistral-reasoning-422] Mistral rejects the reasoning
            // request parameter outright (`422 extra_forbidden body.reasoning`,
            // GH OpenMinis#87). The gate added alongside injectThinkingParams
            // covers only the Chat Completions path; this builder is a SECOND,
            // independent injection site that a Mistral instance with
            // useResponsesAPI enabled reaches ungated. For Mistral the answer to
            // "should any thinking field be sent" is NEVER, on every request
            // path — so suppress the whole block. Must stay FIRST so it wins over
            // the isOAuth fallback below.
            isMistral -> {}
            effort != null -> body.put(
                "reasoning",
                JSONObject().put("effort", effort).put("summary", "auto"),
            )
            isOAuth -> body.put(
                "reasoning",
                JSONObject().put("effort", "low").put("summary", "auto"),
            )
            // [T-thinking-off-explicit] Thinking OFF on a reasoning-capable
            // model: send the explicit off tier instead of omitting `reasoning`
            // — omission lets the vendor default kick in. Same ALLOWLIST as the
            // Chat path (official OpenAI → "none", Volcano Ark → "minimal");
            // vendors with undocumented off semantics keep the historical
            // omission. No summary/include: nothing should stream back.
            // Mirrors iOS OpenAIAgentProvider ff60c818's Responses off branch.
            !thinkingLevel.isEnabled && model.supportsReasoning == true &&
                !model.id.lowercase().let { it.contains("mimo") || it.contains("agnes") } -> {
                explicitOffEffort()?.let { offEffort ->
                    // [OpenMinis#377] Clamp the off tier onto what this model
                    // accepts. explicitOffEffort() answers "which vendor is
                    // this" (official OpenAI → "none") and never "what does
                    // this model take" — so gpt-6-astra, which declares
                    // [low…max] and rejects "none", got HTTP 400
                    // invalid_request_error on every compaction, including all
                    // four halving retries. The enabled branch above has always
                    // clamped; this one did not.
                    val safe = ThinkingRuleResolver.clampOffEffort(
                        offEffort,
                        model.reasoningEffortValues,
                    )
                    body.put("reasoning", JSONObject().put("effort", safe))
                }
            }
        }

        // [T-responses-max-output-tokens] The builder received maxTokens but
        // never wrote it into the body, so Responses-flavor vendors fell back
        // to their (often tiny) defaults and truncated. Same guard as iOS
        // 637cd890/5f148144: maxTokens > 0, and Codex OAuth excluded — the
        // codex_cli_rs body shape is a client fingerprint and must not carry
        // fields the real CLI doesn't send.
        if (maxTokens > 0 && !isOAuth) {
            body.put("max_output_tokens", maxTokens)
        }

        // [T-codex-fast-mode] Fast tier injection (mirrors iOS fb671083 +
        // 838ba929). Wire value verified against openai/codex source
        // (codex-rs/protocol config_types.rs): ServiceTier::Fast sends
        // service_tier="priority" — "fast" is only the UI name, so this stays
        // inside the codex_cli_rs client fingerprint on the OAuth route. Gate
        // is toggle + gpt-family model only: this builder IS the Responses
        // path, and Responses relays (e.g. sub2api) normalize/pass the tier
        // through, so no isOAuth narrowing. Ineligible upstreams ignore the
        // field or silently downgrade (receipt visible via the
        // response.completed service_tier log).
        //
        // [T-android-xai-priority] Guarded on the key being absent so this
        // cannot clobber a per-instance tier set above. The two gates are
        // disjoint today (that toggle is xAI-only, this one gpt-only, and xAI
        // never reaches this builder), so the guard changes no current
        // behaviour — it just means neither feature can silently overwrite the
        // other if either's routing widens later. Both want the same value
        // anyway, so "first writer wins" loses nothing.
        if (!body.has("service_tier") &&
            settings.fastMode &&
            model.id.contains("gpt", ignoreCase = true)
        ) {
            body.put("service_tier", "priority")
        }

        if (systemPrompt != null) {
            body.put("instructions", systemPrompt)
        }

        // Tools — flat shape required by Responses API ({type, name, description,
        // parameters}), distinct from Chat Completions' wrapped {type, function:{...}}.
        // Until this branch existed, Responses-API requests went out with no `tools`
        // field at all, so the model invented its own <tool_call>{...} text format.
        if (tools.isNotEmpty()) {
            val toolsArray = JSONArray()
            for (tool in tools) {
                toolsArray.put(tool.toResponsesAPIJson())
            }
            body.put("tools", toolsArray)
            body.put("tool_choice", "auto")
        }

        // Mirrors iOS convertMessagesResponsesAPI (OpenAIAgentProvider.swift:895):
        // structured content parts become typed input items — function_call /
        // function_call_output — instead of free-text role/content pairs.
        body.put("input", ResponsesInputEncoder.encode(messages, imageParts, supportsImages))

        // [T-android-model-custom-params] buildResponsesAPIBody takes no
        // temperature parameter (no caller supplies one on this path), so there
        // is no "caller forced a value" case to defer to — pass null.
        mergeModelOverridesBody(body, explicitTemperature = null)

        // [T-android-model-use-passthrough-mode GH#72] Same verbatim merge as the
        // chat-completions builder. Skipped for Codex OAuth inside mergeChatExtraBody.
        mergeChatExtraBody(body)

        return body
    }

    /**
     * Map ThinkingLevel → Responses API `reasoning.effort` string. Mirrors
     * iOS reasoningEffort(for:level:) (OpenAIAgentProvider.swift:531).
     * Returns null when the level is OFF — caller decides whether to omit
     * the `reasoning` field entirely or fall back to "low" (Codex requires it).
     */
    private fun mapThinkingLevelToResponsesEffort(level: ThinkingLevel): String? = when (level) {
        ThinkingLevel.OFF -> null
        ThinkingLevel.LOW -> "low"
        ThinkingLevel.MEDIUM -> "medium"
        ThinkingLevel.HIGH -> "high"
        ThinkingLevel.XHIGH -> "xhigh"
        // [T-android-thinking-level-arch] MAX → "max"; ULTRA also → "max" —
        // the Responses/Codex endpoint rejects a literal "ultra"; ultra is a
        // client-side "Max + orchestration" concept only (mirrors iOS).
        ThinkingLevel.MAX, ThinkingLevel.ULTRA -> "max"
    }

    /**
     * Responses-API tool shape — flat {type, name, description, parameters},
     * NOT the Chat Completions wrapper {type, function:{...}}. Mirrors iOS
     * convertToolsResponsesAPI (OpenAIAgentProvider.swift:977).
     */
    private fun AgentToolDefinition.toResponsesAPIJson(): JSONObject {
        val props = JSONObject()
        for ((key, param) in parameters) {
            props.put(key, param.toJson())
        }
        val params = JSONObject().apply {
            put("type", "object")
            put("properties", props)
            if (required.isNotEmpty()) put("required", JSONArray(required))
        }
        return JSONObject().apply {
            put("type", "function")
            put("name", name)
            put("description", description)
            put("parameters", params)
        }
    }


}
