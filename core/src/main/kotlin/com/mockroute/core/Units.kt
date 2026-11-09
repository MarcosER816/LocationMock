package com.mockroute.core

/** Internal values are SI; these convert to/from UI units. */
object Units {
    fun kmhToMs(kmh: Double): Double = kmh / 3.6
    fun msToKmh(ms: Double): Double = ms * 3.6
}
