package com.openminis.app.util

import android.graphics.Color
import android.os.Build
import android.view.Window

/** Android 15+ enforces transparent edge-to-edge bars; older releases need colors. */
@Suppress("DEPRECATION") // These setters are required on API 26–34.
fun Window.makeSystemBarsTransparent() {
    if (Build.VERSION.SDK_INT < 35) {
        statusBarColor = Color.TRANSPARENT
        navigationBarColor = Color.TRANSPARENT
    }
}
