// SPDX-License-Identifier: Apache-2.0
package io.github.rehaancubess.joyframe

import io.github.rehaancubess.joyframe.render.gpu.PackedColor
import io.github.rehaancubess.joyframe.render.gpu.SceneEnvironment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FrameRateLimiterTest {
    private fun accepted(limiter: FrameRateLimiter, vsyncHz: Double, seconds: Double, jitterNanos: Long = 0): Int {
        val frames = (vsyncHz * seconds).toInt()
        var count = 0
        for (i in 0 until frames) {
            val jitter = if (i % 2 == 0) jitterNanos else -jitterNanos
            if (limiter.shouldRun((i * 1_000_000_000.0 / vsyncHz).toLong() + jitter)) count++
        }
        return count
    }

    @Test fun thirtyOnSixtyTakesEverySecondVsync() {
        assertEquals(60, accepted(FrameRateLimiter(30f), 60.0, 2.0))
    }

    @Test fun vsyncJitterDoesNotDropFrames() {
        assertEquals(60, accepted(FrameRateLimiter(30f), 60.0, 2.0, jitterNanos = 1_500_000))
    }

    @Test fun thirtyOnFiftySettlesOnAnEvenTwentyFive() {
        assertEquals(50, accepted(FrameRateLimiter(30f), 50.0, 2.0))
    }

    @Test fun sixtyOnOneTwentyTakesEverySecondVsync() {
        assertEquals(120, accepted(FrameRateLimiter(60f), 120.0, 2.0))
    }

    @Test fun stallRestartsCadence() {
        val limiter = FrameRateLimiter(30f)
        assertTrue(limiter.shouldRun(0))
        assertTrue(limiter.shouldRun(1_000_000_000))
        assertEquals(false, limiter.shouldRun(1_016_666_667))
        assertTrue(limiter.shouldRun(1_033_333_333))
    }

    @Test fun rejectsBadRates() {
        assertFailsWith<IllegalArgumentException> { FrameRateLimiter(0f) }
        assertFailsWith<IllegalArgumentException> { SceneEnvironment(skyClouds = 2f) }
    }

    @Test fun skyDefaultsMatchTheDaySky() {
        val sky = SceneEnvironment()
        assertEquals(PackedColor(0xff4bb8f8.toInt()), sky.skyZenith)
        assertEquals(1f, sky.skySun)
        assertEquals(1f, sky.skyClouds)
    }
}
