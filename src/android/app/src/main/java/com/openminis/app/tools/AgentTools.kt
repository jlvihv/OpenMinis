package com.openminis.app.tools

import com.openminis.app.browser.BrowserAction
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import com.openminis.app.data.model.SubAgentDefinition

/**
 * Central registry of all agent tool definitions.
 * Returns provider-agnostic AgentToolDefinition list used by the agent loop.
 * Schema shape matches iOS; descriptions stay concise to bound per-request input.
 */
object AgentTools {

    fun makeAgentTools(
        supportsImageInput: Boolean = true,
        // [T-android-vision-group / GH#182] When the main model can't natively
        // see images but the user has bound a Vision Group, still expose
        // read_image: ReadImageTool routes the image through a vision-capable
        // group member and returns a text description. Mirrors iOS makeAgentTools
        // visionGroupConfigured. Neither native vision nor a Vision Group → tool
        // stays absent (current behaviour).
        visionGroupConfigured: Boolean = false,
        // [T-p1-delegate-task] True for a helper (child) vm. Depth = 1: a
        // helper never sees the delegation tool.
        isHelper: Boolean = false,
        // [T-p2-agent-settings] Settings › Agents can remove delegation globally.
        delegateEnabled: Boolean = true,
        // [T-tools-granular-switches] Settings › Agent Tools can remove
        // browser_use globally. Separate from delegateEnabled because the two
        // are independent user choices; mirrors iOS AgentToolSwitch.browser.
        browserEnabled: Boolean = true,
        // [T-sub-agents-v1] The names of the enabled sub agents, in roster
        // order. These become `subagent_task.agent`'s enum, and the caller
        // rebuilds them every turn: the schema is not cached, so a rename takes
        // effect on the next request and the model cannot emit a name that
        // would fail to resolve. Empty falls back to the built-in's name so the
        // enum is never an empty list (which some providers reject).
        rosterNames: List<String> = listOf(SubAgentDefinition.BUILT_IN_NAME),
    ): List<AgentToolDefinition> = buildList {
        add(shellExecuteDefinition())
        add(FileReadTool.definition())
        add(FileWriteTool.definition())
        add(FileEditTool.definition())
        if (supportsImageInput || visionGroupConfigured) {
            add(ReadImageTool.definition())
        }
        if (browserEnabled) add(browserUseDefinition())
        if (!isHelper && delegateEnabled) {
            add(subAgentTaskDefinition(rosterNames))
        }
    }

