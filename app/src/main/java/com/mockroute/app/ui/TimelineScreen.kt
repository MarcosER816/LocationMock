package com.mockroute.app.ui

import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mockroute.core.geo.GeoMath
import com.mockroute.core.timeline.Issue
import com.mockroute.core.timeline.IssueKind
import com.mockroute.core.timeline.Segment
import com.mockroute.core.timeline.SegmentKind
import com.mockroute.core.timeline.TimelinePlan
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.roundToInt

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun TimelineScreen(vm: MainViewModel, onBack: () -> Unit, onEditOnMap: () -> Unit) {
    val tc = vm.timelines
    val t by tc.timeline.collectAsStateWithLifecycle()
    val planState by tc.plan.collectAsStateWithLifecycle()
    val saved by tc.saved.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pauseEditing by remember { mutableStateOf<Int?>(null) }
    var showSave by remember { mutableStateOf(false) }
    var showLoad by remember { mutableStateOf(false) }
    val plan = (planState as? TimelinePlanState.Planned)?.plan
    val stale = plan?.isStaleFor(t) == true
    val issues = remember(t) { t.issues() }

    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) runCatching { context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } }
            .getOrNull()?.let(vm::importTimeline)
    }

    Scaffold { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(t.name, style = MaterialTheme.typography.headlineSmall)
                    Text(
                        "${t.size} segment(s)" + (plan?.let { " · ${PlanController.durationLabel(it.totalS)}" } ?: ""),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                OutlinedButton(onClick = onBack) { Text("Done") }
            }

            // First A.
            Card {
                Column(Modifier.padding(12.dp)) {
                    Text("Start point A", style = MaterialTheme.typography.titleSmall)
                    Text(
                        if (t.anchorIsRealLocation) "Your real location (captured before mocking)" else "Custom point",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        "Only the first point (A) and the last point are movable. Points shared by two steps are fixed: " +
                            "to change one, delete the steps after it.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = tc::anchorToRealLocation) { Text("Use my location") }
                        TextButton(onClick = {
                            vm.showTimelineOnMap(true)
                            onEditOnMap()
                        }) { Text("Move on map") }
                    }
                }
            }

            // Step cards. Order is fixed (each step depends on the previous one's end point).
            t.segments.forEachIndexed { i, seg ->
                SegmentCard(
                    i, seg, t, plan, stale, issues.filter { it.segment == i },
                    modifier = Modifier,
                    onEdit = {
                        if (seg is Segment.Pause) pauseEditing = i else {
                            vm.editSegment(i)
                            onEditOnMap()
                        }
                    },
                    onDelete = when (i) {
                        t.size - 1 -> tc::removeLast
                        0 -> tc::removeFirst
                        else -> null
                    },
                    onReroll = { tc.reroll(i) },
                    onResetAuto = { tc.resetToAuto(i) },
                    onEnvironment = { tc.cycleEnvironment(i) },
                    onPlayFrom = { vm.playTimeline(i) },
                    onSnap = tc::fixSnap,
                    onConnector = tc::fixConnector,
                )
            }

            Text("Add a step at the end (from ${com.mockroute.core.timeline.Timeline.pointLabel(t.size)})", style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { tc.add(SegmentKind.Route) }) { Text("+ Route") }
                OutlinedButton(onClick = { tc.add(SegmentKind.Indoor) }) { Text("+ Indoor") }
                OutlinedButton(onClick = { tc.add(SegmentKind.Street) }) { Text("+ Street") }
                OutlinedButton(onClick = { tc.add(SegmentKind.Pause) }) { Text("+ Pause") }
            }
            if (t.size > 0) {
                Text("…or before the first step (ending at A)", style = MaterialTheme.typography.labelMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { tc.addAtStart(SegmentKind.Route) }) { Text("+ Route at start") }
                    TextButton(onClick = { tc.addAtStart(SegmentKind.Pause) }) { Text("+ Pause at start") }
                }
            }

            when (val ps = planState) {
                is TimelinePlanState.Planning -> {
                    Text("Planning segment ${ps.segment + 1} of ${ps.of}…", style = MaterialTheme.typography.bodySmall)
                    LinearProgressIndicator(progress = { (ps.segment + 1f) / ps.of.coerceAtLeast(1) }, modifier = Modifier.fillMaxWidth())
                }
                is TimelinePlanState.Planned -> if (stale) {
                    Text("Changed since planning — Plan all again.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                TimelinePlanState.Idle -> Unit
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Button(onClick = tc::planAll, enabled = t.size > 0) { Text("Plan all") }
                OutlinedButton(onClick = {
                    tc.rerollAll()
                    tc.planAll()
                }, enabled = t.size > 0) { Text("Re-roll all") }
                Button(onClick = { vm.playTimeline(0) }, enabled = plan?.playable == true && !stale) { Text("Play") }
                OutlinedButton(onClick = {
                    vm.showTimelineOnMap(true)
                    onEditOnMap()
                }) { Text("Show on map") }
            }

            Text("Files", style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedButton(onClick = { showSave = true }) { Text("Save as…") }
                OutlinedButton(onClick = {
                    tc.refreshSaved()
                    showLoad = true
                }) { Text("Load (${saved.size})") }
                OutlinedButton(onClick = tc::newTimeline) { Text("New") }
                OutlinedButton(onClick = { scope.launch { share(context, vm.exportTimelineJson(), "application/json") } }) { Text("Export JSON") }
                OutlinedButton(onClick = { importer.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }) { Text("Import") }
                OutlinedButton(
                    onClick = { scope.launch { vm.exportTimelineGpx()?.let { share(context, it, "application/gpx+xml") } } },
                    enabled = plan?.playable == true,
                ) { Text("Export GPX") }
            }
        }
    }

    pauseEditing?.let { i ->
        val p = t.segments.getOrNull(i) as? Segment.Pause
        if (p == null) pauseEditing = null else PauseDialog(p, onDismiss = { pauseEditing = null }) { tc.replace(i, it); pauseEditing = null }
    }
    if (showSave) {
        var name by remember { mutableStateOf(t.name) }
        AlertDialog(
            onDismissRequest = { showSave = false },
            title = { Text("Save timeline") },
            text = { OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, singleLine = true) },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        val n = tc.saveAs(name)
                        vm.showMessage("Saved “$n”")
                    }
                    showSave = false
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { showSave = false }) { Text("Cancel") } },
        )
    }
    if (showLoad) {
        AlertDialog(
            onDismissRequest = { showLoad = false },
            title = { Text("Saved timelines") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (saved.isEmpty()) Text("None yet — use “Save as…”.")
                    saved.forEach { e ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = {
                                scope.launch { runCatching { tc.loadSaved(e.name) }.onFailure { vm.showMessage("Can't load: ${it.message}") } }
                                showLoad = false
                            }, modifier = Modifier.weight(1f)) { Text(e.name) }
                            TextButton(onClick = { scope.launch { tc.deleteSaved(e.name) } }) { Text("Delete") }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showLoad = false }) { Text("Close") } },
        )
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun SegmentCard(
    i: Int,
    seg: Segment,
    t: com.mockroute.core.timeline.Timeline,
    plan: TimelinePlan?,
    stale: Boolean,
    issues: List<Issue>,
    modifier: Modifier,
    onEdit: () -> Unit,
    onDelete: (() -> Unit)?,
    onReroll: () -> Unit,
    onResetAuto: () -> Unit,
    onEnvironment: () -> Unit,
    onPlayFrom: () -> Unit,
    onSnap: (Issue) -> Unit,
    onConnector: (Issue) -> Unit,
) {
    val p = plan?.segments?.getOrNull(i)?.takeIf { it.segment == seg }
    val (icon, title) = when (seg) {
        is Segment.Route -> "🛣" to "Route · ${seg.profile.label}"
        is Segment.IndoorZone -> "🏠" to "Indoor zone"
        is Segment.StreetZone -> "🏘" to "Street zone · ${seg.zone.profile.label}"
        is Segment.Pause -> "⏸" to "Pause"
    }
    Card(modifier.fillMaxWidth(), colors = CardDefaults.cardColors()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val from = com.mockroute.core.timeline.Timeline.pointLabel(i)
            val to = com.mockroute.core.timeline.Timeline.pointLabel(i + 1)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("$icon  $from → $to · $title", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            }
            val info = when {
                p != null && p.error == null && plan != null -> {
                    val start = plan.startEpochS + p.startOffsetS
                    "${if (p.distanceM > 0) formatDistance(p.distanceM) + " · " else ""}${PlanController.durationLabel(p.durationS)} · " +
                        "${clock(start)} → ${clock(start + p.durationS)}"
                }
                p?.error != null -> "Can't plan: ${p.error}"
                seg is Segment.Route -> "${formatDistance(GeoMath.haversineMeters(seg.start, seg.end))} straight-line · not planned"
                else -> if (stale) "changed — plan again" else "not planned yet"
            }
            Text(info, style = MaterialTheme.typography.bodySmall, color = if (p?.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            p?.warning?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            // Where the speed & time come from.
            val custom = when (seg) {
                is Segment.Route -> seg.settings != null
                is Segment.IndoorZone -> seg.settings != null
                is Segment.StreetZone -> seg.settings != null
                is Segment.Pause -> false
            }
            if (seg !is Segment.Pause) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (custom) "Speed & time: custom" else "Speed & time: automatic" + (p?.settingsSource?.let { " ($it)" } ?: ""),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    if (custom) TextButton(onClick = onResetAuto) { Text("Reset to automatic") }
                }
            }
            for (issue in issues) {
                Text(issueText(issue, from, to), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                if (issue.kind == IssueKind.NotOnEdge || issue.kind == IssueKind.NotInside) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (t.canSnap(issue)) TextButton(onClick = { onSnap(issue) }) { Text("Snap to edge") }
                        if (t.canInsertConnector(issue)) TextButton(onClick = { onConnector(issue) }) { Text("Add connector route") }
                    }
                    if (!t.canSnap(issue) && !t.canInsertConnector(issue)) {
                        Text("That point is fixed: edit the zone's shape so it touches it, or delete the steps after it.", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onEdit) { Text("Edit") }
                TextButton(onClick = onReroll) { Text("Re-roll") }
                // GPS reception for the noise model: automatic (indoor zone = indoor, others = urban) or forced.
                TextButton(onClick = onEnvironment) { Text("GPS: ${seg.environment?.label ?: "auto"}") }
                TextButton(onClick = onPlayFrom, enabled = plan?.playable == true && !stale) { Text("Play from here") }
                if (onDelete != null) TextButton(onClick = onDelete) { Text("Delete") }
            }
        }
    }
}

