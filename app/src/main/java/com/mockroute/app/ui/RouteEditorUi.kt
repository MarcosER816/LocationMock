package com.mockroute.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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

private enum class ToolbarItem(val label: String, val color: Color) {
    Explore("Map", com.mockroute.app.ui.theme.AppColors.Text),
    P2P("Route", com.mockroute.app.ui.theme.AppColors.Blue),
    Streets("Trace", com.mockroute.app.ui.theme.AppColors.Cyan),
    Free("Draw", com.mockroute.app.ui.theme.AppColors.Amber),
    Indoor("Area", com.mockroute.app.ui.theme.AppColors.Purple),
}

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
    // One floating bar; the chosen mode fills with its own colour, the others show it as text.
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
        shadowElevation = 8.dp,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(4.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            ToolbarItem.entries.forEach { item ->
                val on = item == selected
                Box(
                    Modifier
                        .weight(1f)
                        .height(40.dp)
                        .background(if (on) item.color else Color.Transparent, MaterialTheme.shapes.small)
                        .clickable(role = androidx.compose.ui.semantics.Role.Tab) {
                            when (item) {
                                ToolbarItem.Explore -> onMode(EditMode.Explore)
                                ToolbarItem.P2P -> onMode(EditMode.PointToPoint)
                                ToolbarItem.Streets -> onStreets()
                                ToolbarItem.Free -> onFreeDraw()
                                ToolbarItem.Indoor -> onMode(EditMode.Indoor)
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        item.label,
                        style = MaterialTheme.typography.labelLarge,
                        color = if (on) com.mockroute.app.ui.theme.AppColors.OnVibrant else item.color.copy(alpha = if (item == ToolbarItem.Explore) 0.75f else 0.95f),
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/** Panel edge colour: the colour of the mode being edited. */
internal fun sheetAccent(state: RouteEditorState): Color = when (state.mode) {
    EditMode.PointToPoint -> com.mockroute.app.ui.theme.AppColors.Blue
    EditMode.Streets -> if (state.snap) com.mockroute.app.ui.theme.AppColors.Cyan else com.mockroute.app.ui.theme.AppColors.Amber
    EditMode.Indoor -> com.mockroute.app.ui.theme.AppColors.Purple
    EditMode.Explore -> com.mockroute.app.ui.theme.AppColors.Outline
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
    MapPanel(accent = sheetAccent(state)) {
        // Scrolls inside the bottom area so the map keeps at least half of the screen.
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
                EditMode.PointToPoint -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (!state.lockA) {
                        OutlinedButton(onClick = { onPick(Endpoint.A) }) {
                            Text(if (state.pickTarget == Endpoint.A) "Tap map for A…" else "Move A")
                        }
                        if (!state.aIsRealLocation) TextButton(onClick = onUseRealA) { Text("A = my location") }
                    }
                    if (state.b != null && !state.lockB) TextButton(onClick = onClear) { Text("Clear B") }
                    if (state.lockA || state.lockB) {
                        Text(
                            "Start${if (state.lockB) " and end are" else " is"} fixed by the neighbouring steps",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
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
                    OutlinedButton(onClick = onOpenPlan) { Text("Speed & time") }
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
                            OutlinedButton(onClick = onReroll) { Text("Shuffle") }
                            Text("Keep duration", style = MaterialTheme.typography.bodyMedium)
                            Switch(checked = lockTime, onCheckedChange = onLockTime)
                        }
                        Button(onClick = onPlayPlan, enabled = !state.loading, modifier = Modifier.fillMaxWidth()) {
                            Text("Start")
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
