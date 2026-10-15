// SPDX-License-Identifier: Apache-2.0
package io.github.rehaancubess.joyframe.input.phone

import io.github.rehaancubess.joyframe.input.ConnectedGamepad
import io.github.rehaancubess.joyframe.input.GamepadMapping
import io.github.rehaancubess.joyframe.input.GamepadState

/** The port [PhonePads] tries first (then the next nine). */
const val PHONE_PAD_PORT: Int = 47800

/** Which controls a phone shows: a menu pad (D-pad, Ⓐ Ⓑ Ⓧ, START) or the driving layout. */
enum class PhoneLayout { MENU, DRIVE }

/**
 * What a phone shows about its player, set by the game with [PhonePads.show]: a short [label] ("P2"),
 * the player's [color] (ARGB), the [layout] and one [status] line ("3RD · LAP 2/3").
 */
data class PhonePadInfo(
    val label: String = "",
    val color: Int = 0xFF8A8FA8.toInt(),
    val layout: PhoneLayout = PhoneLayout.MENU,
    val status: String = "",
)

/**
 * What [PhonePads] serves to phones, by file name (`index.html`, `phonepad.wasm`, …). A file may also
 * come pre-compressed as `<name>.gz` or `<name>.gzip` (Android's build unpacks `.gz` assets), served to
 * browsers that accept gzip.
 */
fun interface PhonePadFiles {
    fun open(name: String): ByteArray?

    companion object {
        /** Nothing but the light HTML controller ([PhonePadPage]). */
        val Lite: PhonePadFiles = PhonePadFiles { null }
    }
}

/**
 * The Compose controller ([PhonePadController], built by Joyframe's `phonepad` module for the browser)
 * when the app bundles it under [folder]: desktop classpath resources or Android assets. Null when it
 * is not bundled (or on iOS and in browsers).
 */
expect fun bundledPhonePadFiles(folder: String = "phonepad"): PhonePadFiles?

/**
 * Phones as controllers over the local network. [start] runs a small HTTP + WebSocket server; a phone
 * that opens [url] (show it as a [QrCode]) gets a controller page in its browser, nothing to install,
 * and appears in [pollAll] as one more [ConnectedGamepad] (id `phone-…`, stable across reconnects of
 * the same phone). The game seats and maps it like any pad.
 *
 * - The page: [files]' `index.html` (with [bundledPhonePadFiles]: Joyframe's [PhonePadController] with
 *   its real [io.github.rehaancubess.joyframe.input.TouchStick], in Kotlin/Wasm), or the light HTML
 *   page [PhonePadPage], which is also at `/lite` for browsers without WebAssembly GC.
 * - Controls: a stick (or tilt, where the phone allows it) is the left stick; BRAKE is the
 *   left trigger; gas is the right trigger (automatic unless the player switches it off); BOOST is
 *   South (Ⓐ), RESET North (Ⓨ), PAUSE Start; the menu layout has a D-pad, Ⓐ Ⓑ Ⓧ and START.
 * - [show] tells a phone its player's label, colour, layout and status; [rumble] vibrates it (Android
 *   browsers; iPhones have no vibration API).
 * - Nothing blocks the caller: sockets live on their own threads, [pollAll] returns a snapshot.
 *
 * - [secure]: the page is served over HTTPS (and the socket is `wss:`), with a self-signed
 *   certificate made on the device and kept in app-private storage. Browsers give only secure pages
 *   the motion sensors, so tilt steering needs this. Each phone shows one "connection not private"
 *   warning the first time. The same files are also served over plain HTTP on the next port, where
 *   the page goes by itself if the browser will not open a WebSocket to a self-signed certificate
 *   (Safari): there it works with touch steering only.
 *
 * Desktop and Android only ([supported] is false on iOS and in browsers). Android apps need the
 * `android.permission.INTERNET` permission. The server is open to the local network; anyone on it
 * who opens the page can join.
 */
