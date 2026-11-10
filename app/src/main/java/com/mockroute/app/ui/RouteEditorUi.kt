package com.mockroute.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.mockroute.app.net.RoutingChoice
import com.mockroute.core.geo.LatLon
import com.mockroute.core.route.RoutingProfile
import org.maplibre.compose.camera.CameraState
import org.maplibre.spatialk.geojson.Position
import java.util.Locale
import kotlin.math.roundToInt

private enum class ToolbarItem(val label: String) { Explore("Explore"), P2P("A→B"), Streets("Streets"), Free("Free"), Indoor("Indoor") }

@Composable
fun ModeToolbar(
    mode: EditMode,
    snap: Boolean,
    onMode: (EditMode) -> Unit,
    onStreets: () -> Unit,
    onFreeDraw: () -> Unit,
) {
    val selected = when (mode) {
        EditMode.Explore -> ToolbarItem.Explore
        EditMode.PointToPoint -> ToolbarItem.P2P
        EditMode.Streets -> if (snap) ToolbarItem.Streets else ToolbarItem.Free
        EditMode.Indoor -> ToolbarItem.Indoor
    }
    Surface(shape = MaterialTheme.shapes.extraLarge, shadowElevation = 4.dp, modifier = Modifier.fillMaxWidth()) {
        SingleChoiceSegmentedButtonRow(Modifier.padding(4.dp).fillMaxWidth()) {
            ToolbarItem.entries.forEachIndexed { i, item ->
                SegmentedButton(
                    selected = item == selected,
                    onClick = {
                        when (item) {
                            ToolbarItem.Explore -> onMode(EditMode.Explore)
                            ToolbarItem.P2P -> onMode(EditMode.PointToPoint)
                            ToolbarItem.Streets -> onStreets()
                            ToolbarItem.Free -> onFreeDraw()
                            ToolbarItem.Indoor -> onMode(EditMode.Indoor)
                        }
                    },
                    shape = SegmentedButtonDefaults.itemShape(i, ToolbarItem.entries.size),
                    icon = {},
                ) { Text(item.label, style = MaterialTheme.typography.labelMedium, maxLines = 1) }
            }
        }
    }
}

