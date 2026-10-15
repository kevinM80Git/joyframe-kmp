// SPDX-License-Identifier: Apache-2.0
package io.github.rehaancubess.joyframe.input.phone

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.rehaancubess.joyframe.input.ActionInput
import io.github.rehaancubess.joyframe.input.DeviceTilt
import io.github.rehaancubess.joyframe.input.TouchStick
import io.github.rehaancubess.joyframe.input.TouchSteer

/**
 * The phone's side of a [PhonePads] pad, observable by Compose: what the device told it ([info]), the
 * link ([connected], [roundTripMs]) and what the player is pressing. [message] is the `in …` line to
 * send (see [PhonePadProtocol]); a client sends it whenever it changes and a few times a second.
 */
class PhonePadUi {
    var info by mutableStateOf(PhonePadInfo())
    var connected by mutableStateOf(false)
    var roundTripMs by mutableIntStateOf(-1)
    /** Gas held down for the player (on by default, like a TV remote); off shows a GAS button. */
    var autoGas by mutableStateOf(true)
    /** Steering by tilting the phone ([DeviceTilt]) instead of the stick. */
    var tilt by mutableStateOf(false)
    /** The steering input: Joyframe's [TouchSteer] (or [TouchStick]) writes [ActionInput.touchMovement]. */
    val stick = ActionInput()
    private val held = IntArray(16)
    /** Buttons pressed since the last [message]: a tap shorter than a frame still goes out once. */
    private var tapped = 0
    private var brakeHeld = 0
    private var gasHeld = 0

    /**
     * Called as any control goes down: the client buzzes the phone briefly, so a thumb that found a
     * button knows it without looking (browsers that can vibrate; iPhones cannot).
     */
    var onPress: () -> Unit = {}

    /** A control went down ([down]) or up. Buttons use [PhonePadProtocol] bits; [BRAKE] and [GAS] are triggers. */
    fun press(bit: Int, down: Boolean) {
        if (down) onPress()
        val d = if (down) 1 else -1
        when (bit) {
            BRAKE -> brakeHeld = (brakeHeld + d).coerceAtLeast(0)
            GAS -> gasHeld = (gasHeld + d).coerceAtLeast(0)
            else -> {
                val i = bit.countTrailingZeroBits()
                if (i < held.size) held[i] = (held[i] + d).coerceAtLeast(0)
                if (down) tapped = tapped or bit
            }
        }
    }

    fun message(): String {
        val drive = info.layout == PhoneLayout.DRIVE
        var mask = tapped
        tapped = 0
        for (i in held.indices) if (held[i] > 0) mask = mask or (1 shl i)
        val brake = if (drive && brakeHeld > 0) 1 else 0
        val gas = when { !drive -> 0; autoGas -> 1 - brake; gasHeld > 0 -> 1; else -> 0 }
        val steer = if (!drive) 0f else if (tilt) DeviceTilt.steer else stick.touchMovement.x
        val s = (kotlin.math.round(steer.coerceIn(-1f, 1f) * 1000f) / 1000f)
        return "in $s $gas $brake $mask"
    }

    companion object {
        /** Pseudo-bits for [press]: the brake and gas "triggers". */
        const val BRAKE = 1 shl 20
        const val GAS = 1 shl 21
    }
}

/** Joyframe's controller colours (the same as [TouchStick]). */
private object Pad {
    val Back = Color(0xff102730)
    val Base = Color(0xff213d49)
    val Ring = Color(0xff46616b)
    val Mint = Color(0xff82ead0)
    val Text = Color(0xffe6fff8)
    val A = Color(0xff3ddc84); val B = Color(0xffff5a5a); val X = Color(0xff3d9bff); val Y = Color(0xffffd23a)
    val Boost = Color(0xffc04fff)
    val Zone = Color(0xff16323d)
}

/**
 * A phone controller for [PhonePads], held sideways. While [PhonePadInfo.layout] is DRIVE: the left
 * half steers ([TouchSteer]: touch anywhere, slide; or tilt via [DeviceTilt], tap to re-centre), the
 * right half is BRAKE and BOOST (and GAS when AUTO GAS is off), with AUTO GAS / TILT / RESET / PAUSE
 * along the top. Otherwise a D-pad, Ⓐ Ⓑ Ⓧ Ⓨ and START. A header shows the player's label in their
 * colour, the device's status line and the round trip. Multi-touch: each control tracks its own finger.
 */
