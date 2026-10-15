// SPDX-License-Identifier: Apache-2.0
package io.github.rehaancubess.joyframe

import io.github.rehaancubess.joyframe.render.gpu.Bounds3
import io.github.rehaancubess.joyframe.render.gpu.GpuMaterial
import io.github.rehaancubess.joyframe.render.gpu.IndexedMesh
import io.github.rehaancubess.joyframe.render.gpu.MeshPrimitive
import io.github.rehaancubess.joyframe.render.gpu.PackedColor
import io.github.rehaancubess.joyframe.render.math.Vec3
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class VertexColorTest {
    private fun triangle(colors: List<PackedColor>) = IndexedMesh(
        id = "tri",
        positions = listOf(Vec3(0f, 0f, 0f), Vec3(1f, 0f, 0f), Vec3(0f, 1f, 0f)),
        normals = List(3) { Vec3(0f, 0f, 1f) },
        textureCoordinates = emptyList(),
        indices = listOf(0, 1, 2),
        primitives = listOf(MeshPrimitive(0, 3, "m")),
        bounds = Bounds3(Vec3(0f, 0f, 0f), Vec3(1f, 1f, 0f)),
        colors = colors,
    )

    @Test fun colorsAreOptional() {
        assertEquals(0, triangle(emptyList()).colors.size)
    }

    @Test fun oneColorPerVertex() {
        val mesh = triangle(listOf(PackedColor(0xffff0000.toInt()), PackedColor(0xff00ff00.toInt()), PackedColor(0xff0000ff.toInt())))
        assertEquals(3, mesh.colors.size)
        assertFailsWith<IllegalArgumentException> { triangle(listOf(PackedColor(-1))) }
    }

    @Test fun emissiveModesDefaultOff() {
        val material = GpuMaterial(id = "m", baseColor = PackedColor(-1))
        assertFalse(material.emissiveFromVertexColor)
        assertFalse(material.emissiveFromAlbedo)
    }
}
