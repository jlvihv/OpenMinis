package com.openminis.app.ui.chat.voice

import android.widget.Toast
import com.openminis.app.R
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.speech.RecognitionState
import com.openminis.app.speech.RecognitionError
import com.openminis.app.speech.SpeechRecognitionManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Own the gesture on the initial pass, before the text field's selection gesture. */
internal fun Modifier.holdToSpeak(
    providerRepository: ProviderRepository,
    enabled: Boolean,
    draft: () -> String,
    ensurePermission: suspend () -> Boolean,
    onActiveChange: (HoldVoiceState) -> Unit,
    onSend: (String) -> Unit,
): Modifier = composed {
    val context = LocalContext.current
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val currentEnabled by rememberUpdatedState(enabled)
    val scope = rememberCoroutineScope()
    val currentDraft by rememberUpdatedState(draft)
    val currentPermission by rememberUpdatedState(ensurePermission)
    val currentSend by rememberUpdatedState(onSend)
    val currentActiveChange by rememberUpdatedState(onActiveChange)
    var assistant by remember { mutableStateOf(false) }
    var holding by remember { mutableStateOf(false) }
    var cancelling by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var generation by remember { mutableIntStateOf(0) }
    var started by remember { mutableStateOf(false) }
    var transcript by remember { mutableStateOf("") }
    var base by remember { mutableStateOf("") }
    var failed by remember { mutableStateOf(false) }
    val threshold = with(LocalDensity.current) { 64.dp.toPx() }

    fun cancel() {
        generation++ // Ignore callbacks and permission results from the discarded take.
        if (started) SpeechRecognitionManager.cancelRecording()
        started = false
        assistant = false
        holding = false
        currentActiveChange(HoldVoiceState())
        busy = false
        cancelling = false
    }

    fun reportError(error: RecognitionError, message: String?) {
        failed = true
        val translated = when (error) {
            RecognitionError.MIC_IN_USE -> context.getString(R.string.voice_mic_in_use)
            RecognitionError.AUDIO_ERROR -> context.getString(R.string.voice_mic_unavailable)
            RecognitionError.PERMISSION_DENIED -> context.getString(R.string.voice_panel_permission_denied)
            else -> message ?: context.getString(R.string.hold_voice_retry)
        }
        Toast.makeText(context, translated, Toast.LENGTH_SHORT).show()
    }

    DisposableEffect(Unit) {
        onDispose {
            generation++
            if (started) SpeechRecognitionManager.cancelRecording()
            currentActiveChange(HoldVoiceState())
        }
    }

    androidx.activity.compose.BackHandler(enabled = assistant || busy) { cancel() }

    LaunchedEffect(VoiceModePrefs.pendingAssistantCapture) {
        if (!VoiceModePrefs.pendingAssistantCapture) return@LaunchedEffect
        VoiceModePrefs.pendingAssistantCapture = false
        if (holding || busy || assistant) return@LaunchedEffect
        scope.launch assistantCapture@{
        VoiceModePrefs.isVoiceActive = false
        assistant = true
        busy = true
        focusManager.clearFocus(force = true)
        keyboard?.hide()
        currentActiveChange(HoldVoiceState(active = true, assistant = true))
        val take = ++generation
        base = currentDraft()
        transcript = ""
        failed = false
        if (!currentPermission()) {
            if (take == generation) {
                Toast.makeText(context, R.string.voice_panel_permission_denied, Toast.LENGTH_SHORT).show()
                cancel()
            }
            return@assistantCapture
        }
        if (take != generation) return@assistantCapture
        SpeechRecognitionManager.clearDegradationAndRefresh()
        val choice = providerRepository.resolveVoiceInputChoice()
        SpeechRecognitionManager.selectEngine(if (choice.isSystem) "system" else "provider")
        if (choice.isSystem) SpeechRecognitionManager.selectLocale(java.util.Locale.SIMPLIFIED_CHINESE)
        (SpeechRecognitionManager.availableEngines().firstOrNull { it.id == "system" }
            as? com.openminis.app.speech.SystemSpeechRecognitionEngine)?.preferOffline = choice.systemPreferOffline == true
        started = true
        SpeechRecognitionManager.startRecording(
            onPartialOrFinal = { text, final ->
                if (take == generation && final && text.isNotBlank()) {
                    transcript = listOf(transcript, text).filter { it.isNotBlank() }.joinToString(" ")
                    currentActiveChange(HoldVoiceState(active = true, processing = true, assistant = true))
                }
            },
            onError = { error, message ->
                if (take == generation) reportError(error, message)
            },
            quickTurn = true,
        )
        // Wait for the quick-turn engine to finish before sending.
        scope.launch {
            val deadline = android.os.SystemClock.elapsedRealtime() + 60_000
            var idleSince = 0L
            while (take == generation && !failed && android.os.SystemClock.elapsedRealtime() < deadline) {
                val now = android.os.SystemClock.elapsedRealtime()
                if (SpeechRecognitionManager.state.value == RecognitionState.IDLE) {
                    if (idleSince == 0L) idleSince = now
                    if (now - idleSince >= 400) break
                } else idleSince = 0L
                delay(50)
            }
            if (take == generation) {
                val text = transcript
                val success = !failed && idleSince != 0L &&
                    android.os.SystemClock.elapsedRealtime() - idleSince >= 400 && text.isNotBlank()
                cancel()
                if (success) currentSend(listOf(base, text).filter { it.isNotBlank() }.joinToString(" "))
                else if (!failed) Toast.makeText(context, R.string.hold_voice_empty, Toast.LENGTH_SHORT).show()
            }
        }
        }
    }

    this.pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            // Decide at DOWN: the field may gain focus during this very press.
            // Never restart an in-progress hold merely because that happens.
            if (!currentEnabled || busy) return@awaitEachGesture
            // Start just before the text field's own long-press timeout. Short taps
            // and drags remain entirely untouched (keyboard, caret and scrolling).
            val releasedEarly = withTimeoutOrNull((viewConfiguration.longPressTimeoutMillis - 50).coerceAtLeast(1)) {
                var interrupted = false
                do {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == down.id }
                    interrupted = change == null || !change.pressed ||
                        (change.position - down.position).getDistance() > viewConfiguration.touchSlop
                } while (!interrupted)
                true
            }
            if (releasedEarly == true) return@awaitEachGesture
            currentEvent.changes.forEach { it.consume() }
            focusManager.clearFocus(force = true)
            keyboard?.hide()
            holding = true
            currentActiveChange(HoldVoiceState(active = true))
            cancelling = false
            val take = ++generation
            base = currentDraft()
            transcript = ""
            failed = false
            scope.launch {
                val allowed = currentPermission()
                if (take != generation || !holding) return@launch
                if (!allowed) {
                    Toast.makeText(context, R.string.voice_panel_permission_denied, Toast.LENGTH_SHORT).show()
                    cancel()
                    return@launch
                }
                keyboard?.hide()
                val choice = providerRepository.resolveVoiceInputChoice()
                SpeechRecognitionManager.selectEngine(if (choice.isSystem) "system" else "provider")
                if (choice.isSystem) SpeechRecognitionManager.selectLocale(java.util.Locale.SIMPLIFIED_CHINESE)
                (SpeechRecognitionManager.availableEngines().firstOrNull { it.id == "system" }
                    as? com.openminis.app.speech.SystemSpeechRecognitionEngine)?.preferOffline = choice.systemPreferOffline == true
                started = true
                SpeechRecognitionManager.startRecording(
                    onPartialOrFinal = { text, final ->
                        if (take == generation && final && text.isNotBlank()) {
                            transcript = listOf(transcript, text).filter { it.isNotBlank() }.joinToString(" ")
                        }
                    },
                    onError = { error, message ->
                        if (take == generation) reportError(error, message)
                    },
                    quickTurn = false,
                )
            }
            try {
                var pressed: Boolean
                var lostPointer = false
                do {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == down.id }
                    pressed = change?.pressed == true
                    if (change == null) lostPointer = true
                    if (change != null) {
                        cancelling = down.position.y - change.position.y >= threshold
                        currentActiveChange(HoldVoiceState(active = true, cancelling = cancelling))
                        change.consume()
                    }
                } while (pressed)
                holding = false
                if (lostPointer || cancelling || !started) {
                    cancel()
                } else {
                    busy = true
                    currentActiveChange(HoldVoiceState(active = true, processing = true))
                    SpeechRecognitionManager.stopRecording()
                    scope.launch {
                        var idleSince = 0L
                        val deadline = android.os.SystemClock.elapsedRealtime() + 60_000
                        while (take == generation && !failed && android.os.SystemClock.elapsedRealtime() < deadline) {
                            val now = android.os.SystemClock.elapsedRealtime()
                            if (SpeechRecognitionManager.state.value == RecognitionState.IDLE) {
                                if (idleSince == 0L) idleSince = now
                                if (now - idleSince >= 400) break
                            } else idleSince = 0L
                            delay(50)
                        }
                        if (take == generation) {
                            val text = transcript
                            val success = !failed && idleSince != 0L &&
                                android.os.SystemClock.elapsedRealtime() - idleSince >= 400 && text.isNotBlank()
                            cancel()
                            if (success) currentSend(listOf(base, text).filter { it.isNotBlank() }.joinToString(" "))
                            else if (!failed) Toast.makeText(context, R.string.hold_voice_empty, Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            } finally {
                // Pointer cancellation (navigation, system gesture) must never send.
                if (holding) cancel()
            }
        }
    }
}