@Composable
fun PhonePadController(ui: PhonePadUi, modifier: Modifier = Modifier) {
    val accent = Color(ui.info.color.toLong() or 0xFF000000L)
    Column(modifier.fillMaxSize().background(Pad.Back)) {
        // Header: who, what's happening, how fast the link is.
        Row(Modifier.fillMaxWidth().height(44.dp).background(Pad.Base).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).background(if (ui.connected) Pad.A else Pad.B, CircleShape))
            Spacer(Modifier.width(10.dp))
            BasicText(ui.info.label.ifEmpty { "--" }, Modifier.background(accent, RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 2.dp),
                TextStyle(Pad.Back, 20.sp, fontWeight = FontWeight.Black))
            Spacer(Modifier.width(12.dp))
            BasicText(if (ui.connected) ui.info.status else "CONNECTING…", Modifier.weight(1f),
                TextStyle(Pad.Text, 16.sp, fontWeight = FontWeight.Bold), maxLines = 1)
            if (ui.roundTripMs >= 0) BasicText("${ui.roundTripMs} ms", style = TextStyle(Pad.Ring, 13.sp, fontWeight = FontWeight.Bold))
        }
        Box(Modifier.fillMaxWidth().height(3.dp).background(accent))
        if (ui.info.layout == PhoneLayout.DRIVE) Drive(ui) else Menu(ui)
    }
}

@Composable
private fun Drive(ui: PhonePadUi) {
    // Sideways in two hands: the left thumb steers anywhere on the left half, the right thumb finds
    // BRAKE and BOOST by position (they fill the right half, edge to edge); small toggles on top.
    Row(Modifier.fillMaxSize().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.weight(1f).fillMaxHeight().background(Pad.Zone, RoundedCornerShape(20.dp))) {
            if (ui.tilt) TiltWheel(Modifier.fillMaxSize()
                .pointerInput(Unit) { awaitEachGesture { awaitFirstDown().consume(); ui.onPress(); DeviceTilt.recenter() } })
            else TouchSteer(ui.stick, Modifier.fillMaxSize())
            BasicText(if (ui.tilt) "TILT TO STEER · TAP TO CENTRE" else "TOUCH ANYWHERE · SLIDE TO STEER",
                Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp), TextStyle(Pad.Ring, 12.sp, fontWeight = FontWeight.Bold))
        }
        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth().height(44.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Toggle("AUTO GAS", ui.autoGas, Modifier.weight(1.2f)) { ui.autoGas = !ui.autoGas }
                if (DeviceTilt.available) Toggle("TILT", ui.tilt, Modifier.weight(1f)) {
                    ui.tilt = !ui.tilt
                    if (ui.tilt) { DeviceTilt.start(); DeviceTilt.recenter() } else DeviceTilt.stop()
                }
                Key("RESET", PhonePadProtocol.Y, ui, Modifier.weight(1f).fillMaxHeight(), Pad.Y, small = true)
                Key("PAUSE", PhonePadProtocol.START, ui, Modifier.weight(1f).fillMaxHeight(), Pad.Ring, small = true)
            }
            Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Key("BRAKE", PhonePadUi.BRAKE, ui, Modifier.weight(1f).fillMaxHeight(), Pad.B, note = "+ STEER: DRIFT")
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Key("BOOST", PhonePadProtocol.A, ui, Modifier.fillMaxWidth().weight(1f), Pad.Boost, note = "HOLD: SURGE")
                    if (!ui.autoGas) Key("GAS", PhonePadUi.GAS, ui, Modifier.fillMaxWidth().weight(1.4f), Pad.A)
                }
            }
        }
    }
}

/** Tilt steering's feedback: a wheel turned as far as the car is steered. */
@Composable
private fun TiltWheel(modifier: Modifier) {
    // DeviceTilt is not Compose state: read it every frame while this is on screen.
    var steer by androidx.compose.runtime.remember { mutableStateOf(0f) }
    androidx.compose.runtime.LaunchedEffect(Unit) { while (true) androidx.compose.runtime.withFrameNanos { steer = DeviceTilt.steer } }
    Canvas(modifier) {
        val r = minOf(size.width, size.height) * .32f
        val c = center
        val stroke = androidx.compose.ui.graphics.drawscope.Stroke(r * .16f)
        drawCircle(Pad.Ring, r, c, style = stroke)
        rotate(steer * 90f, c) {
            drawCircle(Pad.Mint, r, c, style = androidx.compose.ui.graphics.drawscope.Stroke(r * .16f),
                alpha = .25f + .75f * kotlin.math.abs(steer))
            drawLine(Pad.Mint, c, androidx.compose.ui.geometry.Offset(c.x - r, c.y), r * .14f)
            drawLine(Pad.Mint, c, androidx.compose.ui.geometry.Offset(c.x + r, c.y), r * .14f)
            drawLine(Pad.Mint, c, androidx.compose.ui.geometry.Offset(c.x, c.y + r), r * .14f)
            drawCircle(Pad.Text, r * .14f, androidx.compose.ui.geometry.Offset(c.x, c.y - r))
        }
        drawCircle(Pad.Base, r * .26f, c)
    }
}

