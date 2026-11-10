package com.mockroute.app.net

import com.mockroute.core.route.RouteGeometry
import com.mockroute.core.traffic.PathMatch
import com.mockroute.core.traffic.SpeedProfileSample
import com.mockroute.core.traffic.TomTomCodec
import com.mockroute.core.traffic.TrafficException
import com.mockroute.core.traffic.TrafficMode
import com.mockroute.core.traffic.TrafficProvider
import java.io.IOException
import java.time.ZoneId

/**
 * TomTom calculateRoute with departAt (historic speed profiles) along our traced path (see [TomTomCodec]).
 *
 * Path matching falls back Exact (supporting points, track reconstruction) → Corridor (waypoint circles) →
 * Direct (A→B) when TomTom can't rebuild the path. The first method that works is remembered for the rest of
 * this analysis, so the other departure bins don't repeat failed attempts.
 */
class TomTomTrafficProvider(
    private val apiKey: String,
    private val zone: ZoneId,
    /** Called once per real network request (for the "today" counter). */
    private val onRequest: suspend () -> Unit,
) : TrafficProvider {
    override val id = "tomtom"
    override val displayName = "TomTom (historic)"

    private var method: PathMatch? = null

    override suspend fun profile(route: RouteGeometry, departureEpochS: Long, mode: TrafficMode): SpeedProfileSample {
        if (apiKey.isBlank()) throw TrafficException.MissingKey(TomTomCodec.PROVIDER)
        method?.let { return request(route, departureEpochS, mode, it) }
        var last: TrafficException? = null
        for (m in PathMatch.entries) {
            try {
                return request(route, departureEpochS, mode, m).also { method = m }
            } catch (e: TrafficException.RestoreFailed) {
                last = e
            } catch (e: TrafficException.NoRoute) {
                last = e
                if (m == PathMatch.Direct) throw e
            } catch (e: TrafficException.Http) {
                // e.g. 400 BAD_INPUT if TomTom rejects a parameter of this method: try the next, simpler one.
                if (e.code != 400 || m == PathMatch.Direct) throw e
                last = e
            }
        }
        throw last ?: TrafficException.NoRoute(TomTomCodec.PROVIDER, null)
    }

    private suspend fun request(route: RouteGeometry, departureEpochS: Long, mode: TrafficMode, m: PathMatch): SpeedProfileSample {
        onRequest()
        val url = TomTomCodec.url(route, departureEpochS, zone, mode, apiKey, m)
        val resp = try {
            if (m == PathMatch.Exact) HttpJson.post(url, TomTomCodec.body(route)) else HttpJson.get(url)
        } catch (e: IOException) {
            throw TrafficException.Network(TomTomCodec.PROVIDER, e)
        }
        if (resp.code !in 200..299 || resp.body == null) throw TomTomCodec.error(resp.code, resp.body)
        return TomTomCodec.parse(resp.body, route, departureEpochS, m)
    }
}
