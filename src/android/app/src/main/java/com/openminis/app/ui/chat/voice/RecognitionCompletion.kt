package com.openminis.app.ui.chat.voice

import com.openminis.app.speech.RecognitionState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapLatest

/** Event-driven settling: resumed recognition cancels the pending idle timer. */
@OptIn(ExperimentalCoroutinesApi::class)
internal suspend fun awaitRecognitionSettled(states: Flow<RecognitionState>, quietMillis: Long = 400) {
    states.distinctUntilChanged().mapLatest { state ->
        if (state != RecognitionState.IDLE) false
        else {
            delay(quietMillis)
            true
        }
    }.first { it }
}
