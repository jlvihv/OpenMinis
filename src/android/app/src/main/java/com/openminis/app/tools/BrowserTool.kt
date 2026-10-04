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
            // BrowserManager.screenshot stretches the WebView and caps at
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
