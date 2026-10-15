// SPDX-License-Identifier: Apache-2.0
package io.github.rehaancubess.joyframe.render.gpu

import io.github.rehaancubess.joyframe.render.math.Vec3
import io.github.rehaancubess.joyframe.render.water.VortexConfig
import io.github.rehaancubess.joyframe.render.water.VortexSurge
import io.github.rehaancubess.joyframe.render.water.WaterConfig

data class Vec2f(val x: Float = 0f, val y: Float = 0f)

data class PackedColor(val argb: Int) {
    val alpha: Float get() = ((argb ushr 24) and 0xff) / 255f
    val red: Float get() = ((argb ushr 16) and 0xff) / 255f
    val green: Float get() = ((argb ushr 8) and 0xff) / 255f
    val blue: Float get() = (argb and 0xff) / 255f

    companion object {
        val White = PackedColor(0xffffffff.toInt())
        val Black = PackedColor(0xff000000.toInt())
    }
}

enum class GpuBlendMode { Opaque, Alpha, Additive }
enum class GpuCullMode { Back, Front, None }

data class GpuMaterial(
    val id: String,
    val baseColor: PackedColor,

    val albedo: String? = null,
    val roughness: Float = 0.78f,
    val metallic: Float = 0f,
    val emissive: PackedColor = PackedColor.Black,
    val emissiveStrength: Float = 0f,
    val blendMode: GpuBlendMode = if (baseColor.alpha < 0.995f) GpuBlendMode.Alpha else GpuBlendMode.Opaque,
    val depthWrite: Boolean = blendMode == GpuBlendMode.Opaque,
    val cullMode: GpuCullMode = GpuCullMode.None,

    val waterShade: Float = 0f,

    val isVortex: Boolean = false,

    val isFoam: Boolean = false,

    val isBubble: Boolean = false,
    /** Glow colour comes from each vertex's colour (times [emissiveStrength]): one material, any neon. */
    val emissiveFromVertexColor: Boolean = false,
    /** Glow is also multiplied by the [albedo] texture: lit text and signs from one atlas. */
    val emissiveFromAlbedo: Boolean = false,
    /**
     * Takes the instance's [SceneInstance.tint] (base colour and glow). Off for parts that must keep
     * their own colour inside a tinted model (a car's dark cabin, white headlights).
     */
    val tintable: Boolean = true,
    /**
     * Opaque materials only: the glow is masked by each vertex's alpha (0 = no glow, 1 = full), so
     * lit surfaces and light strips can share one mesh and one draw (a car's paint and its neon lines).
     */
    val emissiveMaskFromVertexAlpha: Boolean = false,
) {
    init {
        require(waterShade in 0f..1f) { "$id: waterShade $waterShade outside [0, 1]" }
        require(!isVortex || waterShade > 0f) { "$id: a vortex dish must be water" }
    }
}

data class MeshPrimitive(
    val firstIndex: Int,
    val indexCount: Int,
    val materialId: String,
)

data class Bounds3(
    val minimum: Vec3,
    val maximum: Vec3,
)

class TextureData(
    val id: String,
    val width: Int,
    val height: Int,
    val rgba: ByteArray,
) {
    init {
        require(width > 0 && height > 0) { "$id: texture is ${width}x$height" }
        require(rgba.size == width * height * 4) {
            "$id: expected ${width * height * 4} bytes for ${width}x$height RGBA, got ${rgba.size}"
        }
    }
}

data class IndexedMesh(
    val id: String,
    val positions: List<Vec3>,
    val normals: List<Vec3>,
    val textureCoordinates: List<Vec2f>,
    val indices: List<Int>,
    val primitives: List<MeshPrimitive>,
    val bounds: Bounds3,
    /**
     * Optional per-vertex colour, multiplied into the material's base colour (and its glow when the
     * material sets [GpuMaterial.emissiveFromVertexColor]). Empty means white. Lets one material
     * draw many colours, so a whole chunk of scenery can be a single draw.
     */
    val colors: List<PackedColor> = emptyList(),
) {
    init {
        require(positions.size == normals.size) { "$id: position/normal count mismatch" }
        require(colors.isEmpty() || colors.size == positions.size) { "$id: position/colour count mismatch" }
        require(textureCoordinates.isEmpty() || textureCoordinates.size == positions.size) {
            "$id: position/UV count mismatch"
        }
        require(indices.all { it in positions.indices }) { "$id: index outside vertex buffer" }
        require(primitives.all { it.firstIndex >= 0 && it.indexCount >= 0 && it.firstIndex + it.indexCount <= indices.size }) {
            "$id: primitive outside index buffer"
        }
    }
}

