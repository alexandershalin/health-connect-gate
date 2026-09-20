package com.bishop.healthconnectgate

import android.content.Context
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

internal fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

/**
 * With targetSdk 35 the system draws the app edge to edge. Pad the root by the system bars (and a cutout), on top of [basePaddingPx],
 * so text is never hidden under the status or navigation bar.
 */
internal fun applyInsets(root: View, basePaddingPx: Int) {
    ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
        val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
        view.setPadding(basePaddingPx + bars.left, basePaddingPx + bars.top, basePaddingPx + bars.right, basePaddingPx + bars.bottom)
        insets
    }
}
