// SPDX-License-Identifier: Apache-2.0
package io.github.rehaancubess.joyframe.render.gl

import io.github.rehaancubess.joyframe.render.PaneRect
import io.github.rehaancubess.joyframe.render.RenderDiagnostics
import io.github.rehaancubess.joyframe.render.RenderStats
import io.github.rehaancubess.joyframe.render.SplitLayout
import io.github.rehaancubess.joyframe.render.gpu.GpuBlendMode
import io.github.rehaancubess.joyframe.render.gpu.GpuCullMode
import io.github.rehaancubess.joyframe.render.gpu.GpuMaterial
import io.github.rehaancubess.joyframe.render.gpu.GpuSceneAssets
import io.github.rehaancubess.joyframe.render.gpu.GpuSceneFrame
import io.github.rehaancubess.joyframe.render.gpu.IndexedMesh
import io.github.rehaancubess.joyframe.render.gpu.InstanceKind
import io.github.rehaancubess.joyframe.render.gpu.PackedColor
import io.github.rehaancubess.joyframe.render.gpu.TextureData
import io.github.rehaancubess.joyframe.render.gpu.Transform3D
import io.github.rehaancubess.joyframe.render.math.DepthRange
import io.github.rehaancubess.joyframe.render.math.Mat
import io.github.rehaancubess.joyframe.render.math.Vec3
import io.github.rehaancubess.joyframe.render.water.VortexConfig
import io.github.rehaancubess.joyframe.render.water.VortexSurge
import io.github.rehaancubess.joyframe.render.water.WaterConfig
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sqrt
import kotlin.math.tan
import kotlin.time.TimeSource

/** position 3, normal 3, uv 2, colour 4. */
private const val FLOATS_PER_VERTEX = 12
private const val VERTEX_STRIDE_BYTES = FLOATS_PER_VERTEX * 4

/** Per instance: model matrix 16, normal matrix columns 3 × 4, tint 4. */
private const val FLOATS_PER_INSTANCE = 32
private const val INSTANCE_STRIDE_BYTES = FLOATS_PER_INSTANCE * 4

private const val SHADOW_MAP_SIZE = 2048

private const val FRUSTUM_PLANES = 6

private const val UNIT_SHADOW = 0
private const val UNIT_ALBEDO = 1

private const val SHADOW_HALF_EXTENT = 1_250f
private const val SHADOW_LIGHT_DISTANCE = 2_400f

internal class GlMesh(
    val vertexArray: Int,
    val vertexBuffer: Int,
    val indexBuffer: Int,
    val primitives: List<GlPrimitive>,

    val boundsCenter: Vec3,
    val boundsRadius: Float,
)

internal class GlPrimitive(
    val firstIndex: Int,
    val indexCount: Int,
    val materialId: String,
)

internal class GlAssets(
    val cacheKey: String,
    val meshes: Map<String, GlMesh>,
    val textures: Map<String, Int>,
) {
    /** Models resolved to meshes, local matrices and materials, once per asset set (by model id). */
    private val resolved = HashMap<String, ResolvedModel?>()

    fun resolve(id: String, assets: GpuSceneAssets): ResolvedModel? = resolved.getOrPut(id) {
        val model = assets.models[id] ?: return@getOrPut null
        ResolvedModel(model.parts.mapNotNull { part ->
            val mesh = meshes[part.meshId] ?: return@mapNotNull null
            val identity = part.localTransform == Transform3D()
            val lm = if (identity) null else FloatArray(Mat.SIZE)
            val ln = if (identity) null else FloatArray(Mat.SIZE)
            if (!identity) Mat.compose(part.localTransform, lm!!, ln!!)
            ResolvedPart(mesh, identity, lm, ln, part.materialSlot, mesh.primitives.map { p ->
                val material = assets.materials[p.materialId]
                ResolvedPrimitive(p, material, material?.albedo?.let { textures[it] } ?: 0)
            }.toTypedArray())
        }.toTypedArray())
    }
}

internal class ResolvedModel(val parts: Array<ResolvedPart>)
internal class ResolvedPart(val mesh: GlMesh, val identity: Boolean, val localModel: FloatArray?, val localNormal: FloatArray?,
                            val materialSlot: String?, val primitives: Array<ResolvedPrimitive>)
internal class ResolvedPrimitive(val primitive: GlPrimitive, val material: GpuMaterial?, val albedo: Int)