data class Transform3D(
    val translation: Vec3 = Vec3.ZERO,

    val rotation: Vec3 = Vec3.ZERO,
    val scale: Vec3 = Vec3(1f, 1f, 1f),
) {
    fun interpolateTo(other: Transform3D, alpha: Float): Transform3D {
        val t = alpha.coerceIn(0f, 1f)
        fun linear(a: Float, b: Float) = a + (b - a) * t
        fun angle(a: Float, b: Float): Float {
            var delta = (b - a) % TWO_PI
            if (delta > PI_F) delta -= TWO_PI
            if (delta < -PI_F) delta += TWO_PI
            return a + delta * t
        }
        return Transform3D(
            translation = Vec3(
                linear(translation.x, other.translation.x),
                linear(translation.y, other.translation.y),
                linear(translation.z, other.translation.z),
            ),
            rotation = Vec3(
                angle(rotation.x, other.rotation.x),
                angle(rotation.y, other.rotation.y),
                angle(rotation.z, other.rotation.z),
            ),
            scale = Vec3(
                linear(scale.x, other.scale.x),
                linear(scale.y, other.scale.y),
                linear(scale.z, other.scale.z),
            ),
        )
    }

    private companion object {
        const val PI_F = 3.1415927f
        const val TWO_PI = 6.2831855f
    }
}

data class ModelPart(
    val meshId: String,
    val localTransform: Transform3D = Transform3D(),

    val materialSlot: String? = null,
)

data class ModelDefinition(
    val id: String,
    val parts: List<ModelPart>,
)

enum class InstanceKind { Static, Dynamic, Effect }

data class SceneInstance(
    val id: String,
    val kind: InstanceKind,
    val modelId: String,
    val previousTransform: Transform3D,
    val transform: Transform3D,
    val interpolationAlpha: Float,
    val materials: Map<String, String>,
    val visible: Boolean = true,


    val opacity: Float = 1f,


    val hitFlash: Float = 0f,


    val foamAge: Float = 0f,

    /**
     * Multiplies the base colour and glow of every part whose material is [GpuMaterial.tintable]:
     * one white material, any number of colours. Instances that differ only by tint (and transform)
     * share a material and can be drawn together in one instanced draw.
     */
    val tint: PackedColor = PackedColor.White,
) {
    fun interpolatedTransform(): Transform3D =
        // Nothing to blend (statics, effects placed this frame): no new transform.
        if (previousTransform === transform || interpolationAlpha >= 1f) transform
        else previousTransform.interpolateTo(transform, interpolationAlpha)

    /**
     * Renderer scratch: a static instance's final per-part matrices, computed once (instances are
     * immutable, and a game reuses its static ones frame after frame). Not part of equality.
     */
    internal var gpuPartMatrices: Array<FloatArray>? = null
    internal var gpuMatricesFor: Any? = null
}

data class GpuCamera(
    val eye: Vec3,
    val target: Vec3,
    val up: Vec3 = Vec3.UP,
    val verticalFovDegrees: Float,
    val nearPlane: Float,
    val farPlane: Float,
)

data class DirectionalLight(
    val direction: Vec3,
    val color: PackedColor = PackedColor.White,
    val intensity: Float = 2.5f,
    val castsShadow: Boolean = true,
)

