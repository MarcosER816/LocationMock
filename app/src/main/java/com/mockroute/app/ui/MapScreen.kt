package com.mockroute.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.unit.DpOffset
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mockroute.app.R
import com.mockroute.app.map.TileStyle
import com.mockroute.app.sim.SimStatus
import com.mockroute.app.sim.TrajectorySpec
import com.mockroute.core.Units
import com.mockroute.core.geo.GeoMath
import com.mockroute.core.geo.LatLon
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.camera.CameraState
import org.maplibre.compose.camera.rememberCameraState
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.value.LineCap
import org.maplibre.compose.expressions.value.LineJoin
import org.maplibre.compose.layers.CircleLayer
import org.maplibre.compose.layers.LineLayer
import org.maplibre.compose.layers.SymbolLayer
import org.maplibre.compose.expressions.dsl.format
import org.maplibre.compose.expressions.dsl.span
import androidx.compose.ui.unit.em
import org.maplibre.compose.map.MaplibreMap
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.rememberGeoJsonSource
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.util.ClickResult
import org.maplibre.spatialk.geojson.Position
import java.util.Locale
import kotlin.time.Duration.Companion.milliseconds

private val RealBlue = Color(0xFF1A73E8)
private val MockRed = Color(0xFFD93025)
private val PickOrange = Color(0xFFF29900)
private val RouteBlue = Color(0xFF1565C0)
private val FallbackGrey = Color(0xFF757575)
private val HandleGreen = Color(0xFF1E8E3E)

