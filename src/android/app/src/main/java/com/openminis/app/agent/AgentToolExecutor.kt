package com.openminis.app.agent

import android.content.Context
import com.openminis.app.data.model.ModelImageResizeOptions
import com.openminis.app.data.repository.SkillRepository
import com.openminis.app.tools.AgentToolSwitch
import com.openminis.app.tools.CodemodeTool
import com.openminis.app.tools.EditTool
import com.openminis.app.tools.ReadTool
import com.openminis.app.tools.ToolExecutionResult
import com.openminis.app.tools.WriteTool
import com.openminis.app.agent.jobs.HelperRunner
import org.json.JSONObject

internal class AgentToolExecutor(private val context: Context, private val skills: SkillRepository?) {
    data class InputContext(val fsSessionId: String, val supportsImages: Boolean, val resize: ModelImageResizeOptions?)

    suspend fun execute(name: String, args: String, input: InputContext,
        delegated: suspend (name: String, args: String, action: String?) -> ToolExecutionResult,
        disabled: (String) -> ToolExecutionResult): ToolExecutionResult = when (name) {
        ReadTool.NAME -> ReadTool.execute(args, input.fsSessionId, context,
            resizeOptions = input.resize, supportsImages = input.supportsImages).also { result ->
            if (result.success) runCatching {
                val path = JSONObject(args).optString("path", "")
                if (path.isNotEmpty()) skills?.skillIdFromPath(path)?.let { skills.recordSkillUse(it) }
            }
        }
        WriteTool.NAME -> WriteTool.execute(args, input.fsSessionId, context).also { if (it.success) reloadForPath(args) }
        EditTool.NAME -> EditTool.execute(args, input.fsSessionId, context).also { if (it.success) reloadForPath(args) }
        "bash" -> delegated(name, args, null).also { skills?.requestReload("bash") }
        "browser" -> if (AgentToolSwitch.BROWSER.isEnabled(context)) delegated(name, args, null) else disabled(name)
        HelperRunner.TOOL_NAME -> if (AgentToolSwitch.AGENTS.isEnabled(context)) {
            val action = runCatching { JSONObject(args).optString("action", "").trim().lowercase() }.getOrDefault("")
            delegated(name, args, action)
        } else ToolExecutionResult(HelperRunner.rejectionJson("tools_disabled",
            "Agents are turned off in this app's settings; subagent cannot be used. Do not try it again in this conversation."), false)
        CodemodeTool.NAME -> delegated(name, args, null)
        else -> ToolExecutionResult("Unknown tool: $name", false)
    }

    private fun reloadForPath(args: String) {
        runCatching {
            val path = JSONObject(args).optString("path", "")
            if (path.contains("/skills/") && path.endsWith("SKILL.md")) skills?.requestReload("write", force = true)
        }
    }
}
