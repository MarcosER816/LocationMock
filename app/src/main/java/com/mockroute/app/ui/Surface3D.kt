package com.mockroute.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.mockroute.core.plan.SpeedColorScale
import kotlin.math.cos
import kotlin.math.sin

/**
 * 3D perspective wireframe of p(x, y) on an n×n grid (values normalised to 0..1), drawn on a Compose Canvas — no
 * extra dependency. Drag to rotate (yaw/pitch); height scale slider; optional auto-rotate with an fps counter.
 *
 * Performance: the projected vertices go into two FloatArrays that are reused every frame, and the colours are
 * precomputed per vertex, so each frame is just the projection maths plus 2·n·(n−1) drawLine calls (3,120 for 40×40),
 * with no allocation.
 */
@Composable
fun Surface3D(values: FloatArray, n: Int, modifier: Modifier = Modifier) {
    var yaw by remember { mutableFloatStateOf(35f) }
    var pitch by remember { mutableFloatStateOf(55f) }
    var heightScale by remember { mutableFloatStateOf(0.9f) }
    var autoRotate by remember { mutableStateOf(true) }
    var fps by remember { mutableIntStateOf(0) }
    val px = remember(n) { FloatArray(n * n) }
    val py = remember(n) { FloatArray(n * n) }
    // Colour ramp green → yellow → red by height (OKLab, Stage 4 palette).
    val colors = remember(values) {
        val scale = SpeedColorScale(0.0, 0.5, 1.0)
        IntArray(values.size) { scale.colorFor(values[it].toDouble()) }
    }

    LaunchedEffect(autoRotate) {
        var last = 0L
        var frames = 0
        var windowStart = 0L
        while (true) {
            withFrameNanos { now ->
                if (last != 0L && autoRotate) yaw = (yaw + (now - last) / 1e9f * 25f) % 360f
                last = now
                if (windowStart == 0L) windowStart = now
                frames++
                if (now - windowStart >= 500_000_000L) {
                    fps = (frames * 1e9 / (now - windowStart)).toInt()
                    frames = 0
                    windowStart = now
                }
            }
        }
    }

    Column(modifier) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(260.dp)
                .pointerInput(Unit) {
                    detectDragGestures { change, d ->
                        change.consume()
                        autoRotate = false
                        yaw = (yaw + d.x * 0.4f) % 360f
                        pitch = (pitch - d.y * 0.3f).coerceIn(10f, 85f)
                    }
                },
        ) {
            val cy = cos(Math.toRadians(yaw.toDouble())).toFloat()
            val sy = sin(Math.toRadians(yaw.toDouble())).toFloat()
            val cp = cos(Math.toRadians(pitch.toDouble())).toFloat()
            val sp = sin(Math.toRadians(pitch.toDouble())).toFloat()
            val w = size.width
            val h = size.height
            val focal = minOf(w, h) * 1.1f
            val dist = 3.6f
            for (j in 0 until n) for (i in 0 until n) {
                val k = j * n + i
                val x = (i.toFloat() / (n - 1)) * 2f - 1f
                val y = (j.toFloat() / (n - 1)) * 2f - 1f
                val z = values[k] * heightScale
                // Yaw around the vertical axis, then pitch (camera elevation).
                val xr = x * cy - y * sy
                val yr = x * sy + y * cy
                val depth = yr * cp + z * sp
                val up = z * cp - yr * sp
                val s = focal / (dist + depth)
                px[k] = w / 2 + xr * s
                py[k] = h * 0.62f - up * s
            }
            val stroke = 1.6f
            for (j in 0 until n) for (i in 0 until n - 1) {
                val k = j * n + i
                drawLine(Color(colors[k]), Offset(px[k], py[k]), Offset(px[k + 1], py[k + 1]), stroke)
            }
            for (j in 0 until n - 1) for (i in 0 until n) {
                val k = j * n + i
                drawLine(Color(colors[k]), Offset(px[k], py[k]), Offset(px[k + n], py[k + n]), stroke)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Height", style = MaterialTheme.typography.bodySmall)
            Slider(value = heightScale, onValueChange = { heightScale = it }, valueRange = 0.1f..2f, modifier = Modifier.weight(1f))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Auto-rotate", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            Switch(checked = autoRotate, onCheckedChange = { autoRotate = it })
        }
        Text("$fps fps · drag to rotate · ${n}×$n grid", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
    }
}