    /**
     * [T-sub-agents-v1] The one sub agent tool: delegate, and inspect or stop
     * what you delegated.
     *
     * Replaces `delegate_task` + `agent_status`. The separate status tool is
     * folded in as an `action`, so the model sees ONE tool — the shape iOS
     * settled on. Keep names, types and action semantics compatible while
     * avoiding repeated usage manuals in descriptions.
     *
     * [rosterNames] are the enabled sub agents' names, rebuilt every turn
     * (the schema is not cached), so a rename takes effect on the next request
     * and the model cannot invent a name that would fail to resolve.
     *
     * `task` is deliberately NOT in [required]: it is required for
     * action=delegate and meaningless for status/cancel, which JSON Schema
     * cannot express here. The dispatcher rejects a delegate call with no task.
     */
    private fun subAgentTaskDefinition(rosterNames: List<String>): AgentToolDefinition =
        AgentToolDefinition(
            name = SubAgentDefinition.TOOL_NAME,
            description = "Delegate substantial, self-contained work to an isolated sub agent, or inspect/control your agents. It cannot see this chat or its memory. Avoid trivial 1-2 call tasks or work needing user confirmation. Up to 3 run concurrently; extras queue automatically: never re-delegate queued work. With wait=false, final/partial results arrive as new [Background task finished …] messages, including on cancellation/timeout/failure; no status polling is needed. End your turn when idle, never promise later reporting.",
            parameters = mapOf(
                "tool_title" to AgentToolParam("string", "User-visible 5-10 word summary, in the user's language."),
                "action" to AgentToolParam("string", "delegate (default): start task; status: inspect one/all; steer: correct a running agent; cancel: stop and return partial results; resume: restart interrupted runs (job_id/child_session_id for one, omit both for all).", enumValues = listOf("delegate", "status", "steer", "cancel", "resume")),
                "task" to AgentToolParam("string", "Required for delegate. Complete brief: goal, success criteria, paths/URLs, constraints and deliverable; the agent sees nothing else."),
                "agent" to AgentToolParam("string", "Delegate only. Choose by roster description; omitted = general agent.", enumValues = rosterNames),
                "model_choice" to AgentToolParam("string", "Delegate, Auto agents only; pinned groups ignore this. same_as_me (default): current model, always use when unsure. default_model: strongest group, only for a concrete need for more capability. sub_model: light group, only for mechanical, bounded, easily verified work. Judge task demands, not duration; switching may change cost/quality.", enumValues = listOf("same_as_me", "default_model", "sub_model")),
                "context" to AgentToolParam("string", "Delegate only. Optional raw material appended verbatim to task."),
                "max_minutes" to AgentToolParam("integer", "Delegate time budget: default 10, maximum 60 minutes; timeout stops the run and returns partial results."),
                "wait" to AgentToolParam("boolean", "Delegate only. false (default): return job_id immediately, result arrives later. true: wait for a required dependency; a user message switches the run to background."),
                "progress_report" to AgentToolParam("string", "Delegate only; ignored with wait=true. none (default): final only; frequent: changes every 15s; moderate: once/minute. Progress costs model turns: enable only when needed, never poll or re-delegate on progress.", enumValues = listOf("none", "frequent", "moderate")),
                "job_id" to AgentToolParam("string", "Returned job id or prefix. Required for steer/cancel; optional for status (omit = all in this chat) and resume."),
                "message" to AgentToolParam("string", "Required for steer. Correction delivered at the next turn, without interrupting an active tool; reported missed if already finished."),
                "child_session_id" to AgentToolParam("string", "Resume only. One interrupted child; omit both ids to resume all in this chat."),
            ),
            required = listOf("tool_title"),
            propertyOrdering = listOf(
                "tool_title", "action", "task", "agent", "model_choice", "context",
                "max_minutes", "wait", "progress_report", "job_id", "message",
                "child_session_id",
            ),
        )

