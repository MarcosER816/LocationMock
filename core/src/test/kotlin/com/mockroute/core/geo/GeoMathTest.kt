package com.mockroute.core.geo

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GeoMathTest {
    private fun angleDiff(a: Double, b: Double): Double = abs(((a - b + 540.0) % 360.0) - 180.0)

    private val london = LatLon(51.5074, -0.1278)
    private val paris = LatLon(48.8566, 2.3522)
    private val newYork = LatLon(40.7128, -74.0060)
    private val losAngeles = LatLon(34.0522, -118.2437)
    private val madrid = LatLon(40.4168, -3.7038)
    private val barcelona = LatLon(41.3874, 2.1686)
    private val sydney = LatLon(-33.8688, 151.2093)
    private val melbourne = LatLon(-37.8136, 144.9631)
    private val tokyo = LatLon(35.6762, 139.6503)
    private val osaka = LatLon(34.6937, 135.5023)

    /** Reference: WGS84 ellipsoidal geodesic distances (GeographicLib), km. */
    @Test
    fun haversineCityPairsWithinHalfPercent() {
        val cases = listOf(
            Triple(london, paris, 343.9),
            Triple(newYork, losAngeles, 3944.4),
            Triple(madrid, barcelona, 506.3),
            Triple(sydney, melbourne, 713.9),
            Triple(tokyo, osaka, 393.2),
        )
        for ((a, b, refKm) in cases) {
            val km = GeoMath.haversineMeters(a, b) / 1000
            val rel = abs(km - refKm) / refKm
            assertTrue(rel < 0.005, "$a→$b: $km km vs $refKm km (${rel * 100} %)")
            assertEquals(km, GeoMath.haversineMeters(b, a) / 1000, 1e-9, "symmetry")
        }
        assertEquals(0.0, GeoMath.haversineMeters(paris, paris), 1e-9)
    }

    @Test
    fun bearingCardinalAndCities() {
        val o = LatLon(0.0, 0.0)
        assertEquals(0.0, GeoMath.initialBearingDeg(o, LatLon(1.0, 0.0)), 1e-9)
        assertEquals(90.0, GeoMath.initialBearingDeg(o, LatLon(0.0, 1.0)), 1e-9)
        assertEquals(180.0, GeoMath.initialBearingDeg(o, LatLon(-1.0, 0.0)), 1e-9)
        assertEquals(270.0, GeoMath.initialBearingDeg(o, LatLon(0.0, -1.0)), 1e-9)
        // GeographicLib azimuths; spherical bearings agree within ~0.5°.
        assertEquals(148.05, GeoMath.initialBearingDeg(london, paris), 0.5)
        assertEquals(273.73, GeoMath.initialBearingDeg(newYork, losAngeles), 0.5)
        assertEquals(230.4, GeoMath.initialBearingDeg(sydney, melbourne), 0.5)
        // Across the antimeridian: due east.
        assertEquals(90.0, GeoMath.initialBearingDeg(LatLon(0.0, 179.5), LatLon(0.0, -179.5)), 1e-9)
    }

    @Test
    fun destinationRoundTrip() {
        for (bearing in listOf(0.0, 45.0, 137.0, 270.0, 359.0)) {
            val d = GeoMath.destination(madrid, bearing, 12_345.0)
            assertEquals(12_345.0, GeoMath.haversineMeters(madrid, d), 1e-3)
            assertEquals(0.0, angleDiff(bearing, GeoMath.initialBearingDeg(madrid, d)), 1e-6)
        }
        val wrapped = GeoMath.destination(LatLon(0.0, 179.9), 90.0, 50_000.0)
        assertTrue(wrapped.lon < -179.0, "longitude wraps: ${wrapped.lon}")
    }

    @Test
    fun pointAtDistanceAlongPolyline() {
        val a = LatLon(40.0, -3.0)
        val b = GeoMath.destination(a, 90.0, 1000.0)
        val c = GeoMath.destination(b, 0.0, 500.0)
        val line = listOf(a, b, c)
        assertEquals(1500.0, GeoMath.polylineLengthMeters(line), 1e-3)

        val start = GeoMath.pointAtDistance(line, 0.0)
        assertEquals(0.0, GeoMath.haversineMeters(start.position, a), 1e-6)

        val mid = GeoMath.pointAtDistance(line, 400.0)
        assertEquals(0, mid.segmentIndex)
        assertEquals(400.0, GeoMath.haversineMeters(a, mid.position), 1e-3)
        assertEquals(90.0, mid.bearingDeg, 0.1)

        val second = GeoMath.pointAtDistance(line, 1200.0)
        assertEquals(1, second.segmentIndex)
        assertEquals(200.0, GeoMath.haversineMeters(b, second.position), 1e-3)
        assertEquals(0.0, second.bearingDeg, 0.1)

        val clamped = GeoMath.pointAtDistance(line, 99_999.0)
        assertEquals(0.0, GeoMath.haversineMeters(clamped.position, c), 1e-6)
        val negative = GeoMath.pointAtDistance(line, -5.0)
        assertEquals(0.0, GeoMath.haversineMeters(negative.position, a), 1e-6)
    }

    @Test
    fun enuRoundTripAndAxes() {
        val origin = LatLon(41.3874, 2.1686)
        val north = GeoMath.destination(origin, 0.0, 100.0)
        val enuN = GeoMath.toEnu(origin, north)
        assertEquals(0.0, enuN.east, 0.01)
        assertTrue(abs(enuN.north - 100.0) < 0.6, "north ≈ 100 m, got ${enuN.north}")

        val enu = Enu(east = 250.0, north = -120.0, up = 15.0)
        val (p, alt) = GeoMath.fromEnu(origin, enu, originAlt = 30.0)
        val back = GeoMath.toEnu(origin, p, originAlt = 30.0, alt = alt)
        assertEquals(enu.east, back.east, 1e-3)
        assertEquals(enu.north, back.north, 1e-3)
        assertEquals(enu.up, back.up, 1e-3)
    }
}
