// SPDX-License-Identifier: Apache-2.0
package io.github.rehaancubess.joyframe.audio

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference
import java.io.ByteArrayInputStream
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.Clip
import javax.sound.sampled.FloatControl
import kotlin.math.sqrt

internal actual fun createLoopBackend(voices: Int, sounds: Map<SoundId, ByteArray>): LoopBackend =
    if (System.getProperty("os.name").contains("mac", ignoreCase = true)) MacOpenAlLoops(voices, sounds)
    else JavaSoundLoops(voices, sounds)

/** macOS: one OpenAL source per voice, sharing one buffer per sound; AL_PITCH resamples. */
private class MacOpenAlLoops(private val voices: Int, private val sounds: Map<SoundId, ByteArray>) : LoopBackend {
    private val api by lazy { Native.load("/System/Library/Frameworks/OpenAL.framework/OpenAL", LoopAl::class.java) }
    private var device: Pointer? = null
    private var context: Pointer? = null
    private val buffers = HashMap<SoundId, Int>()
    private val sources = IntArray(voices)

    override suspend fun load() { synchronized(audioDeviceLock) {
        device = checkNotNull(api.alcOpenDevice(null)) { "OpenAL has no default output device" }
        context = checkNotNull(api.alcCreateContext(device, null)) { "OpenAL could not create a context" }
        check(activate()) { "OpenAL could not activate its context" }
        for ((id, wav) in sounds) {
            check(wav.size > WAV_HEADER_BYTES) { "Loop ${id.name} is empty" }
            val ref = IntByReference()
            api.alGenBuffers(1, ref)
            val pcm = wav.copyOfRange(WAV_HEADER_BYTES, wav.size)
            api.alBufferData(ref.value, AL_FORMAT_MONO16, pcm, pcm.size, Wav.SAMPLE_RATE)
            buffers[id] = ref.value
        }
        for (v in 0 until voices) {
            val ref = IntByReference()
            api.alGenSources(1, ref)
            sources[v] = ref.value
            api.alSourcei(ref.value, AL_LOOPING, AL_TRUE)
            api.alSourcei(ref.value, AL_SOURCE_RELATIVE, AL_TRUE)
        }
        check(api.alGetError() == AL_NO_ERROR) { "OpenAL rejected the loop voices" }
    } }

    override fun start(voice: Int, sound: SoundId, volume: Float, pitch: Float, pan: Float) { synchronized(audioDeviceLock) {
        if (!activate()) return
        val source = sources[voice]
        api.alSourceStop(source)
        api.alSourcei(source, AL_BUFFER, buffers[sound] ?: return)
        apply(source, volume, pitch, pan)
        api.alSourcePlay(source)
    } }

    override fun update(voice: Int, volume: Float, pitch: Float, pan: Float) { synchronized(audioDeviceLock) {
        if (activate()) apply(sources[voice], volume, pitch, pan)
    } }

    override fun stop(voice: Int) { synchronized(audioDeviceLock) {
        if (activate()) api.alSourceStop(sources[voice])
    } }

    private fun apply(source: Int, volume: Float, pitch: Float, pan: Float) {
        api.alSourcef(source, AL_GAIN, volume)
        api.alSourcef(source, AL_PITCH, pitch)
        api.alSource3f(source, AL_POSITION, pan, 0f, -sqrt(1f - pan * pan))
    }

    private fun activate(): Boolean = context != null && api.alcMakeContextCurrent(context).toInt() != 0

    override fun release() { synchronized(audioDeviceLock) {
        if (context != null && activate()) {
            sources.forEach { if (it != 0) { api.alSourceStop(it); api.alDeleteSources(1, intArrayOf(it)) } }
            buffers.values.forEach { api.alDeleteBuffers(1, intArrayOf(it)) }
            api.alcMakeContextCurrent(null)
            api.alcDestroyContext(context)
        }
        if (device != null) api.alcCloseDevice(device)
        context = null; device = null; buffers.clear(); sources.fill(0)
    } }

