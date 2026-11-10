package com.mockroute.app.net

import com.mockroute.core.route.RouteGeometry
import com.mockroute.core.traffic.SpeedProfileSample
import com.mockroute.core.traffic.TomTomCodec
import com.mockroute.core.traffic.TrafficException
import com.mockroute.core.traffic.TrafficMode
import com.mockroute.core.traffic.TrafficProvider
import java.io.IOException
import java.time.ZoneId

/** TomTom calculateRoute with departAt (historic speed profiles) along our traced path (see [TomTomCodec]). */
class TomTomTrafficProvider(
    private val apiKey: String,
    private val zone: ZoneId,
    /** Called once per real network request (for the "today" counter). */
    private val onRequest: suspend () -> Unit,
) : TrafficProvider {
    override val id = "tomtom"
    override val displayName = "TomTom (historic)"

    override suspend fun profile(route: RouteGeometry, departureEpochS: Long, mode: TrafficMode): SpeedProfileSample {
        if (apiKey.isBlank()) throw TrafficException.MissingKey(TomTomCodec.PROVIDER)
        onRequest()
        val resp = try {
            HttpJson.post(TomTomCodec.url(route, departureEpochS, zone, mode, apiKey), TomTomCodec.body(route))
        } catch (e: IOException) {
            throw TrafficException.Network(TomTomCodec.PROVIDER, e)
        }
        if (resp.code !in 200..299 || resp.body == null) throw TomTomCodec.error(resp.code, resp.body)
        return TomTomCodec.parse(resp.body, route, departureEpochS)
    }
}
