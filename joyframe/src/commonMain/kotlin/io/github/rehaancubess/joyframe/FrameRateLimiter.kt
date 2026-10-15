// SPDX-License-Identifier: Apache-2.0
package io.github.rehaancubess.joyframe

/**
 * Runs a game loop at a steady fraction of the display rate, for devices that cannot hold the panel's
 * own rate. At 30 Hz on a 60 Hz TV it accepts every second vsync; on a 50 Hz TV it settles on every
 * second vsync too (25 Hz), because an even cadence beats a 30 Hz average made of 20 ms and 40 ms frames.
 *
 * Call [shouldRun] with every vsync timestamp (for example from `withFrameNanos`) and skip the whole
 * frame (input, simulation, publishing) when it returns false. [tolerance] absorbs vsync jitter: a
 * frame is due when at least `1/targetHz - tolerance` has passed since the last accepted one.
 */
class FrameRateLimiter(targetHz: Float, val toleranceNanos: Long = 4_000_000L) {
    init {
        require(targetHz.isFinite() && targetHz > 0f) { "targetHz must be positive" }
        require(toleranceNanos >= 0L) { "toleranceNanos must not be negative" }
    }

    var targetHz: Float = targetHz
        set(value) {
            require(value.isFinite() && value > 0f) { "targetHz must be positive" }
            field = value
        }

    private var last = Long.MIN_VALUE

    fun shouldRun(frameNanos: Long): Boolean {
        val interval = (1_000_000_000.0 / targetHz).toLong()
        // First frame, a clock going backwards, or a long stall: run now and restart the cadence.
        if (last == Long.MIN_VALUE || frameNanos < last || frameNanos - last > interval * 4) {
            last = frameNanos
            return true
        }
        if (frameNanos - last < interval - toleranceNanos) return false
        last = frameNanos
        return true
    }

    /** Forget the cadence, for example after a pause, so the next frame runs immediately. */
    fun reset() { last = Long.MIN_VALUE }
}
