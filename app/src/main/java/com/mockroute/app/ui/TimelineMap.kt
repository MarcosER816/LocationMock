package com.mockroute.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import com.mockroute.core.geo.LatLon
import com.mockroute.core.timeline.Segment
import com.mockroute.core.timeline.Timeline
import com.mockroute.core.timeline.TimelinePlan
import org.maplibre.compose.expressions.dsl.asString
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.dsl.convertToColor
import org.maplibre.compose.expressions.dsl.feature
import org.maplibre.compose.expressions.dsl.format
import org.maplibre.compose.expressions.dsl.span
import org.maplibre.compose.expressions.value.LineCap
import org.maplibre.compose.expressions.value.LineJoin
import org.maplibre.compose.layers.CircleLayer
import org.maplibre.compose.layers.FillLayer
import org.maplibre.compose.layers.LineLayer
import org.maplibre.compose.layers.SymbolLayer
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.rememberGeoJsonSource

private val SEGMENT_COLORS = listOf("#1565C0", "#2E7D32", "#EF6C00", "#6A1B9A", "#00838F", "#AD1457", "#5D4037")

/** All segments of the timeline (planned paths, or straight dashes when unplanned) + numbered shared points. */
@Composable
fun TimelineLayers(t: Timeline, plan: TimelinePlan?) {
    val pathsJson = remember(t, plan) {
        val feats = t.segments.mapIndexed { i, seg ->
            val planned = plan?.segments?.getOrNull(i)?.takeIf { it.segment == seg }?.path
            val pts: List<LatLon> = when {
                planned != null && planned.size >= 2 -> planned
                seg is Segment.Route -> seg.inputs
                else -> listOf(seg.start, seg.end)
            }
            val coords = pts.joinToString(",") { "[${it.lon},${it.lat}]" }
            """{"type":"Feature","properties":{"color":"${SEGMENT_COLORS[i % SEGMENT_COLORS.size]}"},"geometry":{"type":"LineString","coordinates":[$coords]}}"""
        }
        """{"type":"FeatureCollection","features":[${feats.joinToString(",")}]}"""
    }
    val zonesJson = remember(t) {
        val feats = t.segments.mapIndexedNotNull { i, seg ->
            val v = when (seg) {
                is Segment.IndoorZone -> seg.zone.vertices
                is Segment.StreetZone -> seg.zone.vertices
                else -> emptyList()
            }
            if (v.size < 3) null
            else """{"type":"Feature","properties":{"color":"${SEGMENT_COLORS[i % SEGMENT_COLORS.size]}"},"geometry":{"type":"Polygon","coordinates":[[${(v + v.first()).joinToString(",") { "[${it.lon},${it.lat}]" }}]]}}"""
        }
        """{"type":"FeatureCollection","features":[${feats.joinToString(",")}]}"""
    }
    val pointsJson = remember(t) {
        val feats = t.boundaries().mapIndexed { k, p ->
            """{"type":"Feature","properties":{"label":"${Timeline.pointLabel(k)}"},"geometry":{"type":"Point","coordinates":[${p.lon},${p.lat}]}}"""
        }
        """{"type":"FeatureCollection","features":[${feats.joinToString(",")}]}"""
    }
    val zones = rememberGeoJsonSource(GeoJsonData.JsonString(zonesJson))
    FillLayer(id = "tl-zones", source = zones, color = feature["color"].convertToColor(), opacity = const(0.12f))
    val paths = rememberGeoJsonSource(GeoJsonData.JsonString(pathsJson))
    LineLayer(id = "tl-paths", source = paths, color = feature["color"].convertToColor(), width = const(4.dp), cap = const(LineCap.Round), join = const(LineJoin.Round))
    val points = rememberGeoJsonSource(GeoJsonData.JsonString(pointsJson))
    CircleLayer(id = "tl-points", source = points, color = const(Color(0xFF202124)), radius = const(12.dp), strokeColor = const(Color.White), strokeWidth = const(2.dp))
    SymbolLayer(
        id = "tl-points-label",
        source = points,
        textField = format(span(feature["label"].asString())),
        textFont = const(listOf("Noto Sans Bold")),
        textSize = const(0.9.em),
        textColor = const(Color.White),
        textAllowOverlap = const(true),
        textIgnorePlacement = const(true),
    )
}

