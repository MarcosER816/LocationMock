package com.mockroute.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.Locale

/** Display units (Settings → Units). Everything is SI inside; only these formatters change. */
object UiUnits {
    var imperial by mutableStateOf(false)

    fun distance(m: Double): String = if (imperial) {
        val ft = m * 3.28084
        if (ft < 1000) String.format(Locale.US, "%.0f ft", ft) else String.format(Locale.US, "%.2f mi", m / 1609.344)
    } else {
        if (m < 1000) String.format(Locale.US, "%.0f m", m) else String.format(Locale.US, "%.2f km", m / 1000)
    }

    fun speed(mps: Double): String =
        if (imperial) String.format(Locale.US, "%.1f mph", mps * 2.236936) else String.format(Locale.US, "%.1f km/h", mps * 3.6)
}
