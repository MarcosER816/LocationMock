package com.mockroute.core.timeline

import com.mockroute.core.geo.GeoMath
import com.mockroute.core.geo.LatLon
import com.mockroute.core.plan.CenterKind
import com.mockroute.core.plan.GammaSetting
import com.mockroute.core.plan.SegmentPlanSettings
import com.mockroute.core.route.RouteGeometry
import com.mockroute.core.route.RoutingProfile
import com.mockroute.core.streets.Overpass
import com.mockroute.core.streets.RoadGraph
import com.mockroute.core.zone.LocalFrame
import com.mockroute.core.zone.Polygon2
import com.mockroute.core.zone.Vec2
import com.mockroute.core.zone.ZoneDefinition
import com.mockroute.core.zone.ZoneKind
import kotlinx.coroutines.runBlocking
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TimelineTest {
    private val frame = LocalFrame(LatLon(40.4085, -3.7010))
    private fun ll(x: Double, y: Double) = frame.toLatLon(Vec2(x, y))

    private val room = listOf(ll(440.0, -20.0), ll(480.0, -20.0), ll(480.0, 20.0), ll(440.0, 20.0))
    private val area = listOf(ll(-20.0, -20.0), ll(420.0, -20.0), ll(420.0, 280.0), ll(280.0, 280.0), ll(280.0, 420.0), ll(-20.0, 420.0))

    private val walk = SegmentPlanSettings(
        speed = GammaSetting(0.9, 1.8, 1.35, 6.0, CenterKind.Mean),
        time = GammaSetting(300.0, 3600.0, 900.0, 4.0),
        aMaxMps2 = 1.0,
    )

    // ---- property test ------------------------------------------------------------------------

    @Test
    fun chainInvariantHoldsUnderRandomEdits() {
        val rnd = Random(2026)
        var ids = 0
        repeat(1000) { seq ->
            var t = Timeline(anchor = ll(0.0, 0.0))
            repeat(rnd.nextInt(5, 25)) {
                val p = ll(rnd.nextDouble(-500.0, 500.0), rnd.nextDouble(-500.0, 500.0))
                t = when (rnd.nextInt(9)) {
                    0, 1 -> t.add(SegmentKind.entries[rnd.nextInt(4)], "s${ids++}")
                    2 -> if (t.size > 0) t.remove(rnd.nextInt(t.size)) else t
                    3 -> if (t.size > 1) t.move(rnd.nextInt(t.size), rnd.nextInt(t.size)) else t
                    4, 5 -> t.setBoundary(rnd.nextInt(t.size + 1), p)
                    6 -> if (t.size > 0) {
                        val i = rnd.nextInt(t.size)
                        // An editor changed both ends of segment i.
                        t.replace(i, t.segments[i].withStart(p).withEnd(ll(rnd.nextDouble(-500.0, 500.0), rnd.nextDouble(-500.0, 500.0))))
                    } else t
                    7 -> t.insert(rnd.nextInt(t.size + 1), Segment.Pause("s${ids++}", p))
                    else -> t.setAnchor(p, rnd.nextBoolean())
                }
                assertTrue(t.chainOk(), "sequence $seq broke the chain: ${t.boundaries()}")
                // Zones keep entry/exit in sync with start/end.
                for (s in t.segments) {
                    if (s is Segment.IndoorZone) assertTrue(s.zone.entry == s.start && s.zone.exit == s.end)
                    if (s is Segment.StreetZone) assertTrue(s.zone.entry == s.start && s.zone.exit == s.end)
                }
            }
        }
    }

    @Test
    fun movingASharedPointUpdatesBothNeighboursAndPauseRuns() {
        var t = Timeline(anchor = ll(0.0, 0.0)).add(SegmentKind.Route, "r1").add(SegmentKind.Pause, "p").add(SegmentKind.Pause, "p2").add(SegmentKind.Route, "r2")
        val q = ll(100.0, 50.0)
        t = t.setBoundary(1, q) // end of r1 = the pause point(s) = start of r2
        assertEquals(q, t.segments[0].end)
        assertEquals(q, (t.segments[1] as Segment.Pause).at)
        assertEquals(q, (t.segments[2] as Segment.Pause).at)
        assertEquals(q, t.segments[3].start)
        assertTrue(t.chainOk())
        // Moving the first A from the real location marks it as custom.
        val t2 = t.setAnchor(ll(-5.0, -5.0), isRealLocation = false)
        assertTrue(!t2.anchorIsRealLocation && t2.segments[0].start == ll(-5.0, -5.0))
    }

    // ---- validation & fixes ----------------------------------------------------------------------

    private fun indoorTimeline(entry: LatLon): Timeline {
        val t = Timeline(anchor = ll(0.0, 0.0)).add(SegmentKind.Route, "r1").setBoundary(1, entry)
            .add(SegmentKind.Indoor, "z")
        val z = t.segments[1] as Segment.IndoorZone
        return t.replace(1, z.copy(zone = z.zone.copy(vertices = room, closed = true)).withEnd(ll(460.0, 20.0)))
    }

    @Test
    fun indoorEntryOffTheEdgeOffersFixes() {
        val t = indoorTimeline(ll(430.0, 0.0)) // 10 m outside the west wall
        val issue = t.issues().single()
        assertEquals(IssueKind.NotOnEdge, issue.kind)
        assertEquals(Issue.End.Start, issue.which)
        // Fix 1: snap the shared point onto the edge (the previous route's end follows).
        val snapped = t.snapToEdge(issue)
        assertTrue(snapped.issues().isEmpty() && snapped.chainOk())
        assertTrue(abs(frame.toLocal(snapped.segments[0].end).x - 440.0) < 0.01)
        // Fix 2: keep the point and insert a connector route to the edge.
        val conn = t.insertConnector(issue, "c")
        assertEquals(3, conn.size)
        assertTrue(conn.segments[1] is Segment.Route && conn.issues().isEmpty() && conn.chainOk())
        assertEquals(ll(430.0, 0.0), conn.segments[1].start)
    }

    // ---- serialization -----------------------------------------------------------------------------

    @Test
    fun serializationRoundTripAndVersioning() {
        var t = indoorTimeline(ll(450.0, -20.0)).add(SegmentKind.Street, "st").add(SegmentKind.Pause, "pz").copy(name = "Tuesday errands", seedBase = 77)
        t = t.replace(0, (t.segments[0] as Segment.Route).copy(profile = RoutingProfile.Car, waypoints = listOf(ll(100.0, 0.0)), settings = walk))
        val text = TimelineCodec.encode(t)
        assertTrue(text.contains("\"format\": \"mockroute-timeline\"") && text.contains("\"version\": 1") && text.contains("\"type\": \"indoor\""), text.take(300))
        assertEquals(t, TimelineCodec.decode(text))
        assertFailsWith<TimelineFormatException> { TimelineCodec.decode(text.replace("\"version\": 1", "\"version\": 99")) }
        assertFailsWith<TimelineFormatException> { TimelineCodec.decode("""{"format":"something-else","version":1}""") }
        assertFailsWith<TimelineFormatException> { TimelineCodec.decode("not json") }
    }

    // ---- planning end to end ---------------------------------------------------------------------------

    private val ways = Overpass.parse(requireNotNull(javaClass.getResource("/fixtures/overpass_grid.json")).readText())

    private val sources = object : TimelineSources {
        override suspend fun route(seg: Segment.Route) =
            com.mockroute.core.route.StraightLineProvider.build(seg.inputs, seg.profile)
        override suspend fun roadGraph(seg: Segment.StreetZone, frame: LocalFrame, polygon: Polygon2) =
            RoadGraph.build(ways, polygon, frame, seg.zone.profile)
    }

    /** Route → Indoor → Route → Street → Pause. */
    private fun fullTimeline(seedBase: Long): Timeline {
        var t = indoorTimeline(ll(450.0, -20.0))
        t = t.replace(0, (t.segments[0] as Segment.Route).copy(settings = walk))
        t = t.add(SegmentKind.Route, "r2").setBoundary(3, ll(150.0, 150.0))
        t = t.replace(2, (t.segments[2] as Segment.Route).copy(settings = walk))
        t = t.add(SegmentKind.Street, "st")
        val st = t.segments[3] as Segment.StreetZone
        t = t.replace(3, st.copy(zone = st.zone.copy(vertices = area, closed = true, kind = ZoneKind.Streets), settings = walk.copy(time = GammaSetting(300.0, 900.0, 600.0, 4.0))).withEnd(ll(205.0, 95.0)))
        t = t.add(SegmentKind.Pause, "p")
        return t.copy(seedBase = seedBase)
    }

    @Test
    fun fullTimelinePlaysContinuouslyWithoutJumps() = runBlocking {
        for (seedBase in 1L..20L) {
            val t = fullTimeline(seedBase)
            assertTrue(t.issues().isEmpty(), "${t.issues()}")
            val plan = TimelinePlanner.plan(t, startEpochS = 1_794_000_000L, sources = sources)
            assertTrue(plan.playable, plan.segments.mapNotNull { it.error }.toString())
            val traj = assertNotNull(plan.trajectory())
            assertEquals(5, traj.parts.size)
            // Each segment starts exactly at its shared point, at the previous segment's end time.
            for ((i, p) in plan.segments.withIndex()) {
                val at = traj.stateAt(p.startOffsetS + 1e-9).position
                assertTrue(GeoMath.haversineMeters(at, t.boundary(i)) < 0.01, "seed $seedBase seg $i starts ${GeoMath.haversineMeters(at, t.boundary(i))} m off")
                if (i > 0) assertEquals(plan.segments[i - 1].startOffsetS + plan.segments[i - 1].durationS, p.startOffsetS, 1e-9)
            }
            // No jump larger than v_max·Δt anywhere (incl. boundaries), sampled at 1 Hz like the service.
            val vMax = 1.8
            var prev = traj.stateAt(0.0).position
            var tt = 1.0
            while (tt <= traj.durationSeconds + 1) {
                val cur = traj.stateAt(tt).position
                val jump = GeoMath.haversineMeters(prev, cur)
                assertTrue(jump <= vMax * 1.0 + 0.4, "seed $seedBase: jump $jump m at t=$tt (${traj.labelAt(tt)})")
                prev = cur
                tt += 1.0
            }
            // Boundary speeds: equal on both sides (route→indoor = 0; indoor→route = 0; route→street > 0).
            for (k in 1 until plan.segments.size) {
                val tb = plan.segments[k].startOffsetS
                val before = traj.stateAt(tb - 1e-6).speedMps
                val after = traj.stateAt(tb + 1e-6).speedMps
                assertEquals(before, after, 0.05, "seed $seedBase boundary $k speed jump")
            }
            assertTrue(TimelinePlanner.boundarySpeed(t, 3) > 0.0, "route→street keeps moving")
            assertEquals(0.0, TimelinePlanner.boundarySpeed(t, 1)) // indoor entry
            assertEquals(0.0, TimelinePlanner.boundarySpeed(t, 4)) // pause
        }
    }

    @Test
    fun streetZoneWithAPointOutsideIsNotPlanned() = runBlocking {
        // Route A→B ends 100 m west of the street zone, so the zone's fixed start B is outside it.
        var t = Timeline(anchor = ll(-300.0, 0.0)).add(SegmentKind.Route, "r").moveFreePoint(1, ll(-120.0, 100.0)).add(SegmentKind.Street, "st")
        val st = t.segments[1] as Segment.StreetZone
        t = t.replaceKeepingFixed(1, st.copy(zone = st.zone.copy(vertices = area, closed = true, kind = ZoneKind.Streets)).withEnd(ll(205.0, 95.0)))
        assertEquals(ll(-120.0, 100.0), t.segments[1].start) // still fixed
        assertTrue(t.issues().any { it.kind == IssueKind.NotInside && it.which == Issue.End.Start })
        val plan = TimelinePlanner.plan(t, 1_794_000_000L, sources)
        assertTrue(!plan.playable)
        assertTrue(plan.segments[1].error!!.contains("Point B is outside the street zone"), plan.segments[1].error)
    }

    @Test
    fun perStepAltitudeBlendsFromThePreviousStep() = runBlocking {
        var t = fullTimeline(1)
        t = t.replaceKeepingFixed(1, t.segments[1].withAltitude(712.0)) // the indoor zone is upstairs
        val plan = TimelinePlanner.plan(t, 1_794_000_000L, sources)
        val traj = assertNotNull(plan.trajectory(baseAltitudeM = 650.0))
        val s1 = plan.segments[1].startOffsetS
        assertEquals(650.0, traj.stateAt(10.0).altitudeM)            // step 1: base
        assertEquals(650.0, traj.stateAt(s1).altitudeM, 1e-6)        // boundary: no jump
        assertEquals(681.0, traj.stateAt(s1 + 15).altitudeM, 1e-6)   // half-way through the 30 s blend
        assertEquals(712.0, traj.stateAt(s1 + 40).altitudeM)         // the step's own altitude
        val s2 = plan.segments[2].startOffsetS
        assertEquals(650.0, traj.stateAt(s2 + 31).altitudeM)         // back to base afterwards
        assertEquals(t, TimelineCodec.decode(TimelineCodec.encode(t)))
    }

    @Test
    fun firstPlanOfARouteTakesExactlyTheProvidersTime() = runBlocking {
        val withEta = object : TimelineSources {
            override suspend fun route(seg: Segment.Route) =
                com.mockroute.core.route.StraightLineProvider.build(seg.inputs, seg.profile).copy(durationS = 431.0)
            override suspend fun roadGraph(seg: Segment.StreetZone, frame: LocalFrame, polygon: Polygon2): RoadGraph? = null
        }
        val t = Timeline(anchor = ll(0.0, 0.0)).add(SegmentKind.Route, "r").moveFreePoint(1, ll(500.0, 0.0))
        val first = TimelinePlanner.plan(t, 1_794_000_000L, withEta)
        assertEquals(431.0, first.segments[0].durationS, 1.0) // exactly the provider's time
        assertTrue(first.segments[0].settingsSource.contains("provider"))
        // After a shuffle the time is sampled around it (so it differs).
        val shuffled = t.replaceKeepingFixed(0, t.segments[0].reroll())
        val second = TimelinePlanner.plan(shuffled, 1_794_000_000L, withEta)
        assertTrue(abs(second.segments[0].durationS - 431.0) > 1.0, "shuffled ${second.segments[0].durationS}")
    }

    @Test
    fun gpxHasTimestampedPoints() = runBlocking {
        val plan = TimelinePlanner.plan(fullTimeline(1), 1_794_000_000L, sources)
        val traj = assertNotNull(plan.trajectory())
        val gpx = Gpx.write("Test & co", traj, 1_794_000_000L, stepS = 10.0)
        assertTrue(gpx.startsWith("<?xml") && gpx.contains("<gpx version=\"1.1\"") && gpx.contains("Test &amp; co"))
        val n = Regex("<trkpt ").findAll(gpx).count()
        val expected = (traj.durationSeconds / 10).toInt() + 2
        assertTrue(abs(n - expected) <= 1, "$n points vs ~$expected")
        assertTrue(Regex("<time>\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z</time>").containsMatchIn(gpx), gpx.take(400))
    }

    @Test
    fun rerollChangesSeedsDeterministically() {
        val t = fullTimeline(1)
        val s1 = t.segments.map { TimelinePlanner.seedFor(t, it) }
        assertEquals(s1, t.segments.map { TimelinePlanner.seedFor(t, it) })
        val t2 = t.rerollAll(2)
        assertTrue(s1.zip(t2.segments.map { TimelinePlanner.seedFor(t2, it) }).all { (a, b) -> a != b })
        // Per-segment re-roll only changes that one.
        val t3 = t.replace(0, t.segments[0].reroll())
        val s3 = t3.segments.map { TimelinePlanner.seedFor(t3, it) }
        assertTrue(s3[0] != s1[0] && s3.drop(1) == s1.drop(1))
    }
}
