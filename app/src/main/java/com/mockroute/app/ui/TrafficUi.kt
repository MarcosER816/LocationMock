package com.mockroute.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mockroute.core.traffic.TrafficPlanner
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

private val HM = DateTimeFormatter.ofPattern("HH:mm", Locale.US)

internal fun clock(epochS: Double): String = HM.format(Instant.ofEpochSecond(epochS.toLong()).atZone(ZoneId.systemDefault()))

private fun dayLabel(epochS: Long): String {
    val zone = ZoneId.systemDefault()
    val d = Instant.ofEpochSecond(epochS).atZone(zone).toLocalDate()
    val today = LocalDate.now(zone)
    return when (d) {
        today -> "today"
        today.plusDays(1) -> "tomorrow"
        else -> d.toString()
    }
}

/** Car/Bus traffic section of the route sheet. */
@Composable
fun TrafficRow(
    status: TrafficStatus,
    departureEpochS: Long,
    requestsToday: Int,
    onDeparture: (Long) -> Unit,
    onRefetch: () -> Unit,
    cacheInfo: String = "",
) {
    var picking by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AssistChip(onClick = {}, label = { Text("Traffic: TomTom (historic)") })
            if (status is TrafficStatus.Loading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            TextButton(onClick = onRefetch) { Text("Re-fetch") }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { picking = true }) {
                Text("Depart ${clock(departureEpochS.toDouble())} ${dayLabel(departureEpochS)}")
            }
            Text(
                "TomTom today: $requestsToday / 2,500",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        when (status) {
            is TrafficStatus.Ready -> {
                val a = status.analysis
                Text(
                    "Predicted ${fmtHmm(a.totalS)} (−30 min: ${fmtHmm(a.totalMinus30S)}, +30 min: ${fmtHmm(a.totalPlus30S)})" +
                        if (a.timeHeuristic) " · time spread heuristic (±12 %)" else "",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (a.match != com.mockroute.core.traffic.PathMatch.Exact) {
                    Text(
                        "TomTom couldn't follow the exact traced path; using ${a.match.label}. Speeds are mapped onto your route by distance.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
                Text(
                    "${a.requestsMade} request(s), ${a.cacheHits} from cache · ${a.match.label}" +
                        if (a.worst.isNotEmpty()) " · ${a.worst.size} congestion point(s) marked" else " · no notable congestion",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            is TrafficStatus.NoKey -> Text(status.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            is TrafficStatus.Error -> Text(status.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            TrafficStatus.Off -> Unit
            TrafficStatus.Loading -> Text("Fetching typical traffic (≤ ${TrafficPlanner.MAX_REQUESTS_PER_PLAN} requests)…", style = MaterialTheme.typography.bodySmall)
        }
        if (cacheInfo.isNotEmpty() && status !is TrafficStatus.Off) {
            Text(cacheInfo, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
        }
    }
    if (picking) DeparturePicker(departureEpochS, onDismiss = { picking = false }) {
        picking = false
        onDeparture(it)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DeparturePicker(current: Long, onDismiss: () -> Unit, onPick: (Long) -> Unit) {
    val zone = ZoneId.systemDefault()
    val cur = Instant.ofEpochSecond(current).atZone(zone)
    val state = rememberTimePickerState(initialHour = cur.hour, initialMinute = cur.minute, is24Hour = true)
    val today = LocalDate.now(zone)
    var dayOffset by remember { mutableIntStateOf(((cur.toLocalDate().toEpochDay() - today.toEpochDay()).toInt()).coerceIn(0, 6)) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Departure time") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("Today" to 0, "Tomorrow" to 1, "+2 days" to 2).forEach { (label, off) ->
                        FilterChip(selected = dayOffset == off, onClick = { dayOffset = off }, label = { Text(label) })
                    }
                }
                TimePicker(state = state)
                Text(
                    "Typical traffic for that weekday and time. A past time today becomes \"now + 1 min\".",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val t = ZonedDateTime.of(today.plusDays(dayOffset.toLong()), java.time.LocalTime.of(state.hour, state.minute), zone)
                onPick(t.toEpochSecond())
            }) { Text("OK") }
        },
        dismissButton = {
            TextButton(onClick = {
                onPick(System.currentTimeMillis() / 1000 + 60)
            }) { Text("Now + 1 min") }
        },
    )
}
