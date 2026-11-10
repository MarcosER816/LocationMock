package com.mockroute.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mockroute.core.zone.Polygon2
import com.mockroute.core.zone.WanderMode
import com.mockroute.core.zone.ZoneKind
import com.mockroute.core.route.RoutingProfile
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.util.Locale
import kotlin.math.roundToInt

/** Bottom sheet for the INDOOR zone: shape tool, entry/exit, hotspots (mixture), wandering options, plan + play. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun ZoneSheet(
    vm: MainViewModel,
    state: ZoneEditorState,
    status: ZoneStatus,
    seed: Long,
    lockTime: Boolean,
    onOpenPlan: () -> Unit,
) {
    val z = vm.zone
    val def = state.def
    var show3d by remember { mutableStateOf(false) }
    val streets = def.kind == ZoneKind.Streets
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
    ) {
        Column(
            Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Stage 7: indoor (free wandering) vs streets (roads only); profile for streets.
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                FilterChip(selected = !streets, onClick = { vm.setZoneKind(ZoneKind.Indoor) }, label = { Text("Building") })
                FilterChip(selected = streets, onClick = { vm.setZoneKind(ZoneKind.Streets) }, label = { Text("Streets") })
                if (streets) {
                    Text("·", style = MaterialTheme.typography.bodySmall)
                    RoutingProfile.entries.forEach { p ->
                        FilterChip(selected = def.profile == p, onClick = { vm.setZoneProfile(p) }, label = { Text(p.label) })
                    }
                }
            }
            if (streets && def.closed) RoadsRow(vm, def)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ZoneTool.entries.forEach { t ->
                    val label = when (t) {
                        ZoneTool.Draw -> "Shape"
                        ZoneTool.Entry -> "Start point"
                        ZoneTool.Exit -> "End point"
                        ZoneTool.Hotspots -> "Favourite spots"
                    }
                    FilterChip(
                        selected = state.tool == t,
                        onClick = { z.setTool(t) },
                        enabled = t == ZoneTool.Draw || def.closed,
                        label = { Text(label) },
                    )
                }
            }
            when (state.tool) {
                ZoneTool.Draw -> {
                    Text(
                        if (def.closed) "Shape closed (${area(state)}). Drag corners to adjust, or reopen to add corners."
                        else "Tap the map to add corners (${def.vertices.size}). Drag a corner to move it. " +
                            "Tap the first corner (or Close shape) to finish." +
                            if (streets && (z.lockEntry || z.lockExit)) " Draw the shape around the fixed point: it only closes with that point inside or on the edge." else "",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    state.problem?.takeIf { def.vertices.size >= 3 }?.let {
                        Text(
                            when (it) {
                                Polygon2.Companion.Problem.SelfIntersecting -> "The outline crosses itself — move a corner."
                                Polygon2.Companion.Problem.TooSmall -> "Too small (needs ≥ 4 m²)."
                                Polygon2.Companion.Problem.TooFewVertices -> "Needs at least 3 corners."
                            },
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (!def.closed) {
                            OutlinedButton(onClick = z::undoVertex, enabled = def.vertices.isNotEmpty()) { Text("Undo") }
                            Button(onClick = { z.close()?.let(vm::showMessage) }) { Text("Close shape") }
                        } else {
                            OutlinedButton(onClick = z::reopen) { Text("Reopen") }
                        }
                        TextButton(onClick = z::clear) { Text("Clear") }
                    }
                }
                ZoneTool.Entry, ZoneTool.Exit -> if (state.tool == ZoneTool.Exit && z.lockExit || state.tool == ZoneTool.Entry && z.lockEntry) Text(
                    "This point is fixed by the neighbouring step and can't be moved here.",
                    style = MaterialTheme.typography.bodyMedium,
                ) else Text(
                    (if (state.tool == ZoneTool.Exit && z.exitNotPlaced(def)) "The end point isn't placed yet (it's still on the start point). " else "") +
                    if (streets) "Tap anywhere inside (or on the edge) to place ${if (state.tool == ZoneTool.Entry) "start A" else "end B"}: " +
                        "it snaps to the nearest road. Drag A/B to move them along the roads."
                    else "Tap near a wall to place ${if (state.tool == ZoneTool.Entry) "entry A" else "exit B"}: it snaps to the edge " +
                        "(never inside). Drag A/B to slide them along the walls.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                ZoneTool.Hotspots -> HotspotControls(vm, state, onShow3d = { show3d = true })
            }

            if (def.closed) {
                Text("How you move around", style = MaterialTheme.typography.titleSmall)
                Text(String.format(Locale.US, "Pause at a stop: %.0f %% chance, mean %.0f s", def.dwellProbability * 100, def.dwellMeanS), style = MaterialTheme.typography.bodySmall)
                Slider(value = def.dwellProbability.toFloat(), onValueChange = { z.setDwellProbability(it.toDouble()) }, valueRange = 0f..1f)
                Slider(value = def.dwellMeanS.toFloat(), onValueChange = { z.setDwellMean(it.roundToInt().toDouble()) }, valueRange = 5f..180f)
                if (!streets) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Natural sway", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        Switch(checked = def.wobble, onCheckedChange = z::setWobble)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    WanderMode.entries.forEach { m ->
                        FilterChip(
                            selected = def.mode == m,
                            onClick = { z.setMode(m) },
                            label = {
                                Text(
                                    when {
                                        m == WanderMode.Waypoints -> "Go spot to spot"
                                        streets -> "Stroll around"
                                        else -> "Drift around"
                                    },
                                )
                            },
                        )
                    }
                }
            }

            // Plan.
            when (status) {
                is ZoneStatus.NotReady -> Text(status.reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                is ZoneStatus.Infeasible -> Text(
                    "Not feasible: even the shortest way A→B (${formatDistance(status.shortestM)}) doesn't fit the speed & time settings.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                ZoneStatus.Generating -> Text("Generating…", style = MaterialTheme.typography.bodySmall)
                is ZoneStatus.Ready -> {
                    val g = status.generated
                    Text("Plan: ${g.label} · seed ${seed % 10000}", style = MaterialTheme.typography.titleSmall)
                    g.zone.warning?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                    SpeedOverTimeChart(g.zone.plan, g.scale, Modifier.fillMaxWidth())
                }
            }
            if (def.closed) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = onOpenPlan) { Text("Speed & time") }
                    OutlinedButton(onClick = vm.zonePlanner::reroll) { Text("Shuffle") }
                    Text("Keep duration", style = MaterialTheme.typography.bodySmall)
                    Switch(checked = lockTime, onCheckedChange = vm.zonePlanner::setLockTime)
                }
                Button(onClick = vm::playZone, enabled = status is ZoneStatus.Ready, modifier = Modifier.fillMaxWidth()) { Text("Start") }
            }
        }
    }
    if (show3d) {
        val grid = remember(def) { previewGrid(state) }
        AlertDialog(
            onDismissRequest = { show3d = false },
            title = { Text("Where you'll wander (3D)") },
            text = { if (grid != null) Surface3D(grid, 40) else Text("Close the shape first.") },
            confirmButton = { TextButton(onClick = { show3d = false }) { Text("Close") } },
        )
    }
}

@Composable
private fun RoadsRow(vm: MainViewModel, def: com.mockroute.core.zone.ZoneDefinition) {
    val status by vm.roads.status.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val zoneStatus by vm.zonePlanner.status.collectAsStateWithLifecycle()
    // Re-read the built graph whenever a new zone plan was computed (that's when graphs get (re)built).
    val graph = remember(zoneStatus, status) { vm.roads.lastGraph() }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.weight(1f)) {
            Text(
                when (val s = status) {
                    RoadsStatus.None -> "Roads not loaded"
                    RoadsStatus.Loading -> "Loading roads from OpenStreetMap (1 query)…"
                    is RoadsStatus.Error -> s.message
                    is RoadsStatus.Ready -> {
                        val age = (System.currentTimeMillis() - s.savedAtMs) / 86_400_000
                        val g = graph?.let { " · ${it.edgeCount / 2}+ road segments for ${def.profile.label.lowercase()}" } ?: ""
                        (if (s.fromCache) "Roads cached ${if (age == 0L) "today" else "$age d ago"}" else "Roads loaded now") + g
                    }
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (status is RoadsStatus.Error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (vm.roads.coverageStale(def)) {
                Text("The shape grew beyond the loaded roads: tap Refresh roads.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            Text(
                "Overpass today: ${vm.overpassRequestsToday(settings)} / ${com.mockroute.app.net.OverpassClient.DAILY_LIMIT}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
        OutlinedButton(onClick = vm::refreshRoads, enabled = status !is RoadsStatus.Loading) { Text("Update streets") }
    }
}

@Composable
private fun HotspotControls(vm: MainViewModel, state: ZoneEditorState, onShow3d: () -> Unit) {
    val z = vm.zone
    val comps = state.components()
    Text("Hotspots are where you tend to be. Tap the map to move the selected one, or drag its circle.", style = MaterialTheme.typography.bodySmall)
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        comps.indices.forEach { i ->
            FilterChip(selected = state.selected == i, onClick = { z.select(i) }, label = { Text("${i + 1}") })
        }
        TextButton(onClick = z::addComponent) { Text("+ Add") }
        if (comps.size > 1) TextButton(onClick = { z.removeComponent(state.selected) }) { Text("Remove") }
    }
    val c = comps.getOrNull(state.selected) ?: return
    val i = state.selected
    Text(String.format(Locale.US, "Weight %.2f", c.weight), style = MaterialTheme.typography.bodySmall)
    Slider(value = c.weight.toFloat(), onValueChange = { v -> z.updateComponent(i) { it.copy(weight = v.toDouble()) } }, valueRange = 0.05f..1f)
    Text(String.format(Locale.US, "Spread across %.1f m · along %.1f m", c.sigmaXM, c.sigmaYM), style = MaterialTheme.typography.bodySmall)
    Slider(value = c.sigmaXM.toFloat(), onValueChange = { v -> z.updateComponent(i) { it.copy(sigmaXM = v.toDouble()) } }, valueRange = 0.5f..50f)
    Slider(value = c.sigmaYM.toFloat(), onValueChange = { v -> z.updateComponent(i) { it.copy(sigmaYM = v.toDouble()) } }, valueRange = 0.5f..50f)
    Text(String.format(Locale.US, "Rotation %.0f°", c.rotationDeg), style = MaterialTheme.typography.bodySmall)
    Slider(value = c.rotationDeg.toFloat(), onValueChange = { v -> z.updateComponent(i) { it.copy(rotationDeg = v.roundToInt().toDouble()) } }, valueRange = 0f..180f)
    OutlinedButton(onClick = onShow3d) { Text("3D preview") }
}

private fun area(state: ZoneEditorState): String = state.polygon?.let { String.format(Locale.US, "%.0f m²", it.area) } ?: "—"

/** Mixture density on 40×40 over the polygon's bounding box, normalised to 0..1 (0 outside the zone). */
private fun previewGrid(state: ZoneEditorState): FloatArray? {
    val f = state.frame ?: return null
    val poly = state.polygon ?: return null
    val mix = runCatching { state.def.copy(components = state.components()).mixture(f, poly) }.getOrNull() ?: return null
    val g = mix.grid(40, 40, poly.minX, poly.maxX, poly.minY, poly.maxY, poly)
    val max = g.maxOrNull()?.takeIf { it > 0 } ?: return null
    for (k in g.indices) g[k] /= max
    return g
}
