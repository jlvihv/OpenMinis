package com.openminis.app.provider

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMModel
import com.openminis.app.provider.openai.OpenAIProvider
import com.openminis.app.provider.openai.ResponsesToolPairing
import com.openminis.app.tools.CodemodeTool
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CodemodeResponsesTest {
    private val model = LLMModel("gpt-6.1-sol", "Sol", "OpenAI", supportsReasoning = true)
    private fun history() = listOf(
        LLMMessage(LLMMessage.Role.ASSISTANT, "", contentParts = listOf(AgentContentPart.ToolUse(
            "call_one|ctc_one", "codemode", JSONObject().put("code", "return 1")))),
        LLMMessage(LLMMessage.Role.USER, "", contentParts = listOf(AgentContentPart.ToolResult(
            "call_one|ctc_one", "codemode", "Script completed", detailsJson = "{\"calls\":[{\"args\":\"private-preview\"}]}"))),
    )

    @Test fun codexUsesRawGrammarAndCustomHistory() {
        val provider = OpenAIProvider(oauthTokenProvider = { "token" }, model = model)
        val body = provider.buildResponsesAPIBody(history(), null, 1000, false, tools = listOf(CodemodeTool.definition(emptyList())))
        val tool = body.getJSONArray("tools").getJSONObject(0)
        assertEquals("custom", tool.getString("type"))
        assertEquals("lark", tool.getJSONObject("format").getString("syntax"))
        assertEquals(CodemodeTool.SOURCE_GRAMMAR, tool.getJSONObject("format").getString("definition"))
        assertFalse(body.toString().contains("private-preview"))
        val input = body.getJSONArray("input")
        assertEquals("custom_tool_call", input.getJSONObject(0).getString("type"))
        assertEquals("return 1", input.getJSONObject(0).getString("input"))
        assertEquals("ctc_one", input.getJSONObject(0).getString("id"))
        assertEquals("custom_tool_call_output", input.getJSONObject(1).getString("type"))
    }

    @Test fun thirdPartyResponsesFallBackToCodeJson() {
        val provider = OpenAIProvider(apiKey = "key", model = model, basePath = "https://example.org/v1", useResponsesAPI = true)
        val body = provider.buildResponsesAPIBody(history(), null, 1000, false, tools = listOf(CodemodeTool.definition(emptyList())))
        assertEquals("function", body.getJSONArray("tools").getJSONObject(0).getString("type"))
        val input = body.getJSONArray("input")
        assertEquals("function_call", input.getJSONObject(0).getString("type"))
        assertEquals("fc_one", input.getJSONObject(0).getString("id"))
        assertEquals("return 1", JSONObject(input.getJSONObject(0).getString("arguments")).getString("code"))
        assertEquals("function_call_output", input.getJSONObject(1).getString("type"))
    }

    @Test fun pairingRepairsMixedRawAndFunctionCalls() {
        val input = JSONArray().put(JSONObject().put("type", "custom_tool_call").put("call_id", "a"))
            .put(JSONObject().put("type", "function_call").put("call_id", "b"))
            .put(JSONObject().put("type", "message"))
        val result = ResponsesToolPairing.sanitize(input)
        assertEquals(listOf("a", "b"), result.placeholderCalls)
        assertEquals("custom_tool_call_output", result.items.getJSONObject(2).getString("type"))
        assertEquals("function_call_output", result.items.getJSONObject(3).getString("type"))
        assertEquals("message", result.items.getJSONObject(4).getString("type"))
    }
}
