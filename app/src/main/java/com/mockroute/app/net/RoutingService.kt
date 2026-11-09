package com.mockroute.app.net

import android.util.Log
import com.mockroute.app.BuildConfig
import com.mockroute.app.data.AppSettings
import com.mockroute.core.geo.LatLon
import com.mockroute.core.route.RouteResult
import com.mockroute.core.route.RoutingException
import com.mockroute.core.route.RoutingProfile
import com.mockroute.core.route.RoutingProvider
import com.mockroute.core.route.StraightLineProvider
import kotlinx.coroutines.delay

enum class RoutingChoice(val id: String, val label: String) {
    Ors(OpenRouteServiceProvider.ID, "OpenRouteService"),
    GraphHopper(GraphHopperProvider.ID, "GraphHopper"),
    Straight(StraightLineProvider.ID, "Straight lines (offline)"),
    ;

    companion object {
        fun fromId(id: String?): RoutingChoice = entries.firstOrNull { it.id == id } ?: Ors
    }
}

/**
 * Picks the configured provider, serves from [RouteCache], and degrades to straight lines (with a
 * user-facing notice) when routing is unavailable — the app must keep working without keys.
 */
class RoutingService(private val cache: RouteCache) {

    /** Key from Settings (DataStore) wins; otherwise the one baked in at build time from GitHub secrets. */
    fun orsKey(s: AppSettings): String = s.orsApiKey.trim().ifBlank { BuildConfig.ORS_API_KEY.trim() }
    fun ghKey(s: AppSettings): String = s.graphHopperApiKey.trim().ifBlank { BuildConfig.GRAPHHOPPER_API_KEY.trim() }

    /** Everything that changes which router/key is used; when it changes, fallback routes are retried. */
    fun configSignature(s: AppSettings): String = "${s.routingProvider}|${orsKey(s).hashCode()}|${ghKey(s).hashCode()}"

    fun provider(s: AppSettings): RoutingProvider = when (RoutingChoice.fromId(s.routingProvider)) {
        RoutingChoice.Ors -> OpenRouteServiceProvider(orsKey(s))
        RoutingChoice.GraphHopper -> GraphHopperProvider(ghKey(s))
        RoutingChoice.Straight -> StraightLineProvider()
    }

    /**
     * Routes [points] in order. With [snap] = false (free draw) the points are joined directly.
     * Never throws for routing problems: returns a straight-line fallback carrying a notice instead.
     */
    suspend fun route(points: List<LatLon>, profile: RoutingProfile, snap: Boolean, settings: AppSettings): RouteResult {
        if (!snap) return StraightLineProvider.build(points, profile)
        val provider = provider(settings)
        if (provider is StraightLineProvider) return provider.route(points, profile)
        val key = cache.key(provider.id, profile, points)
        cache.get(key)?.let { return it }
        return try {
            routeWithRetry(provider, points, profile).also { cache.put(key, it) }
        } catch (e: RoutingException) {
            Log.w(TAG, "routing failed: ${e.userMessage}")
            val notice = when (e) {
                is RoutingException.MissingKey, is RoutingException.Network -> e.userMessage
                else -> "${e.userMessage} Using a straight line."
            }
            StraightLineProvider.build(points, profile, notice)
        } catch (e: Exception) {
            Log.e(TAG, "routing crashed", e)
            StraightLineProvider.build(points, profile, "Routing unavailable (${e.javaClass.simpleName}). Using a straight line.")
        }
    }

    /** Throws the provider's [RoutingException] (no fallback) — used by Settings → Test routing. */
    suspend fun test(points: List<LatLon>, profile: RoutingProfile, settings: AppSettings): RouteResult =
        routeWithRetry(provider(settings), points, profile)

    /** One retry after a short pause for transient network errors (e.g. right after app start). */
    private suspend fun routeWithRetry(provider: RoutingProvider, points: List<LatLon>, profile: RoutingProfile): RouteResult =
        try {
            provider.route(points, profile)
        } catch (e: RoutingException.Network) {
            delay(1500)
            provider.route(points, profile)
        }

    companion object {
        private const val TAG = "RoutingService"
    }
}
