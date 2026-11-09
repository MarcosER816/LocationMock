package com.mockroute.core

import kotlin.test.Test
import kotlin.test.assertEquals

class CoreTest {
    @Test
    fun nameIsMockRoute() {
        assertEquals("MockRoute", Core.NAME)
    }

    @Test
    fun kmhToMsConverts() {
        assertEquals(10.0, Core.kmhToMs(36.0), 1e-9)
    }
}
