package com.openminis.app.ui.chat.voice

import androidx.compose.animation.core.*
import androidx.compose.animation.animateColorAsState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import com.openminis.app.speech.SpeechRecognitionManager
import kotlin.math.sin

internal data class HoldVoiceState(
    val active: Boolean = false,
    val cancelling: Boolean = false,
    val processing: Boolean = false,
    val assistant: Boolean = false,
)

/** Draw-only overlay: the original field keeps owning the held pointer underneath. */
internal fun Modifier.holdVoiceOverlay(state: HoldVoiceState): Modifier = composed {
    val visibility by animateFloatAsState(if (state.active) 1f else 0f, tween(220), label = "voiceFogFade")
    if (visibility <= 0.001f) return@composed this
    val levels by SpeechRecognitionManager.audioLevels.collectAsState()
    val transition = rememberInfiniteTransition(label = "voiceFog")
    val phase by transition.animateFloat(
        0f, 6.283185f, infiniteRepeatable(tween(3200, easing = LinearEasing)), label = "fogFlow",
    )
    val tint by animateColorAsState(
        if (state.cancelling) Color(0xFFE94967) else Color(0xFF176CEF), tween(180), label = "fogTint",
    )
    val context = LocalContext.current
    val label = context.getString(when {
        state.cancelling -> R.string.hold_voice_release_cancel
        state.processing -> R.string.hold_voice_processing
        state.assistant -> R.string.hold_voice_assistant_listening
        else -> R.string.hold_voice_release_send
    })
    val textMeasurer = rememberTextMeasurer()
    val hintStyle = MaterialTheme.typography.bodyLarge.copy(color = Color.White.copy(alpha = 0.8f))
    this.drawWithContent {
        drawContent()
        val height = 340.dp.toPx().coerceAtMost(size.height * 0.6f)
        val top = size.height - height
        val loudness = (levels.lastOrNull() ?: 0f).coerceIn(0f, 1f)
        // A broad, borderless fog rising from the bottom, not a glowing input card.
        drawRect(
            brush = Brush.verticalGradient(
                0f to Color.Transparent,
                0.25f to tint.copy(alpha = 0.05f),
                0.65f to tint.copy(alpha = 0.65f),
                1f to tint.copy(alpha = 0.9f),
                startY = top, endY = size.height,
            ),
            topLeft = Offset(0f, top), size = Size(size.width, height), alpha = visibility,
        )
        drawRect(
            brush = Brush.radialGradient(
                listOf(tint.copy(alpha = 0.65f + loudness * 0.2f), Color.Transparent),
                center = Offset(size.width * (0.5f + sin(phase) * 0.06f), size.height - height * 0.25f),
                radius = size.width * 0.75f,
            ),
            topLeft = Offset(0f, top), size = Size(size.width, height), alpha = visibility,
        )
        val measured = textMeasurer.measure(label, hintStyle)
        drawText(measured, topLeft = Offset((size.width - measured.size.width) / 2f, top + height * 0.43f), alpha = visibility)
        val count = 45
        val span = size.width * 0.6f
        val waveY = top + height * 0.69f
        for (index in 0 until count) {
            val sampleIndex = levels.size - count + index
            val amplitude = if (!state.processing && !state.cancelling && sampleIndex in levels.indices)
                levels[sampleIndex].coerceIn(0f, 1f) else 0f
            val halfHeight = (2f + amplitude * 12f).dp.toPx()
            val edge = (1f - kotlin.math.abs(index - (count - 1) / 2f) / (count / 2f)).coerceIn(0f, 1f)
            val x = (size.width - span) / 2 + span * index / (count - 1)
            drawLine(
                Color.White.copy(alpha = visibility * (0.15f + edge * 0.65f)),
                Offset(x, waveY - halfHeight), Offset(x, waveY + halfHeight),
                strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round,
            )
        }
    }
}
