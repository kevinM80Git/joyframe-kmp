// SPDX-License-Identifier: Apache-2.0
package io.github.rehaancubess.joyframe.render

import io.github.rehaancubess.joyframe.render.gpu.GpuCamera
import io.github.rehaancubess.joyframe.render.gpu.GpuMaterial
import io.github.rehaancubess.joyframe.render.gpu.GpuSceneAssets
import io.github.rehaancubess.joyframe.render.gpu.GpuSceneFrame
import io.github.rehaancubess.joyframe.render.gpu.InstanceKind
import io.github.rehaancubess.joyframe.render.gpu.ModelDefinition
import io.github.rehaancubess.joyframe.render.gpu.ModelPart
import io.github.rehaancubess.joyframe.render.gpu.PackedColor
import io.github.rehaancubess.joyframe.render.gpu.SceneEnvironment
import io.github.rehaancubess.joyframe.render.gpu.SceneInstance
import io.github.rehaancubess.joyframe.render.gpu.Transform3D
import io.github.rehaancubess.joyframe.render.math.Vec3
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Instanced batching and per-instance tint in the GL backend (macOS offscreen; skipped elsewhere):
 * identical parts share one draw call, tint colours each instance, untintable materials keep their
 * colour, and a hit-flashed instance still renders on its own.
 */
class InstancedBatchTest {
    private val isMac = System.getProperty("os.name").lowercase().contains("mac")

    private fun frame(instances: List<SceneInstance>): GpuSceneFrame {
        val assets = GpuSceneAssets(
            cacheKey = "batch-test",
            meshes = mapOf(
                "cube" to Primitives.box("cube", Vec3(1f, 1f, 1f), "white"),
                "plate" to Primitives.box("plate", Vec3(1f, 1f, 1f), "fixed"),
            ),
            materials = mapOf(
                // Unlit (pure glow) so pixel colours are exactly the tint.
                "white" to GpuMaterial("white", PackedColor.Black, emissive = PackedColor.White, emissiveStrength = 1f),
                "fixed" to GpuMaterial("fixed", PackedColor.Black, emissive = PackedColor(0xff00ff00.toInt()), emissiveStrength = 1f, tintable = false),
            ),
            models = mapOf(
                "cube" to ModelDefinition("cube", listOf(ModelPart("cube"))),
                "plate" to ModelDefinition("plate", listOf(ModelPart("plate"))),
            ),
        )
        return GpuSceneFrame(
            assets = assets,
            camera = GpuCamera(Vec3(0f, 0f, 30f), Vec3.ZERO, verticalFovDegrees = 60f, nearPlane = 0.5f, farPlane = 200f),
            environment = SceneEnvironment(clearColor = PackedColor.Black, ambientColor = PackedColor.Black, fogColor = PackedColor.Black,
                fogStart = 1000f, fogEnd = 2000f, skyZenith = PackedColor.Black, skyHorizon = PackedColor.Black,
                skySun = 0f, skyClouds = 0f, saturation = 1f, contrast = 0f),
            lights = emptyList(),
            instances = instances,
            simulationTick = 0,
            renderTimeSeconds = 0f,
        )
    }

    private fun cube(id: String, x: Float, y: Float, tint: PackedColor, model: String = "cube", hitFlash: Float = 0f) = SceneInstance(
        id, InstanceKind.Static, model, Transform3D(Vec3(x, y, 0f)), Transform3D(Vec3(x, y, 0f)), 1f, emptyMap(),
        tint = tint, hitFlash = hitFlash,
    )

    @Test fun identicalParts_oneDraw_tintPerInstance_untintableKeepsColour() {
        if (!isMac) return
        val red = PackedColor(0xffff0000.toInt()); val blue = PackedColor(0xff0000ff.toInt())
        val many = List(40) { i -> cube("c$i", -14f + (i % 10) * 3f, -6f + (i / 10) * 3f, if (i % 2 == 0) red else blue) }
        OffscreenRenderer().use { renderer ->
            val image = renderer.render(frame(many), 320, 320)
            val stats = RenderDiagnostics.latest
            assertEquals(2, stats.drawCalls, "40 tinted cubes: sky + one instanced draw")
            assertEquals(40, stats.instancesBatched)
            // Centre of cube 0 (x -14, y -6) is red; cube 1 (x -11) is blue.
            fun at(x: Float, y: Float): Int {
                val px = (160 + x / (30f * 0.57735f) * 160).toInt(); val py = (160 - y / (30f * 0.57735f) * 160).toInt()
                return image.getRGB(px, py) and 0xffffff
            }
            assertEquals(0xff0000, at(-14f, -6f))
            assertEquals(0x0000ff, at(-11f, -6f))

            // An untintable material ignores the tint; a flashed instance draws separately but correctly.
            val mixed = listOf(cube("a", -6f, 0f, red, model = "plate"), cube("b", 0f, 0f, red), cube("c", 6f, 0f, red, hitFlash = 0.5f))
            val image2 = renderer.render(frame(mixed), 320, 320)
            fun at2(x: Float, y: Float): Int {
                val px = (160 + x / (30f * 0.57735f) * 160).toInt(); val py = (160 - y / (30f * 0.57735f) * 160).toInt()
                return image2.getRGB(px, py) and 0xffffff
            }
            assertEquals(0x00ff00, at2(-6f, 0f), "untintable stays green")
            assertEquals(0xff0000, at2(0f, 0f))
            assertTrue((at2(6f, 0f) shr 16) and 0xff > 200, "flashed instance drawn")
            assertEquals(4, RenderDiagnostics.latest.drawCalls, "sky + plate + cube + flashed cube")
        }
    }
}
