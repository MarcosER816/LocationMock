package com.mockroute.core.route

import com.mockroute.core.geo.LatLon

/** No-key, no-network provider: joins the points with great-circle lines. */
class StraightLineProvider : RoutingProvider {
    override val id: String = ID
    override val displayName: String = "Straight line"

    override suspend fun route(points: List<LatLon>, profile: RoutingProfile): RouteResult = build(points, profile)

    companion object {
        const val ID = "straight"

        fun build(points: List<LatLon>, profile: RoutingProfile, notice: String? = null): RouteResult {
            val g = RouteGeometry.of(points)
            return RouteResult(
                geometry = g,
                distanceM = g.lengthM,
                durationS = null,
                providerId = ID,
                profile = profile,
                isFallback = notice != null,
                notice = notice,
            )
        }
    }
}
