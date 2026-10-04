package com.openminis.app.provider.openai

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.LLMError
import com.openminis.app.data.model.LLMMediaAttachment
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.LLMResponse
import com.openminis.app.data.model.LLMStreamChunk
import com.openminis.app.data.model.LLMUsage
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.provider.LLMProvider
import com.openminis.app.provider.applyUserAgentOverride
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit
import com.openminis.app.provider.failOnSilentEmptyCompletion

class OpenAIProvider private constructor(
    private val apiKey: String?,
    private val oauthTokenProvider: (suspend () -> String)?,
    override var model: LLMModel = LLMModel.gpt4oMini,
    private val basePath: String = "https://api.openai.com/v1",
    private val extraHeaders: Map<String, String> = emptyMap(),
    /** Codex account ID for OAuth mode (extracted from JWT). */
    var codexAccountId: String? = null,
    /** When true, route through /v1/responses even on API-key providers. */
    private val useResponsesAPI: Boolean = false,
    /**
     * When true, force Chat Completions even when the bearer is provided
     * via OAuth. Set by OAuth-but-not-Codex callers (xAI Grok) — without
     * it the default `usesChatCompletionsAPI = !isOAuth && !useResponsesAPI`
     * heuristic incorrectly drags an OAuth bearer onto the Codex
     * Responses backend at chatgpt.com, where it 404s.
     */
    private val forceChatCompletions: Boolean = false,
    /**
     * [T-provider-custom-user-agent] Per-provider User-Agent override.
     * null/blank → default UA; non-blank → replaces User-Agent on every
     * outbound request (chat + responses). Only set for custom-base
     * OpenAI-compat instances.
     */
    private val customUserAgent: String? = null,
    /**
     * [T-android-azure-openai] Azure OpenAI mode. When true, requests auth with
     * the `api-key:` header (not `Authorization: Bearer`) and the URL is built
     * as {azureBase}/openai/deployments/{model.id}/{path}?api-version=… from
     * [azureBase] (the raw user endpoint, which carries the ?api-version query).
     * Defaults false so every non-Azure path is byte-for-byte unchanged.
     */
    private val isAzure: Boolean = false,
    /**
     * Raw Azure endpoint the user pasted (with any ?api-version query). Only
     * used when [isAzure]; the factory passes instance.customBaseURL verbatim
     * here because [basePath] has been normalized (/v1 appended, query dropped)
     * which is wrong for Azure's deployments-path routing.
     */
    private val azureBase: String? = null,
) : LLMProvider {
    override val name = "OpenAI"
    private val httpRequests = OpenAIHttpRequests(basePath, isAzure, azureBase)

    /**
     * [T-android-thinking-rules-phase2] Owning provider-instance id, set by
     * ProviderFactory after construction (mirrors how [codexAccountId] is a
     * post-construction var). Lets the thinking resolver look up this instance's
     * user-authored custom rules from [com.openminis.app.provider.thinking.ThinkingRuleResolver]'s
     * cache. Null → no custom rules (identical to Phase-1 built-in-only behaviour).
     */
    var thinkingRuleInstanceId: String? = null

    /**
     * [T-android-model-custom-params] Per-model user overrides for this
     * request: temperature / top_p / custom headers / extra body params.
     *
     * A post-construction `var` set by ProviderFactory, matching how
     * [thinkingRuleInstanceId] and [sessionId] are wired — the private primary
     * constructor is shared by a dozen call sites and several secondary
     * constructors, so threading a new parameter through all of them would be a
     * far larger change for no gain.
     *
     * Deliberately SEPARATE from [chatExtraBody] / [chatExtraHeaders], rather
     * than reusing them: those are per-CALL passthrough owned by
     * ModelUseOffloadHandler, which assigns them on a freshly built provider.
     * Folding per-model settings into the same fields would mean one silently
     * clobbering the other depending on call order. Keeping them apart also
     * fixes the precedence deliberately — see [mergeModelOverridesBody].
     *
     * null = no overrides (the default for every caller that does not set it).
     */
    var modelOverrides: com.openminis.app.data.model.ModelOverrides? = null

    /**
     * [T-android-xai-priority] Whether this provider speaks xAI's Priority
     * Processing extension, i.e. whether it is eligible to carry
     * `service_tier: "priority"` when the user's global Fast Mode is on.
     *
     * This is a CAPABILITY flag, not the user's choice. The choice lives in
     * the app-level [com.openminis.app.data.FastModePrefs] toggle that Codex
     * Fast Mode already uses, and is read at request-BUILD time (see
     * [buildRequestBody]) so flipping it applies to the very next request of
     * an ongoing session — including offload / title-gen calls that never pass
     * through ChatViewModel. Storing the user's answer here instead would
     * freeze it at provider-construction time and miss those.
     *
     * False by default so every other provider's body is byte-for-byte
     * unchanged. That matters beyond tidiness: `service_tier` is an xAI
     * extension, and OpenAI-compatible relays that reject unknown body keys
     * would 400 on it, so ProviderFactory sets this for ProviderType.xAI alone.
     * A post-construction var rather than a constructor parameter for the same
     * reason as [thinkingRuleInstanceId] — xAI resolves through two different
     * constructors (API key and OAuth), and threading a flag through both
     * duplicates it.
     */
    var supportsPriorityProcessing: Boolean = false

    /**
     * [T-android-xai-priority] The effective `service_tier` for this request,
     * or null to omit the field. Consulted by both body builders.
     *
     * Omits rather than sending `"default"` when Fast Mode is off: "default" is
     * xAI's own behaviour, so leaving the key out keeps the body byte-identical
     * to before this feature existed.
     */
    internal fun resolvedServiceTier(): String? =
        if (supportsPriorityProcessing && com.openminis.app.data.FastModePrefs.isEnabled()) {
            "priority"
        } else {
            null
        }

    /** API Key constructor (Chat Completions API by default; set useResponsesAPI=true for /v1/responses). */
    constructor(
        apiKey: String,
        model: LLMModel = LLMModel.gpt4oMini,
        basePath: String = "https://api.openai.com/v1",
        extraHeaders: Map<String, String> = emptyMap(),
        useResponsesAPI: Boolean = false,
        customUserAgent: String? = null,
        isAzure: Boolean = false,
        azureBase: String? = null,
    ) : this(
        apiKey = apiKey,
        oauthTokenProvider = null,
        model = model,
        basePath = basePath,
        extraHeaders = extraHeaders,
        useResponsesAPI = useResponsesAPI,
        customUserAgent = customUserAgent,
        isAzure = isAzure,
        azureBase = azureBase,
    )

    /** OAuth constructor (Codex Responses API). */
    constructor(
        oauthTokenProvider: suspend () -> String,
        model: LLMModel = LLMModel.codexMini,
        codexAccountId: String? = null,
    ) : this(apiKey = null, oauthTokenProvider = oauthTokenProvider, model = model, codexAccountId = codexAccountId)

    companion object {
        /**
         * [T-codex-gpt-image25-android] The 2.5 image variants, which name
         * themselves inside the image_generation tool object.
         *
         * Separate from [CODEX_IMAGE_MODEL_IDS] because the two questions are
         * different: that one asks "does this route through the Codex image
         * path", this one asks "does the tool object need an explicit model".
         * gpt-image-2 answers yes to the first and no to the second.
         */
        private val CODEX_IMAGE_25_MODEL_IDS = setOf(
            "gpt-image-2.5-sunburst",
            "gpt-image-2.5-flare",
        )

        /**
         * [T-codex-gpt-image2-oauth-android] Every model that goes through the
         * Codex OAuth image_generation path rather than the ordinary
         * Chat Completions / Responses APIs. Must stay in step with the entries
         * OpenAIModelsApi appends after enrichModels.
         */
        private val CODEX_IMAGE_MODEL_IDS = setOf("gpt-image-2") + CODEX_IMAGE_25_MODEL_IDS

        /**
         * [T-android-thinking-level-arch] Codex OAuth client version advertised
         * in the Version / User-Agent headers. Bumped 0.142.3 → 0.144.1 to
         * match the CLIProxyAPI/sub2api upstream (fixes a gpt-5.6-luna 404 seen
         * on the older client). Shared constant so future bumps touch one place.
         *
         * [T-gpt6-astra] 0.144.1 → 0.153.3. OpenAI gates models on the
         * advertised client version: gpt-6-astra's `minimal_client_version`
         * is 0.153.0, and an older client gets a 400 or an empty reply for it.
         * Tracks CLIProxyAPI c77b1369, which moved its default to 0.153.3.
         * Only the number changes; the UA shape below is unchanged.
         */
        /**
         * [T-codex-dynamic-discovery GH#319] `internal`, not `private`: model
         * DISCOVERY must advertise the same client version as inference.
         * OpenAI gates model availability on this number, so if the two ever
         * drifted the picker could list a model that every send then refuses.
         * Sharing the one constant makes that drift unrepresentable.
         *
         * GH#319 asked us to re-check whether a newer version is required, and
         * the check was run rather than assumed: on a live ChatGPT account at
         * 0.153.3, discovery returned gpt-6-astra / gpt-5.6-{sol,terra,luna} /
         * gpt-5.5, and gpt-6-astra and gpt-5.6-luna both completed a real
         * inference request (2026-09-11). At that point Codex CLI stable was
         * 0.154.0 and bumping would have changed a validated fingerprint with
         * no evidence behind it, so it was left alone — with the note that the
         * signal to revisit is "a discovered model is listed and then refused".
         *
         * [T-gpt6-sol-luna] 0.153.3 → 0.155.0. That signal arrived: gpt-6-sol
         * and gpt-6-luna are gated above 0.153.3, and CLIProxyAPI moved its
         * default codex client to 0.155.0 in the same commit that added these
         * two ids to its registry (2430354330af). The gate is a floor, so the
         * models that were verified callable at 0.153.3 stay callable.
         *
         * NOT verified against a live account here — this session has no Codex
         * OAuth token to probe with. If gpt-6-astra or a gpt-5.6-* model starts
         * failing after this bump, this constant is the first thing to suspect.
         */
        // GPT-6.1 Sol: live same-account A/B probe returned 400 at 0.155.0
        // and response.completed at 0.159.2, with otherwise identical requests.
        internal const val CODEX_CLIENT_VERSION = "0.159.2"

        /**
         * [T-android-stale-conn-retry-hang] Streaming time-to-first-byte
         * budget: response HEADERS must arrive within this window. Does NOT
         * bound the SSE body — a flowing stream stays unlimited.
         *
         * [T-android-ttfb-upload-split / #188] This window now starts at
         * `requestBodyEnd` (upload complete), NOT at call start — a large
         * multimodal body over a slow proxy could burn the whole budget just
         * uploading, so a healthy-but-slow server looked like a dead
         * connection. See [STREAM_UPLOAD_CAP_MS] for the upload-phase bound.
         *
         * Raised 30s -> 120s (user report): complex agent turns,
         * locally-hosted large models, and slow relay endpoints can legitimately
         * take well over 30s to emit the first response header, and the old
         * budget cancelled those healthy requests as false timeouts. readTimeout
         * is 600s, so 120s stays comfortably inside it while still catching a
         * genuinely dead connection.
         */
        private const val STREAM_TTFB_TIMEOUT_MS = 120_000L

        /**
         * [T-android-ttfb-upload-split / #188] Overall ceiling for the UPLOAD
         * phase (call start → requestBodyEnd). Keeps the watchdog effective if
         * the body upload itself wedges (writeTimeout is 30s per write op, but a
         * trickling proxy can dribble bytes forever without tripping it). Chosen
         * generous so a legitimately large body over a slow link isn't cut off:
         * the writeTimeout(30s) already bounds a fully-stalled socket; this only
         * catches the slow-but-never-idle case. Once upload completes the tighter
         * [STREAM_TTFB_TIMEOUT_MS] takes over.
         */
        private const val STREAM_UPLOAD_CAP_MS = 120_000L

        /**
         * Factory for OAuth-bearer OpenAI-compatible providers that aren't
         * Codex (e.g. xAI Grok). Same dynamic bearer plumbing, but the
         * wire format stays Chat Completions and the endpoint is the
         * caller-supplied base URL — not chatgpt.com's Responses API.
         *
         * Implemented as a factory (not a secondary ctor) because the
         * JVM erases the signature down to
         * `(Function1, LLMModel, String)` which collides with the Codex
         * ctor's `(oauthTokenProvider, model, codexAccountId)` overload.
         */
        fun oauthOpenAICompat(
            oauthTokenProvider: suspend () -> String,
            model: LLMModel,
            basePath: String,
            /**
             * [T-copilot-provider] Static headers added to every request.
             * Copilot needs a fixed editor-identity set (Editor-Version,
             * Copilot-Integration-Id, …) alongside the bearer; without them
             * the API refuses the request. Defaulted empty so existing
             * callers (xAI, Kimi) are unchanged.
             */
            extraHeaders: Map<String, String> = emptyMap(),
        ): OpenAIProvider = OpenAIProvider(
            apiKey = null,
            oauthTokenProvider = oauthTokenProvider,
            model = model,
            basePath = basePath,
            forceChatCompletions = true,
            extraHeaders = extraHeaders,
        )
    }

    private val isOAuth: Boolean get() = oauthTokenProvider != null

    // MARK: - Image passthrough [T-android-model-use-image-passthrough GH#62]

    /**
     * Arbitrary extra fields merged into the /images/generations JSON body, so
     * `minis-model-use` can pass provider-specific params our fixed schema never
     * modeled (e.g. Volcengine Seedream's `image` for image-to-image,
     * `watermark`, `tools`). User keys WIN over our defaults (response_format)
     * but never replace the resolved `model`. Empty = no passthrough. Set
     * per-call by ModelUseOffloadHandler on a freshly-built provider; never
     * persisted. Values are raw JSON (String/Number/Boolean/JSONObject/JSONArray).
     */
    var imageExtraBody: Map<String, Any?> = emptyMap()

    /**
     * Extra HTTP headers merged into the /images/generations request (added, not
     * replacing the ctor extraHeaders). Per-call, never persisted.
     */
    var imageExtraHeaders: Map<String, String> = emptyMap()

    /**
     * Optional endpoint-path override for the image request (e.g. a non-standard
     * `/api/v3/images/generations`). When set, replaces the hardcoded
     * `/images/generations` path (base URL + this verbatim). null = default path.
     */
    var imagePathOverride: String? = null

    // MARK: - Chat passthrough [T-android-model-use-passthrough-mode / GH#72]

    /**
     * Arbitrary extra fields merged into the chat/completions AND responses
     * request bodies, mirroring [imageExtraBody] on the image path. Populated
     * per-call by ModelUseOffloadHandler from the input JSON's explicit
     * `extra_body` / `passthrough.body` envelope (never from implicit top-level
     * keys — the chat schema owns its top level). User keys WIN over our
     * defaults (e.g. `plugins`, `web_search_options`, provider-specific knobs)
     * but `model` is force-restored after the merge. Empty = no passthrough.
     * Mirrors iOS OpenAIProvider.chatExtraBody.
     */
    var chatExtraBody: Map<String, Any?> = emptyMap()

    /**
     * Extra HTTP headers merged into chat/completions and /responses requests,
     * applied AFTER the default set → same-name REPLACE semantics over every
     * default (including Authorization/Content-Type). Per-call, never persisted.
     * Mirrors iOS OpenAIProvider.extraHeaders (promoted to all endpoints).
     */
    var chatExtraHeaders: Map<String, String> = emptyMap()

    // MARK: - Per-request headers [T-android-copilot-per-request-headers]

    /**
     * Headers DERIVED from the outgoing body, applied to every chat/responses
     * request. Null (the default) for every provider that does not need them.
     *
     * Exists because some headers are only correct per request and are wrong
     * the moment they are pinned at construction. GitHub Copilot is the case
     * that forced it: `X-Initiator` must say `agent` when the loop is feeding
     * tool results back and `user` when a person typed, and
     * `Copilot-Vision-Request` must be set only when images are actually
     * attached. Both were fixed at build time to `user` / absent, so every
     * agent turn was labelled as human traffic — which is the specific thing
     * that gets a Copilot account flagged — and `X-Request-Id` repeated one
     * UUID for the whole life of the provider instead of identifying a request.
     *
     * A lambda rather than a subclass hook, so the shared request builder stays
     * free of provider-specific branching; the same reason iOS keeps it a
     * closure. Applied in [buildRequest], which is the single choke point every
     * chat and responses call already passes through.
     */
    var perRequestHeaders: ((org.json.JSONObject) -> Map<String, String>)? = null

    /**
     * [T-android-opencode-session-header] Id of the conversation this provider
     * is serving, used only to derive OpenCode Go's `x-opencode-session`
     * header (see [com.openminis.app.provider.OpenCodeSessionHeader]).
     *
     * A `var` read at request time rather than a constructor value, because a
     * provider outlives the event this id changes on: a brand-new chat builds
     * its provider while still a draft (`ChatViewModel.loadSession`), and the
     * real UUID only exists once `ensureSession()` persists the row on the
     * first send. Capturing at construction would leave that first turn — the
     * one that opens the upstream cache entry — without the header, and the
     * turn after it with one, which is precisely the instability OpenCode
     * asks clients to avoid. Reading late means the promotion is picked up
     * with no provider rebuild.
     *
     * Written from the chat ViewModel's main-thread flow and read on the
     * request-building coroutine; `@Volatile` publishes that hand-off. Only
     * ever a whole-reference assignment, so no further synchronisation is
     * needed.
     */
    @Volatile
    var sessionId: String? = null

    // Stable even for attachment-only or one-shot requests without a persisted session.
    private val promptCacheFallbackId = java.util.UUID.randomUUID().toString()

    /**
     * Absolute-path endpoint override. When set (must start with "/"), it
     * replaces the ENTIRE URL path after scheme+host — unlike [imagePathOverride],
     * which is joined after `basePath` and therefore can never escape a base-URL
     * prefix like `/compatible-mode/v1` (proven by iOS device baseline p03).
     * Applies to chat/completions, responses, and images/generations builders.
     * Never applies to Codex OAuth (hardcoded backend). Per-call, never
     * persisted. Mirrors iOS OpenAIProvider.absoluteEndpointOverride.
     */
    var absoluteEndpointOverride: String? = null

    /**
     * [T-android-model-use-passthrough-mode] Build a URL from the provider's
     * scheme+host(+port) ONLY, with [path] replacing the entire URL path.
     * [path] must start with "/" and may carry a query string. Credentials stay
     * bound to the instance's host — callers can never point this at a different
     * host. Returns null if the base URL can't be parsed. Mirrors iOS
     * OpenAIProvider.hostRootURL.
     */
    fun hostRootURL(path: String): String? = httpRequests.hostRootURL(path)

    /**
     * Resolve the effective URL for a modeled endpoint, honoring the
     * absolute-path override when present. [defaultPath] is joined after
     * [basePath] (which is already normalized to base + /v1). Mirrors iOS
     * OpenAIProvider.endpointURL.
     */
    private fun endpointURL(defaultPath: String): String = httpRequests.endpointURL(defaultPath, absoluteEndpointOverride)

    // MARK: - Azure helpers [T-android-azure-openai]

    /**
     * Set the API-key auth header on a request builder. Azure uses the `api-key`
     * header; every other OpenAI-compatible endpoint uses `Authorization:
     * Bearer`. Centralized so the Azure branch can't accidentally set the wrong
     * one. Mirrors iOS OpenAIProvider.applyKeyAuth.
     */
    private fun Request.Builder.applyKeyAuth(token: String): Request.Builder = httpRequests.applyKeyAuth(this, token)

    /**
     * Build the request URL for Azure OpenAI, mirroring the official AzureOpenAI
     * SDK shape (and iOS azureURL, T-ios-azure-openai-deployments):
     *
     *   {azure_endpoint}/openai/deployments/{model.id}/{path}?api-version=…
     *
     * The user pastes the resource endpoint as the custom base — typically the
     * bare `https://x.openai.azure.com`, optionally already including `/openai`,
     * with the `?api-version=…` query on it. We (1) split off the query, (2)
     * strip a trailing `/`, a stray `/v1` (Azure has no /v1), and a trailing
     * `/openai` (re-added), then (3) assemble the deployments path. [path] is
     * e.g. "/chat/completions". Returns null when no Azure base is configured.
     */
    /**
     * Whether this provider uses Chat Completions API (vs Responses API).
     * Responses API is used when OAuth (Codex) OR when the user explicitly
     * flipped the per-instance `useResponsesAPI` switch.
     */
    private val usesChatCompletionsAPI: Boolean get() = forceChatCompletions || (!isOAuth && !useResponsesAPI)

    /**
     * [T-android-tool-splits-reply-fix] Chat Completions streams ONE
     * monolithic `content` string per assistant response — qwen endpoints
     * flush trailing content chunks AFTER tool_calls deltas (chunking
     * artifact), and those must merge back into the single pre-tool text
     * block instead of becoming a post-tool block (which split sentences
     * mid-word in the chat UI). The Responses API streams genuinely ordered
     * output items, so it keeps chronological reconstruction.
     */
    override val streamTextIsMonolithic: Boolean get() = usesChatCompletionsAPI

    /**
     * [T-codex-gpt-image2-oauth-android] gpt-image-2 is a special image-
     * generation model driven through the Codex OAuth backend's built-in
     * image_generation tool (wire model gpt-5.5, tools=[{type:image_generation}]).
     * Only meaningful on the Codex OAuth path; everything else (the GPT-5.x
     * Codex models and their existing OAuth flow) is untouched by this gate.
     *
     * [T-codex-gpt-image25-android] The 2.5 variants route identically — the
     * only difference is that they name themselves inside the tool object
     * (see [buildCodexImageBody]). Membership lives in
     * [CODEX_IMAGE_MODEL_IDS] so the routing gate and the body builder cannot
     * disagree about which ids are image models: a model listed in the picker
     * but missing from this gate would silently take the ordinary Responses
     * path and fail as an unknown model.
     */
    private val isCodexImageModel: Boolean get() = isOAuth && model.id in CODEX_IMAGE_MODEL_IDS

    private suspend fun getToken(): String {
        oauthTokenProvider?.let { return it() }
        return apiKey ?: throw LLMError.InvalidApiKey()
    }

    // T-android-openai-codex-timeout: bump readTimeout 180s → 600s to
    // match iOS. T171 had cut it to 180s on the theory that GPT-5.x
    // thinking warm-up tops out around 60-90s, but the Codex Responses
    // OAuth path on gpt-5.5 with a real-world agent body (440KB, 20
    // messages, 8 tools) routinely sits silent on the SSE stream for
    // 2:50-3:10 between the reasoning `response.output_item.added`
    // event and the burst of text deltas after the reasoning step
    // completes — server-side it's still working, no keep-alive bytes
    // arrive in between, and OkHttp's idle-data-read counter trips.
    // The 180s cap turned that normal reasoning silence into a hard
    // SocketTimeoutException (observed in 0.10-preview, log file
    // minis-2026-05-27.log around 13:28 — 3:00 of silence then trip).
    // Going back to 600s leaves room for the longest realistic
    // reasoning bursts; the cancel-race concern T171 hedged against
    // (OkHttp call.cancel() racing a thread inside execute()) is
    // covered by the outer coroutine cancellation chain — Job.cancel
    // propagates down through the agent loop and the socket gets
    // closed via Call.cancel() from the coroutine's invokeOnCancellation,
    // so a stuck OAuth read never lingers past the agent turn.
    //
    // T-android-openai-codex-timeout: also attach an OkHttp EventListener
    // so future timeout reports show WHICH leg of the network path
    // stalled — DNS, proxy connect, TLS handshake, idle-after-headers,
    // or mid-stream silence. Previous OAuth-streaming logs only printed
    // request/response envelopes; when a SocketTimeoutException fired
    // we had no way to tell whether the upstream proxy went away
    // (idle-close after 3min, common with clash/v2ray), TLS renegotiated,
    // or the server itself stopped emitting bytes. Each milestone goes
    // through AppLogger.info at the OkHttpEvents tag with the call's
    // identity hash so concurrent streams can be disambiguated.
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(600, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        // [T-android-stale-conn-retry-hang] Shared pool so NetworkMonitor's
        // network-transition eviction reaches THIS client's connections —
        // a per-client pool was never evicted, and a dead h2 tunnel through
        // a local proxy got reused on every retry (silent infinite hang).
        .connectionPool(com.openminis.app.network.NetworkMonitor.sharedLLMConnectionPool)
        .eventListenerFactory { OkHttpNetTraceListener() }
        .build()

    /** Detect OpenRouter base URL. */
    private val isOpenRouter: Boolean = basePath.contains("openrouter.ai")

    /**
     * [OpenMinis#191] OpenRouter does NOT enable Anthropic prompt caching
     * automatically — unlike the OpenAI / Grok / Moonshot / Groq models it
     * hosts, which cache with no opt-in. Claude requests must carry an explicit
     * `cache_control` breakpoint or nothing is cached at all, which is why the
     * reporter measured `cache_read_input_tokens` / `cache_write_tokens` pinned
     * at 0 across every turn and a 3-6x cost overrun.
     *
     * Matched on the `anthropic/` model-id prefix, OpenRouter's namespace for
     * the Claude family (`anthropic/claude-sonnet-4.5`, `anthropic/claude-opus-4.1`,
     * …). Scoped to OpenRouter AND that prefix so every other model on the
     * gateway keeps a byte-identical request body.
     *
     * Note the gate is the HOST-matched [isOpenRouter], never a compat flag:
     * on iOS the equivalent `useOpenRouterCompat` only selects the legacy
     * `max_tokens` / no-`stream_options` body shape and Mistral sets it too, so
     * keying on it would have leaked the field into Mistral requests. Android's
     * [isOpenRouter] is already host-matched, the same way [isDashScope] is.
     *
     * Carries the same caveat as [isMistral]: a relay or vanity domain without
     * `openrouter.ai` in its URL is not recognised, which fails safe — the
     * request simply goes out unchanged, i.e. today's behaviour.
     */
    /** Detect DashScope (Alibaba Qwen) base URL. */
    private val isDashScope: Boolean = basePath.contains("dashscope")

    /**
     * [T-android-mistral-reasoning-422] (GH OpenMinis#87, iOS 29065ca0)
     * Detect Mistral's OpenAI-compatible endpoint.
     *
     * Mistral's AssistantMessage is a CLOSED schema
     * (`additionalProperties: false`; only role/content/tool_calls/prefix), so
     * `reasoning_content` on a prior assistant turn is rejected outright with
     * HTTP 422 `extra_forbidden`. Their native reasoning representation is a
     * different, Mistral-signed mechanism (content ThinkChunks), not this
     * field. Note the REQUEST schema has no additionalProperties:false, which
     * is why only multi-turn history carrying reasoning_content ever 422'd
     * while spec-external top-level params went through fine.
     *
     * Case-insensitive to match iOS (LLMProviderFactory lowercases before the
     * same `contains("mistral.ai")` test) — hosts are case-insensitive, so a
     * user typing `API.Mistral.AI` must still be recognised.
     *
     * Known limits, both inherited from iOS's identical predicate: a relay that
     * proxies Mistral models under its own hostname is not detected (still
     * 422s), and a URL that merely mentions mistral.ai in a query string would
     * over-suppress (harmless — the field is optional for everyone else).
     */
    private val isMistral: Boolean = basePath.lowercase().contains("mistral.ai")

    /**
     * [T-android-cerebras-reasoning-400] Talking to Cerebras' inference API
     * (OpenMinis#361). Mirrors iOS OpenAIProvider.isCerebras.
     *
     * Cerebras validates strictly in two ways that both bite us:
     *  - its assistant message is a CLOSED schema, exactly like Mistral's, so
     *    echoing a captured `reasoning_content` in history answers
     *    `400 … property 'messages.N.assistant.reasoning_content' is unsupported`
     *    — turn 1 succeeds and every turn after it fails;
     *  - it re-hosts Qwen-named models (`qwen-3.8-27b`) that would otherwise
     *    match the `*qwen*` thinking rule and be handed Qwen's native
     *    `enable_thinking`, which Cerebras does not accept. Their documented
     *    control is root `reasoning_effort`.
     *
     * Same known limit as [isMistral]: a relay under its own hostname is not
     * detected, which fails safe — the request goes out as it does today.
     */
    private val isCerebras: Boolean = basePath.lowercase().contains("cerebras.ai")

    /**
     * [OpenMinis#163] Talking to xAI's own API (api.x.ai), as opposed to a relay
     * that merely serves grok-named models. Mirrors iOS OpenAIProvider.isXAI.
     *
     * Scopes the "catalog declares no effort tiers → omit reasoning_effort" skip
     * to first-party xAI. The bundled catalog marks 2090 entries across many
     * vendors with the same empty-tier shape (relay-hosted Claude, GPT-5, Qwen,
     * and grok itself behind poe / fastrouter / anyapi); while omitting the
     * field is arguably more correct for some of those too, none of those routes
     * has been verified, so the skip stays where the 400 was actually observed.
     *
     * URL matching alone is sufficient: ProviderFactory always populates a base
     * for xAI, defaulting to https://api.x.ai/v1 when the user set no override.
     */
    private val isXAI: Boolean = basePath.lowercase().let {
        it.contains("api.x.ai") || it.contains("//x.ai")
    }

    /**
     * [T-unified-reasoning-effort] Whether this endpoint applies OpenAI's
     * `reasoning_effort` (Chat) / `reasoning.effort` (Responses) uniformly to
     * EVERY model it hosts — including third-party families (GLM / Kimi /
     * DeepSeek / MiniMax) that, at their vendor-native endpoint, would instead
     * use a `thinking:{}` object or self-reason with no toggle.
     *
     * Three known such gateways (mirrors iOS OpenAIProvider.usesUnifiedReasoningEffort):
     *   • Volcengine Ark (`ark.` / `volces` in the base URL) — re-exposes
     *     doubao/deepseek/glm/kimi through a single OpenAI-compatible surface
     *     where thinking is controlled ONLY by `reasoning_effort` (min tier
     *     `minimal`); the vendor-native `thinking:{}` shape is not honored.
     *   • Azure OpenAI ([isAzure]) — reasoning is `reasoning_effort` for every
     *     model surfaced through the deployment.
     *   • Venice.ai (`api.venice.ai`) — [OpenMinis#86] resells deepseek / claude /
     *     aion behind one OpenAI-compatible surface. Its ChatCompletionRequest
     *     schema is `additionalProperties: false`, so an unknown root key is
     *     rejected at validation time — BEFORE model dispatch — with
     *     `400 Unrecognized key(s) in object: 'thinking'`. That is why every
     *     model failed and why turning thinking OFF did not help: the
     *     `{"type":"disabled"}` branch still sends the key. Venice natively
     *     accepts root `reasoning_effort`, a superset of the tiers the generic
     *     path emits, so no value mapping is needed.
     *
     * Gated tightly so official direct endpoints (DeepSeek/GLM/Kimi native,
     * which DO want their own thinking shape) are never mis-routed. Caveat (same
     * class as [isMistral]): a relay or vanity domain that does not carry these
     * hosts in its URL is still exposed.
     */
    private val usesUnifiedReasoningEffort: Boolean =
        isAzure || basePath.lowercase().let {
            it.contains("volces") || it.contains("ark.") || it.contains("api.venice.ai")
        }

    /**
     * [T-thinking-off-explicit] The wire value for "thinking OFF", or null to
     * keep the historical omit-the-field behavior. ALLOWLIST, not blanket
     * (mirrors iOS OpenAIAgentProvider.explicitOffEffort): only vendors whose
     * off tier is DOCUMENTED get an explicit value —
     *   • official OpenAI base (non-Azure) → "none" (documented off tier);
     *   • Volcano Ark (volces/ark bases, seed/doubao families) → "minimal"
     *     (their smallest tier — Ark's non-off default is what motivated this).
     * Everyone else (relays, NIM, xAI, MiMo, …) keeps field omission = the
     * vendor's own default. Azure stays omission too: its off tier is
     * model-dependent ('none' on gpt-5.1+, 'minimal' on original gpt-5,
     * unsupported on o1/o3), so an explicit value risks a 400.
     */
    private fun explicitOffEffort(modelId: String = model.id): String? {
        if (isAzure) return null
        val base = basePath.lowercase()
        if (base.startsWith("https://api.openai.com")) return "none"
        val lid = modelId.lowercase()
        if (base.contains("volces") || base.contains("ark.") ||
            lid.contains("seed-") || lid.contains("doubao")
        ) {
            return "minimal"
        }
        return null
    }

    /**
     * Non-streaming entry point. Some providers (e.g. GPT-5.x via certain
     * gateways, Codex Responses backend) reject `stream=false` outright with
     * `[400] Stream must be set to true`. To keep this method usable across
     * all providers we always issue a streaming request internally and
     * concatenate the deltas back into a single [LLMResponse]. Callers that
     * actually want incremental delivery should use [streamMessage] instead.
     */
    override suspend fun sendMessageClamped(
        messages: List<LLMMessage>,
        systemPrompt: String?,
        maxTokens: Int,
        temperature: Double?,
        imageParts: List<LLMMessage.ImagePart>,
        tools: List<AgentToolDefinition>,
        thinkingLevel: ThinkingLevel,
    ): LLMResponse = withContext(Dispatchers.IO) {
        val textBuf = StringBuilder()
        var stopReason: String? = null
        var usage: LLMUsage? = null
        // [T-codex-gpt-image2-oauth-android] Collect model-generated media
        // (gpt-image-2 images) so non-streaming callers — notably
        // minis-model-use (ModelUseOffloadHandler) — get them on
        // LLMResponse.mediaAttachments and can write the image to --output.
        val media = mutableListOf<LLMMediaAttachment>()
        streamMessage(
            messages = messages,
            systemPrompt = systemPrompt,
            maxTokens = maxTokens,
            temperature = temperature,
            imageParts = imageParts,
            tools = tools,
            thinkingLevel = thinkingLevel,
        ).collect { chunk ->
            when (chunk) {
                is LLMStreamChunk.Text -> textBuf.append(chunk.text)
                is LLMStreamChunk.Usage -> usage = chunk.usage
                is LLMStreamChunk.Finished -> stopReason = chunk.stopReason
                is LLMStreamChunk.MediaAttachment -> media.add(chunk.attachment)
                else -> Unit
            }
        }
        LLMResponse(textBuf.toString(), stopReason, usage, media)
    }

    override fun streamMessageClamped(
        messages: List<LLMMessage>,
        systemPrompt: String?,
        maxTokens: Int,
        temperature: Double?,
        imageParts: List<LLMMessage.ImagePart>,
        tools: List<AgentToolDefinition>,
        thinkingLevel: ThinkingLevel,
    ): Flow<LLMStreamChunk> = rawStreamMessage(
        messages, systemPrompt, maxTokens, temperature, imageParts, tools, thinkingLevel,
    ).failOnSilentEmptyCompletion(name)

    private fun rawStreamMessage(
        messages: List<LLMMessage>,
        systemPrompt: String?,
        maxTokens: Int,
        temperature: Double?,
        imageParts: List<LLMMessage.ImagePart>,
        tools: List<AgentToolDefinition>,
        thinkingLevel: ThinkingLevel,
    ): Flow<LLMStreamChunk> = callbackFlow {
        val images = if (isCodexImageModel) imageProtocol() else null
        val chatCompletions = usesChatCompletionsAPI
        val body = if (images != null) {
            // [T-gpt-image2-codex-backend-route-android] gpt-image-2 on an
            // OpenAI OAuth (Codex) instance is driven through the Codex backend
            // image_generation tool (chatgpt.com/backend-api/codex/responses),
            // NOT the public /v1/images/generations Images API — a Codex OAuth
            // token lacks the api.model.images.request scope and the Images API
            // 401s with "Missing scopes: api.model.images.request" (see
            // codex_oauth_image_generation_summary.md §11.1). Aligns with iOS
            // commit 2dd35a14. The Codex-backend route is selected purely by
            // `isCodexImageModel` (isOAuth + model id) — it does NOT require a
            // codexAccountId (the Chatgpt-Account-Id header is optional and its
            // absence doesn't 401), which is exactly the iOS fall-through bug
            // this avoids. Android has no Images-API path at all, so an OAuth
            // gpt-image-2 request can never reach /v1/images/generations.
            //
            // Special image-generation body — not Chat Completions, not the
            // normal Responses tool shape.
            com.openminis.app.logging.AppLogger.info(
                "OpenAIProvider",
                "[ModelUseRoute] gpt-image-2 → route=codex-backend " +
                    "url=chatgpt.com/backend-api/codex/responses isOAuth=$isOAuth " +
                    "hasAccountId=${codexAccountId != null}",
            )
            images.codexBody(messages)
        } else if (chatCompletions) {
            buildRequestBody(messages, systemPrompt, maxTokens, stream = true, temperature = temperature, imageParts = imageParts, tools = tools, thinkingLevel = thinkingLevel)
        } else {
            buildResponsesAPIBody(messages, systemPrompt, maxTokens, stream = true, imageParts = imageParts, tools = tools, thinkingLevel = thinkingLevel)
        }
        // T302: serialize the request body exactly once. Pre-T302 we called
        // body.toString() three times per request (debug log + OAuth byte
        // build + non-OAuth RequestBody), each materialising a fresh 30+ MB
        // string for long agent loops with heavy tool outputs. Stacked, that
        // pushed memory-tight devices (HONOR PTP-AN00) past the OOM line.
        // [T-android-mem-probe-trust] Bracket the serialisation itself. The
        // 2026-08-15 field log recorded `bodyLen=2342987` on the request before
        // a process death but nothing about its memory cost, so "did building
        // this body kill us?" could not be answered from the log. We measure
        // around the call and report the realised length, so an OOM thrown here
        // now arrives with an attributed stack instead of anonymously.
        val memBefore = com.openminis.app.diagnostics.MemorySnapshot.capture()
        val serStartNs = System.nanoTime()
        val bodyStr = try {
            body.toString()
        } catch (t: Throwable) {
            com.openminis.app.diagnostics.LargeAllocProbe.report(
                "openai.body.toString", -1, "model=${model.id} messages=${messages.size}",
                memBefore, serStartNs, failure = t,
            )
            throw t
        }
        if (bodyStr.length >= com.openminis.app.diagnostics.LargeAllocProbe.NOTABLE_BYTES) {
            com.openminis.app.diagnostics.LargeAllocProbe.report(
                "openai.body.toString", bodyStr.length.toLong(),
                "model=${model.id} messages=${messages.size}",
                memBefore, serStartNs, failure = null,
            )
        }
        val request = buildRequest(bodyStr, body.getString("model"))
        val headerMap = mutableMapOf<String, String>()
        for (name in request.headers.names()) {
            headerMap[name] = request.headers[name] ?: ""
        }
        val startTime = System.currentTimeMillis()

        // T321: request-side diagnostic log. Header *keys* + Authorization
        // presence (no token values), and a body summary (counts only — never
        // the message text/images/tool-result bytes).
        run {
            val authPresent = request.headers["Authorization"] != null
            val msgsLen = body.optJSONArray("messages")?.length()
                ?: body.optJSONArray("input")?.length() ?: 0
            val toolsLen = body.optJSONArray("tools")?.length() ?: 0
            val temp = if (body.has("temperature")) body.optDouble("temperature") else null
            val maxTok = body.optInt("max_completion_tokens", body.optInt("max_tokens", -1))
            val hasSystem = body.has("instructions") ||
                (body.optJSONArray("messages")?.let { arr ->
                    var found = false
                    for (i in 0 until arr.length()) {
                        if (arr.optJSONObject(i)?.optString("role") == "system") { found = true; break }
                    }
                    found
                } ?: false)
            com.openminis.app.logging.AppLogger.info(
                "OpenAIProvider",
                "[T321] → REQ url=${request.url} model=${model.id} stream=${body.optBoolean("stream", false)} " +
                    "headerKeys=${request.headers.names()} authPresent=$authPresent " +
                    "messages=$msgsLen tools=$toolsLen temp=$temp maxTokens=$maxTok hasSystem=$hasSystem " +
                    "useResponsesAPI=${!usesChatCompletionsAPI} bodyLen=${bodyStr.length}"
            )
        }

        val call = OpenAIStreamCall(client, request, STREAM_UPLOAD_CAP_MS, STREAM_TTFB_TIMEOUT_MS)
        val response = call.execute(this)
        // T321: response-side diagnostic log (status + select header values).
        run {
            val rh = response.headers
            val ct = rh["content-type"] ?: ""
            val rid = rh["x-request-id"] ?: rh["openai-request-id"] ?: ""
            val openAiHdrs = rh.names().filter { it.lowercase().startsWith("openai-") }
            com.openminis.app.logging.AppLogger.info(
                "OpenAIProvider",
                "[T321] ← RSP status=${response.code} content-type=$ct x-request-id=$rid " +
                    "headerKeys=${rh.names()} openAiHeaders=${openAiHdrs.associateWith { rh[it] ?: "" }}"
            )
        }
        if (!response.isSuccessful) {
            val errorBody = response.body?.string() ?: ""
            // T321: full error body — debug-only, but kept unconditional here
            // since non-2xx is rare and the body is critical for diagnosis.
            com.openminis.app.logging.AppLogger.error(
                "OpenAIProvider",
                "[T321] ← HTTP ${response.code} error body: $errorBody"
            )
            response.close()
            // T302: skip the LLMRequestLog write entirely on release builds —
            // not just to avoid the (already-truncated) retention cost, but to
            // dodge constructing the Entry / headerMap copies that go with it.
            if (com.openminis.app.BuildConfig.DEBUG) {
                com.openminis.app.debug.LLMRequestLog.add(
                    com.openminis.app.debug.LLMRequestLog.Entry(
                        provider = "openai",
                        requestURL = request.url.toString(),
                        requestHeaders = headerMap,
                        requestBody = bodyStr,
                        durationMs = System.currentTimeMillis() - startTime,
                        responseStatusCode = response.code,
                        responseBody = errorBody.take(2000),
                    )
                )
            }
            throw mapHttpError(response.code, errorBody)
        }
        if (com.openminis.app.BuildConfig.DEBUG) {
            com.openminis.app.debug.LLMRequestLog.add(
                com.openminis.app.debug.LLMRequestLog.Entry(
                    provider = "openai",
                    requestURL = request.url.toString(),
                    requestHeaders = headerMap,
                    requestBody = bodyStr,
                    durationMs = System.currentTimeMillis() - startTime,
                    responseStatusCode = response.code,
                )
            )
        }

        val reader = BufferedReader(InputStreamReader(response.body!!.byteStream()))

        // [T-codex-gpt-image2-oauth-android] gpt-image-2: the Codex backend
        // streams the image as a base64 blob (PNG / JPEG / WebP) inside the SSE
        // `image_generation_call` output item; there's no incremental text/tool
        // stream to parse. handleCodexImageStream parses the SSE line-by-line,
        // pulls the image (or a structured failure), emits a MediaAttachment
        // chunk, and finishes — bypassing the chat/tool SSE state machine below.
        if (images != null) {
            try {
                images.consumeCodex(reader) { chunk -> trySend(chunk) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                cancel("Image stream error", mapError(e))
            } finally {
                reader.close()
                response.close()
            }
            channel.close()
            awaitClose {
                try { call.cancel() } catch (_: Exception) {}
                try { response.close() } catch (_: Exception) {}
            }
            return@callbackFlow
        }

        // Chat Completions: tool calls are streamed as deltas keyed by index.
        try {
            OpenAIStreamDecoder(reader, !chatCompletions, body.getString("model")) { send(it) }.decode()
        } catch (error: Exception) {
            cancel("Stream error", OpenAIResponseDecoder.transportError(error))
        } finally {
            reader.close()
            response.close()
        }
        channel.close()
        awaitClose {
            try { call.cancel() } catch (_: Exception) {}
            try { response.close() } catch (_: Exception) {}
        }
    }

    // MARK: - Raw Passthrough [T-android-model-use-passthrough-mode]

    /**
     * Result of a raw passthrough call: unparsed response bytes + HTTP status +
     * the fully-assembled URL that was actually hit (surfaced to the caller per
     * the passthrough-mode contract). Mirrors iOS RawPassthroughResult.
     */
    class RawPassthroughResult(
        val data: ByteArray,
        val status: Int,
        val contentType: String?,
        val url: String,
    )

    /**
     * Execute a verbatim request against this provider instance's base URL with
     * the instance's credentials. The response is returned UNPARSED — passthrough
     * mode's output contract is raw bytes; the caller (agent or follow-up script)
     * owns interpretation. Mirrors iOS OpenAIProvider.rawPassthroughRequest.
     *
     * - endpoint: absolute path ("/x/y?q=1", replaces the whole URL path) or
     *   relative segment (joined after basePath like modeled endpoints). null →
     *   the default chat/completions path.
     * - headers: applied LAST → same-name REPLACE semantics over every default
     *   (including Authorization/Content-Type), per design.
     */
    suspend fun rawPassthroughRequest(
        endpoint: String?,
        method: String,
        headers: Map<String, String>,
        bodyObject: JSONObject?,
    ): RawPassthroughResult = withContext(Dispatchers.IO) {
        val url: String = when {
            endpoint != null && endpoint.startsWith("/") ->
                hostRootURL(endpoint)
                    ?: throw LLMError.ProviderError("Invalid passthrough endpoint: $endpoint")
            endpoint != null -> "$basePath/${endpoint.trimStart('/')}"
            else -> endpointURL("/chat/completions")
        }

        val verb = method.uppercase()
        val builder = Request.Builder().url(url)
        if (verb != "GET" && bodyObject != null) {
            builder.method(verb, OpenAIHttpRequests.jsonBody(bodyObject.toString()))
        } else {
            builder.method(verb, null)
        }
        val token = getToken()
        builder.applyKeyAuth(token)
        builder.header("Content-Type", "application/json")
        // ctor extraHeaders, then user headers LAST — replace semantics.
        for ((k, v) in extraHeaders) builder.header(k, v)
        for ((k, v) in headers) builder.header(k, v)

        com.openminis.app.logging.AppLogger.info(
            "OpenAIProvider",
            "[ModelUseRoute] route=raw-passthrough method=$verb url=$url " +
                "bodyKeys=[${bodyObject?.keys()?.asSequence()?.sorted()?.joinToString(",") ?: ""}] " +
                "headerOverrides=[${headers.keys.sorted().joinToString(",")}]",
        )

        val response = client.newCall(builder.build()).execute()
        response.use { resp ->
            RawPassthroughResult(
                data = resp.body?.bytes() ?: ByteArray(0),
                status = resp.code,
                contentType = resp.header("Content-Type"),
                url = url,
            )
        }
    }

    /**
     * [T-android-image-endpoint-mode] Generate an image via the OpenAI Images
     * API (`POST $basePath/images/generations`). Mirrors iOS
     * OpenAIProvider.generateImage. Used only by ModelUseOffloadHandler's
     * image-output routing for API-key OpenAI-compat instances — the Codex
     * OAuth gpt-image-2 path goes through the existing isCodexImageModel branch
     * and never reaches here.
     *
     * Request body: `{ model, prompt, n, size?, quality?, response_format:
     * "b64_json" }`. Some gateways (e.g. xAI) reject `response_format` — on a
     * 400 mentioning it, we retry once without the field (iOS parity).
     *
     * On a non-2xx response throws [mapHttpError]'s result. A route-missing
     * error (404 / "got chat completions response") surfaces as
     * LLMError.ProviderError whose message the handler matches with
     * looksLikeEndpointMissing() to drive the auto-mode fallback.
     */
    private fun imageProtocol(): OpenAIImageProtocol {
        val target = model
        return OpenAIImageProtocol(client, httpRequests, ::getToken,
            OpenAIImageProtocol.Settings(model = target, basePath = basePath, azure = isAzure,
                endpointOverride = absoluteEndpointOverride, pathOverride = imagePathOverride,
                extraBody = imageExtraBody.toMap(), headers = extraHeaders.toMap(),
                imageHeaders = imageExtraHeaders.toMap(), userAgent = customUserAgent,
                image25 = target.id in CODEX_IMAGE_25_MODEL_IDS))
    }

    suspend fun generateImage(prompt: String, n: Int = 1, size: String? = null, quality: String? = null): LLMResponse =
        imageProtocol().generateImage(prompt, n, size, quality)

    suspend fun editImage(prompt: String, images: List<LLMMessage.ImagePart>, n: Int = 1,
        size: String? = null, quality: String? = null): LLMResponse =
        imageProtocol().editImage(prompt, images, n, size, quality)

    private fun conversationEncoder(): OpenAIConversationEncoder {
        val target = model
        val fast = com.openminis.app.data.FastModePrefs.isEnabled()
        return OpenAIConversationEncoder(target,
            OpenAIConversationEncoder.Endpoint(basePath = basePath, oauth = isOAuth,
                forceChat = forceChatCompletions, azure = isAzure, openRouter = isOpenRouter,
                dashScope = isDashScope, mistral = isMistral, cerebras = isCerebras, xai = isXAI,
                unifiedEffort = usesUnifiedReasoningEffort),
            OpenAIConversationEncoder.Settings(cacheKey = OpenAIPromptCache.key(sessionId, promptCacheFallbackId),
                serviceTier = if (supportsPriorityProcessing && fast) "priority" else null, fastMode = fast,
                offEffort = explicitOffEffort(target.id), ruleInstanceId = thinkingRuleInstanceId,
                overrides = modelOverrides, extraBody = chatExtraBody.toMap()))
    }

    internal fun buildRequestBody(
        messages: List<LLMMessage>, systemPrompt: String?, maxTokens: Int, stream: Boolean,
        temperature: Double?, imageParts: List<LLMMessage.ImagePart>,
        tools: List<AgentToolDefinition> = emptyList(), thinkingLevel: ThinkingLevel = ThinkingLevel.OFF,
    ): JSONObject = conversationEncoder().buildRequestBody(messages, systemPrompt, maxTokens, stream,
        temperature, imageParts, tools, thinkingLevel)

    internal fun buildResponsesAPIBody(
        messages: List<LLMMessage>, systemPrompt: String?, maxTokens: Int, stream: Boolean,
        imageParts: List<LLMMessage.ImagePart> = emptyList(), tools: List<AgentToolDefinition> = emptyList(),
        thinkingLevel: ThinkingLevel = ThinkingLevel.OFF,
    ): JSONObject = conversationEncoder().buildResponsesAPIBody(messages, systemPrompt, maxTokens, stream,
        imageParts, tools, thinkingLevel)

    private suspend fun buildRequest(bodyStr: String, deployment: String): Request {
        val token = getToken()
        return httpRequests.conversation(bodyStr, token, useResponsesAPI, absoluteEndpointOverride, deployment,
            OpenAIHttpRequests.Headers(extraHeaders, perRequestHeaders, modelOverrides?.customHeaders ?: emptyMap(),
                chatExtraHeaders, customUserAgent, sessionId),
            if (isOAuth && !forceChatCompletions) OpenAIHttpRequests.Codex(CODEX_CLIENT_VERSION, codexAccountId) else null)
    }

    /**
     * Inject provider-specific thinking parameters into the request body.
     * - OpenRouter: `reasoning: {effort: ...}` (omitted when off so
     *   forced-reasoning models keep their default)
     * - OpenAI o-series / GPT-5.x: `reasoning_effort: ...` (off → skip)
     * - Qwen3 (DashScope): `enable_thinking: true/false, thinking_budget: N`
     *   — Qwen3 thinks by default, so OFF needs an explicit disable.
     * - DeepSeek V4 (deepseek-v4-flash / deepseek-v4-pro): `thinking` object —
     *   V4 thinks by default and rejects requests without an explicit toggle
     *   when reasoning_content is missing. Distinct from deepseek-reasoner /
     *   deepseek-chat which keep the no-params path below.
     * - DeepSeek (pre-V4) / GLM / Kimi / MiniMax: no params (model decides).
     */
    /**
     * [T-android-xhigh-effort-clamp] Clamp the reasoning-effort string for
     * model families whose backend only accepts low/medium/high and 400/422 on
     * our `xhigh` tier: MiMo-2.5/Pro and Agnes. For these, `xhigh` → `high`;
     * every other value passes through untouched, and every other model is
     * unaffected. Applied at the single point where each branch would emit an
     * effort string (Chat Completions reasoning_effort / reasoning.effort AND
     * the Responses API reasoning.effort) so no branch can leak a raw xhigh.
     * lowercase-contains match, mirroring the T-reasoning-effort-fallback keys.
     */
    internal fun clampEffort(effort: String, values: List<String>?): String {
        if (values.isNullOrEmpty()) return effort
        if (values.contains(effort)) return effort
        val ladder = listOf("none", "minimal", "low", "medium", "high", "xhigh", "max")
        val want = ladder.indexOf(effort)
        if (want < 0) return effort
        val declared = values.mapNotNull { v ->
            val i = ladder.indexOf(v)
            if (i >= 0) i to v else null
        }.sortedBy { it.first }
        if (declared.isEmpty()) return effort
        return declared.lastOrNull { it.first <= want }?.second ?: declared.first().second
    }

    // MARK: - Codex image generation (gpt-image-2)

    /**
     * [T-codex-gpt-image2-oauth-android] Build the Codex image_generation
     * request body. The wire model is gpt-5.5 (the Codex backend invokes the
     * underlying gpt-image-2 via the built-in image_generation tool); the user
     * turn is the fixed "Use the image generation tool to create: <prompt>"
     * instruction. The <prompt> is the latest user text — plain string content
     * or the concatenated text parts of the last user message.
     */
    private fun mapHttpError(statusCode: Int, body: String): LLMError = OpenAIResponseDecoder.httpError(statusCode, body)

    private fun mapError(error: Throwable): LLMError = OpenAIResponseDecoder.transportError(error)
}
