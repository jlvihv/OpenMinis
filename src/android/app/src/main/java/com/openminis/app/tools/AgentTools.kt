package com.openminis.app.tools

import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.SubAgentDefinition

/**
 * Central registry of all agent tool definitions.
 * Returns provider-agnostic AgentToolDefinition list used by the agent loop.
 * Schema shape matches iOS; descriptions stay concise to bound per-request input.
 */
object AgentTools {

    fun makeAgentTools(
        // [T-p1-delegate-task] True for a helper (child) vm. Depth = 1: a
        // helper never sees the delegation tool.
        isHelper: Boolean = false,
        // [T-p2-agent-settings] Settings › Agents can remove delegation globally.
        delegateEnabled: Boolean = true,
        // [T-tools-granular-switches] Settings › Agent Tools can remove
        // browser globally. Separate from delegateEnabled because the two
        // are independent user choices; mirrors iOS AgentToolSwitch.browser.
        browserEnabled: Boolean = true,
        // [T-sub-agents-v1] The names of the enabled sub agents, in roster
        // order. These become `subagent.agent`'s enum, and the caller
        // rebuilds them every turn: the schema is not cached, so a rename takes
        // effect on the next request and the model cannot emit a name that
        // would fail to resolve. Empty falls back to the built-in's name so the
        // enum is never an empty list (which some providers reject).
        rosterNames: List<String> = listOf(SubAgentDefinition.BUILT_IN_NAME),
        // Roster descriptions ride the subagent schema: they describe that tool's `agent`
        // parameter, so they belong there rather than in the system prompt.
        rosterSection: String = "",
    ): List<AgentToolDefinition> = buildList {
        add(ReadTool.definition())
        add(BashTool.definition())
        add(EditTool.definition())
        add(WriteTool.definition())
        if (browserEnabled) add(BrowserTool.definition())
        if (!isHelper && delegateEnabled) {
            add(SubagentTool.definition(rosterNames, rosterSection))
        }
    }


}