    // Aligned with iOS AIChatViewModel.swift:4982-4993
    private fun shellExecuteDefinition(): AgentToolDefinition = AgentToolDefinition(
        name = "shell_execute",
        description = "Run /bin/sh -c in Alpine Linux via PRoot, with merged stdout/stderr. Fresh process per call; filesystem persists, terminal state does not.",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "User-visible 5-10 word summary, in the user's language."),
            "command" to AgentToolParam("string", "Shell command, may be multi-line. Maximum 1000 characters; write longer scripts with file_write, then run them."),
            // [T-android-parity-fixes] State the ceiling. It used to say only "use
            // a larger value" while values above 900 were silently cut to 900.
            "timeout" to AgentToolParam("integer", "Timeout in seconds (default: 900, maximum: 3600 — larger values are capped at 3600)."),
            "delay" to AgentToolParam("integer", "Wait seconds before execution without occupying the shell. Blocks this agent; use instead of sleep."),
        ),
        required = listOf("tool_title", "command"),
        propertyOrdering = listOf("tool_title", "command", "timeout", "delay"),
    )

    // Same browser actions/parameters as iOS, with compact descriptions.
    private fun browserUseDefinition(): AgentToolDefinition = AgentToolDefinition(
        name = "browser_use",
        // [T-android-browser-tab-ownership] "up to 3" stopped being true once
        // the ceiling became dynamic (3 alone, +2 per active agent, capped at
        // 6), and an agent sees only its own tabs anyway — so the honest thing
        // to tell it is which tabs it may use, not a number that is now wrong.
        description = "Browse web/minis:// resources (not minis:// action links). screenshot returns pixels; get_readable extracts articles, get_backbone outlines DOM, find_elements discovers controls. scroll_and_collect deduplicates items across virtual/infinite-scroll pages. fetch downloads using the page session and returns a minis:// URL. get_cookies is current-site only (including HttpOnly): returns summary + env file, never raw cookie values; reuse with `. /var/minis/offloads/env_cookies_xxx.sh && command`. set_cookies writes through the native store. Use list_tabs/new_tab ids only.",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "User-visible 5-10 word summary, in the user's language."),
            "action" to AgentToolParam("string", "Browser operation", enumValues = BrowserAction.allValues),
            "url" to AgentToolParam("string", "navigate/new_tab destination or fetch resource URL"),
            "selector" to AgentToolParam("string", "Target CSS selector. scroll: container, or omit to auto-detect."),
            "text" to AgentToolParam("string", "Text for type"),
            "coordinate_x" to AgentToolParam("integer", "Click X instead of selector; pair with coordinate_y"),
            "coordinate_y" to AgentToolParam("integer", "Click Y instead of selector; pair with coordinate_x"),
            "direction" to AgentToolParam("string", "Scroll direction", enumValues = listOf("up", "down")),
            "amount" to AgentToolParam("integer", "Scroll pixels per step (default 500)"),
            "script" to AgentToolParam("string", "execute_js code in async wrapper; supports await and top-level return."),
            "user_agent" to AgentToolParam("string", "set_user_agent profile", enumValues = listOf("desktop_chrome", "mobile_chrome")),
            "max_depth" to AgentToolParam("integer", "get_backbone tree depth (default 5)"),
            "scroll_count" to AgentToolParam("integer", "scroll_and_collect steps (default 10, max 20); scrolls by amount and waits for content."),
            "item_selector" to AgentToolParam("string", "scroll_and_collect item CSS selector; omitted = detect repeated elements."),
            "tab_id" to AgentToolParam("integer", "Target tab, default most recently used. Only ids from list_tabs/new_tab are allowed."),
            "keywords" to AgentToolParam("string", "get_cookies name filter: space-separated string or string array, case-insensitive; omit for all current-site cookies."),
            "fuzzy" to AgentToolParam("boolean", "get_cookies: true (default) = name contains ALL keywords; false = exact match to ANY."),
            "cookies" to AgentToolParam("string", "set_cookies JSON array (or encoded array string). Objects: name/value required; domain defaults current host, path defaults '/', secure/http_only booleans, expires Unix seconds (omitted = session). Also accepts export aliases httpOnly, expirationDate, sameSite/camel-case fields."),
            "timeout" to AgentToolParam("integer", "wait_for_dom_stable timeout seconds (default 10); polls every 0.5s until stable for 3+ intervals."),
            "viewport_width" to AgentToolParam("integer", "set_viewport CSS width; requires viewport_height unless reset=true."),
            "viewport_height" to AgentToolParam("integer", "set_viewport CSS height; requires viewport_width unless reset=true."),
            "reset" to AgentToolParam("boolean", "set_viewport: clear session override and use global viewport."),
            // [T-android-browser-full-page-schema] The capability was already
            // implemented end to end (BrowserActionInput parses `full_page`,
            // BrowserUseManager.screenshot stretches the WebView and caps at
            // MAX_FULL_PAGE_HEIGHT_PX) — it was simply never declared, so the
            // model had no way to ask for it. Wording adapted from iOS: the
            // mechanism differs (Android stretches the WebView's viewport
            // rather than resizing a WKWebView), the cap and the truncation
            // reporting are identical.
            "full_page" to AgentToolParam("boolean", "screenshot: true = full scrollable page, false (default) = viewport. Height capped at 32768px; Truncated: true reports clipping, scroll to capture the rest."),
        ),
        required = listOf("tool_title", "action"),
        propertyOrdering = listOf("tool_title", "action", "tab_id", "url", "selector", "text", "coordinate_x", "coordinate_y", "direction", "amount", "scroll_count", "item_selector", "script", "user_agent", "max_depth", "keywords", "fuzzy", "cookies", "timeout", "viewport_width", "viewport_height", "reset", "full_page"),
    )

}
