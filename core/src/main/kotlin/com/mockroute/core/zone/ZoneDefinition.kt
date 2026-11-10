package com.mockroute.core.zone

import com.mockroute.core.geo.LatLon
import kotlinx.serialization.Serializable

/** How waypoints are chosen. */
@Serializable
enum class WanderMode {
    /** Independent waypoints sampled from the mixture (default). */
    Waypoints,
    /** Waypoints from an overdamped Langevin walk on log p with a reflective boundary: smoother, local wandering. */
    Langevin,
}

/** Mixture component as stored: mean in LatLon so it survives polygon edits; σ in metres; rotation in degrees. */
@Serializable
data class StoredComponent(
    val weight: Double,
    val mean: LatLon,
    val sigmaXM: Double,
    val sigmaYM: Double,
    val rotationDeg: Double = 0.0,
)

/** Everything that defines an INDOOR zone segment (persisted as JSON). */
@Serializable
data class ZoneDefinition(
    val vertices: List<LatLon> = emptyList(),
    val closed: Boolean = false,
    val entry: LatLon? = null,
    val exit: LatLon? = null,
    /** Empty = default (one broad Gaussian at the centroid). */
    val components: List<StoredComponent> = emptyList(),
    val dwellProbability: Double = 0.3,
    val dwellMeanS: Double = 30.0,
    val wobble: Boolean = true,
    val mode: WanderMode = WanderMode.Waypoints,
) {
    /** Local frame anchored at the first vertex. */
    fun frame(): LocalFrame? = vertices.firstOrNull()?.let { LocalFrame(it) }

    fun polygon(frame: LocalFrame): Polygon2? =
        if (vertices.size >= 3 && Polygon2.validate(vertices.map(frame::toLocal)) == null) Polygon2(vertices.map(frame::toLocal)) else null

    fun mixture(frame: LocalFrame, polygon: Polygon2): GaussianMixture2D =
        if (components.isEmpty()) GaussianMixture2D.default(polygon)
        else GaussianMixture2D(
            components.map {
                val m = frame.toLocal(it.mean)
                GaussianComponent(it.weight, m.x, m.y, it.sigmaXM, it.sigmaYM, Math.toRadians(it.rotationDeg))
            },
        )

    fun isReady(): Boolean = closed && entry != null && exit != null && frame()?.let { polygon(it) } != null
}
