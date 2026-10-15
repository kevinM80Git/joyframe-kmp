package io.github.rehaancubess.joyframe

import io.github.rehaancubess.joyframe.input.*
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.*

class TiltTest {
    private fun gravityAt(degrees: Double) = sin(degrees * PI / 180) to cos(degrees * PI / 180)

    @Test fun rollingPastFullLockSteersFullyAndTheDeadZoneHoldsTheLine() {
        val neutral = TiltMath.roll(0.0, 9.81)
        val (x, y) = gravityAt(-30.0)
        assertEquals(1f, TiltMath.steer(TiltMath.roll(x, y), neutral, 26f), .0001f, "right edge dips: steer right")
        val (sx, sy) = gravityAt(1.5)
        assertEquals(0f, TiltMath.steer(TiltMath.roll(sx, sy), neutral, 26f), "inside the dead zone")
        val (hx, hy) = gravityAt(13.0)
        val half = TiltMath.steer(TiltMath.roll(hx, hy), neutral, 26f)
        assertTrue(half < 0f && half > -.5f, "half lock, rescaled past the dead zone: $half")
    }

    @Test fun neutralIsWhereverThePhoneWasHeldAndFlippedAxesGiveTheSameAnswer() {
        val tracker = TiltTracker()
        val (x0, y0) = gravityAt(40.0)
        tracker.reading(x0, y0)
        assertEquals(0f, tracker.steer)
        val (x1, y1) = gravityAt(20.0)
        tracker.reading(x1, y1)
        val normal = tracker.steer
        val flipped = TiltTracker()
        flipped.reading(-x0, -y0); flipped.reading(-x1, -y1)
        assertEquals(normal, flipped.steer, .0001f)
        // Across the ±180° wrap point the shortest way round still wins.
        val wrap = TiltTracker()
        val (a, b) = gravityAt(178.0); val (c, d) = gravityAt(-178.0)
        wrap.reading(a, b); wrap.reading(c, d)
        assertTrue(kotlin.math.abs(wrap.steer) < .2f)
        tracker.recenter(); assertEquals(0f, tracker.steer)
    }

    @Test fun aShakeIsSmoothedButAHeldTiltArrives() {
        val tracker = TiltTracker()
        val (x0, y0) = gravityAt(0.0); tracker.reading(x0, y0)
        // One jolt to full lock moves the wheel only part of the way...
        val (jx, jy) = gravityAt(-40.0); tracker.reading(jx, jy)
        assertTrue(tracker.steer > 0f && tracker.steer < .5f, "one jolt: ${tracker.steer}")
        tracker.reading(x0, y0)
        // ...a tilt held for a sixth of a second (10 readings) is there.
        repeat(10) { tracker.reading(jx, jy) }
        assertTrue(tracker.steer > .95f, "held: ${tracker.steer}")
        repeat(20) { tracker.reading(x0, y0) }
        assertEquals(0f, tracker.steer, "back to exactly centre")
    }

    @Test fun tiltAddsToDriveSteering() {
        val input = ActionInput()
        input.tiltSteer = .5f
        assertEquals(.5f, input.poll().drive.x, .0001f)
        input.clear(); assertEquals(0f, input.poll().drive.x)
    }
}
