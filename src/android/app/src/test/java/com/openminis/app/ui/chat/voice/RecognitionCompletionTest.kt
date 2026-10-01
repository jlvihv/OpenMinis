package com.openminis.app.ui.chat.voice

import com.openminis.app.speech.RecognitionState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RecognitionCompletionTest {
    @Test
    fun `idle must stay stable for 400 milliseconds`() = runTest {
        val states = MutableStateFlow(RecognitionState.IDLE)
        val completion = async { awaitRecognitionSettled(states) }
        runCurrent()
        advanceTimeBy(399)
        runCurrent()
        assertFalse(completion.isCompleted)
        advanceTimeBy(1)
        runCurrent()
        assertTrue(completion.isCompleted)
    }

    @Test
    fun `resumed transcription cancels the idle timer`() = runTest {
        val states = MutableStateFlow(RecognitionState.IDLE)
        val completion = async { awaitRecognitionSettled(states) }
        runCurrent()
        advanceTimeBy(300)
        states.value = RecognitionState.FINISHING
        runCurrent()
        advanceTimeBy(1000)
        assertFalse(completion.isCompleted)
        states.value = RecognitionState.IDLE
        runCurrent()
        advanceTimeBy(399)
        runCurrent()
        assertFalse(completion.isCompleted)
        advanceTimeBy(1)
        runCurrent()
        assertTrue(completion.isCompleted)
    }

    @Test
    fun `cancellation removes the pending completion`() = runTest {
        val states = MutableStateFlow(RecognitionState.RECORDING)
        val completion = async { awaitRecognitionSettled(states) }
        runCurrent()
        completion.cancel()
        runCurrent()
        states.value = RecognitionState.IDLE
        advanceTimeBy(1000)
        runCurrent()
        assertTrue(completion.isCancelled)
        assertEquals(0, states.subscriptionCount.value)
    }

    @Test
    fun `a stalled recognizer times out without completion`() = runTest {
        val states = MutableStateFlow(RecognitionState.FINISHING)
        val completion = async {
            withTimeoutOrNull(60_000) {
                awaitRecognitionSettled(states)
                true
            }
        }
        runCurrent()
        advanceTimeBy(60_000)
        runCurrent()
        assertNull(completion.await())
        assertEquals(0, states.subscriptionCount.value)
    }
}
