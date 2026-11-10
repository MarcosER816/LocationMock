package com.mockroute.core.traffic

import com.mockroute.core.route.RouteGeometry
import kotlinx.serialization.Serializable

/** Car or bus (bus = car speeds with bus rules applied on top, see [TrafficPlanner]). */
@Serializable
enum class TrafficMode { Car, Bus }

/** A congested stretch reported by the provider (distances along OUR route geometry, m). */
@Serializable
data class TrafficSection(
    val startM: Double,
    val endM: Double,
    val effectiveSpeedKmh: Double? = null,
    val delayS: Double = 0.0,
    /** 0 unknown, 1 minor, 2 moderate, 3 major, 4 undefined (TomTom). */
    val magnitude: Int = 0,
    val category: String? = null,
)

/**
 * One provider answer for one departure time: a cumulative time-vs-distance profile along OUR geometry
 * (`offsetsM[i]` reached after `timesS[i]` seconds), the summary times and the congested sections.
 */
@Serializable
data class SpeedProfileSample(
    val departureEpochS: Long,
    val lengthM: Double,
    val travelTimeS: Double,
    val noTrafficTimeS: Double? = null,
    val historicTimeS: Double? = null,
    val liveTimeS: Double? = null,
    val offsetsM: List<Double>,
    val timesS: List<Double>,
    val sections: List<TrafficSection> = emptyList(),
    val provider: String,
) {
    init {
        require(offsetsM.size == timesS.size && offsetsM.size >= 2) { "profile needs ≥ 2 points" }
    }

    /** Seconds after departure at which [s] metres are reached (linear between profile points, clamped). */
    fun timeAt(s: Double): Double {
        if (s <= offsetsM.first()) return timesS.first()
        if (s >= offsetsM.last()) return timesS.last()
        var lo = 0
        var hi = offsetsM.size - 1
        while (hi - lo > 1) {
            val mid = (lo + hi) ushr 1
            if (offsetsM[mid] <= s) lo = mid else hi = mid
        }
        val ds = offsetsM[hi] - offsetsM[lo]
        val f = if (ds > 0) (s - offsetsM[lo]) / ds else 0.0
        return timesS[lo] + f * (timesS[hi] - timesS[lo])
    }
}

interface TrafficProvider {
    val id: String
    val displayName: String

    /** Typical (historic) traffic along [route] for a departure at [departureEpochS] (must be in the future). */
    suspend fun profile(route: RouteGeometry, departureEpochS: Long, mode: TrafficMode): SpeedProfileSample
}

/** Traffic failures with user-facing messages (no key → manual Stage 4 mode). */
sealed class TrafficException(val userMessage: String, cause: Throwable? = null) : Exception(userMessage, cause) {
    class MissingKey(p: String) : TrafficException("Traffic needs a $p API key (Settings → Traffic). Using manual speed & time.")
    class Unauthorized(p: String) : TrafficException("$p rejected the API key. Check it in Settings → Traffic.")
    class QuotaExceeded(p: String) : TrafficException("$p daily quota used up (or rate limited). Try later; manual speed & time still work.")
    class NoRoute(p: String, detail: String?) : TrafficException("$p couldn't follow this route${detail?.let { ": $it" } ?: ""}.")
    class Network(p: String, cause: Throwable?) : TrafficException("Can't reach $p (offline?).", cause)
    class Http(p: String, code: Int, detail: String?) : TrafficException("$p error $code${detail?.let { ": $it" } ?: ""}")
    class Parse(p: String, cause: Throwable?) : TrafficException("Unexpected response from $p.", cause)
}