internal class GlSceneBackend private constructor(
    private val gl: GlApi,
    private val scene: SceneProgram,
    /** Lean variants (no water, vortex or foam), without and with shadow sampling; null if they failed to link. */
    private val litePlain: SceneProgram?,
    private val liteShadow: SceneProgram?,
    private val shadow: ShadowProgram,
    private val sky: SkyProgram,
    private val whiteTexture: Int,
    private val instanceBuffer: Int,
) {
    /** This pane's instance records (all batches), uploaded once before the draws. */
    private var instanceData = FloatArray(FLOATS_PER_INSTANCE * 256)
    private var instanceCapacityBytes = 0
    private val batchStart = IntArray(4096)
    private val batchCount = IntArray(4096)
    private val batchFirst = ArrayList<DrawItem>()
    private val additiveDraws = ArrayList<DrawItem>()
    private val byBatchKey = Comparator<DrawItem> { a, b -> a.batchKey.compareTo(b.batchKey) }

    private val viewMatrix = FloatArray(Mat.SIZE)
    private val projectionMatrix = FloatArray(Mat.SIZE)
    private val viewProjectionMatrix = FloatArray(Mat.SIZE)
    private val lightViewMatrix = FloatArray(Mat.SIZE)
    private val lightProjectionMatrix = FloatArray(Mat.SIZE)
    private val lightViewProjectionMatrix = FloatArray(Mat.SIZE)
    private val instanceModel = FloatArray(Mat.SIZE)
    private val instanceNormal = FloatArray(Mat.SIZE)
    private val partModel = FloatArray(Mat.SIZE)
    private val partNormal = FloatArray(Mat.SIZE)
    private val localModel = FloatArray(Mat.SIZE)
    private val localNormal = FloatArray(Mat.SIZE)

    private val frame = FrameState()

    // The shadow map (a 2048² depth texture, 16 MB) is made the first time a frame casts shadows:
    // scenes without a shadow-casting light (blob shadows, as on Fire TV) never pay for it.
    private var shadowFramebuffer = 0
    private var shadowTexture = 0
    private var shadowsUnavailable = false

    private fun ensureShadowMap(): Boolean {
        if (shadowTexture != 0) return true
        if (shadowsUnavailable) return false
        val texture = gl.genTexture()
        gl.bindTexture(GlConst.TEXTURE_2D, texture)
        gl.texImage2DDepth(SHADOW_MAP_SIZE, SHADOW_MAP_SIZE)
        gl.texParameteri(GlConst.TEXTURE_2D, GlConst.TEXTURE_MIN_FILTER, GlConst.LINEAR)
        gl.texParameteri(GlConst.TEXTURE_2D, GlConst.TEXTURE_MAG_FILTER, GlConst.LINEAR)
        gl.texParameteri(GlConst.TEXTURE_2D, GlConst.TEXTURE_WRAP_S, GlConst.CLAMP_TO_EDGE)
        gl.texParameteri(GlConst.TEXTURE_2D, GlConst.TEXTURE_WRAP_T, GlConst.CLAMP_TO_EDGE)
        gl.texParameteri(GlConst.TEXTURE_2D, GlConst.TEXTURE_COMPARE_MODE, GlConst.COMPARE_REF_TO_TEXTURE)
        gl.texParameteri(GlConst.TEXTURE_2D, GlConst.TEXTURE_COMPARE_FUNC, GlConst.LESS)
        boundAlbedo = -1
        val framebuffer = gl.genFramebuffer()
        gl.bindFramebuffer(framebuffer)
        gl.framebufferDepthTexture(texture)
        gl.disableColorBuffers()
        val complete = gl.framebufferComplete()
        gl.bindFramebuffer(0)
        if (!complete) {
            println("GL: shadow framebuffer incomplete; drawing without shadow maps")
            gl.deleteFramebuffer(framebuffer)
            gl.deleteTexture(texture)
            shadowsUnavailable = true
            return false
        }
        shadowFramebuffer = framebuffer
        shadowTexture = texture
        return true
    }


    private val swellScratch = FloatArray(WaterConfig.SWELL_TRAINS * WaterConfig.FLOATS_PER_TRAIN)
    private val drawPool = ArrayList<DrawItem>()
    private var pooled = 0
    private val opaqueDraws = ArrayList<DrawItem>()
    private val blendedDraws = ArrayList<DrawItem>()
    private val farToNear = Comparator<DrawItem> { a, b -> b.sortDepth.compareTo(a.sortDepth) }

    private var assets: GlAssets? = null
    private var boundVertexArray = -1
    private var currentCull = Int.MIN_VALUE
    private var currentBlend: GpuBlendMode? = null
    private var boundAlbedo = -1


    fun assetsFor(source: GpuSceneAssets): GlAssets {
        assets?.let { if (it.cacheKey == source.cacheKey) return it }
        assets?.let(::release)
        val uploaded = GlAssets(
            cacheKey = source.cacheKey,
            meshes = source.meshes.mapValues { (_, mesh) -> upload(mesh) },
            textures = source.textures.mapValues { (_, texture) -> upload(texture) },
        )
        assets = uploaded
        return uploaded
    }

    private fun upload(mesh: IndexedMesh): GlMesh {
        val vertexData = FloatArray(mesh.positions.size * FLOATS_PER_VERTEX)
        for (i in mesh.positions.indices) {
            val position = mesh.positions[i]
            val normal = mesh.normals[i]
            val uv = mesh.textureCoordinates.getOrNull(i)
            val base = i * FLOATS_PER_VERTEX
            vertexData[base] = position.x
            vertexData[base + 1] = position.y
            vertexData[base + 2] = position.z
            vertexData[base + 3] = normal.x
            vertexData[base + 4] = normal.y
            vertexData[base + 5] = normal.z
            vertexData[base + 6] = uv?.x ?: 0f
            vertexData[base + 7] = uv?.y ?: 0f
            val color = mesh.colors.getOrNull(i)
            vertexData[base + 8] = color?.red ?: 1f
            vertexData[base + 9] = color?.green ?: 1f
            vertexData[base + 10] = color?.blue ?: 1f
            vertexData[base + 11] = color?.alpha ?: 1f
        }
        val indexData = IntArray(mesh.indices.size) { mesh.indices[it] }

        val vertexArray = gl.genVertexArray()
        gl.bindVertexArray(vertexArray)

        val vertexBuffer = gl.genBuffer()
        gl.bindBuffer(GlConst.ARRAY_BUFFER, vertexBuffer)
        gl.bufferData(GlConst.ARRAY_BUFFER, vertexData, GlConst.STATIC_DRAW)

        val indexBuffer = gl.genBuffer()
        gl.bindBuffer(GlConst.ELEMENT_ARRAY_BUFFER, indexBuffer)
        gl.bufferData(GlConst.ELEMENT_ARRAY_BUFFER, indexData, GlConst.STATIC_DRAW)

        gl.enableVertexAttribArray(GlShaders.ATTRIB_POSITION)
        gl.vertexAttribPointer(GlShaders.ATTRIB_POSITION, 3, GlConst.FLOAT, false, VERTEX_STRIDE_BYTES, 0)
        gl.enableVertexAttribArray(GlShaders.ATTRIB_NORMAL)
        gl.vertexAttribPointer(GlShaders.ATTRIB_NORMAL, 3, GlConst.FLOAT, false, VERTEX_STRIDE_BYTES, 12)
        gl.enableVertexAttribArray(GlShaders.ATTRIB_TEXCOORD)
        gl.vertexAttribPointer(GlShaders.ATTRIB_TEXCOORD, 2, GlConst.FLOAT, false, VERTEX_STRIDE_BYTES, 24)
        gl.enableVertexAttribArray(GlShaders.ATTRIB_VERTEX_COLOR)
        gl.vertexAttribPointer(GlShaders.ATTRIB_VERTEX_COLOR, 4, GlConst.FLOAT, false, VERTEX_STRIDE_BYTES, 32)

        // Per-instance attributes from the shared instance buffer; each batch re-points them at its records.
        gl.bindBuffer(GlConst.ARRAY_BUFFER, instanceBuffer)
        ensureInstanceCapacity(INSTANCE_STRIDE_BYTES * 64)
        for (attribute in GlShaders.ATTRIB_INSTANCE_MODEL..GlShaders.ATTRIB_INSTANCE_TINT) {
            gl.enableVertexAttribArray(attribute)
            gl.vertexAttribDivisor(attribute, 1)
        }
        pointInstances(0)

        gl.bindVertexArray(0)
        boundVertexArray = 0

        return GlMesh(
            vertexArray = vertexArray,
            vertexBuffer = vertexBuffer,
            indexBuffer = indexBuffer,
            primitives = mesh.primitives.map { GlPrimitive(it.firstIndex, it.indexCount, it.materialId) },
            boundsCenter = (mesh.bounds.minimum + mesh.bounds.maximum) * 0.5f,
            boundsRadius = (mesh.bounds.maximum - mesh.bounds.minimum).length() * 0.5f,
        )
    }

    /** The bound vertex array's per-instance attributes start at byte [offset] of the instance buffer. */
    private fun pointInstances(offset: Int) {
        for (column in 0 until 4) {
            gl.vertexAttribPointer(GlShaders.ATTRIB_INSTANCE_MODEL + column, 4, GlConst.FLOAT, false, INSTANCE_STRIDE_BYTES, offset + column * 16)
        }
        for (column in 0 until 3) {
            gl.vertexAttribPointer(GlShaders.ATTRIB_INSTANCE_NORMAL + column, 4, GlConst.FLOAT, false, INSTANCE_STRIDE_BYTES, offset + 64 + column * 16)
        }
        gl.vertexAttribPointer(GlShaders.ATTRIB_INSTANCE_TINT, 4, GlConst.FLOAT, false, INSTANCE_STRIDE_BYTES, offset + 112)
    }

    /** With the instance buffer bound: grows it to at least [bytes] (contents are rewritten every pane). */
    private fun ensureInstanceCapacity(bytes: Int) {
        if (bytes <= instanceCapacityBytes) return
        var capacity = maxOf(instanceCapacityBytes, INSTANCE_STRIDE_BYTES * 64)
        while (capacity < bytes) capacity *= 2
        gl.bufferStorage(GlConst.ARRAY_BUFFER, capacity, GlConst.STREAM_DRAW)
        instanceCapacityBytes = capacity
    }

    private fun upload(texture: TextureData): Int {
        val id = gl.genTexture()
        gl.bindTexture(GlConst.TEXTURE_2D, id)
        gl.texImage2DRgba(texture.width, texture.height, texture.rgba)
        gl.texParameteri(GlConst.TEXTURE_2D, GlConst.TEXTURE_MIN_FILTER, GlConst.LINEAR_MIPMAP_LINEAR)
        gl.texParameteri(GlConst.TEXTURE_2D, GlConst.TEXTURE_MAG_FILTER, GlConst.LINEAR)
        gl.texParameteri(GlConst.TEXTURE_2D, GlConst.TEXTURE_WRAP_S, GlConst.REPEAT)
        gl.texParameteri(GlConst.TEXTURE_2D, GlConst.TEXTURE_WRAP_T, GlConst.REPEAT)
        gl.generateMipmap(GlConst.TEXTURE_2D)
        boundAlbedo = -1
        return id
    }

    private fun release(stale: GlAssets) {
        stale.meshes.values.forEach { mesh ->
            gl.deleteVertexArray(mesh.vertexArray)
            gl.deleteBuffer(mesh.vertexBuffer)
            gl.deleteBuffer(mesh.indexBuffer)
        }
        stale.textures.values.forEach(gl::deleteTexture)
        boundAlbedo = -1
    }


    /**
     * [pane], when given, is one split-screen viewport in GL's bottom-left-origin pixels: the projection
     * takes that pane's aspect, and the clear and every draw stay inside it, so panes already drawn this
     * frame survive.
     */
    fun render(source: GpuSceneFrame, widthPixels: Int, heightPixels: Int, targetFramebuffer: Int = 0,
               background: (() -> Unit)? = null, pane: PaneRect? = null) {
        val width = widthPixels.coerceAtLeast(1)
        val height = heightPixels.coerceAtLeast(1)
        val area = pane ?: PaneRect(0, 0, width, height)
        val uploaded = assetsFor(source.assets)
        val prepared = TimeSource.Monotonic.markNow()
        buildFrameState(source, area.aspect)
        collectDraws(source, uploaded)
        statPrepareMicros += prepared.elapsedNow().inWholeMicroseconds
        boundVertexArray = -1
        currentCull = Int.MIN_VALUE
        currentBlend = null
        boundAlbedo = -1
        gl.frontFace(GlConst.CCW)

        renderShadowPass()
        renderScenePass(source, area, pane != null, targetFramebuffer, background)
    }

    /**
     * Every frame into its own pane of one surface, laid out by [SplitLayout]. The gutter between
     * panes is the clear the panes do not cover. Frames should share one asset set (cache key), or
     * the backend re-uploads geometry for each pane.
     */
    fun renderPanes(frames: List<GpuSceneFrame>, widthPixels: Int, heightPixels: Int, gutter: PackedColor,
                    gapPixels: Int, targetFramebuffer: Int = 0) {
        val started = TimeSource.Monotonic.markNow()
        resetStats()
        renderPaneList(frames, widthPixels, heightPixels, gutter, gapPixels, targetFramebuffer)
        val submit = started.elapsedNow().inWholeMicroseconds / 1000f
        var gpuWait = -1f
        if (RenderDiagnostics.syncGpu) {
            val issued = TimeSource.Monotonic.markNow()
            gl.finish()
            gpuWait = issued.elapsedNow().inWholeMicroseconds / 1000f
        }
        publishStats(frames.size, submit, gpuWait)
    }

    private fun renderPaneList(frames: List<GpuSceneFrame>, widthPixels: Int, heightPixels: Int, gutter: PackedColor,
                               gapPixels: Int, targetFramebuffer: Int) {
        if (frames.size <= 1) {
            frames.firstOrNull()?.let { render(it, widthPixels, heightPixels, targetFramebuffer) }
            return
        }
        val width = widthPixels.coerceAtLeast(1)
        val height = heightPixels.coerceAtLeast(1)
        gl.bindFramebuffer(targetFramebuffer)
        gl.viewport(0, 0, width, height)
        gl.disable(GlConst.SCISSOR_TEST)
        gl.clearColor(gutter.red, gutter.green, gutter.blue, 1f)
        gl.depthMask(true)
        gl.clear(GlConst.COLOR_BUFFER_BIT or GlConst.DEPTH_BUFFER_BIT)
        SplitLayout.panes(frames.size, width, height, gapPixels).forEachIndexed { index, rect ->
            render(frames[index], width, height, targetFramebuffer, pane = rect.flippedY(height))
        }
    }

    private var statDrawCalls = 0
    private var statShadowDrawCalls = 0
    private var statTriangles = 0
    private var statInstancesDrawn = 0
    private var statInstancesCulled = 0
    private var statInstancesBatched = 0
    private var statFrame = 0L

    private var statPrepareMicros = 0L
    private var statLiteDraws = 0

    private fun resetStats() {
        statPrepareMicros = 0L
        statLiteDraws = 0
        statDrawCalls = 0; statShadowDrawCalls = 0; statTriangles = 0
        statInstancesDrawn = 0; statInstancesCulled = 0; statInstancesBatched = 0
    }

    private fun publishStats(panes: Int, submitMillis: Float, gpuWaitMillis: Float = -1f) {
        RenderDiagnostics.latest = RenderStats(++statFrame, submitMillis, panes, statDrawCalls,
            statShadowDrawCalls, statTriangles, statInstancesDrawn, statInstancesCulled, statInstancesBatched,
            statPrepareMicros / 1000f, gpuWaitMillis, statLiteDraws)
    }

    private fun buildFrameState(source: GpuSceneFrame, aspect: Float) {
        val camera = source.camera
        val near = camera.nearPlane.coerceAtLeast(1f)
        val far = camera.farPlane.coerceAtLeast(near + 1f)
        Mat.lookAt(camera.eye, camera.target, camera.up, viewMatrix)
        Mat.perspective(
            camera.verticalFovDegrees,
            aspect,
            near,
            far,
            DepthRange.NegativeOneToOne,
            projectionMatrix,
        )
        Mat.multiply(projectionMatrix, viewMatrix, viewProjectionMatrix)

        val key = source.lights.firstOrNull()
        val fill = source.lights.getOrNull(1)
        val shadowCaster = source.lights.firstOrNull { it.castsShadow }
        buildLightMatrices(shadowCaster?.direction ?: Vec3(0f, -1f, 0f), camera.target)
        Mat.multiply(lightProjectionMatrix, lightViewMatrix, lightViewProjectionMatrix)

        frame.cameraPosition = camera.eye
        frame.keyDirection = key?.direction ?: Vec3(0f, -1f, 0f)
        frame.setKeyColor(key?.color ?: PackedColor.White, key?.intensity ?: 1f)
        frame.fillDirection = fill?.direction ?: Vec3(0f, 1f, 0f)
        frame.setFillColor(fill?.color ?: PackedColor.Black, fill?.intensity ?: 0f)

        val environment = source.environment
        frame.setAmbient(environment.ambientColor, environment.ambientIntensity)
        frame.setFog(environment.fogColor, environment.fogStart, environment.fogEnd)
        frame.saturation = environment.saturation
        frame.contrast = environment.contrast
        frame.emissiveFogResistance = environment.emissiveFogResistance
        frame.shadowStrength = if (shadowCaster != null && ensureShadowMap()) SHADOW_STRENGTH else 0f

        val forward = (camera.target - camera.eye).normalized()
        var right = forward.cross(camera.up)
        right = if (right.length() > 1e-4f) right.normalized() else Vec3(1f, 0f, 0f)
        frame.skyForward = forward
        frame.skyRight = right
        frame.skyUp = right.cross(forward).normalized()
        frame.tanHalfFov = tan(camera.verticalFovDegrees * 0.5f * (PI_F / 180f))
        frame.aspect = aspect
        frame.timeSeconds = source.renderTimeSeconds
    }


    private fun buildLightMatrices(direction: Vec3, focus: Vec3) {
        val forward = direction.normalized()
        val up = if (abs(forward.y) > 0.985f) Vec3(0f, 0f, 1f) else Vec3.UP
        val eye = focus - forward * SHADOW_LIGHT_DISTANCE
        Mat.lookAt(eye, focus, up, lightViewMatrix)

        val texelWorldSize = 2f * SHADOW_HALF_EXTENT / SHADOW_MAP_SIZE.toFloat()
        val focusX = lightViewMatrix[0] * focus.x + lightViewMatrix[4] * focus.y +
            lightViewMatrix[8] * focus.z + lightViewMatrix[12]
        val focusY = lightViewMatrix[1] * focus.x + lightViewMatrix[5] * focus.y +
            lightViewMatrix[9] * focus.z + lightViewMatrix[13]
        val offsetX = focusX - floor(focusX / texelWorldSize + 0.5f) * texelWorldSize
        val offsetY = focusY - floor(focusY / texelWorldSize + 0.5f) * texelWorldSize

        Mat.orthographic(
            halfWidth = SHADOW_HALF_EXTENT,
            halfHeight = SHADOW_HALF_EXTENT,
            near = 1f,
            far = SHADOW_LIGHT_DISTANCE * 2f,
            depthRange = DepthRange.NegativeOneToOne,
            out = lightProjectionMatrix,
        )
        lightProjectionMatrix[12] = -offsetX / SHADOW_HALF_EXTENT
        lightProjectionMatrix[13] = -offsetY / SHADOW_HALF_EXTENT
    }


    private val frustumPlanes = FloatArray(FRUSTUM_PLANES * 4)

    private fun extractFrustum(m: FloatArray) {
        fun row(index: Int, out: Int, sign: Float) {
            frustumPlanes[out] = m[3] + sign * m[index]
            frustumPlanes[out + 1] = m[7] + sign * m[4 + index]
            frustumPlanes[out + 2] = m[11] + sign * m[8 + index]
            frustumPlanes[out + 3] = m[15] + sign * m[12 + index]
        }
        row(0, 0, 1f)    // left
        row(0, 4, -1f)   // right
        row(1, 8, 1f)    // bottom
        row(1, 12, -1f)  // top
        row(2, 16, 1f)   // near, for the -1..1 depth range this backend projects into
        row(2, 20, -1f)  // far
        for (plane in 0 until FRUSTUM_PLANES) {
            val base = plane * 4
            val a = frustumPlanes[base]
            val b = frustumPlanes[base + 1]
            val c = frustumPlanes[base + 2]
            val inverse = 1f / sqrt(a * a + b * b + c * c).coerceAtLeast(1e-6f)
            frustumPlanes[base] = a * inverse
            frustumPlanes[base + 1] = b * inverse
            frustumPlanes[base + 2] = c * inverse
            frustumPlanes[base + 3] *= inverse
        }
    }


    private fun insideFrustum(mesh: GlMesh, model: FloatArray): Boolean {
        val c = mesh.boundsCenter
        val x = model[0] * c.x + model[4] * c.y + model[8] * c.z + model[12]
        val y = model[1] * c.x + model[5] * c.y + model[9] * c.z + model[13]
        val z = model[2] * c.x + model[6] * c.y + model[10] * c.z + model[14]
        val sx = model[0] * model[0] + model[1] * model[1] + model[2] * model[2]
        val sy = model[4] * model[4] + model[5] * model[5] + model[6] * model[6]
        val sz = model[8] * model[8] + model[9] * model[9] + model[10] * model[10]
        val radius = mesh.boundsRadius * sqrt(maxOf(sx, sy, sz))
        for (plane in 0 until FRUSTUM_PLANES) {
            val base = plane * 4
            val distance = frustumPlanes[base] * x + frustumPlanes[base + 1] * y +
                frustumPlanes[base + 2] * z + frustumPlanes[base + 3]
            if (distance < -radius) return false
        }
        return true
    }

    private fun obtain(): DrawItem {
        if (pooled == drawPool.size) drawPool.add(DrawItem())
        return drawPool[pooled++]
    }

    private fun collectDraws(source: GpuSceneFrame, uploaded: GlAssets) {
        extractFrustum(viewProjectionMatrix)
        pooled = 0
        opaqueDraws.clear()
        blendedDraws.clear()

        val eye = source.camera.eye
        val ex = eye.x; val ey = eye.y; val ez = eye.z
        val instances = source.instances
        for (n in instances.indices) {
            val instance = instances[n]
            if (!instance.visible) continue
            val resolved = uploaded.resolve(instance.modelId, source.assets) ?: continue
            val hitFlash = instance.hitFlash.coerceIn(0f, 1f)
            val opacity = instance.opacity.coerceIn(0f, 1f)
            val foamAge = instance.foamAge.coerceIn(0f, 1f)
            val tint = instance.tint
            val castsShadow = instance.kind != InstanceKind.Effect
            val parts = resolved.parts
            // Static instances keep their final part matrices (computed once per asset set); the rest
            // compose theirs here, without allocating when there is nothing to interpolate.
            val static = instance.kind == InstanceKind.Static
            var cache = if (static && instance.gpuMatricesFor === resolved) instance.gpuPartMatrices else null
            if (cache == null) {
                Mat.compose(instance.interpolatedTransform(), instanceModel, instanceNormal)
                if (static) {
                    cache = Array(parts.size * 2) { FloatArray(Mat.SIZE) }
                    for (i in parts.indices) composePart(parts[i], cache[i * 2], cache[i * 2 + 1])
                    instance.gpuPartMatrices = cache
                    instance.gpuMatricesFor = resolved
                }
            }
            // Sort depth from the instance origin (the model matrix's translation).
            val tx: Float; val ty: Float; val tz: Float
            if (cache != null && parts.isNotEmpty() && parts[0].identity) {
                val m = cache[0]; tx = m[12]; ty = m[13]; tz = m[14]
            } else if (cache != null) {
                // Static with a non-identity first part: recover the instance origin once more.
                Mat.compose(instance.interpolatedTransform(), instanceModel, instanceNormal)
                tx = instanceModel[12]; ty = instanceModel[13]; tz = instanceModel[14]
            } else { tx = instanceModel[12]; ty = instanceModel[13]; tz = instanceModel[14] }
            val dx = tx - ex; val dy = ty - ey; val dz = tz - ez
            val sortDepth = dx * dx + dy * dy + dz * dz
            var anyInView = false

            for (i in parts.indices) {
                val part = parts[i]
                val model: FloatArray; val normal: FloatArray
                if (cache != null) { model = cache[i * 2]; normal = cache[i * 2 + 1] }
                else { composePart(part, partModel, partNormal); model = partModel; normal = partNormal }
                val mesh = part.mesh
                val inView = instance.kind == InstanceKind.Dynamic || insideFrustum(mesh, model)
                if (inView) anyInView = true

                for (primitive in part.primitives) {
                    if (primitive.primitive.indexCount == 0) continue
                    val material: GpuMaterial
                    val albedo: Int
                    val slot = part.materialSlot
                    val override = if (slot != null) instance.materials[slot] else null
                    if (override != null) {
                        material = source.assets.materials[override] ?: continue
                        albedo = material.albedo?.let { uploaded.textures[it] } ?: 0
                    } else {
                        material = primitive.material ?: continue
                        albedo = primitive.albedo
                    }
                    val item = obtain()
                    item.fill(mesh, primitive.primitive, material, albedo, hitFlash, opacity, castsShadow, sortDepth)
                    if (material.tintable) item.setTint(tint) else item.setTint(PackedColor.White)
                    item.foamAge = foamAge
                    item.inView = inView
                    item.isStatic = static
                    model.copyInto(item.model)
                    normal.copyInto(item.normal)
                    if (item.blendMode == GpuBlendMode.Opaque) opaqueDraws.add(item) else blendedDraws.add(item)
                }
            }
            if (anyInView) statInstancesDrawn++ else statInstancesCulled++
        }
        if (blendedDraws.size > 1) blendedDraws.sortWith(farToNear)
    }

    /** The instance matrices (in [instanceModel] / [instanceNormal]) times [part]'s local transform. */
    private fun composePart(part: ResolvedPart, model: FloatArray, normal: FloatArray) {
        if (part.identity) {
            instanceModel.copyInto(model)
            instanceNormal.copyInto(normal)
        } else {
            Mat.multiply(instanceModel, part.localModel!!, model)
            Mat.multiply(instanceNormal, part.localNormal!!, normal)
        }
    }

    private fun renderShadowPass() {
        if (frame.shadowStrength <= 0.001f) return

        gl.bindFramebuffer(shadowFramebuffer)
        gl.viewport(0, 0, SHADOW_MAP_SIZE, SHADOW_MAP_SIZE)
        gl.enable(GlConst.DEPTH_TEST)
        gl.depthFunc(GlConst.LESS)
        gl.depthMask(true)
        gl.disable(GlConst.BLEND)
        gl.clear(GlConst.DEPTH_BUFFER_BIT)
        gl.enable(GlConst.POLYGON_OFFSET_FILL)
        gl.polygonOffset(SHADOW_SLOPE_BIAS, 0f)

        gl.useProgram(shadow.program)
        gl.uniformMatrix4(shadow.lightViewProjection, lightViewProjectionMatrix)
        opaqueDraws.forEach { item ->
            if (!item.castsShadow) return@forEach
            applyCull(if (item.cullMode == GpuCullMode.None) GpuCullMode.None else GpuCullMode.Back)
            bindMesh(item.mesh ?: return@forEach)
            gl.uniformMatrix4(shadow.model, item.model)
            gl.drawElements(GlConst.TRIANGLES, item.indexCount, GlConst.UNSIGNED_INT, item.firstIndex * 4)
            statShadowDrawCalls++
        }

        gl.disable(GlConst.POLYGON_OFFSET_FILL)
    }

    private fun renderScenePass(source: GpuSceneFrame, pane: PaneRect, scissored: Boolean, targetFramebuffer: Int, background: (() -> Unit)?) {
        gl.bindFramebuffer(targetFramebuffer)
        gl.viewport(pane.x, pane.y, pane.width, pane.height)
        if (scissored) {
            gl.enable(GlConst.SCISSOR_TEST)
            gl.scissor(pane.x, pane.y, pane.width, pane.height)
        }

        val clear = source.environment.clearColor
        gl.clearColor(clear.red, clear.green, clear.blue, 1f)
        gl.depthMask(true)
        gl.clear(GlConst.COLOR_BUFFER_BIT or GlConst.DEPTH_BUFFER_BIT)

        if (background != null) {
            background()
            boundVertexArray = -1
            currentCull = Int.MIN_VALUE
            currentBlend = null
            boundAlbedo = -1
        } else if (RenderDiagnostics.skip and RenderDiagnostics.SKIP_SKY == 0) {
        gl.disable(GlConst.DEPTH_TEST)
        gl.depthMask(false)
        gl.disable(GlConst.BLEND)
        applyCull(GpuCullMode.None)
        gl.useProgram(sky.program)
        gl.uniform4(sky.forward, frame.skyForward.x, frame.skyForward.y, frame.skyForward.z, frame.tanHalfFov)
        gl.uniform4(sky.right, frame.skyRight.x, frame.skyRight.y, frame.skyRight.z, frame.aspect)
        gl.uniform4(sky.up, frame.skyUp.x, frame.skyUp.y, frame.skyUp.z, frame.timeSeconds)
        val env = source.environment
        gl.uniform4(sky.zenithColor, env.skyZenith.red, env.skyZenith.green, env.skyZenith.blue, env.skySun)
        gl.uniform4(sky.horizonColor, env.skyHorizon.red, env.skyHorizon.green, env.skyHorizon.blue, 1f)
        gl.uniform4(sky.cloudColor, env.skyCloud.red, env.skyCloud.green, env.skyCloud.blue, env.skyClouds)
        val moon = env.moonDirection
        gl.uniform4(sky.moon, moon.x, moon.y, moon.z, env.moonRadius)
        gl.uniform4(sky.moonColor, env.moonColor.red, env.moonColor.green, env.moonColor.blue, env.moonGlow)
        gl.uniform4(sky.stars, env.skyStars, 0f, 0f, 0f)
        gl.uniform4(sky.skyline, 0f, 0f, 0f, env.skyline)
        gl.uniform4(sky.skylineColor, env.skylineColor.red, env.skylineColor.green, env.skylineColor.blue, 1f)
        gl.uniform4(sky.skylineLights, env.skylineLights.red, env.skylineLights.green, env.skylineLights.blue, 1f)
        bindVertexArray(sky.emptyVertexArray)
        gl.drawArrays(GlConst.TRIANGLES, 0, 3)
        statDrawCalls++
        }

        // Batches: opaque by state (order does not matter), alpha in far-to-near runs, additive by
        // state after the alpha draws (adding is order-independent). One upload, then one draw per batch.
        val batching = TimeSource.Monotonic.markNow()
        val batches = buildBatches()
        statPrepareMicros += batching.elapsedNow().inWholeMicroseconds
        uploadInstances()

        // The lean program draws everything that is not water, vortex or foam; the full one the rest.
        val lite = if (RenderDiagnostics.fullShaderOnly) null else if (frame.shadowStrength > 0.001f) liteShadow else litePlain
        var needFull = lite == null
        if (!needFull) for (b in 0 until batches) if (needsFull(batchFirst[b])) { needFull = true; break }
        if (needFull) {
            gl.useProgram(scene.program)
            frameUniforms(scene, source)
        }
        if (lite != null) {
            gl.useProgram(lite.program)
            frameUniforms(lite, source)
        }
        var current = if (lite != null) lite else scene
        gl.activeTexture(GlConst.TEXTURE0 + UNIT_ALBEDO)

        gl.enable(GlConst.DEPTH_TEST)
        gl.depthFunc(GlConst.LESS)
        gl.depthMask(true)
        val skip = RenderDiagnostics.skip
        for (b in 0 until batches) {
            val item = batchFirst[b]
            if (b == opaqueBatches) gl.depthMask(false)
            if (skip != 0 && (if (b < opaqueBatches) skip and RenderDiagnostics.SKIP_OPAQUE else skip and RenderDiagnostics.SKIP_BLENDED) != 0) continue
            if (skip and RenderDiagnostics.SKIP_STATIC_BLENDED != 0 && b >= opaqueBatches && item.isStatic) continue
            val program = if (lite != null && !needsFull(item)) lite else scene
            if (program !== current) { gl.useProgram(program.program); current = program }
            applyBlend(item.blendMode)
            encode(item, batchStart[b], batchCount[b], program)
        }

        gl.depthMask(true)
        bindVertexArray(0)
        if (scissored) gl.disable(GlConst.SCISSOR_TEST)
    }


    private fun needsFull(item: DrawItem) = item.waterShade > 0f || item.isVortex || item.isFoam

    /** Per-pane uniforms for [p] (which must be in use). The lite program takes fog distances / 1024. */
    private fun frameUniforms(p: SceneProgram, source: GpuSceneFrame) {
        gl.uniformMatrix4(p.viewProjection, viewProjectionMatrix)
        gl.uniformMatrix4(p.lightViewProjection, lightViewProjectionMatrix)
        gl.uniform4(p.cameraPosition, frame.cameraPosition.x, frame.cameraPosition.y, frame.cameraPosition.z, 0f)
        val k = frame.keyDirection; val kl = 1f / sqrt(k.x * k.x + k.y * k.y + k.z * k.z).coerceAtLeast(1e-6f)
        val f = frame.fillDirection; val fl = 1f / sqrt(f.x * f.x + f.y * f.y + f.z * f.z).coerceAtLeast(1e-6f)
        gl.uniform4(p.lightDirection, k.x * kl, k.y * kl, k.z * kl, 0f)
        gl.uniform4(p.lightColor, frame.keyRed, frame.keyGreen, frame.keyBlue, 1f)
        gl.uniform4(p.fillDirection, f.x * fl, f.y * fl, f.z * fl, 0f)
        gl.uniform4(p.fillColor, frame.fillRed, frame.fillGreen, frame.fillBlue, 1f)
        gl.uniform4(p.ambientColor, frame.ambientRed, frame.ambientGreen, frame.ambientBlue, 1f)
        gl.uniform4(p.fogColor, frame.fogRed, frame.fogGreen, frame.fogBlue, 1f)
        val fogScale = if (p.lite) 1f / 1024f else 1f
        gl.uniform4(p.fogParams, frame.fogStart * fogScale, frame.fogEnd * fogScale, FOG_MAX_DENSITY, 0f)
        gl.uniform4(p.shadowParams, 1f / SHADOW_MAP_SIZE.toFloat(), SHADOW_DEPTH_BIAS, frame.shadowStrength, EXPOSURE)
        gl.uniform4(p.grade, frame.saturation, frame.contrast, frame.emissiveFogResistance, 0f)
        if (p.shadowMap >= 0) {
            gl.activeTexture(GlConst.TEXTURE0 + UNIT_SHADOW)
            gl.bindTexture(GlConst.TEXTURE_2D, shadowTexture)
            gl.uniform1i(p.shadowMap, UNIT_SHADOW)
            gl.activeTexture(GlConst.TEXTURE0 + UNIT_ALBEDO)
            boundAlbedo = -1
        }
        gl.uniform1i(p.albedo, UNIT_ALBEDO)
        if (!p.lite) {
            gl.uniform1f(p.time, frame.timeSeconds)
            uploadWaterUniforms(source.water, frame.timeSeconds)
            uploadVortexUniforms(source.vortex, source.vortexSurge)
        }
    }

    private fun uploadWaterUniforms(water: WaterConfig, timeSeconds: Float) {
        water.packSwell(swellScratch, timeSeconds)
        for (index in scene.waterWaves.indices) {
            val base = index * WaterConfig.FLOATS_PER_TRAIN
            gl.uniform4(
                scene.waterWaves[index],
                swellScratch[base], swellScratch[base + 1], swellScratch[base + 2], swellScratch[base + 3],
            )
        }
        val shallow = water.shallowColor
        val deep = water.deepColor
        gl.uniform4(scene.waterShallow, shallow.red, shallow.green, shallow.blue, water.inverseMaximumAmplitude)
        gl.uniform4(scene.waterDeep, deep.red, deep.green, deep.blue, water.depthTint)
        gl.uniform4(
            scene.waterOptics,
            water.fresnelStrength, water.fresnelHardness, water.specularStrength, water.specularSharpness,
        )
        gl.uniform4(
            scene.waterDetail,
            water.rippleStrength, water.rippleScale,
            water.rippleSpeed * water.animationSpeed, water.quality.shaderLevel,
        )
        gl.uniform4(
            scene.waterLod,
            water.resolvedDetailFadeStart, water.resolvedDetailFadeEnd,
            water.causticScale, water.causticSpeed * water.animationSpeed,
        )
        gl.uniform4(
            scene.waterFoam,
            water.foamCrestStart, water.foamCrestEnd, water.foamIntensity, water.causticIntensity,
        )
        gl.uniform4(
            scene.waterSurf,
            water.shoreFoamEdge, water.shoreFoamSurge, water.chopPatchScale, water.shallowCalm,
        )
    }


    private fun uploadVortexUniforms(vortex: VortexConfig, surge: VortexSurge) {
        gl.uniform4(
            scene.vortexMask,
            vortex.dishEnergy, vortex.dishDepth, vortex.boatInteraction, vortex.throatFraction,
        )
        gl.uniform4(
            scene.vortexSpin,
            vortex.rotationSpeed, vortex.inflowSpeed, vortex.armCount, vortex.spiralTightness,
        )
        gl.uniform4(
            scene.vortexChurn,
            vortex.distortionStrength, vortex.turbulence, vortex.foamAmount, vortex.foamSpeed,
        )
        gl.uniform4(
            scene.vortexLook,
            vortex.foamBreakup, vortex.centerDarkness, vortex.rippleStrength, vortex.highlightIntensity,
        )
        gl.uniform4(
            scene.vortexHull,
            surge.hullX, surge.hullZ, surge.hullSuction, 1f / vortex.boatReach.coerceAtLeast(1f),
        )
        gl.uniform4(
            scene.vortexDeath,
            surge.deathX, surge.deathZ, surge.deathEnvelope, vortex.deathIntensity,
        )
    }

    private var opaqueBatches = 0
    private var instanceFloats = 0

    /** Groups this pane's visible draws into batches and writes their instance records. Returns the batch count. */
    private fun buildBatches(): Int {
        batchFirst.clear()
        additiveDraws.clear()
        instanceFloats = 0
        var batches = 0
        fun begin(item: DrawItem) {
            batchFirst.add(item)
            batchStart[batches] = instanceFloats / FLOATS_PER_INSTANCE
            batchCount[batches] = 0
            batches++
        }
        fun add(item: DrawItem) {
            writeInstance(item)
            batchCount[batches - 1]++
        }
        fun runs(items: List<DrawItem>) {
            var last: DrawItem? = null
            for (item in items) {
                if (!item.inView) continue
                if (last == null || !last.batchesWith(item)) {
                    if (batches == batchStart.size) return
                    begin(item)
                }
                add(item)
                last = item
            }
        }
        if (opaqueDraws.size > 1) opaqueDraws.sortWith(byBatchKey)
        runs(opaqueDraws)
        opaqueBatches = batches
        // Alpha keeps its far-to-near order; additive is pulled out and grouped.
        var last: DrawItem? = null
        for (item in blendedDraws) {
            if (!item.inView) continue
            if (item.blendMode == GpuBlendMode.Additive) { additiveDraws.add(item); continue }
            if (last == null || !last.batchesWith(item)) { if (batches == batchStart.size) break; begin(item) }
            add(item)
            last = item
        }
        if (additiveDraws.size > 1) additiveDraws.sortWith(byBatchKey)
        runs(additiveDraws)
        return batches
    }

    private fun writeInstance(item: DrawItem) {
        if (instanceFloats + FLOATS_PER_INSTANCE > instanceData.size) instanceData = instanceData.copyOf(instanceData.size * 2)
        val d = instanceData
        var o = instanceFloats
        item.model.copyInto(d, o, 0, 16); o += 16
        val n = item.normal
        d[o] = n[0]; d[o + 1] = n[1]; d[o + 2] = n[2]; d[o + 3] = 0f
        d[o + 4] = n[4]; d[o + 5] = n[5]; d[o + 6] = n[6]; d[o + 7] = 0f
        d[o + 8] = n[8]; d[o + 9] = n[9]; d[o + 10] = n[10]; d[o + 11] = 0f
        o += 12
        d[o] = item.tintRed; d[o + 1] = item.tintGreen; d[o + 2] = item.tintBlue; d[o + 3] = item.opacity
        instanceFloats += FLOATS_PER_INSTANCE
    }

    private fun uploadInstances() {
        gl.bindBuffer(GlConst.ARRAY_BUFFER, instanceBuffer)
        val bytes = maxOf(instanceFloats * 4, INSTANCE_STRIDE_BYTES)
        // Orphan the previous pane's storage (no stall waiting for its draws), then one upload.
        if (bytes > instanceCapacityBytes) ensureInstanceCapacity(bytes)
        else gl.bufferStorage(GlConst.ARRAY_BUFFER, instanceCapacityBytes, GlConst.STREAM_DRAW)
        if (instanceFloats > 0) gl.bufferSubData(GlConst.ARRAY_BUFFER, 0, instanceData, instanceFloats)
    }

    private fun encode(item: DrawItem, firstInstance: Int, instances: Int, p: SceneProgram) {
        val mesh = item.mesh ?: return
        applyCull(item.cullMode)
        bindAlbedo(item.albedoTexture)
        bindMesh(mesh)
        gl.bindBuffer(GlConst.ARRAY_BUFFER, instanceBuffer)
        pointInstances(firstInstance * INSTANCE_STRIDE_BYTES)
        // Material uniforms only when they change (consecutive batches often share them).
        p.set4(gl, p.baseColor, 0, item.red, item.green, item.blue, item.alpha)
        p.set4(gl, p.emissive, 1, item.emissiveRed, item.emissiveGreen, item.emissiveBlue, if (item.isBubble) 1f else 0f)
        p.set4(gl, p.emissiveMode, 2, if (item.emissiveFromVertexColor) 1f else 0f, if (item.emissiveFromAlbedo) 1f else 0f,
            if (item.emissiveMask) 1f else 0f, 0f)
        p.set4(gl, p.materialParams, 3, item.roughness, item.metallic, item.receivesShadow, if (item.albedoTexture != 0) 1f else 0f)
        if (!p.lite) {
            gl.uniform1f(p.waterShade, item.waterShade)
            gl.uniform1f(p.vortex, if (item.isVortex) 1f else 0f)
            gl.uniform2(p.foam, if (item.isFoam) 1f else 0f, item.foamAge)
        }
        gl.drawElementsInstanced(GlConst.TRIANGLES, item.indexCount, GlConst.UNSIGNED_INT, item.firstIndex * 4, instances)
        statDrawCalls++
        if (p.lite) statLiteDraws++
        statInstancesBatched += instances
        statTriangles += item.indexCount / 3 * instances
    }

    private fun bindMesh(mesh: GlMesh) = bindVertexArray(mesh.vertexArray)


    private fun bindAlbedo(texture: Int) {
        val wanted = if (texture != 0) texture else whiteTexture
        if (wanted == boundAlbedo) return
        gl.bindTexture(GlConst.TEXTURE_2D, wanted)
        boundAlbedo = wanted
    }

    private fun bindVertexArray(array: Int) {
        if (boundVertexArray == array) return
        gl.bindVertexArray(array)
        boundVertexArray = array
    }

    private fun applyCull(mode: GpuCullMode) {
        val wanted = when (mode) {
            GpuCullMode.None -> CULL_NONE
            GpuCullMode.Back -> GlConst.BACK
            GpuCullMode.Front -> GlConst.FRONT
        }
        if (wanted == currentCull) return
        if (wanted == CULL_NONE) {
            gl.disable(GlConst.CULL_FACE)
        } else {
            if (currentCull == CULL_NONE || currentCull == Int.MIN_VALUE) gl.enable(GlConst.CULL_FACE)
            gl.cullFace(wanted)
        }
        currentCull = wanted
    }

    private fun applyBlend(mode: GpuBlendMode) {
        if (mode == currentBlend) return
        when (mode) {
            GpuBlendMode.Opaque -> gl.disable(GlConst.BLEND)
            GpuBlendMode.Alpha -> {
                if (currentBlend == null || currentBlend == GpuBlendMode.Opaque) gl.enable(GlConst.BLEND)
                gl.blendFuncSeparate(
                    GlConst.SRC_ALPHA, GlConst.ONE_MINUS_SRC_ALPHA,
                    GlConst.SRC_ALPHA, GlConst.ONE_MINUS_SRC_ALPHA,
                )
            }
            GpuBlendMode.Additive -> {
                if (currentBlend == null || currentBlend == GpuBlendMode.Opaque) gl.enable(GlConst.BLEND)
                gl.blendFuncSeparate(GlConst.SRC_ALPHA, GlConst.ONE, GlConst.SRC_ALPHA, GlConst.ONE)
            }
        }
        currentBlend = mode
    }

    fun dispose() {
        assets?.let(::release)
        assets = null
        gl.deleteProgram(scene.program)
        litePlain?.let { gl.deleteProgram(it.program) }
        liteShadow?.let { gl.deleteProgram(it.program) }
        gl.deleteProgram(shadow.program)
        gl.deleteProgram(sky.program)
        gl.deleteVertexArray(sky.emptyVertexArray)
        if (shadowFramebuffer != 0) gl.deleteFramebuffer(shadowFramebuffer)
        if (shadowTexture != 0) gl.deleteTexture(shadowTexture)
        gl.deleteTexture(whiteTexture)
        gl.deleteBuffer(instanceBuffer)
    }

    internal class SceneProgram(val program: Int, gl: GlApi, val lite: Boolean = false) {
        private val cache = FloatArray(4 * 4) { Float.NaN }

        /** Sets a vec4 uniform unless slot [slot] of the cache already holds exactly these values. */
        fun set4(gl: GlApi, location: Int, slot: Int, x: Float, y: Float, z: Float, w: Float) {
            val o = slot * 4
            if (cache[o] == x && cache[o + 1] == y && cache[o + 2] == z && cache[o + 3] == w) return
            cache[o] = x; cache[o + 1] = y; cache[o + 2] = z; cache[o + 3] = w
            gl.uniform4(location, x, y, z, w)
        }

        val viewProjection = gl.uniformLocation(program, "uViewProjection")
        val lightViewProjection = gl.uniformLocation(program, "uLightViewProjection")
        val grade = gl.uniformLocation(program, "uGrade")
        val cameraPosition = gl.uniformLocation(program, "uCameraPosition")
        val lightDirection = gl.uniformLocation(program, "uLightDirection")
        val lightColor = gl.uniformLocation(program, "uLightColor")
        val fillDirection = gl.uniformLocation(program, "uFillDirection")
        val fillColor = gl.uniformLocation(program, "uFillColor")
        val ambientColor = gl.uniformLocation(program, "uAmbientColor")
        val fogColor = gl.uniformLocation(program, "uFogColor")
        val fogParams = gl.uniformLocation(program, "uFogParams")
        val shadowParams = gl.uniformLocation(program, "uShadowParams")
        val baseColor = gl.uniformLocation(program, "uBaseColor")
        val emissive = gl.uniformLocation(program, "uEmissive")
        val materialParams = gl.uniformLocation(program, "uMaterialParams")
        val emissiveMode = gl.uniformLocation(program, "uEmissiveMode")
        val shadowMap = gl.uniformLocation(program, "uShadowMap")
        val albedo = gl.uniformLocation(program, "uAlbedo")

        val time = gl.uniformLocation(program, "uTime")
        val waterShade = gl.uniformLocation(program, "uWaterShade")
        val waterWaves = IntArray(WaterConfig.SWELL_TRAINS) { gl.uniformLocation(program, "uWaterWaves[$it]") }
        val waterShallow = gl.uniformLocation(program, "uWaterShallow")
        val waterDeep = gl.uniformLocation(program, "uWaterDeep")
        val waterOptics = gl.uniformLocation(program, "uWaterOptics")
        val waterDetail = gl.uniformLocation(program, "uWaterDetail")
        val waterLod = gl.uniformLocation(program, "uWaterLod")
        val waterFoam = gl.uniformLocation(program, "uWaterFoam")
        val waterSurf = gl.uniformLocation(program, "uWaterSurf")

        val vortex = gl.uniformLocation(program, "uVortex")

        val foam = gl.uniformLocation(program, "uFoam")
        val vortexMask = gl.uniformLocation(program, "uVortexMask")
        val vortexSpin = gl.uniformLocation(program, "uVortexSpin")
        val vortexChurn = gl.uniformLocation(program, "uVortexChurn")
        val vortexLook = gl.uniformLocation(program, "uVortexLook")
        val vortexHull = gl.uniformLocation(program, "uVortexHull")
        val vortexDeath = gl.uniformLocation(program, "uVortexDeath")
    }

    internal class ShadowProgram(val program: Int, gl: GlApi) {
        val lightViewProjection = gl.uniformLocation(program, "uLightViewProjection")
        val model = gl.uniformLocation(program, "uModel")
    }

    internal class SkyProgram(val program: Int, val emptyVertexArray: Int, gl: GlApi) {
        val forward = gl.uniformLocation(program, "uSkyForward")
        val right = gl.uniformLocation(program, "uSkyRight")
        val up = gl.uniformLocation(program, "uSkyUp")
        val zenithColor = gl.uniformLocation(program, "uZenithColor")
        val horizonColor = gl.uniformLocation(program, "uHorizonColor")
        val cloudColor = gl.uniformLocation(program, "uCloudColor")
        val moon = gl.uniformLocation(program, "uMoon")
        val moonColor = gl.uniformLocation(program, "uMoonColor")
        val stars = gl.uniformLocation(program, "uStars")
        val skyline = gl.uniformLocation(program, "uSkyline")
        val skylineColor = gl.uniformLocation(program, "uSkylineColor")
        val skylineLights = gl.uniformLocation(program, "uSkylineLights")
    }

    companion object {
        private const val FOG_MAX_DENSITY = 0.88f
        private const val PI_F = 3.1415927f
        private const val CULL_NONE = 0


        private const val EXPOSURE = 0.75f


        private const val SHADOW_STRENGTH = 0.72f


        private const val SHADOW_DEPTH_BIAS = 0.0010f


        private const val SHADOW_SLOPE_BIAS = 2.5f


        private val IDENTITY_TRANSFORM = Transform3D()


        fun create(gl: GlApi, dialect: GlslDialect): GlSceneBackend? {
            val sceneProgram = GlProgramBuilder.link(
                gl, "scene",
                GlShaders.sceneVertex(dialect),
                GlShaders.sceneFragment(dialect),
            ) ?: return null
            // Optional: if a lean variant fails to link the full program draws everything.
            val litePlain = GlProgramBuilder.link(gl, "scene-lite",
                GlShaders.sceneLiteVertex(dialect, shadows = false), GlShaders.sceneLiteFragment(dialect, shadows = false))
            val liteShadow = GlProgramBuilder.link(gl, "scene-lite-shadow",
                GlShaders.sceneLiteVertex(dialect, shadows = true), GlShaders.sceneLiteFragment(dialect, shadows = true))
            val shadowProgram = GlProgramBuilder.link(
                gl, "shadow",
                GlShaders.shadowVertex(dialect),
                GlShaders.shadowFragment(dialect),
            ) ?: run {
                gl.deleteProgram(sceneProgram)
                return null
            }
            val skyProgram = GlProgramBuilder.link(
                gl, "sky",
                GlShaders.skyVertex(dialect),
                GlShaders.skyFragment(dialect),
            ) ?: run {
                gl.deleteProgram(sceneProgram)
                gl.deleteProgram(shadowProgram)
                return null
            }

            val white = gl.genTexture()
            gl.bindTexture(GlConst.TEXTURE_2D, white)
            gl.texImage2DRgba(1, 1, ByteArray(4) { 0xFF.toByte() })
            gl.texParameteri(GlConst.TEXTURE_2D, GlConst.TEXTURE_MIN_FILTER, GlConst.LINEAR)
            gl.texParameteri(GlConst.TEXTURE_2D, GlConst.TEXTURE_MAG_FILTER, GlConst.LINEAR)
            gl.texParameteri(GlConst.TEXTURE_2D, GlConst.TEXTURE_WRAP_S, GlConst.CLAMP_TO_EDGE)
            gl.texParameteri(GlConst.TEXTURE_2D, GlConst.TEXTURE_WRAP_T, GlConst.CLAMP_TO_EDGE)

            return GlSceneBackend(
                gl = gl,
                scene = SceneProgram(sceneProgram, gl),
                litePlain = litePlain?.let { SceneProgram(it, gl, lite = true) },
                liteShadow = liteShadow?.let { SceneProgram(it, gl, lite = true) },
                shadow = ShadowProgram(shadowProgram, gl),
                sky = SkyProgram(skyProgram, gl.genVertexArray(), gl),
                whiteTexture = white,
                instanceBuffer = gl.genBuffer(),
            )
        }
    }
}