@Composable
fun MapScreen(vm: MainViewModel, onOpenSettings: () -> Unit) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val sim by vm.simulation.collectAsStateWithLifecycle()
    val selected by vm.selected.collectAsStateWithLifecycle()
    val capturing by vm.capturing.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val editor by vm.editor.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val editing = editor.mode != EditMode.Explore

    val real = settings?.lastRealLocation
    val cameraState = rememberCameraState(
        CameraPosition(
            target = real?.let { Position(it.lon, it.lat) } ?: Position(0.0, 20.0),
            zoom = if (real != null) 15.0 else 1.5,
        ),
    )
    var styleIndex by rememberSaveable { mutableIntStateOf(0) }

    fun flyTo(p: LatLon, zoom: Double = 16.0) = scope.launch {
        cameraState.animateTo(
            cameraState.position.copy(target = Position(p.lon, p.lat), zoom = maxOf(zoom, cameraState.position.zoom)),
            duration = 800.milliseconds,
        )
    }

    // First launch: grab the real location (no simulation can be running in a fresh process) and fly there.
    var initialFixDone by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!initialFixDone && !sim.isActive) {
            initialFixDone = true
            vm.captureRealLocation { fix -> fix?.let { flyTo(LatLon(it.lat, it.lon)) } }
        }
    }

    // While dragging A/B we show the finger position; the editor is updated once on release.
    var dragPreview by remember { mutableStateOf<Pair<Endpoint, LatLon>?>(null) }
    val shownA = dragPreview?.takeIf { it.first == Endpoint.A }?.second ?: editor.a
    val shownB = dragPreview?.takeIf { it.first == Endpoint.B }?.second ?: editor.b

    val currentOnLoadFailed by rememberUpdatedState { _: String? ->
        if (styleIndex < TileStyle.chain.lastIndex) styleIndex++
    }
    val currentOnClick by rememberUpdatedState { pos: Position, _: DpOffset ->
        if (vm.editor.state.value.mode != EditMode.Explore) {
            vm.editor.onMapTap(LatLon(pos.latitude, pos.longitude))
            ClickResult.Consume
        } else {
            ClickResult.Pass
        }
    }
    val currentOnLongClick by rememberUpdatedState { pos: Position, _: DpOffset ->
        val p = LatLon(pos.latitude, pos.longitude)
        if (vm.editor.state.value.mode != EditMode.Explore) vm.editor.onMapTap(p) else vm.select(p)
        ClickResult.Consume
    }

    Box(
        Modifier
            .fillMaxSize()
            .endpointDrag(
                enabled = editor.mode == EditMode.PointToPoint,
                cameraState = cameraState,
                a = editor.a,
                b = editor.b,
                onPreview = { dragPreview = it },
                onMoved = { which, p -> vm.editor.setEndpoint(which, p) },
            ),
    ) {
        MaplibreMap(
            modifier = Modifier.fillMaxSize(),
            baseStyle = BaseStyle.Uri(TileStyle.chain[styleIndex]),
            cameraState = cameraState,
            // MaplibreMap (0.14) remembers its callbacks from the FIRST composition and never updates them, so
            // these lambdas must not capture composition-time values (e.g. the current mode): they forward to
            // rememberUpdatedState holders that always point at the latest handler.
            onMapLoadFailed = { reason -> currentOnLoadFailed(reason) },
            onMapClick = { pos, offset -> currentOnClick(pos, offset) },
            onMapLongClick = { pos, offset -> currentOnLongClick(pos, offset) },
        ) {
            // Route being edited (simplified for display).
            val editedResult = editor.result
            val routeJson = remember(editedResult?.geometry) {
                lineJson(editedResult?.geometry?.simplify(1.0)?.points ?: emptyList())
            }
            val routeSource = rememberGeoJsonSource(GeoJsonData.JsonString(routeJson))
            LineLayer(
                id = "edited-route-casing",
                source = routeSource,
                color = const(Color.White),
                width = const(8.dp),
                cap = const(LineCap.Round),
                join = const(LineJoin.Round),
            )
            LineLayer(
                id = "edited-route",
                source = routeSource,
                color = const(if (editedResult?.isFallback == true) FallbackGrey else RouteBlue),
                width = const(5.dp),
                cap = const(LineCap.Round),
                join = const(LineJoin.Round),
            )
            val waypointSource = rememberGeoJsonSource(
                GeoJsonData.JsonString(pointsJson(if (editor.mode == EditMode.Streets) editor.waypoints else emptyList())),
            )
            CircleLayer(
                id = "street-waypoints",
                source = waypointSource,
                color = const(RouteBlue),
                radius = const(6.dp),
                strokeColor = const(Color.White),
                strokeWidth = const(2.dp),
            )

            // Path of the running simulation.
            val playingJson = remember(sim.spec) {
                lineJson(
                    when (val spec = sim.spec) {
                        is TrajectorySpec.StraightLine -> listOf(spec.from, spec.to)
                        is TrajectorySpec.Polyline -> spec.geometry.simplify(1.0).points
                        else -> emptyList()
                    },
                )
            }
            val pathSource = rememberGeoJsonSource(GeoJsonData.JsonString(playingJson))
            LineLayer(
                id = "walk-path",
                source = pathSource,
                color = const(MockRed),
                width = const(3.dp),
                opacity = const(0.6f),
            )
            val realSource = rememberGeoJsonSource(GeoJsonData.JsonString(pointJson(real?.let { LatLon(it.lat, it.lon) })))
            CircleLayer(
                id = "real-location",
                source = realSource,
                color = const(RealBlue),
                radius = const(8.dp),
                strokeColor = const(Color.White),
                strokeWidth = const(2.dp),
                opacity = const(if (sim.isActive) 0.4f else 1f),
                strokeOpacity = const(if (sim.isActive) 0.4f else 1f),
            )
            val pickSource = rememberGeoJsonSource(GeoJsonData.JsonString(pointJson(selected)))
            CircleLayer(
                id = "picked-point",
                source = pickSource,
                color = const(PickOrange),
                radius = const(9.dp),
                strokeColor = const(Color.White),
                strokeWidth = const(3.dp),
            )
            // A/B endpoints as real map layers: always pinned to their coordinates.
            val p2p = editor.mode == EditMode.PointToPoint
            EndpointLayers("a", if (p2p) shownA else null, "A", HandleGreen)
            EndpointLayers("b", if (p2p) shownB else null, "B", MockRed)

            val mockSource = rememberGeoJsonSource(GeoJsonData.JsonString(pointJson(sim.current?.position)))
            CircleLayer(
                id = "mock-location",
                source = mockSource,
                color = const(MockRed),
                radius = const(8.dp),
                strokeColor = const(Color.White),
                strokeWidth = const(2.dp),
            )
        }

        // Top: mode toolbar, then the round buttons.
        Column(
            Modifier.align(Alignment.TopCenter).fillMaxWidth().windowInsetsPadding(WindowInsets.safeDrawing).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.End,
        ) {
            ModeToolbar(editor.mode, editor.snap, onMode = vm::setEditMode, onFreeDraw = {
                vm.setEditMode(EditMode.Streets)
                vm.editor.setSnap(false)
            }, onStreets = {
                vm.setEditMode(EditMode.Streets)
                vm.editor.setSnap(true)
            })
            SmallFloatingActionButton(onClick = onOpenSettings) {
                Icon(painterResource(R.drawable.ic_settings), contentDescription = "Settings")
            }
            SmallFloatingActionButton(onClick = {
                if (sim.isActive) {
                    // Don't read the location now: it would be our own mock. Show the stored real fix.
                    real?.let { flyTo(LatLon(it.lat, it.lon)) }
                } else {
                    vm.captureRealLocation { fix -> fix?.let { flyTo(LatLon(it.lat, it.lon)) } }
                }
            }) {
                if (capturing) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    Icon(painterResource(R.drawable.ic_my_location), contentDescription = "My real location", tint = RealBlue)
                }
            }
            if (sim.current != null) {
                SmallFloatingActionButton(onClick = { sim.current?.let { flyTo(it.position) } }) {
                    Icon(painterResource(R.drawable.ic_notification), contentDescription = "Mocked location", tint = MockRed)
                }
            }
        }

        // Bottom: attribution + sheets.
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().windowInsetsPadding(WindowInsets.navigationBars),
        ) {
            message?.let { msg ->
                LaunchedEffect(msg) {
                    delay(4000)
                    vm.consumeMessage()
                }
                Snackbar(
                    modifier = Modifier.padding(12.dp),
                    action = { TextButton(onClick = vm::consumeMessage) { Text("OK") } },
                ) { Text(msg) }
            }
            if (sim.isActive) {
                SimulationCard(
                    status = sim.status,
                    label = if (sim.finished && sim.spec !is TrajectorySpec.Static) "Arrived" else sim.spec?.label ?: "",
                    speedKmh = Units.msToKmh(sim.current?.speedMps ?: 0.0),
                    point = sim.current?.position,
                    remainingM = when (val spec = sim.spec) {
                        is TrajectorySpec.StraightLine -> sim.current?.let { GeoMath.haversineMeters(it.position, spec.to) }
                        is TrajectorySpec.Polyline -> (spec.geometry.lengthM - sim.elapsedSeconds * spec.speedMps).coerceAtLeast(0.0)
                        else -> null
                    },
                    onPause = vm::pause,
                    onResume = vm::resume,
                    onStop = vm::stop,
                )
            }
            if (editing && !sim.isActive) {
                SegmentSheet(
                    state = editor,
                    onProfile = vm.editor::setProfile,
                    onUndo = vm.editor::undo,
                    onClear = vm.editor::clear,
                    onSnap = vm.editor::setSnap,
                    onPick = vm.editor::pickNext,
                    onUseRealA = { vm.editor.useRealLocationAsA(vm.realLatLon()) },
                    onStartAtReal = { vm.realLatLon()?.let(vm.editor::addWaypoint) },
                    onSpeed = vm.editor::setPlaySpeed,
                    onPlay = vm::playRoute,
                    onRetry = vm.editor::refresh,
                )
            }
            selected?.takeIf { !editing }?.let { p ->
                PointSheet(
                    point = p,
                    distanceFromM = (sim.current?.position ?: real?.let { LatLon(it.lat, it.lon) })
                        ?.let { GeoMath.haversineMeters(it, p) },
                    onSimulate = { vm.simulateHere(p) },
                    onWalk = { vm.walkStraightTo(p) },
                    onClose = { vm.select(null) },
                )
            }
            Text(
                TileStyle.ATTRIBUTION,
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFF333333),
                modifier = Modifier
                    .align(Alignment.End)
                    .background(Color.White.copy(alpha = 0.7f))
                    .padding(horizontal = 4.dp)
                    .padding(end = 6.dp, bottom = 2.dp),
            )
        }
    }
}