    private companion object {
        const val WAV_HEADER_BYTES = 44
        const val AL_NO_ERROR = 0
        const val AL_TRUE = 1
        const val AL_FORMAT_MONO16 = 0x1101
        const val AL_BUFFER = 0x1009
        const val AL_GAIN = 0x100A
        const val AL_PITCH = 0x1003
        const val AL_LOOPING = 0x1007
        const val AL_POSITION = 0x1004
        const val AL_SOURCE_RELATIVE = 0x202
    }
}

private interface LoopAl : Library {
    fun alcOpenDevice(deviceName: String?): Pointer?
    fun alcCreateContext(device: Pointer?, attributes: IntArray?): Pointer?
    fun alcMakeContextCurrent(context: Pointer?): Byte
    fun alcDestroyContext(context: Pointer?)
    fun alcCloseDevice(device: Pointer?): Byte
    fun alDeleteSources(count: Int, sources: IntArray)
    fun alDeleteBuffers(count: Int, buffers: IntArray)
    fun alGenBuffers(count: Int, buffers: IntByReference)
    fun alBufferData(buffer: Int, format: Int, data: ByteArray, size: Int, frequency: Int)
    fun alGenSources(count: Int, sources: IntByReference)
    fun alSourcei(source: Int, parameter: Int, value: Int)
    fun alSourcef(source: Int, parameter: Int, value: Float)
    fun alSource3f(source: Int, parameter: Int, x: Float, y: Float, z: Float)
    fun alSourcePlay(source: Int)
    fun alSourceStop(source: Int)
    fun alGetError(): Int
}

/** Windows/Linux: a looping Clip per voice per sound. Pitch only where the mixer offers SAMPLE_RATE. */
private class JavaSoundLoops(private val voices: Int, private val sounds: Map<SoundId, ByteArray>) : LoopBackend {
    private val clips = Array(voices) { HashMap<SoundId, Clip>() }
    private val active = arrayOfNulls<Clip>(voices)

    override suspend fun load() { synchronized(audioDeviceLock) {
        for (v in 0 until voices) for ((id, wav) in sounds) {
            val clip = AudioSystem.getClip()
            AudioSystem.getAudioInputStream(ByteArrayInputStream(wav)).use(clip::open)
            clips[v][id] = clip
        }
    } }

    override fun start(voice: Int, sound: SoundId, volume: Float, pitch: Float, pan: Float) { synchronized(audioDeviceLock) {
        active[voice]?.stop()
        val clip = clips[voice][sound] ?: return
        apply(clip, volume, pitch, pan)
        clip.framePosition = 0
        clip.loop(Clip.LOOP_CONTINUOUSLY)
        active[voice] = clip
    } }

    override fun update(voice: Int, volume: Float, pitch: Float, pan: Float) { synchronized(audioDeviceLock) {
        active[voice]?.let { apply(it, volume, pitch, pan) }
    } }

    override fun stop(voice: Int) { synchronized(audioDeviceLock) {
        active[voice]?.stop()
        active[voice] = null
    } }

    private fun apply(clip: Clip, volume: Float, pitch: Float, pan: Float) {
        if (clip.isControlSupported(FloatControl.Type.MASTER_GAIN)) {
            val gain = clip.getControl(FloatControl.Type.MASTER_GAIN) as FloatControl
            gain.value = (20 * kotlin.math.log10(volume.coerceAtLeast(.0001f).toDouble())).toFloat().coerceIn(gain.minimum, gain.maximum)
        }
        listOf(FloatControl.Type.PAN, FloatControl.Type.BALANCE).firstOrNull(clip::isControlSupported)?.let {
            val control = clip.getControl(it) as FloatControl
            control.value = pan.coerceIn(control.minimum, control.maximum)
        }
        if (clip.isControlSupported(FloatControl.Type.SAMPLE_RATE)) {
            val rate = clip.getControl(FloatControl.Type.SAMPLE_RATE) as FloatControl
            rate.value = (Wav.SAMPLE_RATE * pitch).coerceIn(rate.minimum, rate.maximum)
        }
    }

    override fun release() { synchronized(audioDeviceLock) {
        clips.forEach { voice -> voice.values.forEach { it.stop(); it.close() }; voice.clear() }
        active.fill(null)
    } }
}
