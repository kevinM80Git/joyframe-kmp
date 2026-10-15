// SPDX-License-Identifier: Apache-2.0
@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package io.github.rehaancubess.joyframe.audio

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.AVFAudio.AVAudioPlayer
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryAmbient
import platform.AVFAudio.setActive
import platform.Foundation.NSData
import platform.Foundation.create

internal actual fun createLoopBackend(voices: Int, sounds: Map<SoundId, ByteArray>): LoopBackend = IosLoops(voices, sounds)

/** An AVAudioPlayer per voice per sound, looping, with enableRate for pitch. */
private class IosLoops(private val voices: Int, private val sounds: Map<SoundId, ByteArray>) : LoopBackend {
    private val players = Array(voices) { HashMap<SoundId, AVAudioPlayer>() }
    private val active = arrayOfNulls<AVAudioPlayer>(voices)

    override suspend fun load() {
        AVAudioSession.sharedInstance().setCategory(AVAudioSessionCategoryAmbient, null)
        AVAudioSession.sharedInstance().setActive(true, null)
        for (v in 0 until voices) for ((id, wav) in sounds) {
            val data = wav.usePinned { NSData.create(bytes = it.addressOf(0), length = wav.size.toULong()) }
            val player = AVAudioPlayer(data = data, error = null)
            player.enableRate = true
            player.numberOfLoops = -1
            check(player.prepareToPlay()) { "AVAudioPlayer could not prepare loop ${id.name}" }
            players[v][id] = player
        }
    }

    override fun start(voice: Int, sound: SoundId, volume: Float, pitch: Float, pan: Float) {
        active[voice]?.stop()
        val player = players[voice][sound] ?: return
        apply(player, volume, pitch, pan)
        player.currentTime = 0.0
        player.play()
        active[voice] = player
    }

    override fun update(voice: Int, volume: Float, pitch: Float, pan: Float) {
        active[voice]?.let { apply(it, volume, pitch, pan) }
    }

    override fun stop(voice: Int) {
        active[voice]?.stop()
        active[voice] = null
    }

    private fun apply(player: AVAudioPlayer, volume: Float, pitch: Float, pan: Float) {
        player.volume = volume
        player.rate = pitch
        player.pan = pan
    }

    override fun release() {
        active.forEach { it?.stop() }
        active.fill(null)
        players.forEach { it.clear() }
    }
}