@Composable
private fun EndpointLayers(id: String, point: LatLon?, label: String, color: Color) {
    val source = rememberGeoJsonSource(GeoJsonData.JsonString(pointJson(point)))
    CircleLayer(
        id = "endpoint-$id",
        source = source,
        color = const(color),
        radius = const(15.dp),
        strokeColor = const(Color.White),
        strokeWidth = const(3.dp),
    )
    SymbolLayer(
        id = "endpoint-$id-label",
        source = source,
        textField = format(span(const(label))),
        textFont = const(listOf("Noto Sans Bold")),
        textSize = const(1.1.em),
        textColor = const(Color.White),
        textAllowOverlap = const(true),
        textIgnorePlacement = const(true),
    )
}

@Composable
private fun PointSheet(
    point: LatLon,
    distanceFromM: Double?,
    onSimulate: () -> Unit,
    onWalk: () -> Unit,
    onClose: () -> Unit,
) {
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Selected point", style = MaterialTheme.typography.titleMedium)
                    Text(
                        String.format(Locale.US, "%.6f, %.6f", point.lat, point.lon),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    distanceFromM?.let {
                        val walk = it / 1.4
                        Text(
                            "${formatDistance(it)} away · walk ≈ ${formatDuration(walk)} at 5 km/h",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                TextButton(onClick = onClose) { Text("Close") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onSimulate, modifier = Modifier.weight(1f)) { Text("Simulate here") }
                FilledTonalButton(onClick = onWalk, modifier = Modifier.weight(1f)) { Text("Walk straight to here") }
            }
        }
    }
}

