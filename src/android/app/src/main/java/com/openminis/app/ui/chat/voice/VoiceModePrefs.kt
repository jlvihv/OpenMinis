package com.openminis.app.ui.chat.voice

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Process-local voice entry state; never restore a recording after process death. */
object VoiceModePrefs {
    // Retained for existing send/read-aloud integration. New captures use HoldVoiceState.
    var isVoiceActive by mutableStateOf(false)

    /** One-shot request shared by system assistant and voice-chat shortcuts. */
    var pendingAssistantCapture by mutableStateOf(false)

    const val autoSendAfterSpeech = true
    var lastSpokenAssistantKey: Int = 0
}
