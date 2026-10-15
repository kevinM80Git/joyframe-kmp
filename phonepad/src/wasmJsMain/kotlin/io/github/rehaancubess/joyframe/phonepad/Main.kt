// SPDX-License-Identifier: Apache-2.0
package io.github.rehaancubess.joyframe.phonepad

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import io.github.rehaancubess.joyframe.input.DeviceTilt
import io.github.rehaancubess.joyframe.input.phone.PhoneLayout
import io.github.rehaancubess.joyframe.input.phone.PhonePadController
import io.github.rehaancubess.joyframe.input.phone.PhonePadInfo
import io.github.rehaancubess.joyframe.input.phone.PhonePadUi
import kotlinx.browser.document
import kotlinx.browser.localStorage
import kotlinx.browser.window
import org.w3c.dom.MessageEvent
import org.w3c.dom.WebSocket

/**
 * The browser side of a PhonePads pad: connects back to the device that served this page, says hello
 * with an id kept in local storage (a reconnecting phone is the same pad), sends the controls on every
 * change and five times a second, measures the round trip, vibrates when asked, reconnects by itself.
 */
private val ui = PhonePadUi()
private var socket: WebSocket? = null
private var lastSent = ""

private val phoneId: String by lazy {
    localStorage.getItem("jfpad") ?: (randomId()).also { localStorage.setItem("jfpad", it) }
}

private fun randomId(): String = (1..10).map { "abcdefghijklmnopqrstuvwxyz0123456789"[(kotlin.random.Random.nextInt(36))] }.joinToString("")

private fun send(text: String) { socket?.takeIf { it.readyState == WebSocket.OPEN }?.send(text) }

private fun sendInput(force: Boolean) {
    val m = ui.message()
    if (force || m != lastSent) { send(m); lastSent = m }
}

private var opened = false
private var failures = 0

private fun connect() {
    val secure = window.location.protocol == "https:"
    val ws = WebSocket((if (secure) "wss://" else "ws://") + window.location.host + "/ws")
    socket = ws
    ws.onopen = { opened = true; ui.connected = true; send("hello $phoneId"); sendInput(true) }
    ws.onclose = {
        ui.connected = false
        // Safari opens no WebSocket to a self-signed certificate even after the page was allowed:
        // go to the plain-HTTP twin on the next port (touch steering only there).
        if (secure && !opened && ++failures >= 2) window.location.replace(plainTwin())
        else window.setTimeout({ connect(); null }, 1000)
    }
    ws.onmessage = { e: MessageEvent -> onMessage(e.data.toString()) }
}

private fun onMessage(m: String) {
    val p = m.split(' ')
    when (p[0]) {
        "pong" -> {
            val rtt = (now() - (p.getOrNull(1)?.toDoubleOrNull() ?: now())).toInt()
            ui.roundTripMs = rtt
            send("rtt $rtt")
        }
        "vib" -> vibrate(p.getOrNull(1)?.toIntOrNull() ?: 20)
        "info" -> {
            ui.info = PhonePadInfo(
                label = p.getOrNull(3)?.takeIf { it != "-" }?.replace('_', ' ') ?: "",
                color = (p.getOrNull(2)?.toIntOrNull(16) ?: 0x8A8FA8) or (0xFF shl 24),
                layout = if (p.getOrNull(1) == "DRIVE") PhoneLayout.DRIVE else PhoneLayout.MENU,
                status = p.drop(4).joinToString(" "),
            )
            sendInput(true)
        }
    }
}

private fun plainTwin(): String =
    "http://" + window.location.hostname + ":" + ((window.location.port.toIntOrNull() ?: 443) + 1) + window.location.pathname

private fun now(): Double = js("performance.now()")
private fun vibrate(ms: Int) { js("if (navigator.vibrate) navigator.vibrate(ms)") }
private fun motionNeedsPermission(): Boolean = js("typeof DeviceMotionEvent !== 'undefined' && typeof DeviceMotionEvent.requestPermission === 'function'")

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    // A short tick as a thumb lands on a control, so it is found without looking.
    ui.onPress = { vibrate(8) }
    // Tilt or touch, as the player last chose (where tilt needs no permission tap: not iOS).
    if (localStorage.getItem("jfpadTilt") == "1" && DeviceTilt.available && !motionNeedsPermission()) { ui.tilt = true; DeviceTilt.start() }
    connect()
    window.setInterval({ sendInput(true); null }, 200)
    window.setInterval({ send("ping " + now()); null }, 1000)
    ComposeViewport(requireNotNull(document.getElementById("pad"))) {
        // Every frame: the stick and buttons go out as soon as they change.
        LaunchedEffect(Unit) {
            var tilt = ui.tilt
            while (true) withFrameNanos {
                sendInput(false)
                if (ui.tilt != tilt) { tilt = ui.tilt; localStorage.setItem("jfpadTilt", if (tilt) "1" else "0") }
            }
        }
        PhonePadController(ui)
    }
}
