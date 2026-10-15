// SPDX-License-Identifier: Apache-2.0
package io.github.rehaancubess.joyframe.audio

import kotlin.concurrent.Volatile

/**
 * Software mixer for looping voices (mono PCM16 sources → interleaved stereo PCM16 blocks): the core
 * of the Android [LoopVoices] backend, shared so it can be tested anywhere.
 *
 * Pitch resamples by linear interpolation around the loop; gain changes ramp across one block, so
 * parameter updates never click. [start], [update] and [stop] may be called from one thread while
 * another calls [mix]: a change lands at most a block late.
 */
internal class LoopMixer(val voices: Int, val block: Int = 512) {
    @Volatile private var voiceSound = arrayOfNulls<ShortArray>(voices)
    private val position = DoubleArray(voices)
    @Volatile private var rate = FloatArray(voices) { 1f }
    private val targetLeft = FloatArray(voices)
    private val targetRight = FloatArray(voices)
    private val left = FloatArray(voices)
    private val right = FloatArray(voices)
    private val restart = BooleanArray(voices)
    private val mixL = FloatArray(block)
    private val mixR = FloatArray(block)

    fun start(voice: Int, data: ShortArray, volume: Float, pitch: Float, pan: Float) {
        update(voice, volume, pitch, pan)
        restart[voice] = true
        val next = voiceSound.copyOf(); next[voice] = data; voiceSound = next
    }

    fun update(voice: Int, volume: Float, pitch: Float, pan: Float) {
        rate[voice] = pitch
        targetLeft[voice] = leftGain(volume, pan)
        targetRight[voice] = rightGain(volume, pan)
    }

    fun stop(voice: Int) {
        targetLeft[voice] = 0f; targetRight[voice] = 0f
        val next = voiceSound.copyOf(); next[voice] = null; voiceSound = next
    }

    /** Mixes the next [block] frames into [out] (interleaved left/right, size >= block * 2). */
    fun mix(out: ShortArray) {
        mixL.fill(0f); mixR.fill(0f)
        val inv = 1f / block
        val sounds = voiceSound
        for (v in 0 until voices) {
            val data = sounds[v]
            if (data == null || data.isEmpty()) { left[v] = 0f; right[v] = 0f; continue }
            if (restart[v]) { restart[v] = false; position[v] = 0.0; left[v] = 0f; right[v] = 0f }
            val tl = targetLeft[v]; val tr = targetRight[v]
            var gl = left[v]; var gr = right[v]
            if (gl == 0f && gr == 0f && tl == 0f && tr == 0f) continue
            val dl = (tl - gl) * inv; val dr = (tr - gr) * inv
            val step = rate[v].toDouble()
            val size = data.size
            var pos = position[v]
            for (i in 0 until block) {
                val p = pos.toInt()
                val f = (pos - p).toFloat()
                val a = data[p].toFloat()
                val b = data[if (p + 1 >= size) 0 else p + 1].toFloat()
                val s = a + (b - a) * f
                gl += dl; gr += dr
                mixL[i] += s * gl; mixR[i] += s * gr
                pos += step
                if (pos >= size) pos -= size
            }
            position[v] = pos
            left[v] = tl; right[v] = tr
        }
        for (i in 0 until block) {
            out[i * 2] = clamp(mixL[i])
            out[i * 2 + 1] = clamp(mixR[i])
        }
    }

    private fun clamp(v: Float): Short = (if (v > 32767f) 32767f else if (v < -32768f) -32768f else v).toInt().toShort()
}