expect class PhonePads(port: Int = PHONE_PAD_PORT, files: PhonePadFiles = PhonePadFiles.Lite, secure: Boolean = false) {
    /** False where phones can't connect (iOS, browser builds). */
    val supported: Boolean
    /** The address phones open, e.g. `https://192.168.0.20:47800/`; null before [start] or without a network. */
    val url: String?
    /** Starts listening; false when it can't (unsupported, no network, ports busy). Calling it again is harmless. */
    fun start(): Boolean
    /** Every connected phone as a gamepad, in join order. The same list instance while nothing changes. */
    fun pollAll(): List<ConnectedGamepad>
    /** Tells phone [id] what to show; sent only when it changes. */
    fun show(id: String, info: PhonePadInfo)
    /** Vibrates phone [id] (best effort). */
    fun rumble(id: String, durationMs: Int, intensity: Float)
    /** The last round trip phone [id] measured to this device, in milliseconds, or -1. */
    fun roundTripMs(id: String): Int
    /** Stops the server and disconnects every phone. */
    fun close()
}

/**
 * The text protocol between the controller page and [PhonePads] (one WebSocket text message each):
 *
 * - phone → device: `hello <phoneId>`, `in <steer> <gas> <brake> <buttons>` (on every change and 5×
 *   a second), `ping <t>`, `rtt <ms>`.
 * - device → phone: `info <MENU|DRIVE> <RRGGBB> <label> <status…>`, `vib <ms>`, `pong <t>`.
 *
 * `<buttons>` is a bit mask: see the constants.
 */
object PhonePadProtocol {
    const val A = 1
    const val B = 2
    const val X = 4
    const val Y = 8
    const val START = 16
    const val UP = 32
    const val DOWN = 64
    const val LEFT = 128
    const val RIGHT = 256

    /** One `in` message. */
    data class Input(val steer: Float, val gas: Float, val brake: Float, val buttons: Int) {
        fun state(extraButtons: Int = 0): GamepadState = state(steer, gas, brake, buttons or extraButtons)
    }

    /** The controls in an `in` message, or null when it is not one. */
    fun parse(message: String): Input? {
        if (!message.startsWith("in ")) return null
        val parts = message.split(' ')
        if (parts.size < 5) return null
        return Input(parts[1].toFloatOrNull() ?: return null, parts[2].toFloatOrNull() ?: return null,
            parts[3].toFloatOrNull() ?: return null, parts[4].toIntOrNull() ?: return null)
    }

    /** The pad state for an `in` message, or null when it is not one. */
    fun parseInput(message: String): GamepadState? = parse(message)?.state()

    fun state(steer: Float, gas: Float, brake: Float, mask: Int): GamepadState {
        fun on(bit: Int) = mask and bit != 0
        val s = steer.coerceIn(-1f, 1f); val g = gas.coerceIn(0f, 1f); val b = brake.coerceIn(0f, 1f)
        val stickY = (if (on(UP)) 1f else 0f) - (if (on(DOWN)) 1f else 0f)
        var horizontal = s
        if (on(LEFT)) horizontal = -1f
        if (on(RIGHT)) horizontal = 1f
        val vertical = if (stickY != 0f) stickY else g - b
        return GamepadState(
            horizontal = horizontal,
            vertical = vertical.coerceIn(-1f, 1f),
            action = on(X),
            pause = on(START),
            confirm = on(A),
            cancel = on(B),
            leftStickX = s,
            leftStickY = stickY,
            leftTrigger = b,
            rightTrigger = g,
            buttons = GamepadMapping.buttons(
                on(A), on(B), on(X), on(Y), false, false, b, g, false, on(START), false, false,
                on(UP), on(DOWN), on(LEFT), on(RIGHT),
            ),
        )
    }

    fun info(info: PhonePadInfo): String {
        val rgb = (info.color and 0xFFFFFF).toString(16).padStart(6, '0')
        val label = info.label.replace(' ', '_').ifEmpty { "-" }
        return "info ${info.layout.name} $rgb $label ${info.status}".trimEnd()
    }

    /** A phone id from a `hello` (letters and digits, at most 16), or null. */
    fun phoneId(message: String): String? {
        if (!message.startsWith("hello ")) return null
        val id = message.substring(6).trim().filter { it.isLetterOrDigit() }.take(16)
        return id.ifEmpty { null }
    }
}
