package com.mockroute.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mockroute.core.dist.BoundedGamma
import com.mockroute.core.plan.AccelerationPreset
import com.mockroute.core.plan.CenterKind
import com.mockroute.core.plan.CoupledSliders
import com.mockroute.core.plan.Feasibility
import com.mockroute.core.plan.GammaSetting
import com.mockroute.core.plan.SegmentPlanSettings
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.roundToInt

private const val KMH = 3.6

private fun fmtKmh(mps: Double) = UiUnits.speed(mps)

/**
 * Durations for the UI: "25 min", "1 h 05 min". (A bare "6:00" was read as 6 minutes on the phone, so the units
 * are always spelled out.)
 */
internal fun fmtHmm(seconds: Double): String {
    if (!seconds.isFinite()) return "∞"
    val m = ceil(seconds / 60.0 - 1e-9).toLong().coerceAtLeast(0)
    return if (m >= 60) String.format(Locale.US, "%d h %02d min", m / 60, m % 60) else "$m min"
}

/** Time slider scales: fine control up to 1 hour by default, 6 hours (the cap) for long trips. */
private val TIME_SCALES = listOf("1 h" to 3600.0, "6 h" to CoupledSliders.T_CAP)

@Composable
fun PlanScreen(vm: MainViewModel, onBack: () -> Unit, indoor: Boolean = false) {
    val routePlan by vm.plan.collectAsStateWithLifecycle()
    val zonePlan by vm.zonePlanSettings.collectAsStateWithLifecycle()
    val editor by vm.editor.state.collectAsStateWithLifecycle()
    val zoneState by vm.zone.state.collectAsStateWithLifecycle()
    val p = (if (indoor) zonePlan else routePlan) ?: return
    // Feasibility distance: the route length, or for a zone the shortest inside path A→B.
    val d = if (indoor) remember(zoneState.def) { vm.zoneShortestPath() } ?: 0.0 else editor.result?.geometry?.lengthM ?: 0.0
    val update: ((SegmentPlanSettings) -> SegmentPlanSettings) -> Unit = if (indoor) vm::updateZonePlan else vm::updatePlan
    val feasibility: (SegmentPlanSettings) -> Feasibility.Result =
        if (indoor) { s -> com.mockroute.core.zone.ZonePlanner.feasibility(d, s) } else { s -> s.feasibility(d) }

    Scaffold { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Speed & time", style = MaterialTheme.typography.headlineSmall)
                    Text(
                        if (indoor) "Indoor zone · shortest way A→B ${formatDistance(d)} (wandering makes it longer)"
                        else "Segment: ${formatDistance(d)} · ${editor.profile.label}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedButton(onClick = onBack) { Text("Done") }
            }

            if (p.autoFromTraffic) {
                Text(
                    "Auto, from traffic (TomTom historic). Edit anything; your edits stay until the next traffic fetch.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            FeasibilityBanner(d, p, feasibility, onApply = { c -> update { it.applyCoupled(c) } })

            GammaCard(
                title = "Speed",
                setting = p.speed,
                rangeMax = CoupledSliders.V_CAP,
                toUi = { it * KMH },
                fromUi = { it / KMH },
                uiStep = 1.0,
                format = ::fmtKmh,
                onChange = { s -> update { it.copy(speed = s) } },
            )
            GammaCard(
                title = "Time",
                setting = p.time,
                rangeMax = CoupledSliders.T_CAP,
                scales = TIME_SCALES,
                toUi = { it / 60.0 },
                fromUi = { it * 60.0 },
                uiStep = 1.0,
                format = ::fmtHmm,
                onChange = { s -> update { it.copy(time = s) } },
            )
            AccelerationCard(p, if (indoor) com.mockroute.core.route.RoutingProfile.Walk else editor.profile, onChange = { a, preset ->
                update { it.copy(aMaxMps2 = a, accelPreset = preset) }
            })
            TextButton(onClick = if (indoor) vm::resetZonePlan else vm::resetPlan) {
                Text(if (indoor) "Reset to indoor defaults" else "Reset to ${editor.profile.label} defaults")
            }
        }
    }
}

@Composable
private fun FeasibilityBanner(
    d: Double,
    p: SegmentPlanSettings,
    feasibility: (SegmentPlanSettings) -> Feasibility.Result,
    onApply: (CoupledSliders) -> Unit,
) {
    val result = remember(d, p) { feasibility(p) }
    val ok = result.feasible
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (ok) Color(0xFFE6F4EA) else Color(0xFFFCE8E6),
            contentColor = Color(0xFF202124),
        ),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (ok) {
                Text("Feasible ✓", style = MaterialTheme.typography.titleMedium, color = Color(0xFF137333))
                Text("Needs ≥ ${fmtKmh(result.requiredAverageMps)} average to arrive by ${fmtHmm(p.tMax)}.")
                Text(
                    "Fastest possible at ${fmtKmh(p.vMax)} with a = ${"%.1f".format(Locale.US, p.aMaxMps2)} m/s²: " +
                        fmtHmm(result.tMinPhysS),
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                Text("Not feasible", style = MaterialTheme.typography.titleMedium, color = Color(0xFFC5221F))
                result.issues.forEach { Text("• " + issueText(it, d, p, result)) }
                if (Feasibility.Issue.TooSlow in result.issues || Feasibility.Issue.NoSpeed in result.issues) {
                    CoupledPanel(d, p, onApply)
                }
            }
        }
    }
}

