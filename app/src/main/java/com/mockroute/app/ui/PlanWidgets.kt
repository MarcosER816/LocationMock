package com.mockroute.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.mockroute.core.plan.SpeedColorScale
import com.mockroute.core.plan.SpeedPlan
import java.util.Locale

private fun kmh(v: Double) = String.format(Locale.US, "%.0f", v * 3.6)

/** Gradient legend: green (slow) → yellow (mean) → red (fast), labelled in km/h. */
@Composable
fun SpeedLegend(scale: SpeedColorScale, modifier: Modifier = Modifier) {
    val colors = remember(scale) {
        List(17) { i -> Color(scale.colorFor(scale.low + (scale.high - scale.low) * i / 16.0)) }
    }
    Surface(modifier, shape = MaterialTheme.shapes.medium, shadowElevation = 3.dp, color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f)) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp).width(150.dp)) {
            Text("Speed (km/h)", style = MaterialTheme.typography.labelSmall)
            androidx.compose.foundation.layout.Box(
                Modifier.fillMaxWidth().height(8.dp).drawWithCache {
                    val brush = Brush.horizontalGradient(colors)
                    onDrawBehind { drawRect(brush) }
                },
            )
            Row(Modifier.fillMaxWidth()) {
                Text(kmh(scale.low), Modifier.weight(1f), style = MaterialTheme.typography.labelSmall)
                Text(kmh(scale.mid), Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center)
                Text(kmh(scale.high), Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.End)
            }
        }
    }
}

/**
 * Speed-over-time mini chart. The plan is down-sampled once (remember); the Path is built in drawWithCache,
 * so drawing allocates nothing per frame.
 */
@Composable
fun SpeedOverTimeChart(plan: SpeedPlan, scale: SpeedColorScale, modifier: Modifier = Modifier) {
    val series = remember(plan) {
        val n = 200
        val vs = FloatArray(n + 1) { i -> plan.stateAtTime(plan.totalTimeS * i / n).speedMps.toFloat() }
        vs
    }
    val vMaxDisplay = remember(plan, scale) { maxOf(plan.maxSpeedMps, scale.high).toFloat().coerceAtLeast(0.1f) }
    val line = MaterialTheme.colorScheme.primary
    val axis = MaterialTheme.colorScheme.outline
    val meanColor = Color(SpeedColorScale.YELLOW)
    Column(modifier) {
        androidx.compose.foundation.layout.Box(
            Modifier.fillMaxWidth().height(64.dp).drawWithCache {
                val w = size.width
                val h = size.height
                val path = Path()
                for (i in series.indices) {
                    val x = w * i / (series.size - 1)
                    val y = h - h * series[i] / vMaxDisplay
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                val meanY = h - h * (scale.mid / vMaxDisplay).toFloat()
                val stroke = Stroke(2.dp.toPx())
                onDrawBehind {
                    drawLine(axis, Offset(0f, h), Offset(w, h), 1f)
                    drawLine(meanColor, Offset(0f, meanY), Offset(w, meanY), 1.5f)
                    drawPath(path, line, style = stroke)
                }
            },
        )
        Row(Modifier.fillMaxWidth()) {
            Text("0:00", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall)
            Text("max ${kmh(plan.maxSpeedMps)} km/h", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center)
            Text(fmtHmm(plan.totalTimeS), Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.End)
        }
    }
}
