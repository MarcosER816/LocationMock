package com.mockroute.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.mockroute.core.dist.BoundedGamma

/**
 * PDF of a [BoundedGamma] on a Canvas, x axis = [lo, hi] in display units.
 *
 * Performance: the density is sampled once per distribution (remember), and the Path is built in
 * drawWithCache, which only re-runs when the size or the sampled data changes. The per-frame draw lambda
 * allocates nothing, so dragging other sliders or scrolling stays smooth.
 */
@Composable
fun GammaChart(
    dist: BoundedGamma,
    format: (Double) -> String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
) {
    val samples = remember(dist) { sampleDensity(dist) }
    val axis = MaterialTheme.colorScheme.outline
    val meanColor = MaterialTheme.colorScheme.tertiary
    Column(modifier) {
        androidx.compose.foundation.layout.Box(
            Modifier
                .fillMaxWidth()
                .height(120.dp)
                .drawWithCache {
                    val w = size.width
                    val h = size.height
                    val n = samples.ys.size
                    val curve = Path()
                    val fill = Path()
                    if (samples.pointMass) {
                        // Point mass: a single spike in the middle.
                        curve.moveTo(w / 2, h)
                        curve.lineTo(w / 2, 0f)
                    } else {
                        fill.moveTo(0f, h)
                        for (i in 0 until n) {
                            val x = w * i / (n - 1)
                            val y = h - h * 0.92f * samples.ys[i]
                            if (i == 0) curve.moveTo(x, y) else curve.lineTo(x, y)
                            fill.lineTo(x, y)
                        }
                        fill.lineTo(w, h)
                        fill.close()
                    }
                    val meanX = w * samples.meanFraction
                    val stroke = Stroke(width = 2.dp.toPx())
                    val meanStroke = Stroke(width = 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f)))
                    val fillColor = color.copy(alpha = 0.18f)
                    val axisWidth = 1.dp.toPx()
                    val meanPath = Path().apply {
                        moveTo(meanX, 0f)
                        lineTo(meanX, h)
                    }
                    onDrawBehind {
                        drawLine(axis, Offset(0f, h), Offset(w, h), axisWidth)
                        if (!samples.pointMass) drawPath(fill, fillColor, style = Fill)
                        drawPath(curve, color, style = stroke)
                        drawPath(meanPath, meanColor, style = meanStroke)
                    }
                },
        )
        Row(Modifier.fillMaxWidth()) {
            Text(format(dist.lo), Modifier.weight(1f), style = MaterialTheme.typography.labelSmall)
            Text(
                "mean ${format(dist.mean)}",
                Modifier.weight(1f),
                style = MaterialTheme.typography.labelSmall,
                color = meanColor,
                textAlign = TextAlign.Center,
            )
            Text(format(dist.hi), Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.End)
        }
    }
}

private class DensitySamples(val ys: FloatArray, val meanFraction: Float, val pointMass: Boolean)

private const val SAMPLES = 160

/** Normalised (max = 1) density at bin centres; the singular spike of shape < 1 is capped for display. */
private fun sampleDensity(d: BoundedGamma): DensitySamples {
    if (d.isPointMass) return DensitySamples(FloatArray(SAMPLES), 0.5f, true)
    val raw = DoubleArray(SAMPLES) { i ->
        val x = d.lo + d.width * (i + 0.5) / SAMPLES
        d.pdf(x).takeIf { it.isFinite() } ?: 0.0
    }
    val sorted = raw.sorted()
    // Cap at 3× the 90th percentile so a shape<1 spike at lo doesn't flatten the rest of the curve.
    val cap = maxOf(sorted[(SAMPLES * 0.9).toInt()] * 3, sorted.last().takeIf { d.shape >= 1 } ?: 0.0)
    val max = raw.maxOf { minOf(it, cap) }.takeIf { it > 0 } ?: 1.0
    val ys = FloatArray(SAMPLES) { (minOf(raw[it], cap) / max).toFloat() }
    return DensitySamples(ys, ((d.mean - d.lo) / d.width).toFloat().coerceIn(0f, 1f), false)
}
