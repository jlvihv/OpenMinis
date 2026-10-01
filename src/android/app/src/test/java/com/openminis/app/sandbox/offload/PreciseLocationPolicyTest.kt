package com.openminis.app.sandbox.offload

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PreciseLocationPolicyTest {
    @Test fun acceptsFreshPositionsIncludingBestAvailable() {
        assertTrue(PreciseLocationPolicy.usable(101, 100, true, 5f))
        assertTrue(PreciseLocationPolicy.usable(100, 100, true, 50f))
        assertTrue(PreciseLocationPolicy.usable(101, 100, true, 800f))
    }

    @Test fun rejectsCachedFixes() {
        assertFalse(PreciseLocationPolicy.usable(99, 100, true, 5f))
    }

    @Test fun rejectsMissingOrInvalidAccuracy() {
        for (accuracy in listOf(-1f, Float.NaN, Float.POSITIVE_INFINITY)) {
            assertFalse(PreciseLocationPolicy.usable(101, 100, true, accuracy))
        }
        assertFalse(PreciseLocationPolicy.usable(101, 100, false, 0f))
    }
}
