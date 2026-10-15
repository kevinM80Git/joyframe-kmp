// SPDX-License-Identifier: Apache-2.0
package io.github.rehaancubess.joyframe.input

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/**
 * Left/right steering for a touch screen, made to be used without looking: the whole area is the
 * control. The finger lands anywhere and that spot is centre; sliding [reach] sideways is full lock.
 * Past full lock the centre follows the finger, so easing back steers back at once instead of after
 * crossing a dead stretch. Writes `input.touchMovement` (x only, linear; shape it where it is read)
 * and releases to centre on lift or cancellation.
 *
 * Steering a car with a two-axis [TouchStick] couples the axes (pushing up shortens the x reach), and
 * its small fixed circle has to be found by eye; this one has neither problem.
 */
@Composable
fun TouchSteer(input: ActionInput, modifier: Modifier = Modifier, reach: Dp = 72.dp, accent: Color = Color(0xff82ead0)) {
    var steer by remember { mutableFloatStateOf(0f) }
    var origin by remember { mutableStateOf<Offset?>(null) }
    DisposableEffect(input) { onDispose { input.touchMovement = Movement() } }
    Canvas(modifier.semantics { contentDescription = "Touch anywhere and slide left or right to steer" }
        .pointerInput(input) {
            val reachPx = reach.toPx().coerceAtLeast(1f)
            awaitEachGesture {
                val down = awaitFirstDown()
                var centre = down.position.x
                fun update(x: Float) {
                    var dx = x - centre
                    // Past full lock, drag the centre along so the way back responds immediately.
                    if (abs(dx) > reachPx) { centre = x - (if (dx > 0) reachPx else -reachPx); dx = x - centre }
                    steer = (dx / reachPx).coerceIn(-1f, 1f)
                    input.touchMovement = Movement(steer, 0f)
                    origin = Offset(centre, down.position.y)
                }
                try {
                    update(down.position.x); down.consume()
                    do {
                        val event = awaitPointerEvent()
                        val pointer = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!pointer.pressed) break
                        update(pointer.position.x); pointer.consume()
                    } while (true)
                } finally { steer = 0f; origin = null; input.touchMovement = Movement() }
            }
        }) {
        val reachPx = reach.toPx()
        val rail = Color(0xff46616b)
        val o = origin
        // Idle: a rail across the middle with arrows at both ends says "slide sideways, anywhere".
        val cy = o?.y ?: (size.height / 2f)
        val cx = o?.x ?: (size.width / 2f)
        val half = if (o == null) size.width * .38f else reachPx
        val h = 10.dp.toPx()
        drawRoundRect(Color(0xff213d49), Offset(cx - half, cy - h / 2), Size(half * 2, h), CornerRadius(h / 2))
        val a = 14.dp.toPx()
        for (dir in listOf(-1f, 1f)) {
            val tip = cx + dir * (half + a * 1.6f)
            drawPath(Path().apply {
                moveTo(tip, cy); lineTo(tip - dir * a, cy - a); lineTo(tip - dir * a, cy + a); close()
            }, if (o != null && steer * dir > .05f) accent else rail)
        }
        drawLine(rail, Offset(cx, cy - h * 1.6f), Offset(cx, cy + h * 1.6f), 2.dp.toPx())
        if (o != null) {
            // Fill from centre to the finger, then the knob under it.
            val x = cx + steer * reachPx
            drawRoundRect(accent.copy(alpha = .55f), Offset(minOf(cx, x), cy - h / 2), Size(abs(x - cx), h), CornerRadius(h / 2))
            drawCircle(accent, 22.dp.toPx(), Offset(x, cy))
        }
    }
}
