// SPDX-License-Identifier: Apache-2.0
package io.github.rehaancubess.joyframe.input

/** `devicemotion` gravity. Safari needs permission, requested by [start] (call it from a tap). */
actual object DeviceTilt {
    private val tracker = TiltTracker()
    private var handle: JsAny? = null

    actual val available: Boolean get() = motionSupported()

    actual fun start(fullLockDegrees: Float) {
        tracker.fullLockDegrees = fullLockDegrees
        if (handle != null || !available) return
        tracker.recenter()
        handle = listenMotion { x, y -> tracker.reading(x, y) }
    }

    actual fun stop() {
        handle?.let(::unlistenMotion)
        handle = null
        tracker.recenter()
    }

    actual fun recenter() = tracker.recenter()

    actual val steer: Float get() = if (handle != null) tracker.steer else 0f
}

// Touch devices only: desktop browsers define DeviceMotionEvent but never fire it. Secure pages only
// (https or localhost): browsers send no motion events to plain http pages.
private fun motionSupported(): Boolean =
    js("typeof DeviceMotionEvent !== 'undefined' && (navigator.maxTouchPoints > 0) && window.isSecureContext === true")

private fun listenMotion(reading: (Double, Double) -> Unit): JsAny = js("""(function() {
    var on = function(e) {
        var g = e.accelerationIncludingGravity;
        if (g && g.x != null && g.y != null) reading(g.x, g.y);
    };
    if (typeof DeviceMotionEvent.requestPermission === 'function') {
        DeviceMotionEvent.requestPermission().then(function(state) {
            if (state === 'granted') window.addEventListener('devicemotion', on);
        }).catch(function() {});
    } else {
        window.addEventListener('devicemotion', on);
    }
    return { on: on };
})()""")

private fun unlistenMotion(handle: JsAny) { js("window.removeEventListener('devicemotion', handle.on)") }
