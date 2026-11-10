package com.mockroute.core.plan

import com.mockroute.core.geo.LatLon
import com.mockroute.core.route.RouteGeometry
import kotlin.math.ceil
import kotlin.math.max

/** A piece of the route drawn in one colour. */
data class ColoredChunk(val points: List<LatLon>, val argb: Int, val startM: Double, val endM: Double, val meanSpeedMps: Double)

object PlanRendering {
    /**
     * Splits the route into ~[chunkM]-metre pieces, each coloured by its time-averaged speed
     * (chunk length / time to cross it). Chunks share their end points, so the line is continuous.
     */
    fun chunks(geometry: RouteGeometry, plan: SpeedPlan, scale: SpeedColorScale, chunkM: Double = 30.0): List<ColoredChunk> {
        val d = geometry.lengthM
        if (d <= 0) return emptyList()
        val count = max(1, ceil(d / chunkM).toInt())
        val step = d / count
        val toPlan = if (d > 0) plan.distanceM / d else 1.0
        val out = ArrayList<ColoredChunk>(count)
        var vertex = 1 // next original vertex index to include
        for (c in 0 until count) {
            val s0 = c * step
            val s1 = if (c == count - 1) d else (c + 1) * step
            val pts = ArrayList<LatLon>(4)
            pts += geometry.pointAtDistance(s0).position
            while (vertex < geometry.size - 1 && geometry.cumulativeM(vertex) <= s0) vertex++
            while (vertex < geometry.size - 1 && geometry.cumulativeM(vertex) < s1) {
                pts += geometry.points[vertex]
                vertex++
            }
            pts += geometry.pointAtDistance(s1).position
            val t0 = plan.timeAtDistance(s0 * toPlan).first
            val t1 = plan.timeAtDistance(s1 * toPlan).first
            val mean = if (t1 > t0) (s1 - s0) / (t1 - t0) else plan.timeAtDistance(s0 * toPlan).second
            out += ColoredChunk(pts, scale.colorFor(mean), s0, s1, mean)
        }
        return out
    }
}
