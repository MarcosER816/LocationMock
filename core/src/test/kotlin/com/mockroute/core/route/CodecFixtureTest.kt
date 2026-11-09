package com.mockroute.core.route

import com.mockroute.core.geo.LatLon
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CodecFixtureTest {
    private fun fixture(name: String): String =
        requireNotNull(javaClass.getResource("/fixtures/$name")) { "missing fixture $name" }.readText()

    @Test
    fun orsGeoJsonParses() {
        val r = OrsCodec.parse(fixture("ors_foot_walking.geojson"), RoutingProfile.Walk)
        assertEquals(9, r.geometry.size)
        assertEquals(LatLon(49.41461, 8.681495), r.geometry.start)
        assertEquals(LatLon(49.420318, 8.687872), r.geometry.end)
        assertEquals(1032.6, r.distanceM, 1e-9)
        assertEquals(743.5, r.durationS!!, 1e-9)
        assertEquals("ors", r.providerId)
        assertEquals(3, r.spans.size)
        assertEquals("street", r.spans[0].roadClass)
        assertEquals("footway", r.spans[1].roadClass)
        assertEquals(0.0, r.spans[0].startM)
        assertEquals(r.geometry.cumulativeM(3), r.spans[0].endM)
        assertEquals(r.geometry.lengthM, r.spans.last().endM)
        assertTrue(r.spans.all { it.speedLimitKmh == null })
    }

    @Test
    fun orsRequestAndUrl() {
        val body = OrsCodec.requestBody(listOf(LatLon(49.41461, 8.681495), LatLon(49.420318, 8.687872)))
        val obj = Json.parseToJsonElement(body).toString()
        assertTrue(obj.contains("\"coordinates\":[[8.681495,49.41461],[8.687872,49.420318]]"), obj)
        assertTrue(obj.contains("\"extra_info\":[\"waytype\"]"), obj)
        assertEquals(
            "https://api.heigit.org/openrouteservice/v2/directions/foot-walking/geojson",
            OrsCodec.url(RoutingProfile.Walk),
        )
        assertEquals("driving-hgv", OrsCodec.profilePath(RoutingProfile.Bus))
    }

    @Test
    fun orsErrorsAreFriendly() {
        assertIs<RoutingException.NoRoute>(OrsCodec.error(404, fixture("ors_error_2010.json")))
        assertIs<RoutingException.Unauthorized>(OrsCodec.error(401, """{"error":"Authorization field missing"}"""))
        // Real api.heigit.org answer for an invalid key (checked 2026-11-09).
        assertIs<RoutingException.Forbidden>(OrsCodec.error(403, """{"error": "Access to this API has been disallowed"}"""))
        assertIs<RoutingException.RateLimited>(OrsCodec.error(429, "garbage"))
        assertIs<RoutingException.Http>(OrsCodec.error(500, """{"error":{"code":2099,"message":"boom"}}"""))
        assertFailsWith<RoutingException.Parse> { OrsCodec.parse("not json", RoutingProfile.Walk) }
        assertFailsWith<RoutingException.NoRoute> { OrsCodec.parse("""{"features":[]}""", RoutingProfile.Walk) }
    }

    @Test
    fun graphHopperParsesWithDetails() {
        val r = GraphHopperCodec.parse(fixture("graphhopper_car.json"), RoutingProfile.Car)
        assertEquals(6, r.geometry.size)
        assertEquals(LatLon(52.517037, 13.388860), r.geometry.start)
        assertEquals(1520.4, r.distanceM, 1e-9)
        assertEquals(151.0, r.durationS!!, 1e-9)
        // Cuts at 0,2,3,4,5 → spans [0,2] 50 primary, [2,3] 30 primary, [3,4] 30 residential, [4,5] null residential.
        assertEquals(4, r.spans.size)
        assertEquals(50.0, r.spans[0].speedLimitKmh)
        assertEquals("primary", r.spans[0].roadClass)
        assertEquals(30.0, r.spans[2].speedLimitKmh)
        assertEquals("residential", r.spans[2].roadClass)
        assertNull(r.spans[3].speedLimitKmh)
        assertEquals(r.geometry.lengthM, r.spans.last().endM)
    }

    @Test
    fun graphHopperRequestAndErrors() {
        val body = GraphHopperCodec.requestBody(listOf(LatLon(52.0, 13.0), LatLon(52.1, 13.1)), RoutingProfile.Walk)
        assertTrue(body.contains("\"profile\":\"foot\""), body)
        assertTrue(body.contains("\"points_encoded\":false"), body)
        assertIs<RoutingException.Unauthorized>(GraphHopperCodec.error(401, """{"message":"Wrong credentials. Register and get a valid API key"}"""))
        assertIs<RoutingException.RateLimited>(GraphHopperCodec.error(429, null))
        assertIs<RoutingException.NoRoute>(GraphHopperCodec.error(400, """{"message":"Cannot find point 0: 52.0,13.0"}"""))
    }

    @Test
    fun resultConcatAndCacheSerialization() {
        val leg1 = OrsCodec.parse(fixture("ors_foot_walking.geojson"), RoutingProfile.Walk)
        val leg2 = StraightLineProvider.build(listOf(leg1.geometry.end!!, LatLon(49.421, 8.689)), RoutingProfile.Walk, "no key")
        val all = RouteResult.concat(listOf(leg1, leg2), RoutingProfile.Walk)!!
        assertEquals(leg1.geometry.size + 1, all.geometry.size)
        assertEquals(leg1.distanceM + leg2.distanceM, all.distanceM, 1e-9)
        assertNull(all.durationS) // straight line has no duration
        assertTrue(all.isFallback)
        assertEquals("ors+straight", all.providerId)
        val json = Json.encodeToString(RouteResult.serializer(), all)
        assertEquals(all, Json.decodeFromString(RouteResult.serializer(), json))
    }
}
