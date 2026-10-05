package com.openminis.app.agent

import android.content.Context
import com.openminis.app.browser.BrowserTabPool
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.ModelImageResizeOptions
import com.openminis.app.data.repository.ChatRepository
import com.openminis.app.data.repository.SkillRepository
import com.openminis.app.tools.AgentToolSwitch
import com.openminis.app.tools.CodemodeTool
import com.openminis.app.tools.EditTool
import com.openminis.app.tools.ReadTool
import com.openminis.app.tools.ToolExecutionResult
import com.openminis.app.tools.WriteTool
import com.openminis.app.agent.jobs.HelperRunner
import org.json.JSONObject

internal class AgentToolExecutor(private val context: Context, private val skills: SkillRepository?,
    repository: ChatRepository, browser: () -> BrowserTabPool,
    private val definitions: () -> List<AgentToolDefinition>) {
    private val codemode = AgentCodemodeExecutor(context, repository)
    private val browserExecutor = AgentBrowserExecutor(context, browser)

    data class InputContext(val fsSessionId: String, val supportsImages: Boolean, val resize: ModelImageResizeOptions?,
        val sessionId: String, val checkBranch: () -> Unit)
    data class Effects(
        val subagent: suspend (args: String, action: String?) -> ToolExecutionResult,
        val disabled: (String) -> ToolExecutionResult,
        val bashLine: (String) -> Unit,
        val openUrl: (String) -> Unit,
        val codemodeUpdate: (List<CodemodeTool.Call>) -> Unit,
    )

    suspend fun execute(name: String, args: String, toolId: String, input: InputContext,
        effects: Effects, nestedEffects: (String, String) -> Effects): ToolExecutionResult {
        input.checkBranch()
        return when (name) {
            ReadTool.NAME -> ReadTool.execute(args, input.fsSessionId, context,
                resizeOptions = input.resize, supportsImages = input.supportsImages).also { result ->
                if (result.success) runCatching {
                    val path = JSONObject(args).optString("path", "")
                    if (path.isNotEmpty()) skills?.skillIdFromPath(path)?.let { skills.recordSkillUse(it) }
                }
            }
            WriteTool.NAME -> WriteTool.execute(args, input.fsSessionId, context).also { if (it.success) reloadForPath(args) }
            EditTool.NAME -> EditTool.execute(args, input.fsSessionId, context).also { if (it.success) reloadForPath(args) }
            "bash" -> AgentBashExecutor(context).execute(args, input.sessionId, input.fsSessionId,
                effects.bashLine, effects.openUrl).also { skills?.requestReload("bash") }
            "browser" -> if (AgentToolSwitch.BROWSER.isEnabled(context)) browserExecutor.execute(args, input) else effects.disabled(name)
            HelperRunner.TOOL_NAME -> if (AgentToolSwitch.AGENTS.isEnabled(context)) {
                val action = runCatching { JSONObject(args).optString("action", "").trim().lowercase() }.getOrDefault("")
                effects.subagent(args, action)
            } else ToolExecutionResult(HelperRunner.rejectionJson("tools_disabled",
                "Agents are turned off in this app's settings; subagent cannot be used. Do not try it again in this conversation."), false)
            CodemodeTool.NAME -> codemode.execute(args, toolId, input, definitions(), effects.disabled, effects.codemodeUpdate,
                invoke = { nestedName, nestedArgs, id ->
                    // Codemode's catalog/preflight excludes itself; every nested call reuses this captured input.
                    execute(nestedName, nestedArgs, id, input, nestedEffects(nestedName, id), nestedEffects)
                })
            else -> ToolExecutionResult("Unknown tool: $name", false)
        }
    }

    private fun reloadForPath(args: String) {
        runCatching {
            val path = JSONObject(args).optString("path", "")
            if (path.contains("/skills/") && path.endsWith("SKILL.md")) skills?.requestReload("write", force = true)
        }
    }
}
