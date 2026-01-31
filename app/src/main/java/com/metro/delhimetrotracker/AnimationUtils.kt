package com.metro.delhimetrotracker.utils
import android.app.Activity
import android.os.Build

// Call this immediately after startActivity or finish
fun Activity.applyTransition(enterAnim: Int, exitAnim: Int) {
    if (Build.VERSION.SDK_INT >= 34) {
        // The new way (Android 14+)
        overrideActivityTransition(
            Activity.OVERRIDE_TRANSITION_OPEN,
            enterAnim,
            exitAnim
        )
    } else {
        // The old way (Backwards compatible)
        @Suppress("DEPRECATION")
        overridePendingTransition(enterAnim, exitAnim)
    }
}