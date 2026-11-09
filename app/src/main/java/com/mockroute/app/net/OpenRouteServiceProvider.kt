package com.mockroute.app.net

import com.mockroute.core.geo.LatLon
import com.mockroute.core.route.OrsCodec
import com.mockroute.core.route.RouteResult
import com.mockroute.core.route.RoutingException
import com.mockroute.core.route.RoutingProfile
import com.mockroute.core.route.RoutingProvider
import java.io.IOException

/** openrouteservice via HeiGIT's api.heigit.org (see [OrsCodec]). */
class OpenRouteServiceProvider(private val apiKey: String) : RoutingProvider {
    override val id: String = ID
    override val displayName: String = OrsCodec.PROVIDER

    override suspend fun route(points: List<LatLon>, profile: RoutingProfile): RouteResult {
        if (apiKey.isBlank()) throw RoutingException.MissingKey(OrsCodec.PROVIDER)
        val resp = try {
            HttpJson.post(OrsCodec.url(profile), OrsCodec.requestBody(points), mapOf("Authorization" to apiKey))
        } catch (e: IOException) {
            throw RoutingException.Network(OrsCodec.PROVIDER, e)
        }
        if (resp.code !in 200..299 || resp.body == null) throw OrsCodec.error(resp.code, resp.body)
        return OrsCodec.parse(resp.body, profile, id)
    }

    companion object {
        const val ID = "ors"
    }
}