private fun issueText(i: Issue, from: String, to: String): String = when (i.kind) {
    IssueKind.RouteIncomplete -> "Set this route's end point $to (Edit)."
    IssueKind.ZoneIncomplete -> "Draw and close this zone's shape (Edit)."
    IssueKind.NotOnEdge -> "Point ${if (i.which == Issue.End.Start) from else to} isn't on the indoor zone's edge."
    IssueKind.NotInside -> "Point ${if (i.which == Issue.End.Start) from else to} is outside the street zone."
}

@Composable
private fun PauseDialog(p: Segment.Pause, onDismiss: () -> Unit, onSave: (Segment.Pause) -> Unit) {
    var lo by remember { mutableFloatStateOf((p.duration.lo / 60).toFloat()) }
    var mean by remember { mutableFloatStateOf((p.duration.center / 60).toFloat()) }
    var hi by remember { mutableFloatStateOf((p.duration.hi / 60).toFloat()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Pause") },
        text = {
            Column {
                Text("Shortest ${lo.roundToInt()} min")
                Slider(value = lo, onValueChange = { lo = it.coerceAtMost(hi) }, valueRange = 0f..120f)
                Text("Typical ${mean.roundToInt()} min")
                Slider(value = mean, onValueChange = { mean = it.coerceIn(lo, hi) }, valueRange = 0f..120f)
                Text("Longest ${hi.roundToInt()} min")
                Slider(value = hi, onValueChange = { hi = it.coerceAtLeast(lo) }, valueRange = 0f..240f)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val l = lo.roundToInt() * 60.0
                val h = maxOf(hi.roundToInt() * 60.0, l)
                onSave(p.copy(duration = p.duration.copy(lo = l, hi = h, center = (mean.roundToInt() * 60.0).coerceIn(l, h))))
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun share(context: Context, file: File, mime: String) {
    val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
    val send = Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(send, "Share ${file.name}").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
