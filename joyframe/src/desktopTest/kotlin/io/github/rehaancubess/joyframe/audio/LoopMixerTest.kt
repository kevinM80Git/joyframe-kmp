package io.github.rehaancubess.joyframe.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LoopMixerTest {
    private val loop = ShortArray(441) { (sin(2 * PI * it / 441.0) * 10000).toInt().toShort() } // 100 Hz, one period

    @Test fun pitchOneReproducesTheLoopAcrossBlocksAndWraps() {
        val m = LoopMixer(2, block = 256)
        m.start(0, loop, volume = 1f, pitch = 1f, pan = 0f)
        val out = ShortArray(512)
        m.mix(out) // first block ramps up from silence
        val samples = ArrayList<Short>()
        repeat(4) { m.mix(out); for (i in 0 until 256) samples += out[i * 2] }
        // Steady state: exactly the loop, continuing across block and loop boundaries.
        for ((k, s) in samples.withIndex()) assertEquals(loop[(256 + k) % loop.size], s, "sample $k")
        assertTrue(samples.withIndex().all { (k, s) -> out.size > 0 && s == loop[(256 + k) % loop.size] })
    }

    @Test fun panAndVolumeSplitTheChannels() {
        val m = LoopMixer(1, block = 64)
        m.start(0, ShortArray(64) { 1000 }, volume = 0.5f, pitch = 1f, pan = 1f)
        val out = ShortArray(128)
        m.mix(out); m.mix(out)
        assertEquals(0, out[0].toInt(), "panned right: left silent")
        assertEquals(500, out[1].toInt(), "half volume on the right")
    }

    @Test fun gainChangesRampWithoutAJump() {
        val m = LoopMixer(1, block = 128)
        m.start(0, ShortArray(128) { 20000 }, volume = 0.2f, pitch = 1f, pan = 0f)
        val out = ShortArray(256)
        m.mix(out); m.mix(out)
        m.update(0, volume = 1f, pitch = 1f, pan = 0f)
        m.mix(out)
        var worst = 0
        for (i in 1 until 128) worst = maxOf(worst, abs(out[i * 2] - out[(i - 1) * 2]))
        assertTrue(worst < 200, "smooth ramp (largest step $worst)")
        assertEquals(20000, out[254].toInt(), "reaches the new gain by the block's end")
    }

    @Test fun pitchTwoSkipsEverySecondSampleAndStopSilences() {
        val m = LoopMixer(1, block = 32)
        val ramp = ShortArray(400) { (it * 10).toShort() }
        m.start(0, ramp, volume = 1f, pitch = 2f, pan = 0f)
        val out = ShortArray(64)
        m.mix(out); m.mix(out)
        assertEquals(ramp[64].toInt(), out[0].toInt(), "twice as fast")
        assertEquals(ramp[66].toInt(), out[2].toInt())
        m.stop(0)
        m.mix(out)
        assertTrue(out.all { it.toInt() == 0 }, "stopped: silence")
    }
}