private class FrameState {
    var cameraPosition = Vec3.ZERO
    var keyDirection = Vec3(0f, -1f, 0f)
    var fillDirection = Vec3(0f, 1f, 0f)
    var keyRed = 1f; var keyGreen = 1f; var keyBlue = 1f
    var fillRed = 0f; var fillGreen = 0f; var fillBlue = 0f
    var ambientRed = 0f; var ambientGreen = 0f; var ambientBlue = 0f
    var fogRed = 0f; var fogGreen = 0f; var fogBlue = 0f
    var fogStart = 0f
    var fogEnd = 1f
    var saturation = 1.24f
    var contrast = 0.20f
    var emissiveFogResistance = 0f
    var shadowStrength = 0f
    var skyForward = Vec3(0f, 0f, -1f)
    var skyRight = Vec3(1f, 0f, 0f)
    var skyUp = Vec3.UP
    var tanHalfFov = 1f
    var aspect = 1f
    var timeSeconds = 0f

    fun setKeyColor(color: PackedColor, intensity: Float) {
        keyRed = color.red * intensity; keyGreen = color.green * intensity; keyBlue = color.blue * intensity
    }

    fun setFillColor(color: PackedColor, intensity: Float) {
        fillRed = color.red * intensity; fillGreen = color.green * intensity; fillBlue = color.blue * intensity
    }