/**
 * While editing step [editing]: every OTHER step in grey — routes as their (planned or straight) path, wandering
 * zones as a grey heatmap + outline — and all points with their letters, so the user sees what comes before/after.
 */
@Composable
fun TimelineContextLayers(t: Timeline, plan: TimelinePlan?, editing: Int) {
    val linesJson = remember(t, plan, editing) {
        val feats = t.segments.mapIndexedNotNull { i, seg ->
            if (i == editing || seg is Segment.IndoorZone || seg is Segment.StreetZone || seg is Segment.Pause) return@mapIndexedNotNull null
            val planned = plan?.segments?.getOrNull(i)?.takeIf { it.segment == seg }?.path
            val pts = if (planned != null && planned.size >= 2) planned else (seg as Segment.Route).inputs
            """{"type":"Feature","properties":{},"geometry":{"type":"LineString","coordinates":[${pts.joinToString(",") { "[${it.lon},${it.lat}]" }}]}}"""
        }
        """{"type":"FeatureCollection","features":[${feats.joinToString(",")}]}"""
    }
    val outlinesJson = remember(t, editing) {
        val feats = t.segments.mapIndexedNotNull { i, seg ->
            val v = when (seg) {
                is Segment.IndoorZone -> seg.zone.vertices
                is Segment.StreetZone -> seg.zone.vertices
                else -> emptyList()
            }
            if (i == editing || v.size < 3) null
            else """{"type":"Feature","properties":{},"geometry":{"type":"LineString","coordinates":[${(v + v.first()).joinToString(",") { "[${it.lon},${it.lat}]" }}]}}"""
        }
        """{"type":"FeatureCollection","features":[${feats.joinToString(",")}]}"""
    }
    val heatJson = remember(t, editing) {
        val parts = t.segments.mapIndexedNotNull { i, seg ->
            if (i == editing) return@mapIndexedNotNull null
            when (seg) {
                is Segment.IndoorZone -> zoneHeatJson(seg.zone, "97,97,97")
                is Segment.StreetZone -> zoneHeatJson(seg.zone, "97,97,97")
                else -> null
            }
        }
        // Merge the feature arrays of each zone's FeatureCollection.
        val feats = parts.mapNotNull { j -> j.substringAfter("\"features\":[", "").removeSuffix("]}").takeIf { it.isNotBlank() } }
        """{"type":"FeatureCollection","features":[${feats.joinToString(",")}]}"""
    }
    val pointsJson = remember(t, editing) {
        val feats = t.boundaries().mapIndexedNotNull { k, p ->
            if (k == editing || k == editing + 1) null // the step's own points are drawn by the editor
            else """{"type":"Feature","properties":{"label":"${Timeline.pointLabel(k)}"},"geometry":{"type":"Point","coordinates":[${p.lon},${p.lat}]}}"""
        }
        """{"type":"FeatureCollection","features":[${feats.joinToString(",")}]}"""
    }
    val grey = Color(0xFF757575)
    val heat = rememberGeoJsonSource(GeoJsonData.JsonString(heatJson))
    FillLayer(id = "ctx-heat", source = heat, color = feature["color"].convertToColor())
    val outlines = rememberGeoJsonSource(GeoJsonData.JsonString(outlinesJson))
    LineLayer(id = "ctx-outlines", source = outlines, color = const(grey), width = const(2.dp), opacity = const(0.7f))
    val lines = rememberGeoJsonSource(GeoJsonData.JsonString(linesJson))
    LineLayer(id = "ctx-lines", source = lines, color = const(grey), width = const(4.dp), opacity = const(0.7f), cap = const(LineCap.Round), join = const(LineJoin.Round))
    val points = rememberGeoJsonSource(GeoJsonData.JsonString(pointsJson))
    CircleLayer(id = "ctx-points", source = points, color = const(grey), radius = const(11.dp), strokeColor = const(Color.White), strokeWidth = const(2.dp))
    SymbolLayer(
        id = "ctx-points-label",
        source = points,
        textField = format(span(feature["label"].asString())),
        textFont = const(listOf("Noto Sans Bold")),
        textSize = const(0.85.em),
        textColor = const(Color.White),
        textAllowOverlap = const(true),
        textIgnorePlacement = const(true),
    )
}
