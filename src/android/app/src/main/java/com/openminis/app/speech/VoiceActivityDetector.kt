package com.openminis.app.speech

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.math.tanh

/** Why a speech segment ended. Mirrors iOS `SegmentEndReason`. */
enum class SegmentEndReason {
    /** VAD saw the configured silence window — the mic should STOP. */
    SILENCE_DETECTED,

    /** Segment hit the length cap — flush it but KEEP recording. */
    MAX_LENGTH_REACHED,

    /**
     * User tapped stop mid-utterance.
     *
     * NOT CURRENTLY EMITTED on Android, and the difference from iOS is
     * deliberate rather than an omission. iOS calls `vad.flush()` to cut a
     * segment on demand and then holds a sub-2 s result in `pendingSegments`
     * to merge with the next utterance (VoiceInputPanel.swift:679-689). The
     * Android library owns segmentation and exposes no flush entry point, so
     * there is nothing to hold: a manual stop tears the detector down and the
     * platform recogniser (System engine) or the user's own tap (Provider
     * engine) finalises whatever it already had. Kept in the enum so the
     * distinction stays visible if the library ever gains a flush API.
     */
    MANUAL_FLUSH,
}

/** Delegate for [VoiceActivityDetector]. All callbacks arrive off the main thread. */
interface VoiceActivityListener {
    fun onVoiceStart() {}

    /**
     * A segment closed. [wav] is a complete 16 kHz WAV (header included).
     *
     * [spokenSeconds] is the ACCUMULATED SPEECH in the segment, derived from
     * the payload, not wall-clock. Callers gate the minimum-length rule on it:
     * wall-clock between speech-start and the close necessarily includes the
     * ~5 s silence window that triggered the close, so it can never fall below
     * a 2 s threshold and the rule would silently never fire.
     */
    fun onVoiceEnd(wav: ByteArray, reason: SegmentEndReason, spokenSeconds: Float)

    /** Live level in [0,1] for the waveform, from the AGC-boosted tap. */
    fun onLevel(level: Float) {}

    /** Capture died and could not be recovered; UI should return to idle. */
    fun onCaptureError(message: String) {}

    /**
     * [T-voice-mic-preempted] Capture failed or was silenced because another
     * app holds the microphone (see [MicInUse]). Capture has stopped. Defaults
     * to [onCaptureError] for listeners that do not tell the two apart.
     */
    fun onMicInUse(detail: String) { onCaptureError(detail) }

    /**
     * A session guard fired and capture has stopped. Distinct from
     * [onCaptureError]: nothing went wrong, the session simply ran out of its
     * allowance, so the UI should settle rather than show a failure.
     */
    fun onSessionLimit(limit: SessionLimit) {}
}

/** Why a capture session ended on its own. Mirrors iOS's panel timers. */
enum class SessionLimit {
    /** No speech for 30 s — a forgotten mic (iOS `idleTimeout`). */
    IDLE,

    /** Backgrounded 15 s mid-capture (iOS `backgroundTimeout`). */
    BACKGROUNDED,

    /** 300 s of continuous capture (iOS `maxTotalRecordingSeconds`). */
    MAX_DURATION,
}

