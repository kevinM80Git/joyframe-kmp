// SPDX-License-Identifier: Apache-2.0
package io.github.rehaancubess.joyframe.input.phone

import io.github.rehaancubess.joyframe.input.ConnectedGamepad

actual fun bundledPhonePadFiles(folder: String): PhonePadFiles? = null

/** Not available here: no listening sockets. [supported] is false and every call does nothing. */
actual class PhonePads actual constructor(port: Int, files: PhonePadFiles, secure: Boolean) {
    actual val supported: Boolean = false
    actual val url: String? = null
    actual fun start(): Boolean = false
    actual fun pollAll(): List<ConnectedGamepad> = emptyList()
    actual fun show(id: String, info: PhonePadInfo) {}
    actual fun rumble(id: String, durationMs: Int, intensity: Float) {}
    actual fun roundTripMs(id: String): Int = -1
    actual fun close() {}
}
