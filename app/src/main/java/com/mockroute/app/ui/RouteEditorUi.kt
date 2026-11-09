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

private enum class ToolbarItem(val label: String) { Explore("Explore"), P2P("A → B"), Streets("Streets"), Free("Free draw") }

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
                        }
                    },
                    shape = SegmentedButtonDefaults.itemShape(i, ToolbarItem.entries.size),
                    icon = {},
                ) { Text(item.label, style = MaterialTheme.typography.labelMedium, maxLines = 1) }
            }
        }
    }
}

/**
 * A labelled circle pinned to [point] that can be dragged; [onMoved] fires once on release.
 *
 * The screen position is computed inside the layout-phase `offset {}` lambda, which reads
 * [CameraState.position]: every camera move re-places the handle without recomposition. (Computing it during
 * composition doesn't work: the handle's inputs don't change while the map pans, so Compose skips it and the
 * handle stays glued to the screen.)
 */
@Composable
fun DragHandle(label: String, point: LatLon, color: Color, cameraState: CameraState, onMoved: (LatLon) -> Unit) {
    val density = LocalDensity.current
    // Screen position while a drag is in progress; null = follow the map.
    var drag by remember(point) { mutableStateOf<DpOffset?>(null) }
    val size = 34.dp

    fun anchor(): DpOffset? {
        drag?.let { return it }
        @Suppress("UNUSED_VARIABLE")
        val cameraRead = cameraState.position // snapshot read → re-layout on camera move
        return cameraState.projection?.screenLocationFromPosition(Position(point.lon, point.lat))
    }

    Box(
        Modifier
            .offset {
                val a = anchor() ?: return@offset IntOffset(-10_000, -10_000)
                IntOffset((a.x - size / 2).roundToPx(), (a.y - size / 2).roundToPx())
            }
            .size(size)
            .background(color, CircleShape)
            .border(3.dp, Color.White, CircleShape)
            .pointerInput(point) {
                detectDragGestures(
                    onDragStart = { drag = anchor() },
                    onDragEnd = {
                        val d = drag
                        val projection = cameraState.projection
                        if (d != null && projection != null) {
                            val pos = projection.positionFromScreenLocation(d)
                            onMoved(LatLon(pos.latitude.coerceIn(-85.0, 85.0), pos.longitude))
                        } else {
                            drag = null
                        }
                    },
                    onDragCancel = { drag = null },
                ) { change, amount ->
                    change.consume()
                    val cur = drag ?: return@detectDragGestures
                    drag = with(density) { DpOffset(cur.x + amount.x.toDp(), cur.y + amount.y.toDp()) }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = Color.White, fontWeight = FontWeight.Bold)
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
    onSpeed: (Double) -> Unit,
    onPlay: () -> Unit,
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
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
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
                EditMode.Explore -> Unit
            }

            // Temporary constant-speed playback.
            if (result != null && result.geometry.size >= 2) {
                Text(
                    String.format(Locale.US, "Play speed: %.0f km/h · ≈ %s", state.playSpeedKmh,
                        formatDuration(result.geometry.lengthM / (state.playSpeedKmh / 3.6))),
                    style = MaterialTheme.typography.bodySmall,
                )
                Slider(
                    value = state.playSpeedKmh.toFloat(),
                    onValueChange = { onSpeed(it.roundToInt().toDouble()) },
                    valueRange = 1f..100f,
                )
                Button(onClick = onPlay, enabled = !state.loading, modifier = Modifier.fillMaxWidth()) {
                    Text("Play at constant speed")
                }
            }
        }
    }
}

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
    EditMode.Explore -> ""
}

private fun providerLabel(id: String): String = id.split("+").joinToString(" + ") { part ->
    RoutingChoice.entries.firstOrNull { it.id == part }?.label?.substringBefore(" (") ?: part
}
