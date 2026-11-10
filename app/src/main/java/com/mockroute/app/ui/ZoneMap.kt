package com.mockroute.app.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import com.mockroute.core.geo.LatLon
import com.mockroute.core.zone.GaussianMixture2D
import com.mockroute.core.zone.Vec2
import org.maplibre.compose.camera.CameraState
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
import org.maplibre.spatialk.geojson.Position
import java.util.Locale
import kotlin.math.pow

private const val EMPTY = """{"type":"FeatureCollection","features":[]}"""
private val ZonePurple = Color(0xFF7B1FA2)

/**
 * Drag any of [handles] (id → position, drawn as map layers). Same technique as [endpointDrag]: a touch-down within
 * 36 dp of a handle (positions computed fresh from the projection) is claimed in the Initial pass so the map
 * doesn't pan; [onDrag] gets live positions while the finger moves.
 */
fun Modifier.mapHandlesDrag(
    enabled: Boolean,
    cameraState: CameraState,
    handles: List<Pair<String, LatLon>>,
    onDrag: (String, LatLon) -> Unit,
    onTap: (String) -> Unit = {},
): Modifier = if (!enabled) this else composed {
    val current by rememberUpdatedState(handles)
    val drag by rememberUpdatedState(onDrag)
    val tap by rememberUpdatedState(onTap)
    pointerInput(cameraState) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val projection = cameraState.projection ?: return@awaitEachGesture
            fun px(p: LatLon): Offset = projection.screenLocationFromPosition(Position(p.lon, p.lat)).let { Offset(it.x.toPx(), it.y.toPx()) }
            val hit = current.map { it.first to px(it.second) }
                .minByOrNull { (it.second - down.position).getDistance() }
                ?.takeIf { (it.second - down.position).getDistance() <= 36.dp.toPx() } ?: return@awaitEachGesture
            down.consume()
            val grab = hit.second - down.position
            var moved = false
            val slop = viewConfiguration.touchSlop
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                event.changes.forEach { it.consume() }
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (!change.pressed) break
                if (!moved && (change.position - down.position).getDistance() < slop) continue
                moved = true
                val p = change.position + grab
                val pos = projection.positionFromScreenLocation(DpOffset(p.x.toDp(), p.y.toDp()))
                drag(hit.first, LatLon(pos.latitude.coerceIn(-85.0, 85.0), pos.longitude))
            }
            // A tap on a handle (no drag) never falls through to the map, so it can't add a duplicate corner.
            if (!moved) tap(hit.first)
        }
    }
}

