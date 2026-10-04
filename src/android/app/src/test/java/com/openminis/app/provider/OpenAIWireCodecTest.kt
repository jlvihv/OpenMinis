package com.openminis.app.provider

import com.openminis.app.data.model.*
import com.openminis.app.provider.openai.ChatMessagesEncoder
import com.openminis.app.provider.openai.OpenAIResponseDecoder
import com.openminis.app.provider.openai.ResponsesInputEncoder
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class OpenAIWireCodecTest {
    @Test fun responsesProjectionPreservesTypedCallsAndLastUserMultimediaOrdering() {
        val source = "// @options: {\"tool_title\":\"检查\"}\nreturn 1"
        val calls = LLMMessage(LLMMessage.Role.ASSISTANT, "", contentParts = listOf(
            AgentContentPart.Text("before"), AgentContentPart.ToolUse("call_1|fc_1", "codemode", JSONObject().put("code", source)),
            AgentContentPart.ToolUse("call_2", "bash", JSONObject().put("command", "true"))))
        val results = LLMMessage(LLMMessage.Role.USER, "", contentParts = listOf(
            AgentContentPart.ToolResult("call_1|fc_1", "codemode", "one"),
            AgentContentPart.ToolResult("call_2", "bash", "two")))
        val last = LLMMessage(LLMMessage.Role.USER, "caption", audioParts = listOf(LLMMessage.AudioPart("wav", "AA==")))
        val image = LLMMessage.ImagePart(byteArrayOf(1), "image/png", noVisionPlaceholder = "no vision")
        val input = ResponsesInputEncoder.encode(listOf(LLMMessage(LLMMessage.Role.USER, "first"), calls, results, last), listOf(image), false, true)
        assertEquals(7, input.length())
        assertEquals("first", input.getJSONObject(0).getString("content"))
        assertEquals("before", input.getJSONObject(1).getString("content"))
        assertEquals("custom_tool_call", input.getJSONObject(2).getString("type"))
        assertEquals("ctc_1", input.getJSONObject(2).getString("id"))
        assertEquals(source, input.getJSONObject(2).getString("input"))
        assertEquals("fc_syn_call_2", input.getJSONObject(3).getString("id"))
        assertEquals("custom_tool_call_output", input.getJSONObject(4).getString("type"))
        assertEquals("function_call_output", input.getJSONObject(5).getString("type"))
        val content = input.getJSONObject(6).getJSONArray("content")
        assertEquals("input_audio", content.getJSONObject(0).getString("type"))
        assertEquals("caption", content.getJSONObject(1).getString("text"))
        assertEquals("no vision", content.getJSONObject(2).getString("text"))
        val structured = last.copy(audioParts = emptyList(), contentParts = listOf(AgentContentPart.Text("A"),
            AgentContentPart.ImageData(byteArrayOf(1), "image/png", noVisionPlaceholder = "B"), AgentContentPart.Text("C")))
        val blocks = ResponsesInputEncoder.encode(listOf(structured), emptyList(), false, false).getJSONObject(0).getJSONArray("content")
        assertEquals(listOf("A", "B", "C"), (0 until blocks.length()).map { blocks.getJSONObject(it).getString("text") })
    }

    @Test fun chatProjectionPreservesReasoningAndPairedRequestLocalIds() {
        val raw = "call_" + "a".repeat(80)
        val call = LLMMessage(LLMMessage.Role.ASSISTANT, "", contentParts = listOf(
            AgentContentPart.ToolUse("$raw|fc_item", "bash", JSONObject().put("command", "true"))))
        val result = LLMMessage(LLMMessage.Role.USER, "", contentParts = listOf(AgentContentPart.ToolResult(raw, "bash", "ok")))
        val last = LLMMessage(LLMMessage.Role.USER, "caption", audioParts = listOf(LLMMessage.AudioPart("wav", "AA==")))
        val image = LLMMessage.ImagePart(byteArrayOf(1), "image/png", noVisionPlaceholder = "no vision")
        val messages = ChatMessagesEncoder.encode(listOf(call, result, call.copy(reasoningContent = "thought"), result, last), "system", listOf(image), false, true)
        val firstId = messages.getJSONObject(1).getJSONArray("tool_calls").getJSONObject(0).getString("id")
        val secondId = messages.getJSONObject(3).getJSONArray("tool_calls").getJSONObject(0).getString("id")
        assertTrue(firstId.length <= 64)
        assertEquals("$firstId-2", secondId)
        assertEquals(firstId, messages.getJSONObject(2).getString("tool_call_id"))
        assertEquals(secondId, messages.getJSONObject(4).getString("tool_call_id"))
        assertEquals("", messages.getJSONObject(1).getString("reasoning_content"))
        assertEquals("thought", messages.getJSONObject(3).getString("reasoning_content"))
        val content = messages.getJSONObject(5).getJSONArray("content")
        assertEquals("no vision", content.getJSONObject(0).getString("text"))
        assertEquals("input_audio", content.getJSONObject(1).getString("type"))
        assertEquals("caption", content.getJSONObject(2).getString("text"))
        val forbidden = ChatMessagesEncoder.encode(listOf(call.copy(reasoningContent = "thought")), null, emptyList(), false, false)
        assertFalse(forbidden.getJSONObject(0).has("reasoning_content"))
    }

    @Test fun receiptNormalizationAndErrorClassificationPreserveAccountingAndFallback() {
        val chat = OpenAIResponseDecoder.chatUsage(JSONObject("""{"prompt_tokens":1000,"completion_tokens":10,"prompt_cache_hit_tokens":900}"""))
        val responses = OpenAIResponseDecoder.responsesUsage(JSONObject("""{"input_tokens":1000,"output_tokens":10,"input_tokens_details":{"cached_tokens":900}}"""))
        assertEquals(chat, responses)
        assertEquals(100, chat.inputTokens)
        assertEquals(1000, chat.latestContextTokens)
        assertEquals(900, chat.cacheReadInputTokens)
        assertEquals(1000, OpenAIResponseDecoder.chatUsage(JSONObject("""{"prompt_tokens":1000}""")).inputTokens)
        assertEquals(10, OpenAIResponseDecoder.responsesUsage(JSONObject("""{"input_tokens":10,"input_tokens_details":{"cached_tokens":20}}""")).inputTokens)
        assertTrue(OpenAIResponseDecoder.httpError(401, "") is LLMError.InvalidApiKey)
        assertTrue(OpenAIResponseDecoder.httpError(429, "") is LLMError.RateLimited)
        assertEquals(503, (OpenAIResponseDecoder.httpError(503, "overloaded") as LLMError.TransientError).httpStatus)
        assertEquals(503, (OpenAIResponseDecoder.httpError(503, "model_not_found") as LLMError.ProviderError).httpStatus)
        assertEquals(520, (OpenAIResponseDecoder.httpError(520, """{"error":{"message":"edge"}}""") as LLMError.ProviderError).httpStatus)
        val domain = LLMError.InvalidApiKey()
        assertSame(domain, OpenAIResponseDecoder.transportError(domain))
        assertTrue(OpenAIResponseDecoder.transportError(java.io.IOException()) is LLMError.NetworkError)
    }
}
