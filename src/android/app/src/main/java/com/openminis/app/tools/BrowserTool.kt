package com.openminis.app.tools

import com.openminis.app.browser.BrowserAction
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam

object BrowserTool {
    const val NAME = "browser"

    fun definition(): AgentToolDefinition = AgentToolDefinition(
        name = NAME,
        // [T-android-browser-tab-ownership] "up to 3" stopped being true once
        // the ceiling became dynamic (3 alone, +2 per active agent, capped at
        // 6), and an agent sees only its own tabs anyway — so the honest thing
        // to tell it is which tabs it may use, not a number that is now wrong.
        description = "Browse web/minis:// resources (never minis:// action links) — HTML sub-resources support relative URLs. screenshot = pixels; get_readable = article text; get_backbone = DOM outline; find_elements = controls; scroll_and_collect deduplicates items across infinite-scroll pages; fetch downloads through the page session and returns a minis:// URL. get_cookies (current site, including HttpOnly) returns a summary + env file, never raw values: reuse with `. /var/minis/offloads/env_cookies_xxx.sh && command`. Use list_tabs/new_tab ids only.",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "User-visible summary of this call."),
            "action" to AgentToolParam("string", "Browser operation", enumValues = BrowserAction.allValues),
            "url" to AgentToolParam("string", "navigate/new_tab target or fetch URL"),
            "selector" to AgentToolParam("string", "CSS selector; scroll: container (omit = auto-detect)"),
            "text" to AgentToolParam("string", "Text for type"),
            "coordinate_x" to AgentToolParam("integer", "Click X (with coordinate_y) instead of selector"),
            "coordinate_y" to AgentToolParam("integer", "Click Y (with coordinate_x) instead of selector"),
            "direction" to AgentToolParam("string", "Scroll direction", enumValues = listOf("up", "down")),
            "amount" to AgentToolParam("integer", "Scroll pixels per step (default 500)"),
            "script" to AgentToolParam("string", "execute_js code (async wrapper; await and top-level return allowed)"),
            "user_agent" to AgentToolParam("string", "set_user_agent profile", enumValues = listOf("desktop_chrome", "mobile_chrome")),
            "max_depth" to AgentToolParam("integer", "get_backbone tree depth (default 5)"),
            "scroll_count" to AgentToolParam("integer", "scroll_and_collect steps (default 10, max 20)"),
            "item_selector" to AgentToolParam("string", "scroll_and_collect item selector (omit = detect repeats)"),
            "tab_id" to AgentToolParam("integer", "Target tab (default: most recent); ids from list_tabs/new_tab only"),
            "keywords" to AgentToolParam("string", "get_cookies name filter (space-separated, case-insensitive; omit = all)"),
            "fuzzy" to AgentToolParam("boolean", "get_cookies: true = contains ALL keywords, false = exact ANY"),
            "cookies" to AgentToolParam("string", "set_cookies JSON array: name/value required; domain = current host, path = '/', secure/http_only booleans, expires Unix seconds (omit = session). Export aliases accepted."),
            "timeout" to AgentToolParam("integer", "wait_for_dom_stable seconds (default 10; 0.5s polls, 3 stable needed)"),
            "viewport_width" to AgentToolParam("integer", "set_viewport CSS width (needs height unless reset=true)"),
            "viewport_height" to AgentToolParam("integer", "set_viewport CSS height (needs width unless reset=true)"),
            "reset" to AgentToolParam("boolean", "set_viewport: drop the session override"),
            // [T-android-browser-full-page-schema] The capability was already
            // implemented end to end (BrowserActionInput parses `full_page`,
            // BrowserManager.screenshot stretches the WebView and caps at
            // MAX_FULL_PAGE_HEIGHT_PX) — it was simply never declared, so the
            // model had no way to ask for it. Wording adapted from iOS: the
            // mechanism differs (Android stretches the WebView's viewport
            // rather than resizing a WKWebView), the cap and the truncation
            // reporting are identical.
            "full_page" to AgentToolParam("boolean", "screenshot: true = full page (capped 32768px; Truncated reports clipping), false = viewport"),
        ),
        required = listOf("tool_title", "action"),
        propertyOrdering = listOf("tool_title", "action", "tab_id", "url", "selector", "text", "coordinate_x", "coordinate_y", "direction", "amount", "scroll_count", "item_selector", "script", "user_agent", "max_depth", "keywords", "fuzzy", "cookies", "timeout", "viewport_width", "viewport_height", "reset", "full_page"),
    )

}