/** All zone map layers (indoor and streets). Must be called inside the MaplibreMap content. */
@Composable
fun ZoneLayers(
    state: ZoneEditorState,
    status: ZoneStatus,
    roads: com.mockroute.core.streets.RoadGraph? = null,
    labels: Pair<String, String> = "A" to "B",
) {
    val def = state.def
    // Stage 7: grey overlay of the usable roads (street zones).
    val roadsJson = remember(roads, def.kind) {
        if (roads == null || def.kind != com.mockroute.core.zone.ZoneKind.Streets) EMPTY else roadsJson(roads, state)
    }
    val roadSource = rememberGeoJsonSource(GeoJsonData.JsonString(roadsJson))
    LineLayer(id = "zone-roads", source = roadSource, color = const(Color(0xFF616161)), width = const(4.dp), opacity = const(0.55f), cap = const(LineCap.Round), join = const(LineJoin.Round))
    // Outline / fill.
    val shapeJson = remember(def.vertices, def.closed) { shapeJson(def.vertices, def.closed) }
    val shape = rememberGeoJsonSource(GeoJsonData.JsonString(shapeJson))
    FillLayer(id = "zone-fill", source = shape, color = const(ZonePurple), opacity = const(0.08f), visible = def.closed)
    LineLayer(id = "zone-outline", source = shape, color = const(ZonePurple), width = const(3.dp), cap = const(LineCap.Round), join = const(LineJoin.Round))

    // Heat overlay of the mixture (40×40 cells clipped to the polygon).
    val heatJson = remember(def) { heatJson(state) }
    val heat = rememberGeoJsonSource(GeoJsonData.JsonString(heatJson))
    FillLayer(id = "zone-heat", source = heat, color = feature["color"].convertToColor(), visible = def.closed && state.tool == ZoneTool.Hotspots)

    // Plan gradient + label.
    val ready = (status as? ZoneStatus.Ready)?.generated
    val grad = rememberGeoJsonSource(GeoJsonData.JsonString(ready?.chunksJson ?: EMPTY))
    LineLayer(id = "zone-plan", source = grad, color = feature["color"].convertToColor(), width = const(3.dp), opacity = const(0.85f), cap = const(LineCap.Round), join = const(LineJoin.Round))
    val labelSource = rememberGeoJsonSource(GeoJsonData.JsonString(pointsJson(listOfNotNull(ready?.midpoint), listOf(ready?.label ?: ""))))
    SymbolLayer(
        id = "zone-plan-label",
        source = labelSource,
        textField = format(span(feature["label"].asString())),
        textFont = const(listOf("Noto Sans Bold")),
        textSize = const(0.95.em),
        textColor = const(Color(0xFF202124)),
        textHaloColor = const(Color.White),
        textHaloWidth = const(4.dp),
        textAllowOverlap = const(true),
        textIgnorePlacement = const(true),
    )

    // Vertices.
    val vertSource = rememberGeoJsonSource(GeoJsonData.JsonString(pointsJson(def.vertices, def.vertices.map { "" })))
    CircleLayer(id = "zone-vertices", source = vertSource, color = const(Color.White), radius = const(6.dp), strokeColor = const(ZonePurple), strokeWidth = const(3.dp))

    // Hotspot centres (numbered); the selected one bigger.
    val comps = remember(def) { state.components() }
    val meansJson = remember(comps, state.selected) { pointsJson(comps.map { it.mean }, comps.indices.map { "${it + 1}" }) }
    val selJson = remember(comps, state.selected) { pointsJson(listOfNotNull(comps.getOrNull(state.selected)?.mean), listOf("")) }
    val means = rememberGeoJsonSource(GeoJsonData.JsonString(if (def.closed && state.tool == ZoneTool.Hotspots) meansJson else EMPTY))
    val sel = rememberGeoJsonSource(GeoJsonData.JsonString(if (def.closed && state.tool == ZoneTool.Hotspots) selJson else EMPTY))
    CircleLayer(id = "zone-mean-selected", source = sel, color = const(Color(0x33FFFFFF)), radius = const(17.dp), strokeColor = const(ZonePurple), strokeWidth = const(2.dp))
    CircleLayer(id = "zone-means", source = means, color = const(ZonePurple), radius = const(11.dp), strokeColor = const(Color.White), strokeWidth = const(2.dp))
    SymbolLayer(
        id = "zone-means-label",
        source = means,
        textField = format(span(feature["label"].asString())),
        textFont = const(listOf("Noto Sans Bold")),
        textSize = const(0.9.em),
        textColor = const(Color.White),
        textAllowOverlap = const(true),
        textIgnorePlacement = const(true),
    )

    // Entry/exit, always on the edge.
    EndpointLayers("zone-a", def.entry, labels.first, Color(0xFF1E8E3E))
    EndpointLayers("zone-b", def.exit, labels.second, Color(0xFFD93025))
}

/** Each undirected road once (twin edges skipped), as LineStrings. */
private fun roadsJson(g: com.mockroute.core.streets.RoadGraph, state: ZoneEditorState): String {
    val f = state.frame ?: return EMPTY
    val sb = StringBuilder("""{"type":"FeatureCollection","features":[""")
    var first = true
    for ((i, e) in g.edges.withIndex()) {
        if (e.twin in 0 until i) continue
        if (!first) sb.append(',')
        first = false
        sb.append("""{"type":"Feature","properties":{},"geometry":{"type":"LineString","coordinates":[""")
        sb.append(coords(e.points.map(f::toLatLon))).append("]}}")
    }
    return sb.append("]}").toString()
}