    fun setAmbient(color: PackedColor, intensity: Float) {
        ambientRed = color.red * intensity
        ambientGreen = color.green * intensity
        ambientBlue = color.blue * intensity
    }

    fun setFog(color: PackedColor, start: Float, end: Float) {
        fogRed = color.red; fogGreen = color.green; fogBlue = color.blue
        fogStart = start
        fogEnd = end
    }
}

private class DrawItem {
    val model = FloatArray(Mat.SIZE)
    val normal = FloatArray(Mat.SIZE)
    var mesh: GlMesh? = null
    var albedoTexture = 0
    var firstIndex = 0
    var indexCount = 0
    var red = 1f
    var green = 1f
    var blue = 1f
    var alpha = 1f
    var emissiveRed = 0f
    var emissiveGreen = 0f
    var emissiveBlue = 0f
    var roughness = 0.78f
    var metallic = 0f
    var receivesShadow = 1f
    var cullMode = GpuCullMode.None
    var blendMode = GpuBlendMode.Opaque
    var castsShadow = true
    var waterShade = 0f
    var isVortex = false
    var isFoam = false
    var isBubble = false
    var emissiveFromVertexColor = false
    var emissiveFromAlbedo = false
    var emissiveMask = false
    var foamAge = 0f
    var tintRed = 1f
    var tintGreen = 1f
    var tintBlue = 1f
    /** The instance's opacity (the material's own alpha stays in the base colour). */
    var opacity = 1f
    var hitFlash = 0f
    var material: GpuMaterial? = null
    /** Groups draws that can share one instanced draw (see [batchesWith]); equal keys are only a hint. */
    var batchKey = 0L

