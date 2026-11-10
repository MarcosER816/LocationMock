package com.mockroute.core.plan

import com.mockroute.core.route.RoutingProfile
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SegmentPlanSettingsTest {
    @Test
    fun serializationRoundTrip() {
        val s = SegmentPlanSettings.defaults(RoutingProfile.Bus, 7_500.0)
            .copy(speed = GammaSetting(1.0, 12.0, 4.0, 2.5, CenterKind.Mode))
        val json = Json.encodeToString(SegmentPlanSettings.serializer(), s)
        assertEquals(s, Json.decodeFromString(SegmentPlanSettings.serializer(), json))
    }

    @Test
    fun defaultsAreFeasibleAndWithinCaps() {
        for (p in RoutingProfile.entries) {
            for (d in listOf(200.0, 2_000.0, 20_000.0)) {
                val s = SegmentPlanSettings.defaults(p, d)
                assertTrue(s.vMax <= CoupledSliders.V_CAP && s.tMax <= CoupledSliders.T_CAP)
                assertTrue(s.feasibility(d).feasible, "$p, $d m: ${s.feasibility(d)}")
                assertEquals(AccelerationPreset.defaultFor(p).aMps2, s.aMaxMps2)
            }
        }
    }

    @Test
    fun gammaSettingModeFallsBackToMeanForSmallShape() {
        val g = GammaSetting(0.0, 10.0, 3.0, 0.8, CenterKind.Mode).distribution()
        assertEquals(3.0, g.mean, 1e-6)
    }
}
