package com.openminis.app.tools

import com.openminis.app.agent.AndroidSystemPrompt
import com.openminis.app.agent.jobs.HelperRunner
import com.openminis.app.browser.BrowserAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Budgets cover the fixed prompt + real serialized schemas, not history/user-authored fragments. */
class AgentPromptBudgetTest {
    @Test
    fun `fixed prompt and full tool schemas have bounded size`() {
        val prompt = AndroidSystemPrompt.build("", true, HelperRunner.systemPromptBullet(true), true)
        val tools = AgentTools.makeAgentTools()
        val schemas = tools.sumOf { it.toOpenAIJson().toString().length }
        println("Agent prompt budget: system=${prompt.length}, schemas=$schemas, total=${prompt.length + schemas}")
        assertTrue("System guidance grew to ${prompt.length} characters", prompt.length < 5_500)
        assertTrue("Tool schemas grew to $schemas characters", schemas < 12_000)
        assertTrue("Fixed input grew to ${prompt.length + schemas} characters", prompt.length + schemas < 17_000)
    }

    @Test
    fun `compression preserves tool names parameter types required fields and ordering`() {
        val expected = mapOf(
            "shell_execute" to "tool_title:string command:string timeout:integer delay:integer",
            "file_read" to "tool_title:string path:string offset:integer lines:integer max_length:integer direction:string",
            "file_write" to "tool_title:string path:string content:string append:boolean create_dirs:boolean",
            "file_edit" to "tool_title:string path:string old_string:string new_string:string replace_all:boolean",
            "read_image" to "tool_title:string path:string prompt:string",
            "browser_use" to "tool_title:string action:string url:string selector:string text:string coordinate_x:integer coordinate_y:integer direction:string amount:integer script:string user_agent:string max_depth:integer scroll_count:integer item_selector:string tab_id:integer keywords:string fuzzy:boolean cookies:string timeout:integer viewport_width:integer viewport_height:integer reset:boolean full_page:boolean",
            "subagent_task" to "tool_title:string action:string task:string agent:string model_choice:string context:string max_minutes:integer wait:boolean progress_report:string job_id:string message:string child_session_id:string",
        )
        val required = mapOf(
            "shell_execute" to listOf("tool_title", "command"),
            "file_read" to listOf("tool_title", "path"),
            "file_write" to listOf("tool_title", "path", "content"),
            "file_edit" to listOf("tool_title", "path", "old_string", "new_string"),
            "read_image" to listOf("tool_title", "path"),
            "browser_use" to listOf("tool_title", "action"),
            "subagent_task" to listOf("tool_title"),
        )
        val tools = AgentTools.makeAgentTools(rosterNames = listOf("General", "Research"))
        assertEquals(expected.keys.toList(), tools.map { it.name })
        for (tool in tools) {
            assertEquals(tool.name, expected[tool.name], tool.parameters.entries.joinToString(" ") { "${it.key}:${it.value.type}" })
            assertEquals(tool.name, required[tool.name], tool.required)
            val ordering = when (tool.name) {
                "file_read" -> listOf("tool_title", "path", "offset", "lines", "direction", "max_length")
                "browser_use" -> "tool_title action tab_id url selector text coordinate_x coordinate_y direction amount scroll_count item_selector script user_agent max_depth keywords fuzzy cookies timeout viewport_width viewport_height reset full_page".split(" ")
                else -> tool.parameters.keys.toList()
            }
            assertEquals(tool.name, ordering, tool.propertyOrdering)
        }
        val browser = tools.single { it.name == "browser_use" }
        assertEquals(BrowserAction.allValues, browser.parameters.getValue("action").enumValues)
        val subagent = tools.single { it.name == "subagent_task" }
        assertEquals(listOf("General", "Research"), subagent.parameters.getValue("agent").enumValues)
        assertEquals(listOf("up", "down"), browser.parameters.getValue("direction").enumValues)
        assertEquals(listOf("desktop_chrome", "mobile_chrome"), browser.parameters.getValue("user_agent").enumValues)
        assertEquals(listOf("delegate", "status", "steer", "cancel", "resume"), subagent.parameters.getValue("action").enumValues)
        assertEquals(listOf("same_as_me", "default_model", "sub_model"), subagent.parameters.getValue("model_choice").enumValues)
        assertEquals(listOf("none", "frequent", "moderate"), subagent.parameters.getValue("progress_report").enumValues)
    }

    @Test
    fun `non-obvious browser and file contracts remain discoverable`() {
        val tools = AgentTools.makeAgentTools().associateBy { it.name }
        val browser = tools.getValue("browser_use")
        assertTrue(browser.description.contains("raw cookie values"))
        assertTrue(browser.description.contains("minis://"))
        assertTrue(browser.parameters.getValue("script").description.contains("await"))
        assertTrue(browser.parameters.getValue("cookies").description.contains("http_only"))
        assertTrue(browser.parameters.getValue("full_page").description.contains("32768"))
        assertTrue(browser.parameters.getValue("tab_id").description.contains("list_tabs"))
        assertTrue(tools.getValue("file_write").description.contains("8KB"))
        assertTrue(tools.getValue("file_edit").description.contains("file_read first"))
        assertTrue(tools.getValue("file_read").parameters.getValue("offset").description.contains("next_offset"))
    }
}