data class SceneEnvironment(
    val clearColor: PackedColor = PackedColor(0xff8ed4f8.toInt()),
    val ambientColor: PackedColor = PackedColor(0xffeaf5ff.toInt()),
    val ambientIntensity: Float = 0.74f,
    val fogColor: PackedColor = PackedColor(0xffd9f3fc.toInt()),
    val fogStart: Float = 1_800f,
    val fogEnd: Float = 6_200f,
    /** Sky gradient drawn behind the scene: straight up, at the horizon, and the cloud tint. */
    val skyZenith: PackedColor = PackedColor(0xff4bb8f8.toInt()),
    val skyHorizon: PackedColor = PackedColor(0xffeaf8ff.toInt()),
    val skyCloud: PackedColor = PackedColor(0xfffdfeff.toInt()),
    /** Strength of the built-in sun disc and halo, 0 to hide it (a night sky). */
    val skySun: Float = 1f,
    /** Cloud cover, 0..1. At 0 the per-pixel cloud noise is skipped entirely, which saves fill rate. */
    val skyClouds: Float = 1f,
    /** Colour grade after exposure: saturation (1 = neutral) and an S-curve contrast blend (0 = none). */
    val saturation: Float = 1.24f,
    val contrast: Float = 0.20f,
    /**
     * How much glow punches through fog, 0..1: 0 fogs emissive like everything else; 1 keeps neon at
     * full strength at any distance (the fog still hides the unlit surfaces around it).
     */
    val emissiveFogResistance: Float = 0f,
    /** Star field brightness above the horizon, 0 to skip it (no per-pixel cost). */
    val skyStars: Float = 0f,
    /** A moon (or planet) disc in the sky: direction toward it, angular radius in radians, colour and glow; [moonGlow] 0 hides it. */
    val moonDirection: Vec3 = Vec3(0f, 0.3f, -1f),
    val moonRadius: Float = 0.06f,
    val moonColor: PackedColor = PackedColor(0xfff2ecff.toInt()),
    val moonGlow: Float = 0f,
    /**
     * A distant city silhouette around the horizon, drawn by the sky pass (no geometry, never cut by
     * the far plane): [skyline] is its opacity (0 skips it), [skylineColor] the near layer (a hazier
     * far layer blends toward the horizon), [skylineLights] the colour of its sparse windows.
     */
    val skyline: Float = 0f,
    val skylineColor: PackedColor = PackedColor(0xff0a0816.toInt()),
    val skylineLights: PackedColor = PackedColor(0xffffc890.toInt()),
) {
    init {
        require(skySun.isFinite() && skySun >= 0f) { "skySun must be finite and non-negative" }
        require(skyClouds.isFinite() && skyClouds in 0f..1f) { "skyClouds must be in 0..1" }
        require(emissiveFogResistance in 0f..1f) { "emissiveFogResistance must be in 0..1" }
        require(saturation.isFinite() && saturation >= 0f) { "saturation must be finite and non-negative" }
    }
}

data class GpuSceneAssets(
    val cacheKey: String,
    val meshes: Map<String, IndexedMesh>,
    val materials: Map<String, GpuMaterial>,
    val models: Map<String, ModelDefinition>,
    val textures: Map<String, TextureData> = emptyMap(),
) {
    init {
        require(models.values.flatMap { it.parts }.all { it.meshId in meshes }) { "Model references a missing mesh" }
        require(meshes.values.flatMap { it.primitives }.all { it.materialId in materials }) { "Mesh references a missing material" }
        require(materials.values.all { it.albedo == null || it.albedo in textures }) {
            val missing = materials.values.mapNotNull { it.albedo }.filterNot { it in textures }.distinct()
            "Material references a missing texture: $missing"
        }
        require(meshes.values.none { mesh ->
            mesh.textureCoordinates.isEmpty() && mesh.primitives.any { materials[it.materialId]?.albedo != null }
        }) { "A textured material is used by a mesh that carries no UVs" }
    }
}

data class GpuSceneFrame(
    val assets: GpuSceneAssets,
    val camera: GpuCamera,
    val environment: SceneEnvironment,
    val lights: List<DirectionalLight>,
    val instances: List<SceneInstance>,
    val simulationTick: Long,
    val renderTimeSeconds: Float,

    val water: WaterConfig = WaterConfig(),

    val vortex: VortexConfig = VortexConfig(),

    val vortexSurge: VortexSurge = VortexSurge(),
) {
    init {
        require(instances.all { it.modelId in assets.models }) { "Instance references a missing model" }
        require(instances.all { instance -> instance.materials.values.all { it in assets.materials } }) {
            "Instance references a missing material"
        }
        require(instances.all { instance ->
            assets.models.getValue(instance.modelId).parts.all { part ->
                part.materialSlot == null || part.materialSlot in instance.materials
            }
        }) { "Instance does not bind every model material slot" }
    }
}
