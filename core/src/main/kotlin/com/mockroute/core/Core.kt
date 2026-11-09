package com.mockroute.core

/** Pure-Kotlin core module (no Android imports). Real logic arrives from Stage 1 onward. */
object Core {
    const val NAME: String = "MockRoute"

    /** Converts km/h (UI unit) to m/s (internal SI unit). */
    fun kmhToMs(kmh: Double): Double = kmh / 3.6
}
