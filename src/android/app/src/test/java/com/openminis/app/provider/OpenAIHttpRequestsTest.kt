package com.openminis.app.provider

import com.openminis.app.provider.openai.OpenAIHttpRequests
import okhttp3.Request
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test

class OpenAIHttpRequestsTest {
    private val body = """{"prompt_cache_key":"serialized-session","input":[{"text":"中文🧪"}]}"""

    @Test fun normalRoutingAndHeaderPrecedenceKeepExactUtf8Body() {
        val requests = OpenAIHttpRequests("https://opencode.ai/zen/go/v1", false, null)
        val headers = OpenAIHttpRequests.Headers(
            defaults = mapOf("X-Layer" to "default", "user-agent" to "editor-protocol"),
            derive = { mapOf("X-Layer" to "derived", "X-Body-Key" to it.getString("prompt_cache_key")) },
            model = mapOf("X-Layer" to "model"), call = mapOf("X-Layer" to "call"), sessionId = "live-session")
        val request = requests.conversation(body, "test-token", false, "/actual/chat?q=1", "deployment", headers)
        assertEquals("https://opencode.ai/actual/chat?q=1", request.url.toString())
        assertEquals("Bearer test-token", request.header("Authorization"))
        assertEquals("call", request.header("X-Layer"))
        assertEquals("serialized-session", request.header("X-Body-Key"))
        assertEquals("live-session", request.header(OpenCodeSessionHeader.HEADER))
        assertEquals("editor-protocol", request.header("User-Agent"))
        assertEquals("application/json", request.body!!.contentType().toString())
        val bytes = Buffer().also { request.body!!.writeTo(it) }.readByteArray()
        assertArrayEquals(body.toByteArray(Charsets.UTF_8), bytes)
        assertEquals(bytes.size.toLong(), request.body!!.contentLength())
        val overridden = requests.conversation(body, "", true, null, "deployment", headers.copy(userAgent = "explicit"))
        assertNull(overridden.header("Authorization"))
        assertEquals("explicit", overridden.header("User-Agent"))
        assertEquals("/zen/go/v1/responses", overridden.url.encodedPath)
        assertEquals("/zen/go/v1/chat/completions", requests.conversation(body, "", false, null, "d", headers).url.encodedPath)
    }

    @Test fun azureAndCodexAuthenticationRemainSeparate() {
        val requests = OpenAIHttpRequests("https://example.test/v1", true, " https://resource.test/openai/v1/?api-version=2026-01-01 ")
        val azure = requests.conversation(body, "test-token", false, null, "deployment", OpenAIHttpRequests.Headers())
        assertEquals("https://resource.test/openai/deployments/deployment/chat/completions?api-version=2026-01-01", azure.url.toString())
        assertEquals("test-token", azure.header("api-key"))
        assertNull(azure.header("Authorization"))
        assertNull(requests.applyKeyAuth(Request.Builder().url("https://resource.test"), "").build().header("api-key"))
        val codex = requests.conversation(body, "test-oauth", false, "/ignored", "deployment",
            OpenAIHttpRequests.Headers(defaults = mapOf("X-Ignored" to "ignored"), sessionId = "different-live-id"),
            OpenAIHttpRequests.Codex("test-version", "test-account"))
        assertEquals("https://chatgpt.com/backend-api/codex/responses", codex.url.toString())
        assertEquals("Bearer test-oauth", codex.header("Authorization"))
        assertNull(codex.header("api-key"))
        assertNull(codex.header("X-Ignored"))
        assertEquals("serialized-session", codex.header("session-id"))
        assertEquals("serialized-session", codex.header("x-client-request-id"))
        assertEquals("test-account", codex.header("Chatgpt-Account-Id"))
        assertEquals("codex_cli_rs/test-version (Android; arm64)", codex.header("User-Agent"))
        assertEquals(body, Buffer().also { codex.body!!.writeTo(it) }.readUtf8())
    }
}
