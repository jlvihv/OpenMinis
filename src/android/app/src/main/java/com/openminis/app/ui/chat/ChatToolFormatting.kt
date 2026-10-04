package com.openminis.app.ui.chat

import com.openminis.app.R
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.NoteAdd
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.ui.graphics.Color

internal val stepTimestampFormatter: java.text.SimpleDateFormat =
    java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
internal fun formatStepTimestamp(epochMs: Long): String = stepTimestampFormatter.format(java.util.Date(epochMs))

internal fun formatStepDuration(seconds: Long, stillRunning: Boolean): String {
    val safe = seconds.coerceAtLeast(0L)
    val base = when {
        safe < 60L -> "${safe}s"
        safe < 3600L -> {
            val m = safe / 60L; val s = safe % 60L
            if (s == 0L) "${m}m" else "${m}m${s}s"
        }
        else -> {
            val h = safe / 3600L; val m = (safe % 3600L) / 60L
            if (m == 0L) "${h}h" else "${h}h${m}m"
        }
    }
    return if (stillRunning) "$base…" else base
}

/** image is the visual category for a read returning pixels. */
internal fun toolAccentColor(toolName: String): Color = when (toolName) {
    "bash" -> Color(0xFF34C759)
    "read" -> Color(0xFF32ADE6)
    "write" -> Color(0xFF007AFF)
    "edit" -> Color(0xFFFF9500)
    "browser" -> Color(0xFF007AFF)
    "image" -> Color(0xFFAF52DE)
    "web_search" -> Color(0xFF32ADE6)
    "subagent" -> HelperAccentStatic
    else -> Color(0xFF8E8E93)
}
internal fun toolIconFor(toolName: String) = when (toolName) {
    "bash" -> Icons.Default.Terminal
    "read" -> Icons.Default.Description
    "write" -> Icons.AutoMirrored.Filled.NoteAdd
    "edit" -> Icons.Default.EditNote
    "browser" -> Icons.Default.Language
    "image" -> Icons.Default.Image
    "web_search" -> Icons.Default.Search
    "subagent" -> Icons.Default.Groups
    else -> Icons.Default.Build
}
@androidx.annotation.DrawableRes
internal fun toolIconResFor(toolName: String?): Int = when (toolName) {
    "bash" -> R.drawable.ic_tool_terminal
    "read" -> R.drawable.ic_tool_description
    "write" -> R.drawable.ic_tool_note_add
    "edit" -> R.drawable.ic_tool_edit_note
    "browser" -> R.drawable.ic_tool_globe
    "image" -> R.drawable.ic_tool_image
    "web_search" -> R.drawable.ic_tool_search
    "subagent" -> R.drawable.ic_tool_groups
    else -> R.drawable.ic_tool_build
}
@androidx.annotation.ColorInt
internal fun toolAccentColorInt(toolName: String?): Int = when (toolName) {
    "bash" -> 0xFF34C759.toInt()
    "read" -> 0xFF32ADE6.toInt()
    "write" -> 0xFF007AFF.toInt()
    "edit" -> 0xFFFF9500.toInt()
    "browser" -> 0xFF007AFF.toInt()
    "image" -> 0xFFAF52DE.toInt()
    "web_search" -> 0xFF32ADE6.toInt()
    "subagent" -> 0xFFAF52DE.toInt()
    else -> 0xFF8E8E93.toInt()
}
internal fun friendlyToolTitleFor(toolName: String?): String = when (toolName) {
    null, "" -> "Minis"
    "bash" -> "Execute Bash"
    "read" -> "Read File"
    "write" -> "Write File"
    "edit" -> "Edit File"
    "browser" -> "Browse Web"
    "image" -> "Read Image"
    "web_search" -> "Search Web"
    else -> toolName.orEmpty().split('_').filter { it.isNotEmpty() }
        .joinToString(" ") { it.replaceFirstChar { ch -> ch.uppercase() } }
}
internal fun toolDisplayName(toolName: String): String = when (toolName) {
    "bash" -> "terminal"
    "read" -> "file reader"
    "write" -> "file writer"
    "edit" -> "file editor"
    "browser" -> "browser"
    "image" -> "image viewer"
    "web_search" -> "search"
    "subagent" -> "agent"
    else -> toolName
}
internal fun toolTitleLabel(toolName: String): String = when (toolName) {
    "bash" -> "Minis is using Bash"
    "read" -> "Minis is reading File"
    "write" -> "Minis is using Editor"
    "edit" -> "Minis is editing File"
    "browser" -> "Minis is using Browser"
    "image" -> "Minis is reading Image"
    "web_search" -> "Minis is using Search"
    "subagent" -> "Minis is using an Agent"
    else -> "Minis is using ${toolDisplayName(toolName)}"
}
internal fun formatToolDuration(ms: Long): String {
    val seconds = ms / 1000.0
    return when {
        seconds < 1 -> String.format("%.1fs", seconds)
        seconds < 60 -> String.format("%.0fs", seconds)
        else -> "${(seconds / 60).toInt()}m ${(seconds % 60).toInt()}s"
    }
}
