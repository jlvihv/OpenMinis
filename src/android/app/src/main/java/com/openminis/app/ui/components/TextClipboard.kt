package com.openminis.app.ui.components

import android.content.ClipData
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.text.AnnotatedString
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Plain-text copy actions using Compose's current asynchronous clipboard API. */
class TextClipboard internal constructor(
    private val clipboard: Clipboard,
    private val scope: CoroutineScope,
) {
    fun setText(text: AnnotatedString) {
        scope.launch {
            clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("", text.text)))
        }
    }
}

@Composable
fun rememberTextClipboard(): TextClipboard {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    return remember(clipboard, scope) { TextClipboard(clipboard, scope) }
}
