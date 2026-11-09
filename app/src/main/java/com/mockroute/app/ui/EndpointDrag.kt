package com.mockroute.app.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.mockroute.core.geo.LatLon
import org.maplibre.compose.camera.CameraState
import org.maplibre.spatialk.geojson.Position

/**
 * Drag A/B markers that are drawn as map layers.
 *
 * Applied to the Box that contains the map. In the Initial pass (parents before children) we look at
 * each touch-down: if it lands within 36 dp of A or B — positions computed fresh from the map projection
 * at that moment, so they can never be stale — we consume the gesture (Compose's AndroidView interop then
 * stops feeding it to the map, so the map doesn't pan) and track the finger. Any other touch is left
 * untouched and the map pans/zooms as usual.
 */
fun Modifier.endpointDrag(
    enabled: Boolean,
    cameraState: CameraState,
    a: LatLon?,
    b: LatLon?,
    onPreview: (Pair<Endpoint, LatLon>?) -> Unit,
    onMoved: (Endpoint, LatLon) -> Unit,
): Modifier = if (!enabled) this else composed {
    val currentA by rememberUpdatedState(a)
    val currentB by rememberUpdatedState(b)
    val preview by rememberUpdatedState(onPreview)
    val moved by rememberUpdatedState(onMoved)
    pointerInput(cameraState) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val projection = cameraState.projection ?: return@awaitEachGesture

            fun toPx(p: LatLon): Offset {
                val d = projection.screenLocationFromPosition(Position(p.lon, p.lat))
                return Offset(d.x.toPx(), d.y.toPx())
            }

            val hit = listOfNotNull(currentA?.let { Endpoint.A to toPx(it) }, currentB?.let { Endpoint.B to toPx(it) })
                .minByOrNull { (it.second - down.position).getDistance() }
                ?.takeIf { (it.second - down.position).getDistance() <= 36.dp.toPx() }
                ?: return@awaitEachGesture

            down.consume()
            // Keep the finger's offset from the marker centre so the marker doesn't jump under the finger.
            val grab = hit.second - down.position
            var last: LatLon? = null
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                event.changes.forEach { it.consume() }
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (!change.pressed) break
                val p = change.position + grab
                val pos = projection.positionFromScreenLocation(DpOffset(p.x.toDp(), p.y.toDp()))
                last = LatLon(pos.latitude.coerceIn(-85.0, 85.0), pos.longitude).also { preview(hit.first to it) }
            }
            preview(null)
            last?.let { moved(hit.first, it) }
        }
    }
}
