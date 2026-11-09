package com.mockroute.app.net

import android.net.Uri
import com.mockroute.core.geo.LatLon
import com.mockroute.core.route.GraphHopperCodec
import com.mockroute.core.route.RouteResult
import com.mockroute.core.route.RoutingException
import com.mockroute.core.route.RoutingProfile
import com.mockroute.core.route.RoutingProvider
import java.io.IOException

/** GraphHopper Directions API (free: 500 credits/day, non-commercial). */
class GraphHopperProvider(private val apiKey: String) : RoutingProvider {
    override val id: String = ID
    override val displayName: String = GraphHopperCodec.PROVIDER

    override suspend fun route(points: List<LatLon>, profile: RoutingProfile): RouteResult {
        if (apiKey.isBlank()) throw RoutingException.MissingKey(GraphHopperCodec.PROVIDER)
        val url = Uri.parse(GraphHopperCodec.BASE_URL).buildUpon().appendQueryParameter("key", apiKey).build().toString()
        val resp = try {
            HttpJson.post(url, GraphHopperCodec.requestBody(points, profile))
        } catch (e: IOException) {
            throw RoutingException.Network(GraphHopperCodec.PROVIDER, e)
        }
        if (resp.code !in 200..299 || resp.body == null) throw GraphHopperCodec.error(resp.code, resp.body)
        return GraphHopperCodec.parse(resp.body, profile, id)
    }

    companion object {
        const val ID = "graphhopper"
    }
}
