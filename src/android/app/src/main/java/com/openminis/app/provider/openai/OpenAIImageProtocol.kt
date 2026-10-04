package com.openminis.app.provider.openai

import android.util.Base64
import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMError
import com.openminis.app.data.model.LLMMediaAttachment
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.LLMResponse
import com.openminis.app.data.model.LLMStreamChunk
import com.openminis.app.provider.applyUserAgentOverride
import com.openminis.app.provider.safeOptString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader

internal class OpenAIImageProtocol(
    private val client: OkHttpClient,
    private val http: OpenAIHttpRequests,
    private val token: suspend () -> String,
    private val settings: Settings,
) {
    data class Settings(
        val model: LLMModel,
        val basePath: String,
        val azure: Boolean,
        val endpointOverride: String?,
        val pathOverride: String?,
        val extraBody: Map<String, Any?>,
        val headers: Map<String, String>,
        val imageHeaders: Map<String, String>,
        val userAgent: String?,
        val image25: Boolean,
    )
    private val model get() = settings.model
    private val basePath get() = settings.basePath
    private val isAzure get() = settings.azure
    private val absoluteEndpointOverride get() = settings.endpointOverride
    private val imagePathOverride get() = settings.pathOverride
    private val imageExtraBody get() = settings.extraBody
    private val extraHeaders get() = settings.headers
    private val imageExtraHeaders get() = settings.imageHeaders
    private val customUserAgent get() = settings.userAgent
    private suspend fun getToken() = token()
    private fun hostRootURL(path: String) = http.hostRootURL(path)
    private fun azureUrl(path: String) = http.azureURL(path, model.id)
    private fun Request.Builder.applyKeyAuth(token: String): Request.Builder = http.applyKeyAuth(this, token)
    private fun mapHttpError(status: Int, body: String) = OpenAIResponseDecoder.httpError(status, body)

    suspend fun generateImage(
        prompt: String,
        n: Int = 1,
        size: String? = null,
        quality: String? = null,
    ): LLMResponse = withContext(Dispatchers.IO) {
        val token = getToken()
        // [T-android-model-use-image-passthrough GH#62] Honor an explicit
        // endpoint-path override (non-standard providers); default otherwise.
        val imagePath = imagePathOverride?.takeIf { it.isNotBlank() } ?: "/images/generations"
        // [T-android-model-use-passthrough-mode] The absolute-path override wins
        // over the legacy relative imagePathOverride (which is joined after
        // basePath and can't escape base prefixes — iOS baseline p03).
        // [T-android-azure-openai] Azure image generation routes via the
        // deployments path + api-key header; falls back to basePath otherwise.
        val abs = absoluteEndpointOverride
        val url = when {
            abs != null && abs.startsWith("/") -> hostRootURL(abs) ?: "$basePath$imagePath"
            isAzure -> azureUrl(imagePath) ?: "$basePath$imagePath"
            else -> "$basePath$imagePath"
        }

        // [T-android-model-use-image-passthrough GH#62] When the user explicitly
        // supplies response_format, respect it and skip the b64_json auto-probe.
        val userSetResponseFormat = imageExtraBody.containsKey("response_format")
        var triedWithoutFormat = userSetResponseFormat
        while (true) {
            val body = JSONObject()
                .put("model", model.id)
                .put("prompt", prompt)
                .put("n", n)
            if (size != null) body.put("size", size)
            if (quality != null) body.put("quality", quality)
            if (!triedWithoutFormat) body.put("response_format", "b64_json")
            // [T-android-model-use-image-passthrough GH#62] Merge user-supplied
            // passthrough fields. User keys WIN over our defaults (they can
            // override prompt/size or add Seedream's `image`/`watermark`), but
            // `model` is force-kept to the resolved id afterward so a stray
            // override can't misroute the request.
            for ((k, v) in imageExtraBody) body.put(k, v ?: JSONObject.NULL)
            body.put("model", model.id)

            val bodyStr = body.toString()
            val builder = Request.Builder()
                .url(url)
                .post(OpenAIHttpRequests.jsonBody(bodyStr))
                .applyKeyAuth(token)
                .header("Content-Type", "application/json")
            for ((key, value) in extraHeaders) {
                builder.header(key, value)
            }
            // [T-android-model-use-image-passthrough GH#62] Per-call passthrough
            // headers, merged after the ctor extraHeaders so they can add/override.
            for ((key, value) in imageExtraHeaders) {
                builder.header(key, value)
            }
            builder.applyUserAgentOverride(customUserAgent)
            val request = builder.build()

            com.openminis.app.logging.AppLogger.info(
                "OpenAIProvider",
                "[ModelUseRoute] → images/generations url=$url model=${model.id} n=$n " +
                    "size=$size quality=$quality respFormat=${if (triedWithoutFormat) "<none>" else "b64_json"}",
            )

            val response = client.newCall(request).execute()
            val statusCode = response.code
            val responseBody = response.body?.string() ?: ""
            response.close()

            // Some providers (xAI) don't support b64_json — retry without it once.
            if (!triedWithoutFormat && statusCode == 400 &&
                (responseBody.lowercase().contains("response_format") || responseBody.contains("b64_json"))
            ) {
                com.openminis.app.logging.AppLogger.info(
                    "OpenAIProvider",
                    "[ModelUseRoute] images/generations rejected b64_json — retrying without response_format",
                )
                triedWithoutFormat = true
                continue
            }

            if (statusCode !in 200..299) {
                com.openminis.app.logging.AppLogger.warning(
                    "OpenAIProvider",
                    "[ModelUseRoute] images/generations HTTP $statusCode body=${responseBody.take(300)}",
                )
                throw mapHttpError(statusCode, responseBody)
            }

            val json = try {
                JSONObject(responseBody)
            } catch (e: Exception) {
                throw LLMError.ProviderError("images/generations returned non-JSON body: ${e.message}")
            }
            return@withContext parseImageGenerationsResult(json)
        }
        @Suppress("UNREACHABLE_CODE")
        throw LLMError.ProviderError("images/generations: unreachable")
    }

    /**
     * [T-android-image-edit-endpoint] Call `/images/edits` for image-to-image
     * (reference-image) generation. Android previously had no such endpoint, so
     * minis-model-use returned `image_edit_not_supported` for every
     * input-image + pure-image-generator call — the gap this closes. Mirrors
     * iOS `OpenAIProvider.editImage`.
     *
     * Request: multipart/form-data with `image` (file), `prompt`, `model`, `n`,
     * plus optional `size` / `quality`.
     * Response: identical shape to `/images/generations`
     * (`{ data: [{ b64_json?, url? }] }`), so [parseImageGenerationsResult] is
     * reused verbatim.
     *
     * Multi-image: the first attachment goes in as `image`, any extras as
     * `image[]` — same field naming as iOS. Providers that only accept a single
     * reference image reject the extras themselves; nothing is silently dropped
     * on our side.
     */
    suspend fun editImage(
        prompt: String,
        images: List<LLMMessage.ImagePart>,
        n: Int = 1,
        size: String? = null,
        quality: String? = null,
    ): LLMResponse = withContext(Dispatchers.IO) {
        if (images.isEmpty()) {
            throw LLMError.ProviderError("images/edits requires at least one input image")
        }
        val token = getToken()
        // Same override precedence as generateImage: explicit path override →
        // Azure deployments path → basePath. Only the default differs.
        val imagePath = imagePathOverride?.takeIf { it.isNotBlank() } ?: "/images/edits"
        val abs = absoluteEndpointOverride
        val url = when {
            abs != null && abs.startsWith("/") -> hostRootURL(abs) ?: "$basePath$imagePath"
            isAzure -> azureUrl(imagePath) ?: "$basePath$imagePath"
            else -> "$basePath$imagePath"
        }

        // b64_json auto-probe, mirroring generateImage: some providers reject
        // response_format on the edits route, so retry once without it.
        val userSetResponseFormat = imageExtraBody.containsKey("response_format")
        var triedWithoutFormat = userSetResponseFormat
        while (true) {
            val multipart = MultipartBody.Builder().setType(MultipartBody.FORM)
            multipart.addFormDataPart("model", model.id)
            multipart.addFormDataPart("prompt", prompt)
            multipart.addFormDataPart("n", n.toString())
            if (size != null) multipart.addFormDataPart("size", size)
            if (quality != null) multipart.addFormDataPart("quality", quality)
            if (!triedWithoutFormat) multipart.addFormDataPart("response_format", "b64_json")
            // Passthrough body fields arrive as JSON scalars; multipart carries
            // text only, so stringify. `model` is re-pinned below so a stray
            // override can't misroute the request (same rule as generateImage).
            for ((k, v) in imageExtraBody) {
                if (k == "model") continue
                multipart.addFormDataPart(k, v?.toString() ?: "")
            }

            for ((idx, img) in images.withIndex()) {
                val ext = img.mimeType.substringAfterLast('/', "").ifEmpty { "png" }
                val fieldName = if (idx == 0) "image" else "image[]"
                multipart.addFormDataPart(
                    fieldName,
                    "image$idx.$ext",
                    img.data.toRequestBody(img.mimeType.toMediaType()),
                )
            }

            val builder = Request.Builder()
                .url(url)
                .post(multipart.build())
                .applyKeyAuth(token)
            for ((key, value) in extraHeaders) {
                builder.header(key, value)
            }
            for ((key, value) in imageExtraHeaders) {
                builder.header(key, value)
            }
            builder.applyUserAgentOverride(customUserAgent)
            val request = builder.build()

            com.openminis.app.logging.AppLogger.info(
                "OpenAIProvider",
                "[ModelUseRoute] → images/edits url=$url model=${model.id} n=$n " +
                    "size=$size quality=$quality images=${images.size} " +
                    "respFormat=${if (triedWithoutFormat) "<none>" else "b64_json"}",
            )

            val response = client.newCall(request).execute()
            val statusCode = response.code
            val responseBody = response.body?.string() ?: ""
            response.close()

            if (!triedWithoutFormat && statusCode == 400 &&
                (responseBody.lowercase().contains("response_format") || responseBody.contains("b64_json"))
            ) {
                com.openminis.app.logging.AppLogger.info(
                    "OpenAIProvider",
                    "[ModelUseRoute] images/edits rejected b64_json — retrying without response_format",
                )
                triedWithoutFormat = true
                continue
            }

            if (statusCode !in 200..299) {
                com.openminis.app.logging.AppLogger.warning(
                    "OpenAIProvider",
                    "[ModelUseRoute] images/edits HTTP $statusCode body=${responseBody.take(300)}",
                )
                throw mapHttpError(statusCode, responseBody)
            }

            val json = try {
                JSONObject(responseBody)
            } catch (e: Exception) {
                throw LLMError.ProviderError("images/edits returned non-JSON body: ${e.message}")
            }
            return@withContext parseImageGenerationsResult(json)
        }
        @Suppress("UNREACHABLE_CODE")
        throw LLMError.ProviderError("images/edits: unreachable")
    }

    /**
     * Parse the `/images/generations` response into an [LLMResponse] carrying
     * the decoded image bytes as [LLMMediaAttachment]s. Supports `b64_json`
     * (inline) and `url` (downloaded) item shapes. Mirrors iOS
     * parseImageGenerationsResult. When the body has no `data` array but DOES
     * carry `choices`, a proxy silently rerouted us to chat completions — throw
     * a route-missing error so auto-mode falls back instead of caching the
     * wrong endpoint.
     */
    private fun parseImageGenerationsResult(json: JSONObject): LLMResponse {
        val dataArray = json.optJSONArray("data")
        if (dataArray == null) {
            if (json.has("choices")) {
                throw LLMError.ProviderError(
                    "[404] /images/generations not supported (got chat completions response)",
                )
            }
            return LLMResponse("", "end_turn", null, emptyList())
        }

        val attachments = mutableListOf<LLMMediaAttachment>()
        val revisedPrompts = mutableListOf<String>()
        for (i in 0 until dataArray.length()) {
            val item = dataArray.optJSONObject(i) ?: continue
            val hintMime = item.safeOptString("mime_type", "").ifEmpty { null } // xAI extension
            val b64 = item.safeOptString("b64_json", "")
            if (b64.isNotEmpty()) {
                val bytes = try {
                    Base64.decode(b64, Base64.DEFAULT)
                } catch (e: IllegalArgumentException) {
                    com.openminis.app.logging.AppLogger.warning(
                        "OpenAIProvider",
                        "[ModelUseRoute] images/generations b64 decode failed: ${e.message}",
                    )
                    continue
                }
                val mime = hintMime ?: detectImageMime(bytes)
                attachments.add(LLMMediaAttachment(LLMMediaAttachment.MediaType.IMAGE, mime, bytes))
            } else {
                val urlStr = item.safeOptString("url", "")
                if (urlStr.isNotEmpty()) {
                    try {
                        val dlReq = Request.Builder().url(urlStr).get().build()
                        val dlResp = client.newCall(dlReq).execute()
                        val dlBytes = dlResp.body?.bytes()
                        val ctMime = dlResp.header("Content-Type")
                        dlResp.close()
                        if (dlBytes != null && dlBytes.isNotEmpty()) {
                            val mime = hintMime ?: ctMime ?: detectImageMime(dlBytes)
                            attachments.add(LLMMediaAttachment(LLMMediaAttachment.MediaType.IMAGE, mime, dlBytes))
                        }
                    } catch (e: Exception) {
                        com.openminis.app.logging.AppLogger.warning(
                            "OpenAIProvider",
                            "[ModelUseRoute] failed to download image from $urlStr: ${e.message}",
                        )
                    }
                }
            }
            val revised = item.safeOptString("revised_prompt", "")
            if (revised.isNotEmpty()) revisedPrompts.add(revised)
        }

        val text = revisedPrompts.joinToString("\n")
        return LLMResponse(text, "end_turn", null, attachments)
    }

    // `internal` rather than private so the serialization can be asserted
    // directly in unit tests. The tool-result-image regression this guards
    // (T-android-toolresult-image-dropped) is a property of the request BODY,
    // and going through MockWebServer to read it only adds a network dependency
    // to a question that is pure JSON construction.
    fun codexBody(messages: List<LLMMessage>): JSONObject {
        val lastUser = messages.lastOrNull { it.role == LLMMessage.Role.USER }
        val prompt = lastUser?.let { m ->
            m.content.takeIf { it.isNotBlank() }
                ?: m.contentParts.filterIsInstance<AgentContentPart.Text>()
                    .joinToString(" ") { it.text }.trim()
        }.orEmpty()
        return JSONObject().apply {
            put("model", "gpt-5.5")
            put("instructions", "You are a helpful assistant. Use tools when available.")
            put("input", JSONArray().put(JSONObject().apply {
                put("role", "user")
                put("content", "Use the image generation tool to create: $prompt")
            }))
            put("store", false)
            // [T-codex-gpt-image25-android] Name the image model in the tool
            // object for the 2.5 variants; leave it off for gpt-image-2.
            //
            // A bare {type:image_generation} lets the backend pick its default,
            // which is what gpt-image-2 has always relied on — adding the field
            // there would pin behaviour that is currently the backend's to
            // choose, so this stays additive and that path is byte-identical.
            // Mirrors CLIProxyAPI PR #5642.
            val imageTool = JSONObject().put("type", "image_generation")
            if (settings.image25) imageTool.put("model", model.id)
            put("tools", JSONArray().put(imageTool))
            put("reasoning", JSONObject().put("effort", "low"))
            put("include", JSONArray())
            put("tool_choice", "auto")
            put("parallel_tool_calls", true)
            put("stream", true)
        }
    }

    /**
     * [T-android-codex-image-stream-parse-fix #617] Consume the Codex Responses
     * SSE stream and extract the generated image, emitting it as a
     * MediaAttachment chunk followed by Finished.
     *
     * Structurally aligned with iOS `consumeCodexImageStream` (1225ec0b /
     * 2dd35a14): parse each `data:` SSE line as JSON and pull the base64 from
     * the `image_generation_call` output item's `result` field — NOT a blind
     * regex over the raw body. The previous regex `iVBOR[A-Za-z0-9+/=]{1000,}`
     * only matched PNG base64 (iVBOR is the base64 of the PNG \x89PNG header),
     * so a WebP (UklGR…) or JPEG (/9j/…) image — which gpt-image-2 routinely
     * returns — never matched and the method threw "no image data" even though
     * the Codex backend had returned a full ~8 MB valid image (#615 diagnosis).
     *
     * Failure modes, each a distinct LLMError (mirrors iOS):
     *   - auth (401/403): surfaced earlier by the non-2xx branch → mapHttpError,
     *     never reaches here.
     *   - safety refusal: `image_generation_call` status=failed and/or a refusal
     *     message instead of an image → ProviderError("rejected by safety…").
     *   - no image: stream completed with neither image nor refusal →
     *     ProviderError("No image data…"). Only reported in this genuine case —
     *     not on a successfully-decoded non-PNG image.
     *   - network/interface: read throws (IOException) → caller maps to
     *     NetworkError.
     *
     * Streams line-by-line (no 8 MB StringBuilder + regex backtracking): only
     * the one `result` base64 string is retained, decoded once at the end.
     * Never logs the token (the SSE body carries no Authorization).
     */
    suspend fun consumeCodex(
        reader: BufferedReader,
        emit: (LLMStreamChunk) -> Unit,
    ) {
        var b64Result: String? = null
        var revisedPrompt: String? = null
        var imageCallFailed = false
        var refusalText: String? = null

        // Pull the base64 result / failure / revised prompt out of one output
        // item. Used both for streamed `response.output_item.done` items and,
        // as a fallback, for every item in the final `response.completed`
        // payload (matches iOS scanItem).
        fun scanItem(item: JSONObject) {
            when (item.optString("type")) {
                "image_generation_call" -> {
                    if (item.optString("status") == "failed") imageCallFailed = true
                    item.optString("result").takeIf { it.isNotEmpty() }?.let { b64Result = it }
                    item.optString("revised_prompt").takeIf { it.isNotEmpty() }?.let { revisedPrompt = it }
                }
                "message", "output_text" -> {
                    // Refusal / explanation text the model emits when it declines.
                    val content = item.optJSONArray("content")
                    if (content != null) {
                        for (i in 0 until content.length()) {
                            val c = content.optJSONObject(i) ?: continue
                            if (c.optString("type").contains("text")) {
                                c.optString("text").takeIf { it.isNotEmpty() }?.let { refusalText = it }
                            }
                        }
                    } else {
                        item.optString("text").takeIf { it.isNotEmpty() }?.let { refusalText = it }
                    }
                }
            }
        }

        var line: String?
        while (reader.readLine().also { line = it } != null) {
            val l = line ?: continue
            // Tolerate both `data: {…}` and `data:{…}` (same as the chat path).
            if (!l.startsWith("data:")) continue
            val payload = l.removePrefix("data:").let { if (it.startsWith(" ")) it.removePrefix(" ") else it }
            if (payload == "[DONE]") break

            val event = try { JSONObject(payload) } catch (e: Exception) { continue }
            when (event.optString("type")) {
                "response.output_item.done" -> {
                    event.optJSONObject("item")?.let { scanItem(it) }
                }
                "response.output_text.done", "response.output_text.delta" -> {
                    event.optString("text").takeIf { it.isNotEmpty() }?.let { refusalText = it }
                        ?: event.optString("delta").takeIf { it.isNotEmpty() }
                            ?.let { refusalText = (refusalText ?: "") + it }
                }
                "response.completed" -> {
                    if (b64Result == null) {
                        val output = event.optJSONObject("response")?.optJSONArray("output")
                        if (output != null) {
                            for (i in 0 until output.length()) {
                                output.optJSONObject(i)?.let { scanItem(it) }
                            }
                        }
                    }
                }
                "response.failed", "error" -> {
                    val msg = event.optJSONObject("response")?.optJSONObject("error")?.optString("message")
                        ?.takeIf { it.isNotEmpty() }
                        ?: event.optJSONObject("error")?.optString("message")?.takeIf { it.isNotEmpty() }
                        ?: "Codex image generation failed"
                    throw LLMError.ProviderError(msg)
                }
            }
        }

        // Success: base64 image extracted. Detect the real format from the
        // decoded bytes (PNG / JPEG / WebP / GIF) instead of assuming PNG.
        val b64 = b64Result
        if (b64 != null) {
            val bytes = try {
                Base64.decode(b64, Base64.DEFAULT)
            } catch (e: Exception) {
                throw LLMError.ProviderError("Failed to decode generated image: ${e.message}")
            }
            if (bytes.isNotEmpty()) {
                emit(
                    LLMStreamChunk.MediaAttachment(
                        LLMMediaAttachment(
                            type = LLMMediaAttachment.MediaType.IMAGE,
                            mimeType = detectImageMime(bytes),
                            data = bytes,
                        ),
                    ),
                )
                emit(LLMStreamChunk.Finished("end_turn"))
                return
            }
        }

        // Safety refusal: the image call explicitly failed and/or the model
        // returned a refusal message instead of an image.
        if (imageCallFailed || refusalText != null) {
            val reason = refusalText?.trim()
            throw LLMError.ProviderError(
                "Image generation was rejected by the safety system" +
                    (reason?.takeIf { it.isNotBlank() }?.let { ": $it" } ?: "."),
            )
        }

        // Stream completed with neither an image nor a refusal.
        throw LLMError.ProviderError("No image data in Codex response")
    }

    /**
     * [T-android-codex-image-stream-parse-fix] Detect an image's MIME type from
     * its magic bytes. Mirrors iOS `detectImageMime`. gpt-image-2 can return
     * PNG, JPEG, or WebP, so the previous hardcoded "image/png" mislabeled
     * non-PNG output. Falls back to image/png when too short / unrecognized.
     */
    private fun detectImageMime(data: ByteArray): String {
        if (data.size < 4) return "image/png"
        val b = data.take(4).map { it.toInt() and 0xFF }
        return when {
            b[0] == 0x89 && b[1] == 0x50 && b[2] == 0x4E && b[3] == 0x47 -> "image/png"
            b[0] == 0xFF && b[1] == 0xD8 -> "image/jpeg"
            b[0] == 0x52 && b[1] == 0x49 && b[2] == 0x46 && b[3] == 0x46 -> "image/webp" // RIFF (WebP)
            b[0] == 0x47 && b[1] == 0x49 && b[2] == 0x46 -> "image/gif"
            else -> "image/png"
        }
    }

    // MARK: - Responses API (Codex OAuth)

    /**
     * Build request body for the Responses API format (used by Codex OAuth).
     * Uses `input` instead of `messages`, `instructions` instead of system prompt.
     */
    // `internal` for the same reason as [buildRequestBody]: the tool-result-image
    // regression is asserted against the constructed JSON directly.

}
