package com.mockroute.core.route

import com.mockroute.core.geo.LatLon
import kotlinx.serialization.Serializable

@Serializable
enum class RoutingProfile(val label: String) {
    Walk("Walk"),
    Car("Car"),
    Bus("Bus"),
}

/** Attributes that hold over [startM, endM) along the route geometry. */
@Serializable
data class RouteSpan(
    val startM: Double,
    val endM: Double,
    val speedLimitKmh: Double? = null,
    val roadClass: String? = null,
)

@Serializable
data class RouteResult(
    val geometry: RouteGeometry,
    /** Provider-reported distance (falls back to geometry length). */
    val distanceM: Double,
    /** Provider-reported travel time in seconds, if any. */
    val durationS: Double? = null,
    val providerId: String,
    val profile: RoutingProfile,
    val spans: List<RouteSpan> = emptyList(),
    /** True when this is a straight-line stand-in for a failed/unavailable router. */
    val isFallback: Boolean = false,
    /** User-facing notice explaining a fallback. */
    val notice: String? = null,
) {
    companion object {
        /** Concatenates consecutive legs (end of leg i == start of leg i+1) into one result. */
        fun concat(legs: List<RouteResult>, profile: RoutingProfile): RouteResult? {
            if (legs.isEmpty()) return null
            var geometry = RouteGeometry.EMPTY
            val spans = mutableListOf<RouteSpan>()
            var durationKnown = true
            var duration = 0.0
            for (leg in legs) {
                val offset = geometry.lengthM
                spans += leg.spans.map { it.copy(startM = it.startM + offset, endM = it.endM + offset) }
                geometry += leg.geometry
                if (leg.durationS == null) durationKnown = false else duration += leg.durationS
            }
            return RouteResult(
                geometry = geometry,
                distanceM = legs.sumOf { it.distanceM },
                durationS = if (durationKnown) duration else null,
                providerId = legs.map { it.providerId }.distinct().joinToString("+"),
                profile = profile,
                spans = spans,
                isFallback = legs.any { it.isFallback },
                notice = legs.firstNotNullOfOrNull { it.notice },
            )
        }
    }
}

/** Disk-cache envelope for routing results (save time stored in the file). */
@Serializable
data class CachedRoute(val savedAtMs: Long, val result: RouteResult)

interface RoutingProvider {
    /** Stable id used for cache keys and display, e.g. "ors". */
    val id: String
    val displayName: String

    /** Routes through [points] (≥ 2) in order. Throws [RoutingException] on failure. */
    suspend fun route(points: List<LatLon>, profile: RoutingProfile): RouteResult
}

/** Routing failures with user-friendly messages. */
sealed class RoutingException(val userMessage: String, cause: Throwable? = null) : Exception(userMessage, cause) {
    class MissingKey(provider: String) : RoutingException("Routing unavailable: no $provider API key. Using straight lines.")
    class Unauthorized(provider: String) : RoutingException("$provider rejected the API key (401). Check the key in Settings.")
    class Forbidden(provider: String) :
        RoutingException("$provider refused the request (403): the API key is invalid/not activated, or today's quota is used up. Check the key in Settings or try again tomorrow.")
    class RateLimited(provider: String) :
        RoutingException("$provider rate limit reached (429). Wait a minute and try again.")
    class NoRoute(provider: String, detail: String?) :
        RoutingException("$provider found no route here${detail?.let { ": $it" } ?: ""}. Try points closer to a road.")
    class Network(provider: String, cause: Throwable?) :
        RoutingException("Can't reach $provider (${cause?.message?.take(140) ?: "offline?"}). Using straight lines.", cause)
    class Http(provider: String, code: Int, detail: String?) :
        RoutingException("$provider error $code${detail?.let { ": $it" } ?: ""}")
    class Parse(provider: String, cause: Throwable?) :
        RoutingException("Unexpected response from $provider.", cause)
}
