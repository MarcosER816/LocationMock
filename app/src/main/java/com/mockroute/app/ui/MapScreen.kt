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
import org.maplibre.compose.layers.FillLayer
import org.maplibre.compose.layers.SymbolLayer
import org.maplibre.compose.expressions.dsl.feature
import org.maplibre.compose.expressions.dsl.convertToColor
import org.maplibre.compose.expressions.dsl.asString
import org.maplibre.compose.expressions.dsl.offset
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
fun MapScreen(vm: MainViewModel, onOpenSettings: () -> Unit, onOpenPlan: () -> Unit, onOpenTimeline: () -> Unit = {}) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val sim by vm.simulation.collectAsStateWithLifecycle()
    val selected by vm.selected.collectAsStateWithLifecycle()
    val capturing by vm.capturing.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val editor by vm.editor.state.collectAsStateWithLifecycle()
    val plan by vm.plan.collectAsStateWithLifecycle()
    val planStatus by vm.planner.status.collectAsStateWithLifecycle()
    val planSeed by vm.planner.seed.collectAsStateWithLifecycle()
    val lockTime by vm.planner.lockTime.collectAsStateWithLifecycle()
    val trafficStatus by vm.traffic.status.collectAsStateWithLifecycle()
    val zoneState by vm.zone.state.collectAsStateWithLifecycle()
    val zoneStatus by vm.zonePlanner.status.collectAsStateWithLifecycle()
    val zoneSeed by vm.zonePlanner.seed.collectAsStateWithLifecycle()
    val zoneLock by vm.zonePlanner.lockTime.collectAsStateWithLifecycle()
    val indoor = editor.mode == EditMode.Indoor
    val editingSegment by vm.editingSegment.collectAsStateWithLifecycle()
    val timelineOnMap by vm.timelineOnMap.collectAsStateWithLifecycle()
    val timeline by vm.timelines.timeline.collectAsStateWithLifecycle()
    val timelinePlan by vm.timelines.plan.collectAsStateWithLifecycle()
    val showTimeline = timelineOnMap && editor.mode == EditMode.Explore
    val departure by vm.traffic.departure.collectAsStateWithLifecycle()
    // Tap on the plan line → speed & ETA at that point.
    var inspect by remember { mutableStateOf<PlanInspection?>(null) }
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
        if (vm.editor.state.value.mode == EditMode.Indoor) {
            vm.zone.onMapTap(LatLon(pos.latitude, pos.longitude))
            return@rememberUpdatedState ClickResult.Consume
        }
        val ready = vm.planner.status.value as? PlanStatus.Ready
        val hit = ready?.let { inspectPlan(it.generated, LatLon(pos.latitude, pos.longitude), cameraState.metersPerDpAtTarget * 20) }
        if (hit != null) {
            inspect = hit
            ClickResult.Consume
        } else if (vm.editor.state.value.mode != EditMode.Explore) {
            inspect = null
            vm.editor.onMapTap(LatLon(pos.latitude, pos.longitude))
            ClickResult.Consume
        } else {
            inspect = null
            ClickResult.Pass
        }
    }
    val currentOnLongClick by rememberUpdatedState { pos: Position, _: DpOffset ->
        val p = LatLon(pos.latitude, pos.longitude)
        if (vm.editor.state.value.mode == EditMode.Indoor) {
            vm.zone.onMapTap(p)
            return@rememberUpdatedState ClickResult.Consume
        }
        if (vm.editor.state.value.mode != EditMode.Explore) vm.editor.onMapTap(p) else vm.select(p)
        ClickResult.Consume
    }

    Box(Modifier.fillMaxSize()) {
        MaplibreMap(
            // Drag gestures live on the MAP (not the Box): the Box also holds the sheets, and a tap on a sheet
            // button near a projected corner must not be swallowed as a drag.
            modifier = Modifier
                .fillMaxSize()
                .endpointDrag(
                    enabled = editor.mode == EditMode.PointToPoint,
                    cameraState = cameraState,
                    // Fixed (shared) points can't be dragged.
                    a = editor.a.takeUnless { editor.lockA },
                    b = editor.b.takeUnless { editor.lockB },
                    onPreview = { dragPreview = it },
                    onMoved = { which, p -> vm.editor.setEndpoint(which, p) },
                )
                // Stage 8: drag a numbered timeline boundary (a point shared by two segments).
                .mapHandlesDrag(
                    enabled = showTimeline,
                    cameraState = cameraState,
                    // Only the first and the last point are movable; shared points are fixed.
                    handles = timeline.boundaries().mapIndexedNotNull { k, p -> if (timeline.isFixed(k)) null else "b$k" to p },
                    onDrag = { id, p -> id.drop(1).toIntOrNull()?.let { vm.timelines.moveFreePoint(it, p) } },
                    onTap = { id ->
                        val k = id.drop(1).toIntOrNull() ?: 0
                        vm.showMessage("Point ${com.mockroute.core.timeline.Timeline.pointLabel(k)}: drag it to move it")
                    },
                )
                .mapHandlesDrag(
                    enabled = indoor,
                    cameraState = cameraState,
                    handles = zoneHandles(zoneState, vm.zone.lockEntry, vm.zone.lockExit),
                    onDrag = vm.zone::drag,
                    onTap = { id -> vm.zone.onHandleTap(id)?.let(vm::showMessage) },
                ),
            baseStyle = BaseStyle.Uri(TileStyle.chain[styleIndex]),
            cameraState = cameraState,
            // MaplibreMap (0.14) remembers its callbacks from the FIRST composition and never updates them, so
            // these lambdas must not capture composition-time values (e.g. the current mode): they forward to
            // rememberUpdatedState holders that always point at the latest handler.
            onMapLoadFailed = { reason -> currentOnLoadFailed(reason) },
            onMapClick = { pos, offset -> currentOnClick(pos, offset) },
            onMapLongClick = { pos, offset -> currentOnLongClick(pos, offset) },
        ) {
            // Stage 8.1: while editing a timeline step, the other steps in grey (drawn below the editor's layers).
            editingSegment?.let { k -> TimelineContextLayers(timeline, (timelinePlan as? TimelinePlanState.Planned)?.plan, k) }
            val pointLabels = remember(editingSegment) { vm.editingLabels() }
            if (indoor) ZoneLayers(zoneState, zoneStatus, remember(zoneStatus) { vm.roads.lastGraph() }, pointLabels)
            if (showTimeline) TimelineLayers(timeline, (timelinePlan as? TimelinePlanState.Planned)?.plan)

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
            val ready = planStatus as? PlanStatus.Ready
            LineLayer(
                id = "edited-route",
                source = routeSource,
                visible = ready == null,
                color = const(if (editedResult?.isFallback == true) FallbackGrey else RouteBlue),
                width = const(5.dp),
                cap = const(LineCap.Round),
                join = const(LineJoin.Round),
            )
            // Stage 4: speed gradient (data-driven colour per ~30 m chunk) + midpoint duration pill.
            val gradientSource = rememberGeoJsonSource(GeoJsonData.JsonString(ready?.generated?.chunksJson ?: EMPTY_FC))
            LineLayer(
                id = "plan-gradient",
                source = gradientSource,
                color = feature["color"].convertToColor(),
                width = const(5.dp),
                cap = const(LineCap.Round),
                join = const(LineJoin.Round),
            )
            val midSource = rememberGeoJsonSource(GeoJsonData.JsonString(pointJson(ready?.generated?.midpoint)))
            SymbolLayer(
                id = "plan-label",
                source = midSource,
                textField = format(span(const(ready?.generated?.label ?: ""))),
                textFont = const(listOf("Noto Sans Bold")),
                textSize = const(1.0.em),
                textColor = const(Color(0xFF202124)),
                textHaloColor = const(Color.White),
                textHaloWidth = const(4.dp),
                textOffset = offset(0.em, (-1.6).em),
                textAllowOverlap = const(true),
                textIgnorePlacement = const(true),
            )
            // Stage 5: worst traffic points ("≈ 08:14, 14 km/h, +4 min").
            val worstJson = remember(trafficStatus, editor.result?.geometry) {
                val tr = trafficStatus as? TrafficStatus.Ready
                val g = editor.result?.geometry
                if (tr == null || g == null || tr.geometry != g) EMPTY_FC
                else worstPointsJson(tr.analysis, g)
            }
            val worstSource = rememberGeoJsonSource(GeoJsonData.JsonString(worstJson))
            CircleLayer(
                id = "traffic-worst",
                source = worstSource,
                color = const(Color(0xFFC5221F)),
                radius = const(9.dp),
                strokeColor = const(Color.White),
                strokeWidth = const(3.dp),
            )
            SymbolLayer(
                id = "traffic-worst-label",
                source = worstSource,
                textField = format(span(feature["label"].asString())),
                textFont = const(listOf("Noto Sans Bold")),
                textSize = const(0.9.em),
                textColor = const(Color(0xFFC5221F)),
                textHaloColor = const(Color.White),
                textHaloWidth = const(3.dp),
                textOffset = offset(0.em, 1.5.em),
                textAllowOverlap = const(true),
                textIgnorePlacement = const(true),
            )

            val inspectSource = rememberGeoJsonSource(GeoJsonData.JsonString(pointJson(inspect?.point)))
            CircleLayer(
                id = "plan-inspect",
                source = inspectSource,
                color = const(Color.White),
                radius = const(7.dp),
                strokeColor = const(Color(0xFF202124)),
                strokeWidth = const(3.dp),
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
            EndpointLayers("a", if (p2p) shownA else null, pointLabels.first, HandleGreen)
            EndpointLayers("b", if (p2p) shownB else null, pointLabels.second, MockRed)

            // Stage 9 debug overlay: true path (thin black line) vs injected points (purple; red during a jump).
            val noiseSamples by com.mockroute.app.sim.NoiseDebug.samples.collectAsStateWithLifecycle()
            val showNoise = settings?.noise?.debugOverlay == true && sim.isActive && noiseSamples.isNotEmpty()
            val truthJson = remember(noiseSamples, showNoise) { if (showNoise) lineJson(noiseSamples.map { it.truth }) else EMPTY_FC }
            val noisyJson = remember(noiseSamples, showNoise) {
                if (!showNoise) EMPTY_FC
                else noiseSamples.joinToString(",", prefix = """{"type":"FeatureCollection","features":[""", postfix = "]}") { s ->
                    """{"type":"Feature","properties":{"color":"${if (s.multipath) "#D93025" else "#7B1FA2"}"},"geometry":{"type":"Point","coordinates":[${s.reported.lon},${s.reported.lat}]}}"""
                }
            }
            // Accuracy circle (the reported 68 % radius) around the injected position.
            val circleJson = remember(sim.current, showNoise) {
                val c = sim.current
                if (!showNoise || c == null) EMPTY_FC
                else {
                    val ring = (0..36).joinToString(",") { k ->
                        val p = GeoMath.destination(c.position, k * 10.0, c.accuracyM)
                        "[${p.lon},${p.lat}]"
                    }
                    """{"type":"FeatureCollection","features":[{"type":"Feature","properties":{},"geometry":{"type":"Polygon","coordinates":[[$ring]]}}]}"""
                }
            }
            val circleSource = rememberGeoJsonSource(GeoJsonData.JsonString(circleJson))
            FillLayer(id = "noise-accuracy", source = circleSource, color = const(Color(0xFF1A73E8)), opacity = const(0.15f), outlineColor = const(Color(0xFF1A73E8)))
            val truthSource = rememberGeoJsonSource(GeoJsonData.JsonString(truthJson))
            LineLayer(id = "noise-truth", source = truthSource, color = const(Color(0xFF202124)), width = const(2.dp))
            val noisySource = rememberGeoJsonSource(GeoJsonData.JsonString(noisyJson))
            CircleLayer(id = "noise-points", source = noisySource, color = feature["color"].convertToColor(), radius = const(3.dp), opacity = const(0.7f))

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

        (planStatus as? PlanStatus.Ready)?.let { r ->
            SpeedLegend(
                r.generated.scale,
                Modifier.align(Alignment.TopStart).windowInsetsPadding(WindowInsets.safeDrawing).padding(start = 12.dp, top = 76.dp),
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
            SmallFloatingActionButton(onClick = onOpenTimeline) { Text("⏱", style = MaterialTheme.typography.titleMedium) }
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
            editingSegment?.let { k ->
                Surface(
                    Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                    shadowElevation = 4.dp,
                ) {
                    Row(Modifier.padding(horizontal = 14.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Editing timeline segment ${k + 1}", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        TextButton(onClick = { vm.finishEditingSegment(apply = false) }) { Text("Cancel") }
                        Button(onClick = {
                            vm.finishEditingSegment(apply = true)
                            onOpenTimeline()
                        }) { Text("Done") }
                    }
                }
            }
            if (showTimeline && editingSegment == null) {
                Surface(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), shape = MaterialTheme.shapes.large, shadowElevation = 4.dp) {
                    Row(Modifier.padding(horizontal = 14.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Timeline “${timeline.name}”: drag the first or last point to move it", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                        TextButton(onClick = { vm.showTimelineOnMap(false) }) { Text("Hide") }
                        TextButton(onClick = onOpenTimeline) { Text("Open") }
                    }
                }
            }
            inspect?.let { ins ->
                Surface(
                    Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    shape = MaterialTheme.shapes.large,
                    shadowElevation = 4.dp,
                ) {
                    Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(ins.text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        TextButton(onClick = { inspect = null }) { Text("OK") }
                    }
                }
            }
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
                        is TrajectorySpec.Plan -> (spec.plan.distanceM - spec.plan.stateAtTime(sim.elapsedSeconds).distanceM).coerceAtLeast(0.0)
                        is TrajectorySpec.Zone -> (spec.zone.plan.distanceM - spec.zone.plan.stateAtTime(sim.elapsedSeconds).distanceM).coerceAtLeast(0.0)
                        is TrajectorySpec.Timeline -> null
                        else -> null
                    },
                    onPause = vm::pause,
                    onResume = vm::resume,
                    onStop = vm::stop,
                    onSkip = if (sim.spec is TrajectorySpec.Timeline) vm::skipSegment else null,
                    timelineLabel = (sim.spec as? TrajectorySpec.Timeline)?.let { "${it.trajectory.labelAt(sim.elapsedSeconds)} · " +
                        "${(100 * sim.elapsedSeconds / it.trajectory.durationSeconds).toInt().coerceIn(0, 100)} %" },
                )
            }
            if (indoor && !sim.isActive) {
                ZoneSheet(vm, zoneState, zoneStatus, zoneSeed, zoneLock, onOpenPlan = onOpenPlan)
            }
            if (editing && !indoor && !sim.isActive) {
                SegmentSheet(
                    state = editor,
                    onProfile = vm.editor::setProfile,
                    onUndo = vm.editor::undo,
                    onClear = vm.editor::clear,
                    onSnap = vm.editor::setSnap,
                    onPick = vm.editor::pickNext,
                    onUseRealA = { vm.editor.useRealLocationAsA(vm.realLatLon()) },
                    onStartAtReal = { vm.realLatLon()?.let(vm.editor::addWaypoint) },
                    onRetry = vm.editor::refresh,
                    plan = plan,
                    onOpenPlan = onOpenPlan,
                    planStatus = planStatus,
                    seed = planSeed,
                    lockTime = lockTime,
                    onReroll = vm.planner::reroll,
                    onLockTime = vm.planner::setLockTime,
                    onPlayPlan = vm::playPlan,
                    trafficStatus = trafficStatus,
                    departure = departure,
                    requestsToday = vm.traffic.requestsToday(settings),
                    onDeparture = vm.traffic::setDeparture,
                    onRefetch = vm.traffic::refetchNow,
                    trafficCacheInfo = vm.trafficCacheInfo(trafficStatus),
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

private fun worstPointsJson(a: com.mockroute.core.traffic.TrafficAnalysis, g: com.mockroute.core.route.RouteGeometry): String {
    if (a.worst.isEmpty()) return EMPTY_FC
    return a.worst.joinToString(",", prefix = """{"type":"FeatureCollection","features":[""", postfix = "]}") { w ->
        val p = g.pointAtDistance(w.atM).position
        val label = String.format(
            Locale.US, "≈ %s, %.0f km/h, +%d min", clock(w.arrivalEpochS), w.speedMps * 3.6, Math.round(w.delayS / 60.0),
        )
        """{"type":"Feature","properties":{"label":"$label"},"geometry":{"type":"Point","coordinates":[${p.lon},${p.lat}]}}"""
    }
}

class PlanInspection(val point: LatLon, val text: String)

/** Nearest point of the plan to [tap] if within [thresholdM]: speed there and ETA from the start. */
private fun inspectPlan(g: GeneratedPlan, tap: LatLon, thresholdM: Double): PlanInspection? {
    val (along, off) = g.geometry.project(tap)
    if (off > thresholdM.coerceAtLeast(5.0)) return null
    val (t, v) = g.plan.timeAtDistance(along * g.plan.distanceM / g.geometry.lengthM)
    val text = String.format(
        Locale.US, "%s from start · %.1f km/h · ETA +%s",
        formatDistance(along), v * 3.6, fmtHmm(t),
    )
    return PlanInspection(g.geometry.pointAtDistance(along).position, text)
}

@Composable
internal fun EndpointLayers(id: String, point: LatLon?, label: String, color: Color) {
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
    onSkip: (() -> Unit)? = null,
    timelineLabel: String? = null,
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
                timelineLabel?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                point?.let {
                    Text(
                        String.format(Locale.US, "%.5f, %.5f", it.lat, it.lon) +
                            (remainingM?.let { r -> " · ${formatDistance(r)} left" } ?: ""),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            onSkip?.let { TextButton(onClick = it) { Text("Skip") } }
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
