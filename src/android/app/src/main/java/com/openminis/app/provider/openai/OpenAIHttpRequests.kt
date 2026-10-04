package com.openminis.app.provider.openai

import com.openminis.app.provider.MinisUserAgent
import com.openminis.app.provider.OpenCodeSessionHeader
import com.openminis.app.provider.applyUserAgentOverride
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody
import org.json.JSONObject

/** HTTP routing/auth/header policy only. Does not build model input, acquire tokens or send calls. */
internal class OpenAIHttpRequests(
    private val basePath: String,
    private val isAzure: Boolean,
    private val azureBase: String?,
) {
    data class Headers(
        val defaults: Map<String, String> = emptyMap(),
        val derive: ((JSONObject) -> Map<String, String>)? = null,
        val model: Map<String, String> = emptyMap(),
        val call: Map<String, String> = emptyMap(),
        val userAgent: String? = null,
        val sessionId: String? = null,
    )
    data class Codex(val clientVersion: String, val accountId: String?)

    fun hostRootURL(path: String): String? {
        val base = basePath.toHttpUrlOrNull() ?: return null
        val queryIndex = path.indexOf('?')
        return base.newBuilder().encodedPath(if (queryIndex >= 0) path.substring(0, queryIndex) else path)
            .fragment(null).encodedQuery(if (queryIndex >= 0) path.substring(queryIndex + 1) else null).build().toString()
    }

    fun endpointURL(path: String, absoluteOverride: String?): String =
        absoluteOverride?.takeIf { it.startsWith("/") }?.let { hostRootURL(it) } ?: "$basePath$path"

    fun azureURL(path: String, deployment: String): String? {
        val raw = azureBase?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val index = raw.indexOf('?')
        val query = if (index >= 0) raw.substring(index) else ""
        var root = (if (index >= 0) raw.substring(0, index) else raw).trimEnd('/')
        if (root.endsWith("/v1")) root = root.dropLast(3).trimEnd('/')
        if (root.endsWith("/openai")) root = root.dropLast("/openai".length).trimEnd('/')
        return "$root/openai/deployments/$deployment/${path.removePrefix("/")}$query"
    }

    fun applyKeyAuth(builder: Request.Builder, token: String): Request.Builder = when {
        token.isEmpty() -> builder // Keyless compatible endpoints require no malformed empty auth header.
        isAzure -> builder.header("api-key", token)
        else -> builder.header("Authorization", "Bearer $token")
    }

    fun conversation(body: String, token: String, responses: Boolean, absoluteOverride: String?,
        deployment: String, headers: Headers, codex: Codex? = null): Request {
        if (codex != null) {
            val builder = Request.Builder().url("https://chatgpt.com/backend-api/codex/responses")
                .post(jsonBody(body)).header("Authorization", "Bearer $token")
                .header("Version", codex.clientVersion).header("Openai-Beta", "responses=experimental")
                .header("User-Agent", "codex_cli_rs/${codex.clientVersion} (Android; arm64)")
                .header("Originator", "codex_cli_rs")
            // Header identity comes from this exact body's key, never another live session-id read.
            val parsed = JSONObject(body)
            OpenAIPromptCache.codexHeaders(parsed).forEach { (key, value) -> builder.header(key, value) }
            if (com.openminis.app.BuildConfig.DEBUG) android.util.Log.d("PromptCache", OpenAIPromptCache.diagnostics(parsed))
            codex.accountId?.let { builder.header("Chatgpt-Account-Id", it) }
            builder.applyUserAgentOverride(headers.userAgent, defaultUserAgent = null)
            return builder.build()
        }
        val path = if (responses) "/responses" else "/chat/completions"
        val url = when {
            absoluteOverride?.startsWith("/") == true -> endpointURL(path, absoluteOverride)
            isAzure -> azureURL(path, deployment) ?: "$basePath$path"
            else -> "$basePath$path"
        }
        val builder = applyKeyAuth(Request.Builder().url(url).post(jsonBody(body)), token)
            .header("Content-Type", "application/json")
        headers.defaults.forEach { (key, value) -> builder.header(key, value) }
        headers.derive?.let { derive -> runCatching { JSONObject(body) }.getOrNull()?.let {
            derive(it).forEach { (key, value) -> builder.header(key, value) }
        } }
        OpenCodeSessionHeader.headersFor(url, headers.sessionId).forEach { (key, value) -> builder.header(key, value) }
        headers.model.forEach { (key, value) -> builder.header(key, value) }
        headers.call.forEach { (key, value) -> builder.header(key, value) }
        // Protocol UA (e.g. Copilot) survives branding; an explicit instance override still wins.
        builder.applyUserAgentOverride(headers.userAgent,
            defaultUserAgent = if (headers.defaults.keys.any { it.equals("User-Agent", true) }) null else MinisUserAgent.DEFAULT)
        return builder.build()
    }

    companion object {
        /** Bare application/json on all JSON routes; charset suffixes break some compatible servers. */
        fun jsonBody(text: String): RequestBody {
            val bytes = text.toByteArray(Charsets.UTF_8)
            val type = "application/json".toMediaType()
            return object : RequestBody() {
                override fun contentType() = type
                override fun contentLength() = bytes.size.toLong()
                override fun writeTo(sink: okio.BufferedSink) { sink.write(bytes) }
            }
        }
    }
}
