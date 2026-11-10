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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
    ) {
        Column(
            Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ZoneTool.entries.forEach { t ->
                    val label = when (t) {
                        ZoneTool.Draw -> "Shape"
                        ZoneTool.Entry -> "Entry A"
                        ZoneTool.Exit -> "Exit B"
                        ZoneTool.Hotspots -> "Hotspots"
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
                            "Tap the first corner (or Close shape) to finish.",
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
                ZoneTool.Entry, ZoneTool.Exit -> Text(
                    "Tap near a wall to place ${if (state.tool == ZoneTool.Entry) "entry A" else "exit B"}: it snaps to the edge " +
                        "(never inside). Drag A/B to slide them along the walls.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                ZoneTool.Hotspots -> HotspotControls(vm, state, onShow3d = { show3d = true })
            }

            if (def.closed) {
                Text("Wandering", style = MaterialTheme.typography.titleSmall)
                Text(String.format(Locale.US, "Pause at a stop: %.0f %% chance, mean %.0f s", def.dwellProbability * 100, def.dwellMeanS), style = MaterialTheme.typography.bodySmall)
                Slider(value = def.dwellProbability.toFloat(), onValueChange = { z.setDwellProbability(it.toDouble()) }, valueRange = 0f..1f)
                Slider(value = def.dwellMeanS.toFloat(), onValueChange = { z.setDwellMean(it.roundToInt().toDouble()) }, valueRange = 5f..180f)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Side-to-side wobble", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Switch(checked = def.wobble, onCheckedChange = z::setWobble)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    WanderMode.entries.forEach { m ->
                        FilterChip(
                            selected = def.mode == m,
                            onClick = { z.setMode(m) },
                            label = { Text(if (m == WanderMode.Waypoints) "Jump between hotspots" else "Drift (Langevin)") },
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
                    OutlinedButton(onClick = onOpenPlan) { Text("Speed & time…") }
                    OutlinedButton(onClick = vm.zonePlanner::reroll) { Text("Re-roll") }
                    Text("Lock time", style = MaterialTheme.typography.bodySmall)
                    Switch(checked = lockTime, onCheckedChange = vm.zonePlanner::setLockTime)
                }
                Button(onClick = vm::playZone, enabled = status is ZoneStatus.Ready, modifier = Modifier.fillMaxWidth()) { Text("Play zone") }
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