private fun issueText(i: Feasibility.Issue, d: Double, p: SegmentPlanSettings, r: Feasibility.Result): String = when (i) {
    Feasibility.Issue.TooSlow ->
        "At most ${fmtKmh(p.vMax)} (a = ${"%.1f".format(Locale.US, p.aMaxMps2)} m/s²) the fastest time is " +
            "${fmtHmm(r.tMinPhysS)}, longer than t_max ${fmtHmm(p.tMax)}."
    Feasibility.Issue.TooShort ->
        "Too short: at least ${fmtKmh(p.vMin)} for at least ${fmtHmm(p.tMin)} covers " +
            "${formatDistance(p.vMin * p.tMin)} > ${formatDistance(d)}. Lower v_min or t_min."
    Feasibility.Issue.TimeRangeInverted -> "t_min is above t_max."
    Feasibility.Issue.SpeedRangeInverted -> "v_min is above v_max."
    Feasibility.Issue.NoSpeed -> "v_max is 0."
}

/** The linked (v_max, t_max) sliders. Default proposal: keep v_max, raise t_max (rounded up to the minute). */
@Composable
private fun CoupledPanel(d: Double, p: SegmentPlanSettings, onApply: (CoupledSliders) -> Unit) {
    var c by remember(d, p.aMaxMps2, p.vMax, p.tMax) {
        mutableStateOf(CoupledSliders.propose(d, p.aMaxMps2, p.vMax.coerceAtLeast(0.1), p.tMax))
    }
    if (c.tooLongForCaps) {
        Text(
            "Route too long for the caps: even 100 km/h for 6:00 isn't enough. Shorten the segment.",
            color = Color(0xFFC5221F),
        )
        return
    }
    Text("Fix it (lowering one raises the other):", style = MaterialTheme.typography.labelLarge)
    Text("v_max ${fmtKmh(c.vMax)}")
    Slider(
        value = (c.vMax * KMH).toFloat(),
        onValueChange = { c = c.withVMax(it.roundToInt() / KMH) },
        valueRange = 0f..100f,
    )
    Text("t_max ${fmtHmm(c.tMax)}")
    val tScale = if (maxOf(c.tMax, c.tFloor) > 3600.0) 360f else 60f
    Slider(
        value = (c.tMax / 60.0).toFloat(),
        onValueChange = { c = c.withTMax(it.roundToInt() * 60.0) },
        valueRange = 0f..tScale,
    )
    Text(if (tScale == 60f) "Slider: 0 – 1 h" else "Slider: 0 – 6 h", style = MaterialTheme.typography.bodySmall)
    Button(onClick = { onApply(c) }) { Text("Apply ${fmtKmh(c.vMax)} / ${fmtHmm(c.tMax)}") }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun GammaCard(
    title: String,
    setting: GammaSetting,
    rangeMax: Double,
    toUi: (Double) -> Double,
    fromUi: (Double) -> Double,
    uiStep: Double,
    format: (Double) -> String,
    onChange: (GammaSetting) -> Unit,
    scales: List<Pair<String, Double>>? = null,
) {
    val dist: BoundedGamma = remember(setting) { setting.distribution() }
    // Slider scale (time only): the smallest scale that fits the current range.
    var scaleMax by remember { mutableStateOf(scales?.firstOrNull { it.second >= setting.hi }?.second ?: rangeMax) }
    val sliderMax = if (scales != null) maxOf(scaleMax, setting.hi) else rangeMax
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text("Range ${format(setting.lo)} – ${format(setting.hi)}", style = MaterialTheme.typography.bodyMedium)
            RangeSlider(
                value = toUi(setting.lo).toFloat()..toUi(setting.hi).toFloat(),
                onValueChange = { r ->
                    val lo = fromUi((r.start / uiStep).roundToInt() * uiStep)
                    val hi = fromUi((r.endInclusive / uiStep).roundToInt() * uiStep)
                    onChange(setting.withRange(lo, hi))
                },
                valueRange = 0f..toUi(sliderMax).toFloat(),
            )
            if (scales != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Slider scale:", style = MaterialTheme.typography.bodySmall)
                    scales.forEach { (label, max) ->
                        FilterChip(
                            selected = scaleMax == max,
                            onClick = {
                                scaleMax = max
                                if (setting.hi > max) onChange(setting.withRange(minOf(setting.lo, max), max))
                            },
                            label = { Text("0 – $label") },
                        )
                    }
                }
            }
            SingleChoiceSegmentedButtonRow {
                CenterKind.entries.forEachIndexed { i, k ->
                    SegmentedButton(
                        selected = setting.centerKind == k,
                        onClick = { onChange(setting.copy(centerKind = k)) },
                        shape = SegmentedButtonDefaults.itemShape(i, CenterKind.entries.size),
                        icon = {},
                    ) { Text(k.name) }
                }
            }
            if (setting.hi > setting.lo) {
                Text("${setting.centerKind.name}: ${format(setting.center)}", style = MaterialTheme.typography.bodyMedium)
                Slider(
                    value = setting.center.toFloat(),
                    onValueChange = { onChange(setting.copy(center = it.toDouble())) },
                    valueRange = setting.lo.toFloat()..setting.hi.toFloat(),
                )
                Text(String.format(Locale.US, "Consistency: %.1f (higher = steadier)", setting.shape), style = MaterialTheme.typography.bodyMedium)
                Slider(
                    value = setting.shape.toFloat(),
                    onValueChange = { onChange(setting.copy(shape = (it * 2).roundToInt() / 2.0)) },
                    valueRange = BoundedGamma.MIN_SHAPE.toFloat()..BoundedGamma.MAX_SHAPE.toFloat(),
                )
                val wanted = setting.center
                val achieved = if (setting.centerKind == CenterKind.Mean || setting.shape <= 1.0) dist.mean else dist.mode
                if (kotlin.math.abs(achieved - wanted) > 1e-3 * (setting.hi - setting.lo)) {
                    Text(
                        "With shape ${"%.1f".format(Locale.US, setting.shape)} the ${setting.centerKind.name.lowercase()} " +
                            "can reach at most ${format(achieved)}: raise the shape or widen the range.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (setting.centerKind == CenterKind.Mode && setting.shape <= 1.0) {
                    Text("Mode needs shape > 1; using the mean.", style = MaterialTheme.typography.bodySmall)
                }
            } else {
                Text("Fixed at ${format(setting.lo)} (range has zero width).", style = MaterialTheme.typography.bodySmall)
            }
            GammaChart(dist, format, Modifier.fillMaxWidth().padding(top = 8.dp))
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun AccelerationCard(
    p: SegmentPlanSettings,
    profile: com.mockroute.core.route.RoutingProfile,
    onChange: (Double, AccelerationPreset?) -> Unit,
) {
    var text by rememberSaveable { mutableStateOf(String.format(Locale.US, "%.2f", p.aMaxMps2)) }
    // Follow outside changes (preset chips, profile switch) without fighting the user's typing.
    LaunchedEffect(p.aMaxMps2) {
        if (text.replace(',', '.').toDoubleOrNull() != p.aMaxMps2) text = String.format(Locale.US, "%.2f", p.aMaxMps2)
    }
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Acceleration a_max", style = MaterialTheme.typography.titleMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val presets = AccelerationPreset.forProfile(profile) +
                    AccelerationPreset.entries.filter { it !in AccelerationPreset.forProfile(profile) }
                presets.forEach { preset ->
                    FilterChip(
                        selected = p.accelPreset == preset,
                        onClick = { onChange(preset.aMps2, preset) },
                        label = { Text("${preset.label} ${preset.aMps2}") },
                    )
                }
            }
            p.accelPreset?.let { Text(it.note, style = MaterialTheme.typography.bodySmall) }
            OutlinedTextField(
                value = text,
                onValueChange = { t ->
                    text = t
                    t.replace(',', '.').toDoubleOrNull()?.takeIf { it > 0 && it.isFinite() }?.let { onChange(it, null) }
                },
                label = { Text("a_max (m/s², > 0, no upper cap)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
