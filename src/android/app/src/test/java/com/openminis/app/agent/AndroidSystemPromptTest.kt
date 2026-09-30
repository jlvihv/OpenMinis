package com.openminis.app.agent

import com.openminis.app.ProductionSources
import com.openminis.app.tools.AgentTools
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidSystemPromptTest {
    private fun prompt(browser: Boolean = true, delegation: Boolean = true): String =
        AndroidSystemPrompt.build(
            identitySection = "Identity and personality.\n\n",
            browserEnabled = browser,
            delegationBullets = if (delegation) "- subagent_task: Delegate.\nRoster marker." else "",
            delegationOffered = delegation,
        )

    @Test
    fun `runtime guidance stays compact and deterministic`() {
        val text = prompt()
        assertTrue("Keep runtime guidance below 5.5k characters; schemas/help own usage", text.length < 5_500)
        assertEquals(text, prompt())
        assertTrue(text.startsWith("Identity and personality.\n\nAct on"))
        assertFalse(text.contains("Runtime context:"))
        assertFalse(text.contains("Current date:"))
        assertFalse(text.contains("Device language:"))
    }

    @Test
    fun `capability gates remove all references to unavailable tools`() {
        for (browser in listOf(false, true)) {
            for (delegation in listOf(false, true)) {
                val text = prompt(browser, delegation)
                assertEquals(browser, text.contains("browser_use"))
                assertEquals(browser, text.contains("Google login/OAuth"))
                assertEquals(browser, text.contains("HTML sub-resources"))
                assertEquals(delegation, text.contains("subagent_task"))
                assertEquals(delegation, text.contains("Roster marker"))
                // Deep links are still useful when browsing is disabled.
                assertTrue(text.contains("minis:// action URLs"))
                assertTrue(text.contains("minis://settings/permissions"))
            }
        }
    }

    @Test
    fun `delegation redirects stay next to both easily confused CLIs`() {
        val text = prompt()
        val modelLine = text.lines().single { it.startsWith("- minis-model-use:") }
        val scheduledLine = text.lines().single { it.startsWith("- minis-scheduled:") }
        assertTrue(modelLine.contains("use subagent_task instead"))
        assertTrue(scheduledLine.contains("Do not use minis-scheduled to delegate"))
        assertTrue(text.contains("results arrive automatically as new messages"))
        assertTrue(text.contains("do not poll them in a loop"))
    }

    @Test
    fun `waiting scheduling and helper boundaries remain explicit`() {
        val text = prompt()
        assertTrue(text.contains("delay parameter, not sleep"))
        assertTrue(text.contains("Never promise future monitoring or reporting without registering a follow-up"))
        assertTrue(text.contains("Run `minis-scheduled create …` via shell_execute"))
        assertTrue(text.contains("report its task id"))
        assertTrue(text.contains("force-stop cancels pending tasks"))
        assertTrue(text.contains("Helpers must not schedule tasks or delegate further"))
        assertTrue(text.contains("--target follow-up in THIS chat"))
        assertTrue(text.contains("--target new only if the user explicitly wants a separate chat"))
        assertTrue(text.contains("Read the returned delivery line"))
    }

    @Test
    fun `secret handling and user approval cannot be lost to compression`() {
        val text = prompt()
        assertTrue(text.contains("NEVER echo, print, cat, log or otherwise output"))
        assertTrue(text.contains("never inline literal values"))
        assertTrue(text.contains("[ -n \"\$VAR\" ] && echo 'set' || echo 'not set'"))
        assertTrue(text.contains("\$\$ENV_VAR reference"))
        assertTrue(text.contains("API keys/OAuth tokens/env values are never readable"))
        assertTrue(text.contains("OAuth tokens and env values are not settable here"))
        assertTrue(text.contains("Writes require user approval"))
        assertTrue(text.contains("relay the returned user_message"))
        assertTrue(text.contains("On permission_denied, relay the error and do not retry"))
        assertTrue(text.contains("create_key=ENV_NAME&create_value="))
    }

    @Test
    fun `resource rendering encoding and action distinction remain explicit`() {
        val text = prompt()
        assertTrue(text.contains("minis://<directory>/<path> maps to /var/minis/<directory>/<path>"))
        assertTrue(text.contains("embed ALL images/audio/video with ![description](minis://...)"))
        assertTrue(text.contains("Link files with [name](minis://...)"))
        assertTrue(text.contains("[text](url) only creates a link"))
        assertTrue(text.contains("Prefer the minis_url"))
        assertTrue(text.contains("non-ASCII characters, emoji and spaces"))
        assertTrue(text.contains("Never send minis:// action URLs to browser_use"))
        assertTrue(text.contains("DOES NOT execute"))
        assertFalse(text.contains("WebKit"))
        assertFalse(text.contains("QuickLook"))
    }

    @Test
    fun `Linux pitfalls and bounded file search remain covered`() {
        val text = prompt()
        for (rule in listOf(
            "Use file tools, not shell echo/printf/heredocs",
            "BusyBox ash, NOT bash", "ICMP/ping is blocked", "musllinux_aarch64",
            "matplotlib.use('Agg')", "redirect stdout/stderr", "apk add ripgrep",
            "Search /var/minis/ first", "never start with the whole filesystem", "read-only",
        )) assertTrue("Missing shell/file guard: $rule", text.contains(rule))
    }

    @Test
    fun `tool-specific rules live in schemas rather than being repeated`() {
        val text = prompt()
        assertFalse(text.contains("1000 characters"))
        assertFalse(text.contains("file_read first"))
        val tools = AgentTools.makeAgentTools().associateBy { it.name }
        assertTrue(tools.getValue("file_edit").description.contains("file_read first"))
        assertTrue(tools.getValue("shell_execute").parameters.getValue("command").description.contains("Maximum 1000 characters"))
    }

    @Test
    fun `CLI discovery is retained without embedding full manuals`() {
        val text = prompt()
        assertTrue(text.contains("run via shell_execute, NOT function tools"))
        assertTrue(text.contains("run <command> --help"))
        for (cli in listOf(
            "android-alarm", "android-calendar", "android-clipboard", "android-contacts",
            "android-device", "android-location", "android-notification", "android-open",
            "android-photos", "android-player", "android-speak", "android-speech",
            "android-weather", "android-shizuku-cli", "android-a11y-cli", "minis-open",
            "minis-sessions-cli", "minis-model-use", "minis-config", "minis-scheduled",
        )) assertTrue("Missing CLI: $cli", text.contains(cli))
        for (group in listOf("Personal data:", "Device:", "Media:", "System:")) {
            assertTrue("CLI directory must stay grouped: $group", text.contains(group))
        }
        assertTrue(text.contains("topic-help <topic>"))
        assertTrue(text.contains("paginate/filter lists"))
        assertTrue(text.contains("Use OpenAI-compatible messages JSON"))
        assertTrue(text.contains("modality capabilities"))
        assertTrue(text.contains("warnings and applied_extras"))
    }

    @Test
    fun `Google login failure redirects rather than retrying`() {
        val text = prompt()
        assertTrue(text.contains("accounts.google.com"))
        assertTrue(text.contains("disallowed_useragent"))
        assertTrue(text.contains("do not retry or attempt login"))
        assertTrue(text.contains("login in system Chrome"))
        assertTrue(text.contains("paste the needed content back into chat"))
    }

    @Test
    fun `identity fragments precede guidance and changing runtime values remain last`() {
        val source = ProductionSources.read("ui/chat/ChatViewModel.kt")
            .substringAfter("private fun buildSystemPrompt(): String?")
            .substringBefore("suspend fun executeBrowserUse")
        assertTrue(source.contains("HelperRunner.identitySection(it, browserEnabled = browserToolEnabled)"))
        assertTrue(source.contains("SystemPromptBuilder.identitySection(context)"))
        assertTrue(source.contains("browserEnabled = browserToolEnabled"))
        assertTrue(source.contains("delegationOffered = delegationOffered"))
        val base = source.indexOf("append(base)")
        val skills = source.indexOf("append(skillFragment)")
        val mcp = source.indexOf("append(mcpFragment)")
        val runtime = source.indexOf("Runtime context:")
        assertTrue(base >= 0 && base < skills && skills < mcp && mcp < runtime)
    }
}