@Composable
private fun SimulationCard(
    status: SimStatus,
    label: String,
    speedKmh: Double,
    point: LatLon?,
    remainingM: Double?,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
) {
    Surface(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.secondaryContainer,
        shadowElevation = 6.dp,
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    if (status == SimStatus.Paused) "Paused · $label" else label,
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(String.format(Locale.US, "%.1f km/h", speedKmh), style = MaterialTheme.typography.bodyMedium)
                point?.let {
                    Text(
                        String.format(Locale.US, "%.5f, %.5f", it.lat, it.lon) +
                            (remainingM?.let { r -> " · ${formatDistance(r)} left" } ?: ""),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            if (status == SimStatus.Paused) {
                OutlinedButton(onClick = onResume) { Text("Resume") }
            } else {
                OutlinedButton(onClick = onPause) { Text("Pause") }
            }
            Button(onClick = onStop, modifier = Modifier.padding(start = 8.dp)) { Text("Stop") }
        }
    }
}

internal fun formatDistance(m: Double): String =
    if (m < 1000) String.format(Locale.US, "%.0f m", m) else String.format(Locale.US, "%.2f km", m / 1000)

/** h:mm (UI convention), or m:ss under an hour. */
internal fun formatDuration(seconds: Double): String {
    val s = seconds.toLong()
    return if (s >= 3600) String.format(Locale.US, "%d:%02d h", s / 3600, (s % 3600) / 60)
    else String.format(Locale.US, "%d:%02d min", s / 60, s % 60)
}

private fun pointJson(p: LatLon?): String =
    if (p == null) EMPTY_FC
    else """{"type":"FeatureCollection","features":[{"type":"Feature","properties":{},"geometry":{"type":"Point","coordinates":[${p.lon},${p.lat}]}}]}"""

private fun lineJson(points: List<LatLon>): String =
    if (points.size < 2) EMPTY_FC
    else """{"type":"FeatureCollection","features":[{"type":"Feature","properties":{},"geometry":{"type":"LineString","coordinates":[${
        points.joinToString(",") { "[${it.lon},${it.lat}]" }
    }]}}]}"""

private fun pointsJson(points: List<LatLon>): String =
    if (points.isEmpty()) EMPTY_FC
    else """{"type":"FeatureCollection","features":[${
        points.joinToString(",") { """{"type":"Feature","properties":{},"geometry":{"type":"Point","coordinates":[${it.lon},${it.lat}]}}""" }
    }]}"""

private const val EMPTY_FC = """{"type":"FeatureCollection","features":[]}"""
