package com.openminis.app.ui.chat.voice

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import com.openminis.app.speech.SpeechRecognitionManager
import kotlin.math.abs
import kotlin.math.sin

internal data class HoldVoiceState(
    val active: Boolean = false,
    val cancelling: Boolean = false,
    val processing: Boolean = false,
    val assistant: Boolean = false,
    val onFinish: (() -> Unit)? = null,
)

/** Independent display list; assistant-only controls never intercept a held text-field gesture. */
@Composable
internal fun HoldVoiceOverlay(state: HoldVoiceState, modifier: Modifier = Modifier) {
    val visibility = animateFloatAsState(if (state.active) 1f else 0f, tween(220), label = "voiceFogFade")
    // Remove all animation and audio observers once the exit fade completes.
    if (!state.active && visibility.value <= 0.001f) return
    val levels = SpeechRecognitionManager.audioLevels.collectAsState()
    val transition = rememberInfiniteTransition(label = "voiceFog")
    val phase = transition.animateFloat(
        0f, 6.283185f, infiniteRepeatable(tween(3200, easing = LinearEasing)), label = "fogFlow",
    )
    val tint = animateColorAsState(
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
    BoxWithConstraints(modifier.graphicsLayer().drawWithCache {
        val height = 340.dp.toPx().coerceAtMost(size.height * 0.6f)
        val top = size.height - height
        val area = Size(size.width, height)
        val origin = Offset(0f, top)
        val color = tint.value
        val background = Brush.verticalGradient(
            0f to Color.Transparent,
            0.25f to color.copy(alpha = 0.05f),
            0.65f to color.copy(alpha = 0.65f),
            1f to color.copy(alpha = 0.9f),
            startY = top, endY = size.height,
        )
        // Animate this unit gradient via canvas transforms rather than
        // allocating a new brush/shader and color list on every frame.
        val fog = Brush.radialGradient(listOf(color, Color.Transparent), center = Offset.Zero, radius = 1f)
        val measured = textMeasurer.measure(
            label, hintStyle,
            constraints = Constraints(maxWidth = (size.width - 32.dp.toPx()).toInt().coerceAtLeast(0)),
        )
        val textPosition = Offset((size.width - measured.size.width) / 2f, top + height * 0.43f)
        val count = 45
        val span = size.width * 0.6f
        val waveY = top + height * 0.69f
        val waveX = FloatArray(count) { (size.width - span) / 2f + span * it / (count - 1) }
        val waveAlpha = FloatArray(count) {
            val edge = (1f - abs(it - (count - 1) / 2f) / (count / 2f)).coerceIn(0f, 1f)
            0.15f + edge * 0.65f
        }
        val minimumBar = 2.dp.toPx()
        val maximumAmplitude = 12.dp.toPx()
        val stroke = 2.dp.toPx()
        onDrawBehind {
            // All continuously changing values are read in DRAW, not cache
            // construction or composition, so only this layer is invalidated.
            val alpha = visibility.value
            val samples = levels.value
            val loudness = (samples.lastOrNull() ?: 0f).coerceIn(0f, 1f)
            drawRect(background, origin, area, alpha = alpha)
            clipRect(top = top) {
                withTransform({
                    translate(size.width * (0.5f + sin(phase.value) * 0.06f), size.height - height * 0.25f)
                    scale(size.width * 0.75f, height * 0.8f, pivot = Offset.Zero)
                }) {
                    drawCircle(fog, radius = 1f, center = Offset.Zero, alpha = alpha * (0.65f + loudness * 0.2f))
                }
            }
            drawText(measured, topLeft = textPosition, alpha = alpha)
            for (index in 0 until count) {
                val sampleIndex = samples.size - count + index
                val amplitude = if (!state.processing && !state.cancelling && sampleIndex in samples.indices)
                    samples[sampleIndex].coerceIn(0f, 1f) else 0f
                val halfHeight = minimumBar + amplitude * maximumAmplitude
                drawLine(
                    Color.White.copy(alpha = alpha * waveAlpha[index]),
                    Offset(waveX[index], waveY - halfHeight), Offset(waveX[index], waveY + halfHeight),
                    strokeWidth = stroke, cap = StrokeCap.Round,
                )
            }
        }
    }) {
        if (state.assistant) {
            // Invisible hit target matching the fog, not a separate button.
            // Keep consuming taps while processing so they cannot focus the
            // hidden composer underneath; onFinish is null during that state.
            Box(
                Modifier.align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(minOf(340.dp, maxHeight * 0.6f))
                    .clickable(
                        enabled = state.active,
                        interactionSource = null,
                        indication = null,
                        onClickLabel = stringResource(R.string.hold_voice_send_now),
                    ) { if (!state.processing) state.onFinish?.invoke() },
            )
        }
    }
}