/** Raw microphone capture without a native speech-detection model. */
class VoiceActivityDetector(private val context: Context, private val listener: VoiceActivityListener,
    private val endSilenceFrames: Int = DEFAULT_END_FRAMES) {
    companion object {
        const val DEFAULT_END_FRAMES = 156
        const val QUICK_TURN_END_FRAMES = 31
        const val SAMPLE_RATE = 48000
        internal const val WAV_SAMPLE_RATE = 16000
        internal const val WAV_HEADER_BYTES = 44
        const val IDLE_TIMEOUT_MS = 30000L
        const val BACKGROUND_TIMEOUT_MS = 15000L
        const val MAX_TOTAL_RECORDING_MS = 300000L
    }
    @Volatile var isSpeaking = false
        private set
    @Volatile var isRunning = false
        private set
    var maxSegmentSeconds = 59
    @Volatile var rawAudioSink: ((ByteArray, Int) -> Unit)? = null
    @Volatile var isBackgrounded = false
    private var recorder: AudioRecord? = null
    private val lock = Any()
    private val pcm = java.io.ByteArrayOutputStream()
    @Suppress("MissingPermission")
    fun start(): String? {
        if (isRunning) return null
        return try {
            val size = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            require(size > 0)
            val rec = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, max(size * 4, 8192))
            recorder = rec
            check(rec.state == AudioRecord.STATE_INITIALIZED)
            try {
                rec.startRecording()
            } catch (e: Exception) {
                if (MicInUse.captureFailure(context, rec.audioSessionId) == RecognitionError.MIC_IN_USE) {
                    listener.onMicInUse(e.message ?: "Microphone in use")
                }
                throw e
            }
            synchronized(lock) { pcm.reset() }
            isRunning = true
            CoroutineScope(Dispatchers.IO).launch {
                val started = System.currentTimeMillis()
                var backgroundAt = 0L
                val bytes = ByteArray(4096)
                try {
                    while (isRunning) {
                        val now = System.currentTimeMillis()
                        if (now - started >= MAX_TOTAL_RECORDING_MS) { listener.onSessionLimit(SessionLimit.MAX_DURATION); break }
                        if (isBackgrounded) {
                            if (backgroundAt == 0L) backgroundAt = now
                            if (now - backgroundAt >= BACKGROUND_TIMEOUT_MS) { listener.onSessionLimit(SessionLimit.BACKGROUNDED); break }
                        } else backgroundAt = 0L
                        if (MicInUse.isSilenced(context, rec.audioSessionId)) { listener.onMicInUse("Microphone in use"); break }
                        val n = rec.read(bytes, 0, bytes.size)
                        if (n < 0) error("Microphone read failed")
                        if (n == 0) continue
                        rawAudioSink?.invoke(bytes, n)
                        synchronized(lock) { pcm.write(bytes, 0, n) }
                        var energy = 0.0
                        for (i in 0 until n - 1 step 2) {
                            val v = ((bytes[i + 1].toInt() shl 8) or (bytes[i].toInt() and 255)).toShort() / 32768.0
                            energy += v * v
                        }
                        listener.onLevel(sqrt(energy / (n / 2)).toFloat().times(14).coerceIn(0f, 1f))
                        if (!isSpeaking && sqrt(energy / (n / 2)) >= 0.01) { isSpeaking = true; listener.onVoiceStart() }
                        if (synchronized(lock) { pcm.size() } >= SAMPLE_RATE * 2 * maxSegmentSeconds) {
                            val wav = flush()
                            if (wav != null) listener.onVoiceEnd(wav, SegmentEndReason.MAX_LENGTH_REACHED,
                                (wav.size - WAV_HEADER_BYTES) / (WAV_SAMPLE_RATE * 2f))
                        }
                    }
                } catch (e: Exception) { if (isRunning) listener.onCaptureError(e.message ?: "Microphone capture failed") }
                finally { if (recorder === rec) stop() else runCatching { rec.release() } }
            }
            null
        } catch (e: Exception) { stop(); e.message ?: "Microphone unavailable" }
    }
    fun flush(): ByteArray? {
        val bytes = synchronized(lock) { pcm.toByteArray().also { pcm.reset() } }
        if (bytes.isEmpty()) return null
        isSpeaking = false
        val out = ByteArray(bytes.size / 6 * 2)
        for (i in out.indices step 2) {
            var total = 0
            for (k in 0..2) {
                val j = i * 3 + k * 2
                total += ((bytes[j + 1].toInt() shl 8) or (bytes[j].toInt() and 255)).toShort().toInt()
            }
            val v = total / 3
            out[i] = v.toByte(); out[i + 1] = (v shr 8).toByte()
        }
        return com.openminis.app.provider.voice.VoiceProvider.wrapPcm16InWav(out, WAV_SAMPLE_RATE)
    }
    fun stop() {
        isRunning = false; isSpeaking = false
        val rec = recorder; recorder = null
        rec?.let { runCatching { it.stop() }; runCatching { it.release() } }
    }
    fun cancel() { stop(); synchronized(lock) { pcm.reset() } }
}
