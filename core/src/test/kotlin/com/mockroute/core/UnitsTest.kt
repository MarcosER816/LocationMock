package com.mockroute.core

import kotlin.test.Test
import kotlin.test.assertEquals

class UnitsTest {
    @Test
    fun roundTrip() {
        assertEquals(10.0, Units.kmhToMs(36.0), 1e-12)
        assertEquals(36.0, Units.msToKmh(10.0), 1e-12)
    }
}
