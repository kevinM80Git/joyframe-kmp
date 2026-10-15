// SPDX-License-Identifier: Apache-2.0
package io.github.rehaancubess.joyframe.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Process

internal actual fun createLoopBackend(voices: Int, sounds: Map<SoundId, ByteArray>): LoopBackend =
    MixedLoops(voices, sounds)

/**
 * Every voice mixed in software into one stereo [AudioTrack] by one audio-priority thread.
 *
 * Earlier this was a SoundPool per [LoopVoices]: one native stream per voice, each resampled for
 * its pitch on SoundPool's own thread and then mixed again by the system mixer. Measured on a Fire
 * TV Stick 4K (2nd gen) with 18 looping voices, that cost ~22% of a core in the app plus ~45% in
 * the audio server and HAL. Here the voices cost a few multiply-adds per sample and the system
 * mixes one track.
 *
 * Pitch is linear interpolation over the loop; gain changes ramp across one block, so updates at
 * [LoopVoices.APPLY_HZ] never click. Voice parameters are written by the [LoopVoices] worker and read
 * by the mixer thread; a block may see a change a block late, which nobody can hear.
 */
private class MixedLoops(private val voices: Int, private val sounds: Map<SoundId, ByteArray>) : LoopBackend {
    private val pcm = HashMap<SoundId, ShortArray>()
    private val mixer = LoopMixer(voices, BLOCK)
    private var track: AudioTrack? = null
    private var thread: Thread? = null
    @Volatile private var running = false

    override suspend fun load() {
        for ((id, wav) in sounds) pcm[id] = Wav.decode(wav)
        val min = AudioTrack.getMinBufferSize(Wav.SAMPLE_RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        val bytes = maxOf(min, BLOCK * 4 * 4) // at least four blocks queued: no underruns on a busy CPU
        val created = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(Wav.SAMPLE_RATE)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(bytes)
            .build()
        track = created
        running = true
        created.play()
        thread = Thread({ mixLoop(created) }, "JoyframeLoopMixer").apply { isDaemon = true; start() }
    }

    private fun mixLoop(out: AudioTrack) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        val block = ShortArray(BLOCK * 2)
        while (running) {
            mixer.mix(block)
            // Blocking write: paces this thread to the hardware.
            if (out.write(block, 0, block.size) < 0) break
        }
    }

    override fun start(voice: Int, sound: SoundId, volume: Float, pitch: Float, pan: Float) {
        mixer.start(voice, pcm[sound] ?: return, volume, pitch, pan)
    }

    override fun update(voice: Int, volume: Float, pitch: Float, pan: Float) = mixer.update(voice, volume, pitch, pan)

    override fun stop(voice: Int) = mixer.stop(voice)

    override fun release() {
        running = false
        thread?.join(500)
        thread = null
        track?.let { runCatching { it.stop() }; it.release() }
        track = null
        pcm.clear()
    }

    private companion object {
        /** Frames per mix block (~11.6 ms at 44.1 kHz). */
        const val BLOCK = 512
    }
}
