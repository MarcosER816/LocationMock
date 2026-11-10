package com.mockroute.core.timeline

import com.mockroute.core.geo.LatLon
import com.mockroute.core.plan.AutoSettings
import com.mockroute.core.route.RoutingProfile
import com.mockroute.core.zone.LocalFrame
import com.mockroute.core.zone.Vec2
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TimelineRulesTest {
    private val frame = LocalFrame(LatLon(40.4085, -3.7010))
    private fun ll(x: Double, y: Double) = frame.toLatLon(Vec2(x, y))
    private val zone = ZoneId.of("Europe/Madrid")

    @Test
    fun lettersForPoints() {
        assertEquals(listOf("A", "B", "C", "Z", "AA", "AB", "AZ", "BA"), listOf(0, 1, 2, 25, 26, 27, 51, 52).map(Timeline::pointLabel))
    }

    @Test
    fun onlyFirstAndLastPointsAreFree() {
        val t = Timeline(anchor = ll(0.0, 0.0)).add(SegmentKind.Route, "1").add(SegmentKind.Route, "2").add(SegmentKind.Route, "3")
        assertEquals(listOf(false, true, true, false), (0..3).map(t::isFixed))
        // Moving a fixed point is refused; free ones move.
        assertEquals(t, t.moveFreePoint(1, ll(9.0, 9.0)))
        assertEquals(ll(5.0, 5.0), t.moveFreePoint(3, ll(5.0, 5.0)).boundary(3))
        assertEquals(ll(-5.0, 0.0), t.moveFreePoint(0, ll(-5.0, 0.0)).anchor)
    }

    /** Property: whatever an editor returns (snapped/moved A and B), fixed points never move. */
    @Test
    fun editorsCannotMoveFixedPoints() {
        val rnd = Random(8)
        repeat(1000) { seq ->
            var t = Timeline(anchor = ll(0.0, 0.0))
            repeat(rnd.nextInt(3, 12)) { t = t.add(SegmentKind.entries[rnd.nextInt(4)], "s$seq-$it").moveFreePoint(t.size + 1, ll(rnd.nextDouble(-300.0, 300.0), rnd.nextDouble(-300.0, 300.0))) }
            repeat(20) {
                val fixedBefore = (1 until t.size).associateWith { t.boundary(it) }
                val i = rnd.nextInt(t.size)
                val edited = t.segments[i].withStart(ll(rnd.nextDouble(-300.0, 300.0), 1.0)).withEnd(ll(2.0, rnd.nextDouble(-300.0, 300.0)))
                t = t.replaceKeepingFixed(i, edited)
                assertTrue(t.chainOk())
                for ((k, p) in fixedBefore) assertEquals(p, t.boundary(k), "seq $seq: fixed point $k moved")
            }
        }
    }

    @Test
    fun addAndRemoveOnlyAtTheEnds() {
        var t = Timeline(anchor = ll(0.0, 0.0)).add(SegmentKind.Route, "1").moveFreePoint(1, ll(100.0, 0.0)).add(SegmentKind.Route, "2").moveFreePoint(2, ll(200.0, 0.0))
        // Add at start: ends at the old first point (now fixed), starts there until moved.
        t = t.addAtStart(SegmentKind.Route, "0").moveFreePoint(0, ll(-100.0, 0.0))
        assertEquals(listOf(ll(-100.0, 0.0), ll(0.0, 0.0), ll(100.0, 0.0), ll(200.0, 0.0)), t.boundaries())
        assertFalse(t.anchorIsRealLocation)
        // Remove first: its end becomes the new (free) first point.
        val rf = t.removeFirst()
        assertEquals(ll(0.0, 0.0), rf.anchor)
        assertEquals(2, rf.size)
        // Remove last: the previous end becomes the free last point.
        val rl = t.removeLast()
        assertEquals(ll(100.0, 0.0), rl.boundary(rl.size))
        assertTrue(rf.chainOk() && rl.chainOk())
        // New zones start with their fixed entry as the first corner.
        val z = t.add(SegmentKind.Indoor, "z").segments.last() as Segment.IndoorZone
        assertEquals(listOf(ll(200.0, 0.0)), z.zone.vertices)
        // Street zones get no seeded corner (the entry may be anywhere inside); the entry is still the fixed point.
        val sz = t.add(SegmentKind.Street, "sz").segments.last() as Segment.StreetZone
        assertTrue(sz.zone.vertices.isEmpty())
        assertEquals(ll(200.0, 0.0), sz.zone.entry)
    }

    @Test
    fun fixesAreOnlyOfferedWhenTheyMoveNoFixedPoint() {
        val t = Timeline(anchor = ll(0.0, 0.0)).add(SegmentKind.Route, "r").add(SegmentKind.Indoor, "z").add(SegmentKind.Route, "r2")
        assertFalse(t.canSnap(Issue(1, Issue.End.Start, IssueKind.NotOnEdge)))  // shared with the route before
        assertFalse(t.canSnap(Issue(1, Issue.End.EndPoint, IssueKind.NotOnEdge))) // shared with the route after
        assertFalse(t.canInsertConnector(Issue(1, Issue.End.Start, IssueKind.NotOnEdge))) // would insert in the middle
        val last = Timeline(anchor = ll(0.0, 0.0)).add(SegmentKind.Indoor, "z")
        assertTrue(last.canSnap(Issue(0, Issue.End.Start, IssueKind.NotOnEdge)) && last.canInsertConnector(Issue(0, Issue.End.EndPoint, IssueKind.NotOnEdge)))
    }

    @Test
    fun automaticSettingsDependOnTheHourAndAreFeasible() {
        val wedPeak = ZonedDateTime.of(2026, 11, 11, 8, 15, 0, 0, zone).toEpochSecond()
        val wedNight = ZonedDateTime.of(2026, 11, 11, 3, 0, 0, 0, zone).toEpochSecond()
        val satMorning = ZonedDateTime.of(2026, 11, 14, 8, 15, 0, 0, zone).toEpochSecond()
        assertEquals(AutoSettings.Period.MorningPeak, AutoSettings.period(wedPeak, zone))
        assertEquals(AutoSettings.Period.Night, AutoSettings.period(wedNight, zone))
        assertEquals(AutoSettings.Period.Day, AutoSettings.period(satMorning, zone))

        val d = 8000.0
        val peak = AutoSettings.forRoute(RoutingProfile.Car, d, providerEtaS = 900.0, departEpochS = wedPeak, zone = zone)
        val night = AutoSettings.forRoute(RoutingProfile.Car, d, providerEtaS = 900.0, departEpochS = wedNight, zone = zone)
        assertTrue(peak.settings.time.distribution().mean > night.settings.time.distribution().mean * 1.3, "rush hour slower")
        assertEquals(900.0 * 1.35, peak.settings.time.distribution().mean, 900.0 * 1.35 * 0.03)
        assertTrue(peak.source.contains("route ETA") && peak.source.contains("08:15"), peak.source)
        // No router ETA → standard speed for the hour.
        val std = AutoSettings.forRoute(RoutingProfile.Car, d, null, wedNight, zone)
        assertEquals(d / (40 / 3.6), std.settings.time.distribution().mean, 0.03 * d / (40 / 3.6))
        // Walking: crossing waits make it longer at rush hour than at night (same ETA).
        val walkPeak = AutoSettings.forRoute(RoutingProfile.Walk, 1500.0, 1125.0, wedPeak, zone)
        val walkNight = AutoSettings.forRoute(RoutingProfile.Walk, 1500.0, 1125.0, wedNight, zone)
        assertTrue(walkPeak.expectedWaitS > walkNight.expectedWaitS && walkPeak.expectedWaitS > 0)
        assertTrue(walkPeak.source.endsWith("+ crossings"))
        for (r in listOf(peak, night, std, walkPeak, walkNight)) {
            assertTrue(r.settings.feasibility(if (r === walkPeak || r === walkNight) 1500.0 else d).let { it.feasible || it.issues == listOf(com.mockroute.core.plan.Feasibility.Issue.TooShort) }, "${r.source}: ${r.settings}")
            assertTrue(r.settings.vMax <= 100 / 3.6 + 1e-9 && r.settings.tMax <= 6 * 3600.0)
        }
        // Crossing stops: about one per 150 m, waits > 0.
        val stops = AutoSettings.crossingStops(1500.0, wedPeak, zone, Random(1))
        assertTrue(stops.size in 1..9 && stops.all { it.durationS >= 3 && it.atM in 40.0..1460.0 })
    }
}
