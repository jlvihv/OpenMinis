package com.openminis.app.tools

import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CodemodeToolTest {
    @Test fun sourcePreservesLinesAndPiDefaults() {
        assertEquals(CodemodeTool.Source("return 1"), CodemodeTool.parseSource("return 1"))
        val source = CodemodeTool.parseSource("  // @options: {\"max_output_tokens\":0,\"timeout_ms\":100}\r\nreturn 1")
        assertEquals("\nreturn 1", source.code)
        assertEquals(0L, source.maxOutputTokens)
        assertEquals(100L, source.timeoutMs)
    }

    @Test fun invalidSourcesAreRejected() {
        val invalid = listOf("", " \n", "// @options: {}", "// @options: null\nreturn 1",
            "// @options: {\"max_output_tokens\":-1}\nreturn 1", "// @options: {\"timeout_ms\":0}\nreturn 1",
            "// @options: {\"timeout_ms\":2147483648}\nreturn 1", "// @options: {\"max_output_tokens\":1.5}\nreturn 1",
            "// @options: {\"other\":1}\nreturn 1", "// @options: {'timeout_ms':1}\nreturn 1",
            "// @options: {} trailing\nreturn 1", "// @options: []\nreturn 1")
        invalid.forEach { input ->
            try { CodemodeTool.parseSource(input); fail("Accepted invalid source: $input") }
            catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun identifiersMatchPi() {
        assertEquals("my_tool", CodemodeTool.identifier("my-tool"))
        assertEquals("_foo", CodemodeTool.identifier("1foo"))
        assertEquals("_", CodemodeTool.identifier(""))
        assertEquals("mcp__dev_radius__search", CodemodeTool.identifier("mcp__dev-radius__search"))
    }

    @Test fun onlyModeHidesDeclarationsNotCallableTools() {
        val direct = AgentTools.makeAgentTools(delegateEnabled = false, browserEnabled = false, supportsImageInput = false)
        val on = AgentTools.prepareCodemodeLoadout(direct, "on")
        assertEquals(direct.size + 1, on.size)
        assertTrue(on.first().description.contains("Codemode: tools."))
        assertEquals(listOf("codemode"), AgentTools.prepareCodemodeLoadout(direct, "only").map { it.name })
        assertEquals(direct, AgentTools.prepareCodemodeLoadout(direct, "off"))
        val only = AgentTools.prepareCodemodeLoadout(direct, "only").single()
        assertTrue(only.description.contains("tools:"))
        assertEquals(listOf("code"), only.required)
        assertFalse(only.parameters.containsKey("tool_title"))
    }

    @Test fun inlineBudgetLeavesToolsDiscoverable() {
        val tools = AgentTools.makeAgentTools()
        val definition = CodemodeTool.definition(tools, 0)
        assertFalse(definition.description.contains("###"))
        val result = CodemodeTool.search(tools, "shell command", 8)
        assertEquals("shell_execute", result.getJSONObject(0).getString("name"))
    }

    @Test fun discoveryMatchesCamelCaseAndPlurals() {
        val tools = listOf(AgentToolDefinition("searchIssues", "Search project issues", mapOf("query" to AgentToolParam("string", "Search term"))))
        assertEquals(1, CodemodeTool.search(tools, "issue", 8).length())
        assertEquals(0, CodemodeTool.search(tools, "the and of", 8).length())
    }

    @Test fun storeBranchDeltasAndDeletes() {
        val store = JSONObject()
        CodemodeTool.applyWrites(store, "[[\"a\",\"{\\\"n\\\":1}\"],[\"b\",\"2\"]]")
        assertEquals("{\"n\":1}", store.getString("a"))
        CodemodeTool.applyWrites(store, "[[\"a\"],[\"b\",\"3\"]]")
        assertFalse(store.has("a"))
        assertEquals("3", store.getString("b"))
    }
}