    var inView = true
    var isStatic = false
    var sortDepth = 0f

    fun setTint(color: PackedColor) { tintRed = color.red; tintGreen = color.green; tintBlue = color.blue }

    /** Same mesh range, material and render state: one instanced draw can carry both. */
    fun batchesWith(other: DrawItem): Boolean =
        mesh === other.mesh && firstIndex == other.firstIndex && indexCount == other.indexCount &&
            material === other.material && albedoTexture == other.albedoTexture && blendMode == other.blendMode &&
            hitFlash == 0f && other.hitFlash == 0f && !isFoam && !isVortex && waterShade == 0f && !isBubble

    fun fill(
        mesh: GlMesh,
        primitive: GlPrimitive,
        material: GpuMaterial,
        albedoTexture: Int,
        hitFlash: Float,
        opacity: Float,
        instanceCastsShadow: Boolean,
        sortDepth: Float,
    ) {
        this.mesh = mesh
        this.albedoTexture = albedoTexture
        firstIndex = primitive.firstIndex
        indexCount = primitive.indexCount

        val source = material.baseColor
        red = source.red + (1f - source.red) * hitFlash
        green = source.green + (1f - source.green) * hitFlash
        blue = source.blue + (1f - source.blue) * hitFlash
        this.hitFlash = hitFlash
        this.opacity = opacity
        this.material = material
        val combined = (source.alpha * opacity).coerceIn(0f, 1f)
        alpha = source.alpha

        val emissive = material.emissive
        val strength = material.emissiveStrength
        val flashGlow = if (albedoTexture != 0) hitFlash * TEXTURED_FLASH_GLOW else 0f
        emissiveRed = emissive.red * strength + flashGlow
        emissiveGreen = emissive.green * strength + flashGlow
        emissiveBlue = emissive.blue * strength + flashGlow

        roughness = material.roughness
        metallic = material.metallic
        receivesShadow = if (strength > 0.001f) 0f else 1f
        cullMode = material.cullMode
        blendMode = if (combined < 0.995f) {
            if (material.blendMode == GpuBlendMode.Additive) GpuBlendMode.Additive else GpuBlendMode.Alpha
        } else {
            material.blendMode
        }
        castsShadow = instanceCastsShadow && blendMode == GpuBlendMode.Opaque && material.waterShade <= 0f
        this.sortDepth = sortDepth
        waterShade = material.waterShade
        isVortex = material.isVortex
        isFoam = material.isFoam
        isBubble = material.isBubble
        emissiveFromVertexColor = material.emissiveFromVertexColor
        emissiveFromAlbedo = material.emissiveFromAlbedo && albedoTexture != 0
        emissiveMask = material.emissiveMaskFromVertexAlpha
        batchKey = (mesh.vertexArray.toLong() shl 40) xor (firstIndex.toLong() shl 20) xor
            (material.id.hashCode().toLong() and 0xfffff) xor (blendMode.ordinal.toLong() shl 62)
    }
}

private const val TEXTURED_FLASH_GLOW = 0.9f
