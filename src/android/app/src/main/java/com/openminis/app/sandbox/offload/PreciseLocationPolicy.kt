package com.openminis.app.sandbox.offload

/** Fresh positions are usable even when below the requested accuracy; report that explicitly. */
internal object PreciseLocationPolicy {
    fun usable(fixNanos: Long, requestNanos: Long, hasAccuracy: Boolean, accuracy: Float): Boolean =
        fixNanos >= requestNanos && hasAccuracy && accuracy.isFinite() && accuracy >= 0f
}