@Composable
private fun Menu(ui: PhonePadUi) {
    Row(Modifier.fillMaxSize().padding(14.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
        BoxWithConstraints(Modifier.fillMaxHeight().aspectRatio(1f)) {
            val k = maxHeight / 3.2f
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween, horizontalAlignment = Alignment.CenterHorizontally) {
                Key("▲", PhonePadProtocol.UP, ui, Modifier.size(k), Pad.Ring)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Key("◀", PhonePadProtocol.LEFT, ui, Modifier.size(k), Pad.Ring)
                    Key("▶", PhonePadProtocol.RIGHT, ui, Modifier.size(k), Pad.Ring)
                }
                Key("▼", PhonePadProtocol.DOWN, ui, Modifier.size(k), Pad.Ring)
            }
        }
        Column(Modifier.fillMaxHeight(), verticalArrangement = Arrangement.SpaceEvenly, horizontalAlignment = Alignment.CenterHorizontally) {
            Key("START", PhonePadProtocol.START, ui, Modifier.width(180.dp).height(46.dp), Pad.Ring, small = true)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Key("X", PhonePadProtocol.X, ui, Modifier.size(78.dp), Pad.X, shape = CircleShape)
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Key("Y", PhonePadProtocol.Y, ui, Modifier.size(78.dp), Pad.Y, shape = CircleShape)
                    Key("B", PhonePadProtocol.B, ui, Modifier.size(78.dp), Pad.B, shape = CircleShape)
                }
                Key("A", PhonePadProtocol.A, ui, Modifier.size(92.dp), Pad.A, shape = CircleShape)
            }
        }
    }
}

/** A held control: down while a finger is on it (its own finger, so several can be held at once). */
@Composable
private fun Key(label: String, bit: Int, ui: PhonePadUi, modifier: Modifier, color: Color, note: String? = null,
                small: Boolean = false, shape: Shape = RoundedCornerShape(16.dp)) {
    var down by androidx.compose.runtime.remember { mutableStateOf(false) }
    Box(modifier
        .background(if (down) color else Pad.Base, shape)
        .border(3.dp, if (down) Pad.Mint else color, shape)
        .pointerInput(bit) {
            awaitEachGesture {
                awaitFirstDown().consume()
                down = true; ui.press(bit, true)
                try { waitForUpOrCancellation() } finally { down = false; ui.press(bit, false) }
            }
        }, contentAlignment = Alignment.Center) {
        val ink = if (down) Pad.Back else Pad.Text
        // Arrows are drawn, not typed: a browser's default font may have no arrow glyphs.
        val arrow = when (label) { "▲" -> 0; "▶" -> 1; "▼" -> 2; "◀" -> 3; else -> -1 }
        if (arrow >= 0) Canvas(Modifier.fillMaxSize(0.38f)) {
            val w = size.width; val h = size.height
            val path = Path().apply {
                when (arrow) {
                    0 -> { moveTo(w / 2, 0f); lineTo(w, h); lineTo(0f, h) }
                    1 -> { moveTo(w, h / 2); lineTo(0f, h); lineTo(0f, 0f) }
                    2 -> { moveTo(w / 2, h); lineTo(0f, 0f); lineTo(w, 0f) }
                    else -> { moveTo(0f, h / 2); lineTo(w, 0f); lineTo(w, h) }
                }
                close()
            }
            drawPath(path, ink)
        } else Column(horizontalAlignment = Alignment.CenterHorizontally) {
            BasicText(label, style = TextStyle(ink, if (small) 15.sp else 26.sp, fontWeight = FontWeight.Black, textAlign = TextAlign.Center))
            if (note != null) BasicText(note, style = TextStyle(if (down) Pad.Back else Pad.Ring, 11.sp, fontWeight = FontWeight.Bold))
        }
    }
}

@Composable
private fun Toggle(label: String, on: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Box(modifier.fillMaxHeight()
        .background(if (on) Pad.Mint else Pad.Base, RoundedCornerShape(12.dp))
        .border(2.dp, if (on) Pad.Mint else Pad.Ring, RoundedCornerShape(12.dp))
        .pointerInput(label) { awaitEachGesture { awaitFirstDown().consume(); if (waitForUpOrCancellation() != null) onClick() } },
        contentAlignment = Alignment.Center) {
        BasicText(label, style = TextStyle(if (on) Pad.Back else Pad.Text, 14.sp, fontWeight = FontWeight.Black))
    }
}
