package com.openminis.app.ui.chat.voice

import android.widget.Toast
import androidx.activity.compose.BackHandler
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
import com.openminis.app.R
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.speech.RecognitionError
import com.openminis.app.speech.SpeechRecognitionManager
import com.openminis.app.speech.SystemSpeechRecognitionEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Non-UI session data does not need to trigger composition when transcripts arrive. */
private class CaptureSession {
    var generation = 0
    var started = false
    var base = ""
    var transcript = ""
    var failed = false
    var finishRequested = false
    var startJob: Job? = null
    var finishJob: Job? = null
}

/** Observe DOWN before selection; focused fields keep their normal text gestures. */
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
    val currentRepository by rememberUpdatedState(providerRepository)
    val currentDraft by rememberUpdatedState(draft)
    val currentPermission by rememberUpdatedState(ensurePermission)
    val currentSend by rememberUpdatedState(onSend)
    val currentActiveChange by rememberUpdatedState(onActiveChange)
    val scope = rememberCoroutineScope()
    val session = remember { CaptureSession() }
    var holding by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var assistant by remember { mutableStateOf(false) }
    val threshold = with(LocalDensity.current) { 64.dp.toPx() }

    fun cancel() {
        session.generation++ // Invalidate callbacks before cancelling the engine/jobs.
        session.startJob?.cancel()
        session.finishJob?.cancel()
        session.startJob = null
        session.finishJob = null
        if (session.started) SpeechRecognitionManager.cancelRecording()
        session.started = false
        holding = false
        busy = false
        assistant = false
        currentActiveChange(HoldVoiceState())
    }

    fun reportError(error: RecognitionError, message: String?) {
        session.failed = true
        val translated = when (error) {
            RecognitionError.MIC_IN_USE -> context.getString(R.string.voice_mic_in_use)
            RecognitionError.AUDIO_ERROR -> context.getString(R.string.voice_mic_unavailable)
            RecognitionError.PERMISSION_DENIED -> context.getString(R.string.voice_panel_permission_denied)
            else -> message ?: context.getString(R.string.hold_voice_retry)
        }
        Toast.makeText(context, translated, Toast.LENGTH_SHORT).show()
        cancel()
    }

    fun watchForCompletion(take: Int) {
        session.finishJob?.cancel()
        session.finishJob = scope.launch {
            val settled = withTimeoutOrNull(60_000) {
                awaitRecognitionSettled(SpeechRecognitionManager.state)
                true
            } == true
            if (take != session.generation) return@launch
            val base = session.base
            val text = session.transcript
            val success = settled && !session.failed && text.isNotBlank()
            // Detach the finishing job before reset, avoiding self-cancellation.
            session.finishJob = null
            cancel()
            if (success) currentSend(if (base.isBlank()) text else "$base $text")
            else Toast.makeText(context, R.string.hold_voice_empty, Toast.LENGTH_SHORT).show()
        }
    }

    fun finishAssistant(take: Int) {
        if (take != session.generation || !assistant || !session.started || session.finishRequested) return
        session.finishRequested = true
        currentActiveChange(HoldVoiceState(active = true, assistant = true, processing = true))
        SpeechRecognitionManager.stopRecording()
        if (take == session.generation) watchForCompletion(take)
    }

    // Long-press and system-assistant entries share permission, routing,
    // callbacks, cancellation and completion rather than duplicating pipelines.
    fun startCapture(assistantMode: Boolean): Int {
        val take = ++session.generation
        assistant = assistantMode
        holding = !assistantMode
        busy = assistantMode
        session.base = currentDraft()
        session.transcript = ""
        session.failed = false
        session.finishRequested = false
        focusManager.clearFocus(force = true)
        keyboard?.hide()
        currentActiveChange(HoldVoiceState(active = true, assistant = assistantMode))
        session.startJob = scope.launch {
            try {
                val allowed = currentPermission()
                if (take != session.generation) return@launch
                if (!allowed) {
                    reportError(RecognitionError.PERMISSION_DENIED, null)
                    return@launch
                }
                SpeechRecognitionManager.clearDegradationAndRefresh()
                val choice = currentRepository.resolveVoiceInputChoice()
                SpeechRecognitionManager.selectEngine(if (choice.isSystem) "system" else "provider")
                if (choice.isSystem) SpeechRecognitionManager.selectLocale(java.util.Locale.SIMPLIFIED_CHINESE)
                (SpeechRecognitionManager.availableEngines().firstOrNull { it.id == "system" }
                    as? SystemSpeechRecognitionEngine)?.preferOffline = choice.systemPreferOffline == true
                session.started = true
                SpeechRecognitionManager.startRecording(
                    onPartialOrFinal = { text, final ->
                        if (take == session.generation && final && text.isNotBlank()) {
                            session.transcript = if (session.transcript.isBlank()) text else "${session.transcript} $text"
                            if (assistantMode) {
                                session.finishRequested = true
                                currentActiveChange(HoldVoiceState(active = true, processing = true, assistant = true))
                            }
                        }
                    },
                    onError = { error, message ->
                        if (take == session.generation) reportError(error, message)
                    },
                    quickTurn = assistantMode,
                )
                if (take == session.generation && assistantMode) {
                    if (!session.finishRequested) currentActiveChange(
                        HoldVoiceState(active = true, assistant = true, onFinish = { finishAssistant(take) }),
                    )
                    watchForCompletion(take)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (take == session.generation) reportError(RecognitionError.AUDIO_ERROR, null)
            }
        }
        return take
    }

    DisposableEffect(Unit) { onDispose { cancel() } }
    BackHandler(enabled = holding || busy || assistant) { cancel() }
    LaunchedEffect(VoiceModePrefs.pendingAssistantCapture) {
        if (!VoiceModePrefs.pendingAssistantCapture) return@LaunchedEffect
        VoiceModePrefs.pendingAssistantCapture = false
        if (!holding && !busy) {
            VoiceModePrefs.isVoiceActive = false
            // The composition-owned job outlives consumption of this one-shot flag.
            startCapture(assistantMode = true)
        }
    }

    this.pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            // Snapshot eligibility at DOWN; focus may change during the press.
            if (!currentEnabled || busy || holding) return@awaitEachGesture
            val releasedEarly = withTimeoutOrNull((viewConfiguration.longPressTimeoutMillis - 50).coerceAtLeast(1)) {
                do {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == down.id }
                    val interrupted = change == null || !change.pressed ||
                        (change.position - down.position).getDistance() > viewConfiguration.touchSlop
                } while (!interrupted)
                true
            }
            if (releasedEarly == true) return@awaitEachGesture
            currentEvent.changes.forEach { it.consume() }
            val take = startCapture(assistantMode = false)
            var cancelling = false
            try {
                var pressed: Boolean
                var lostPointer = false
                do {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == down.id }
                    pressed = change?.pressed == true
                    if (change == null) lostPointer = true
                    if (change != null) {
                        val nextCancelling = down.position.y - change.position.y >= threshold
                        // Publish only a threshold crossing, not every finger move.
                        if (take == session.generation && nextCancelling != cancelling) {
                            currentActiveChange(HoldVoiceState(active = true, cancelling = nextCancelling))
                        }
                        cancelling = nextCancelling
                        change.consume()
                    }
                } while (pressed)
                if (take != session.generation) return@awaitEachGesture
                holding = false
                if (lostPointer || cancelling || !session.started) cancel()
                else {
                    busy = true
                    currentActiveChange(HoldVoiceState(active = true, processing = true))
                    SpeechRecognitionManager.stopRecording()
                    if (take == session.generation) watchForCompletion(take)
                }
            } finally {
                // System gestures, navigation and pointer cancellation never send.
                if (take == session.generation && holding) cancel()
            }
        }
    }
}