@Composable
fun SegmentSheet(
    state: RouteEditorState,
    onProfile: (RoutingProfile) -> Unit,
    onUndo: () -> Unit,
    onClear: () -> Unit,
    onSnap: (Boolean) -> Unit,
    onPick: (Endpoint) -> Unit,
    onUseRealA: () -> Unit,
    onStartAtReal: () -> Unit,
    onRetry: () -> Unit,
    plan: com.mockroute.core.plan.SegmentPlanSettings?,
    onOpenPlan: () -> Unit,
    planStatus: PlanStatus,
    seed: Long,
    lockTime: Boolean,
    onReroll: () -> Unit,
    onLockTime: (Boolean) -> Unit,
    onPlayPlan: () -> Unit,
    trafficStatus: TrafficStatus,
    departure: Long,
    requestsToday: Int,
    onDeparture: (Long) -> Unit,
    onRefetch: () -> Unit,
    trafficCacheInfo: String = "",
) {
    val result = state.result
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // Summary line.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        if (result != null) formatDistance(result.distanceM) else hint(state),
                        style = if (result != null) MaterialTheme.typography.titleLarge else MaterialTheme.typography.bodyMedium,
                    )
                    if (result != null) {
                        val provider = providerLabel(result.providerId)
                        val eta = result.durationS?.let { " · ${formatDuration(it)} (provider)" } ?: ""
                        Text(
                            "$provider · ${state.profile.label}$eta",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (state.loading) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
            }
            result?.notice?.let {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(it, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = onRetry, enabled = !state.loading) { Text("Retry") }
                }
            }

            // Profile.
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RoutingProfile.entries.forEach { p ->
                    FilterChip(selected = state.profile == p, onClick = { onProfile(p) }, label = { Text(p.label) })
                }
            }

            // Mode-specific controls.
            when (state.mode) {
                EditMode.PointToPoint -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { onPick(Endpoint.A) }) {
                        Text(if (state.pickTarget == Endpoint.A) "Tap map for A…" else "Move A")
                    }
                    if (!state.aIsRealLocation) TextButton(onClick = onUseRealA) { Text("A = my location") }
                    if (state.b != null) TextButton(onClick = onClear) { Text("Clear B") }
                }
                EditMode.Streets -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(onClick = onUndo, enabled = state.waypoints.isNotEmpty()) { Text("Undo") }
                        OutlinedButton(onClick = onClear, enabled = state.waypoints.isNotEmpty()) { Text("Clear") }
                        if (state.waypoints.isEmpty()) TextButton(onClick = onStartAtReal) { Text("Start at my location") }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Free draw (no snapping)", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        Switch(checked = !state.snap, onCheckedChange = { onSnap(!it) })
                    }
                }
                EditMode.Explore, EditMode.Indoor -> Unit
            }

            // Stage 5: traffic (Car/Bus).
            if (result != null && state.profile != RoutingProfile.Walk) {
                TrafficRow(trafficStatus, departure, requestsToday, onDeparture, onRefetch, trafficCacheInfo)
            }

            // Stage 3: speed/time distributions + feasibility.
            if (result != null && result.geometry.size >= 2) {
                val d = result.geometry.lengthM
                val feas = plan?.feasibility(d)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        when {
                            plan == null || feas == null -> "Speed & time not set"
                            feas.feasible -> "✓ ${fmtSpeedRange(plan)} · ${fmtHmm(plan.tMin)}–${fmtHmm(plan.tMax)}"
                            else -> "✗ Not feasible — tap to fix"
                        },
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (feas?.feasible == false) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                    )
                    OutlinedButton(onClick = onOpenPlan) { Text("Speed & time…") }
                }
            }

            // Stage 4: generated plan.
            if (result != null && result.geometry.size >= 2) {
                when (planStatus) {
                    is PlanStatus.Ready -> {
                        val g = planStatus.generated
                        Text(
                            "Plan: ${g.label} · seed ${seed % 10000}",
                            style = MaterialTheme.typography.titleSmall,
                        )
                        g.plan.warning?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        }
                        SpeedOverTimeChart(g.plan, g.scale, Modifier.fillMaxWidth())
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            OutlinedButton(onClick = onReroll) { Text("Re-roll") }
                            Text("Lock time", style = MaterialTheme.typography.bodyMedium)
                            Switch(checked = lockTime, onCheckedChange = onLockTime)
                        }
                        Button(onClick = onPlayPlan, enabled = !state.loading, modifier = Modifier.fillMaxWidth()) {
                            Text("Play plan")
                        }
                    }
                    PlanStatus.Generating -> Text("Generating plan…", style = MaterialTheme.typography.bodySmall)
                    PlanStatus.Infeasible -> Text(
                        "Fix speed & time to get a plan.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    PlanStatus.NoRoute -> Unit
                }
            }
        }
    }
}

private fun fmtSpeedRange(p: com.mockroute.core.plan.SegmentPlanSettings) =
    String.format(Locale.US, "%.0f–%.0f km/h", p.vMin * 3.6, p.vMax * 3.6)

private fun hint(s: RouteEditorState): String = when (s.mode) {
    EditMode.PointToPoint -> when {
        s.a == null -> "Tap “Move A”, then tap the map to set the start"
        s.b == null -> "Tap the map to set B (drag A/B to adjust)"
        else -> "Routing…"
    }
    EditMode.Streets -> when {
        s.waypoints.isEmpty() -> if (s.snap) "Tap the map street by street" else "Tap points to draw freely"
        s.waypoints.size == 1 -> "Tap the next point"
        else -> "Routing…"
    }
    EditMode.Explore, EditMode.Indoor -> ""
}

private fun providerLabel(id: String): String = id.split("+").joinToString(" + ") { part ->
    RoutingChoice.entries.firstOrNull { it.id == part }?.label?.substringBefore(" (") ?: part
}
