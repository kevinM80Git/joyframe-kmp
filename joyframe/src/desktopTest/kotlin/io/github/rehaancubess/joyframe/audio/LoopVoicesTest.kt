// SPDX-License-Identifier: Apache-2.0
package io.github.rehaancubess.joyframe.audio

import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LoopVoicesTest {
    private class FakeBackend : LoopBackend {
        val calls: MutableList<String> = Collections.synchronizedList(mutableListOf())
        override suspend fun load() { calls += "load" }
        override fun start(voice: Int, sound: SoundId, volume: Float, pitch: Float, pan: Float) { calls += "start $voice ${sound.name} $pitch" }
        override fun update(voice: Int, volume: Float, pitch: Float, pan: Float) { calls += "update $voice $pitch" }
        override fun stop(voice: Int) { calls += "stop $voice" }
        override fun release() { calls += "release" }
    }

    private val engine = SoundId("engine")
    private val skid = SoundId("skid")
    private fun settle() = Thread.sleep(120)

    @Test fun startsUpdatesOnlyAudibleChangesAndStops() {
        val backend = FakeBackend()
        val voices = LoopVoices(backend, setOf(engine, skid), 2)
        voices.set(0, engine, .5f, pitch = 1f)
        settle()
        assertTrue("start 0 engine 1.0" in backend.calls, "${backend.calls}")
        backend.calls.clear()
        voices.set(0, engine, .5f, pitch = 1.002f) // below the pitch threshold
        settle()
        assertTrue(backend.calls.none { it.startsWith("update") }, "inaudible change skipped: ${backend.calls}")
        voices.set(0, engine, .5f, pitch = 1.3f)
        settle()
        assertTrue(backend.calls.any { it == "update 0 1.3" }, "${backend.calls}")
        voices.set(0, skid, .5f)
        settle()
        assertTrue(backend.calls.containsAll(listOf("stop 0", "start 0 skid 1.0")), "sound change restarts: ${backend.calls}")
        backend.calls.clear()
        voices.set(0, skid, 0f)
        settle()
        assertEquals(listOf("stop 0"), backend.calls.toList())
        voices.close()
    }

    @Test fun backgroundStopsAndForegroundResumes() {
        val backend = FakeBackend()
        val voices = LoopVoices(backend, setOf(engine), 1)
        voices.set(0, engine, .8f, pitch = 1.5f)
        settle()
        voices.setForeground(false)
        settle()
        assertTrue(backend.calls.last() == "stop 0", "${backend.calls}")
        voices.setForeground(true)
        settle()
        assertTrue(backend.calls.last() == "start 0 engine 1.5", "${backend.calls}")
        voices.close()
        settle()
        assertTrue("release" in backend.calls)
    }

    @Test fun pitchIsClampedToTheSoundPoolRange() {
        val backend = FakeBackend()
        val voices = LoopVoices(backend, setOf(engine), 1)
        voices.set(0, engine, 1f, pitch = 9f)
        settle()
        assertTrue("start 0 engine 2.0" in backend.calls, "${backend.calls}")
        voices.close()
    }
}