/** Draggable handles for the current tool. */
fun zoneHandles(state: ZoneEditorState): List<Pair<String, LatLon>> {
    val d = state.def
    val out = ArrayList<Pair<String, LatLon>>()
    d.entry?.let { out += "entry" to it }
    d.exit?.let { out += "exit" to it }
    if (d.closed && state.tool == ZoneTool.Hotspots) state.components().forEachIndexed { i, c -> out += "c$i" to c.mean }
    if (state.tool == ZoneTool.Draw || !d.closed) d.vertices.forEachIndexed { i, v -> out += "v$i" to v }
    return out
}

private fun coords(points: List<LatLon>) = points.joinToString(",") { "[${it.lon},${it.lat}]" }

private fun shapeJson(v: List<LatLon>, closed: Boolean): String = when {
    v.size < 2 -> EMPTY
    closed && v.size >= 3 -> """{"type":"FeatureCollection","features":[{"type":"Feature","properties":{},"geometry":{"type":"Polygon","coordinates":[[${coords(v + v.first())}]]}}]}"""
    else -> """{"type":"FeatureCollection","features":[{"type":"Feature","properties":{},"geometry":{"type":"LineString","coordinates":[${coords(v)}]}}]}"""
}

private fun pointsJson(points: List<LatLon>, labels: List<String>): String =
    if (points.isEmpty()) EMPTY
    else points.indices.joinToString(",", prefix = """{"type":"FeatureCollection","features":[""", postfix = "]}") { i ->
        """{"type":"Feature","properties":{"label":"${labels.getOrElse(i) { "" }}"},"geometry":{"type":"Point","coordinates":[${points[i].lon},${points[i].lat}]}}"""
    }

/** 40×40 density cells inside the polygon as rgba-coloured squares (alpha ∝ density^0.7). */
private fun heatJson(state: ZoneEditorState): String =
    if (!state.def.closed) EMPTY else zoneHeatJson(state.def.copy(components = state.components()), "230,81,0")

/** Heat cells for any zone definition in colour [rgb] ("r,g,b"); used grey for the other timeline steps. */
internal fun zoneHeatJson(def: com.mockroute.core.zone.ZoneDefinition, rgb: String): String {
    val f = def.frame() ?: return EMPTY
    val poly = def.polygon(f) ?: return EMPTY
    if (!def.closed) return EMPTY
    val mix: GaussianMixture2D = runCatching { def.mixture(f, poly) }.getOrNull() ?: return EMPTY
    val n = 40
    val grid = mix.grid(n, n, poly.minX, poly.maxX, poly.minY, poly.maxY, poly)
    val max = grid.maxOrNull()?.takeIf { it > 0 } ?: return EMPTY
    val dx = (poly.maxX - poly.minX) / n
    val dy = (poly.maxY - poly.minY) / n
    val sb = StringBuilder("""{"type":"FeatureCollection","features":[""")
    var first = true
    for (j in 0 until n) for (i in 0 until n) {
        val a = 0.6 * (grid[j * n + i] / max).toDouble().pow(0.7)
        if (a < 0.03) continue
        val x0 = poly.minX + i * dx
        val y0 = poly.minY + j * dy
        val ring = listOf(Vec2(x0, y0), Vec2(x0 + dx, y0), Vec2(x0 + dx, y0 + dy), Vec2(x0, y0 + dy), Vec2(x0, y0)).map(f::toLatLon)
        if (!first) sb.append(',')
        first = false
        sb.append("""{"type":"Feature","properties":{"color":"rgba($rgb,""")
            .append(String.format(Locale.US, "%.3f", a))
            .append(""")"},"geometry":{"type":"Polygon","coordinates":[[""").append(coords(ring)).append("]]}}")
    }
    return sb.append("]}").toString()
}
