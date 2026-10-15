// SPDX-License-Identifier: Apache-2.0
package io.github.rehaancubess.joyframe.audio

import kotlinx.coroutines.suspendCancellableCoroutine
import org.khronos.webgl.toInt8Array
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal actual fun createLoopBackend(voices: Int, sounds: Map<SoundId, ByteArray>): LoopBackend = WebLoops(voices, sounds)

/** Web Audio: per voice a looping buffer source → stereo panner → gain; playbackRate for pitch. */
private class WebLoops(private val voices: Int, private val sounds: Map<SoundId, ByteArray>) : LoopBackend {
    private var context: AudioContext? = null
    private val buffers = HashMap<SoundId, AudioBuffer>()
    private val sources = arrayOfNulls<LoopSourceNode>(voices)
    private val gains = arrayOfNulls<GainNode>(voices)
    private val panners = arrayOfNulls<StereoPannerNode>(voices)

    override suspend fun load() {
        val ctx = newLoopContext()
        context = ctx
        for ((id, wav) in sounds) {
            buffers[id] = suspendCancellableCoroutine { done ->
                ctx.decodeAudioData(wav.toInt8Array().buffer, { done.resume(it) },
                    { done.resumeWithException(IllegalStateException("Browser could not decode loop ${id.name}")) })
            }
        }
    }

    override fun start(voice: Int, sound: SoundId, volume: Float, pitch: Float, pan: Float) {
        val ctx = context ?: return
        stop(voice)
        resumeContext(ctx) // best effort; browsers stay silent until a user gesture
        val gain = ctx.createGain()
        gain.connect(ctx.destination)
        val panner = createLoopPanner(ctx)
        val source = createLoopSource(ctx)
        source.buffer = buffers[sound] ?: return
        source.loop = true
        if (panner != null) { source.connect(panner); panner.connect(gain) } else source.connect(gain)
        sources[voice] = source; gains[voice] = gain; panners[voice] = panner
        update(voice, volume, pitch, pan)
        source.start()
    }

    override fun update(voice: Int, volume: Float, pitch: Float, pan: Float) {
        gains[voice]?.gain?.value = volume
        sources[voice]?.playbackRate?.value = pitch
        panners[voice]?.pan?.value = pan
    }

    override fun stop(voice: Int) {
        sources[voice]?.let { runCatching { it.stop() }; it.disconnect() }
        panners[voice]?.disconnect()
        gains[voice]?.disconnect()
        sources[voice] = null; panners[voice] = null; gains[voice] = null
    }

    override fun release() {
        for (v in 0 until voices) stop(v)
        context?.close()
        context = null
        buffers.clear()
    }
}

internal external interface LoopSourceNode : AudioNode {
    var buffer: AudioBuffer?
    var loop: Boolean
    val playbackRate: AudioParam
    fun start()
    fun stop()
}

private fun newLoopContext(): AudioContext = js("new (window.AudioContext || window.webkitAudioContext)()")
private fun createLoopSource(ctx: AudioContext): LoopSourceNode = js("ctx.createBufferSource()")
private fun createLoopPanner(ctx: AudioContext): StereoPannerNode? = js("ctx.createStereoPanner ? ctx.createStereoPanner() : null")
private fun resumeContext(ctx: AudioContext) {
    js("(function() { if (ctx.state === 'suspended') ctx.resume().catch(function() {}); })()")
}
