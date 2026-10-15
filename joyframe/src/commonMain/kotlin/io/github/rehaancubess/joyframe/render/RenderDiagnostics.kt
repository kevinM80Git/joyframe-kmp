// SPDX-License-Identifier: Apache-2.0
package io.github.rehaancubess.joyframe.render

import kotlin.concurrent.Volatile

/**
 * What the renderer did for its most recent presented frame, summed over every split-screen pane.
 *
 * [submitMillis] is CPU time on the render thread to cull, sort and issue the frame's GL calls. It is
 * not GPU time: a fill-bound frame can submit in 2 ms and still miss vsync, which shows up as a long
 * frame interval instead. [frame] increases once per presented frame, so the rate of change of it is
 * the render thread's own frame rate.
 *
 * Instances count scene instances (not model parts): [instancesDrawn] had at least one part inside a
 * pane's frustum, [instancesCulled] had none. A frame with four panes counts every instance four times.
 */
data class RenderStats(
    val frame: Long = 0,
    val submitMillis: Float = 0f,
    val panes: Int = 0,
    val drawCalls: Int = 0,
    val shadowDrawCalls: Int = 0,
    val triangles: Int = 0,
    val instancesDrawn: Int = 0,
    val instancesCulled: Int = 0,
    /** Part instances drawn through instanced batches (draw calls saved = this − batched draws). */
    val instancesBatched: Int = 0,
    /**
     * The CPU side of [submitMillis] spent preparing (camera, frustum culling, sorting, batching and
     * writing instance records); the rest is issuing GL calls.
     */
    val prepareMillis: Float = 0f,
    /**
     * Only while [RenderDiagnostics.syncGpu] is on: how long `glFinish` waited after the last call,
     * i.e. GPU work still queued when the CPU was done. Approximates GPU time on GPUs without timer
     * queries (it also stalls the pipeline, so leave it off outside diagnostics). -1 when off.
     */
    val gpuWaitMillis: Float = -1f,
    /** Draw calls made with the lean scene program (the rest used the full one). */
    val liteDrawCalls: Int = 0,
)

/**
 * Latest [RenderStats] from the active GL backend, written by the render thread and safe to read from
 * the UI thread. Diagnostics only: the toolkit never reads it back. Backends that do not report
 * (Metal today) leave [latest] at [RenderStats.frame] 0.
 */
object RenderDiagnostics {
    @Volatile var latest: RenderStats = RenderStats()
        internal set

    /** Diagnostics: end every frame with `glFinish` and report the wait as [RenderStats.gpuWaitMillis]. */
    @Volatile var syncGpu: Boolean = false

    /**
     * Diagnostics only: leave out a part of every frame to measure what it costs (with [syncGpu]).
     * Bits: [SKIP_SKY] the sky pass, [SKIP_OPAQUE] opaque draws, [SKIP_BLENDED] alpha and additive draws.
     */
    @Volatile var skip: Int = 0
    /** Diagnostics: draw everything with the full scene program (to compare against the lean one). */
    @Volatile var fullShaderOnly: Boolean = false
    const val SKIP_SKY = 1
    const val SKIP_OPAQUE = 2
    const val SKIP_BLENDED = 4
    /** Only the alpha / additive draws of static scenery (with [SKIP_BLENDED] off, the dynamic ones stay). */
    const val SKIP_STATIC_BLENDED = 8
}
