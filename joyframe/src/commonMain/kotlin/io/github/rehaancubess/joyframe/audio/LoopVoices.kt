// SPDX-License-Identifier: Apache-2.0
package io.github.rehaancubess.joyframe.audio

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.concurrent.Volatile
import kotlin.math.abs

/**
 * A fixed set of looping voices with live volume, pitch and pan: engines, tyre squeal, wind.
 * Each voice plays one of [sounds] on a loop (or nothing). Set a voice every frame if you like:
 * a worker applies changes at most [APPLY_HZ] times a second and skips inaudible ones, because
 * native setters are not free.
 *
 * Pitch is a playback-rate multiplier clamped to 0.5..2 (the Android SoundPool range).
 * Desktop Windows/Linux applies pitch only where Java Sound exposes a sample-rate control.
 * Android requires JoyframeAndroid.initialize; browsers need a user gesture before sound plays.
 */
class LoopVoices internal constructor(
    private val backend: LoopBackend,
    private val known: Set<SoundId>,
    val voiceCount: Int,
) {
    constructor(sounds: Map<SoundId, PcmSound>, voiceCount: Int) :
        this(createLoopBackend(voiceCount.coerceIn(1, 16), sounds.mapValues { it.value.wav }), sounds.keys.toSet(), voiceCount)

    init {
        require(known.isNotEmpty() && known.size <= 32) { "LoopVoices takes 1 to 32 sounds" }
        require(voiceCount in 1..16) { "LoopVoices takes 1 to 16 voices" }
    }

    private val wantedSound = arrayOfNulls<SoundId>(voiceCount)
    private val wantedVolume = FloatArray(voiceCount)
    private val wantedPitch = FloatArray(voiceCount) { 1f }
    private val wantedPan = FloatArray(voiceCount)
    @Volatile private var foreground = true
    @Volatile private var closed = false
    private val mutableStatus = MutableStateFlow<AudioStatus>(AudioStatus.Loading)
    val status: StateFlow<AudioStatus> = mutableStatus.asStateFlow()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    init {
        scope.launch {
            try {
                backend.load()
            } catch (failure: Throwable) {
                if (!closed) mutableStatus.value = AudioStatus.Failed(failure.message ?: "Loop audio failed to load")
                runCatching { backend.release() }
                return@launch
            }
            if (!closed) mutableStatus.value = AudioStatus.Ready
            val playing = arrayOfNulls<SoundId>(voiceCount)
            val volume = FloatArray(voiceCount); val pitch = FloatArray(voiceCount); val pan = FloatArray(voiceCount)
            try {
                while (!closed) {
                    val audible = foreground
                    for (v in 0 until voiceCount) {
                        val sound = if (audible) wantedSound[v] else null
                        val vol = wantedVolume[v]
                        if (sound == null || vol <= SILENT) {
                            if (playing[v] != null) { backend.stop(v); playing[v] = null }
                            continue
                        }
                        val p = wantedPitch[v]; val pn = wantedPan[v]
                        if (playing[v] != sound) {
                            if (playing[v] != null) backend.stop(v)
                            backend.start(v, sound, vol, p, pn)
                            playing[v] = sound; volume[v] = vol; pitch[v] = p; pan[v] = pn
                        } else if (abs(vol - volume[v]) >= .01f || abs(p - pitch[v]) >= .005f || abs(pn - pan[v]) >= .02f) {
                            backend.update(v, vol, p, pn)
                            volume[v] = vol; pitch[v] = p; pan[v] = pn
                        }
                    }
                    delay(1000L / APPLY_HZ)
                }
            } catch (failure: Throwable) {
                if (!closed) mutableStatus.value = AudioStatus.Failed(failure.message ?: "Loop audio failed")
            } finally {
                for (v in 0 until voiceCount) if (playing[v] != null) runCatching { backend.stop(v) }
                runCatching { backend.release() }
            }
        }
    }

    /** Plays [sound] on [voice] (restarting only if the sound changes), or silences it when null or volume 0. */
    fun set(voice: Int, sound: SoundId?, volume: Float, pitch: Float = 1f, pan: Float = 0f) {
        require(voice in 0 until voiceCount) { "voice $voice outside 0 until $voiceCount" }
        require(sound == null || sound in known) { "Unknown loop ${sound?.name}" }
        wantedSound[voice] = sound
        wantedVolume[voice] = if (volume.isFinite()) volume.coerceIn(0f, 1f) else 0f
        wantedPitch[voice] = if (pitch.isFinite()) pitch.coerceIn(MIN_PITCH, MAX_PITCH) else 1f
        wantedPan[voice] = if (pan.isFinite()) pan.coerceIn(-1f, 1f) else 0f
    }

    fun silence(voice: Int) = set(voice, null, 0f)

    fun silenceAll() { for (v in 0 until voiceCount) silence(v) }

    /** Background or paused: every voice stops; they resume as set when back in the foreground. */
    fun setForeground(value: Boolean) { foreground = value }

    fun close() {
        if (closed) return
        closed = true
        mutableStatus.value = AudioStatus.Closed
        // The worker's finally block stops and releases native voices; cancel only if it never started.
        scope.launch { delay(500); scope.cancel() }
    }

    companion object {
        const val APPLY_HZ = 30
        const val MIN_PITCH = .5f
        const val MAX_PITCH = 2f
        private const val SILENT = .001f
    }
}

/** Platform side of [LoopVoices]. Called only from its worker coroutine. */
internal interface LoopBackend {
    suspend fun load()
    fun start(voice: Int, sound: SoundId, volume: Float, pitch: Float, pan: Float)
    fun update(voice: Int, volume: Float, pitch: Float, pan: Float)
    fun stop(voice: Int)
    fun release()
}

internal expect fun createLoopBackend(voices: Int, sounds: Map<SoundId, ByteArray>): LoopBackend

/** Balance law shared by backends without a native pan: centre keeps full volume in both ears. */
internal fun leftGain(volume: Float, pan: Float) = volume * minOf(1f, 1f - pan)
internal fun rightGain(volume: Float, pan: Float) = volume * minOf(1f, 1f + pan)
