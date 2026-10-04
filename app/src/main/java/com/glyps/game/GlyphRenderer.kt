package com.glyps.game

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.opengl.Matrix
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Three-pass glyph-grid renderer:
 *  Pass 1 (SHADOW_*): scene geometry (position only) -> a depth-only texture, rendered
 *                      from the sun's direction (orthographic). Real shadow mapping.
 *  Pass 2 (SCENE_*):   real 3D geometry -> offscreen color+depth texture, sampling the
 *                      shadow texture to attenuate the sun's contribution where occluded.
 *  Pass 3 (GLYPH_*):   fullscreen shader samples the scene texture per glyph cell and
 *                      picks a glyph from the atlas. This pass's output IS the final
 *                      image shown on screen.
 *
 * This revision fixes "looks like a modeled game with a grid added, not real ASCII art":
 * glyph cell size had been pushed down (across several rounds chasing "smoother") to
 * where it approached the resolution of the 3D render feeding it. Past that point there
 * is no information compression happening -- adjacent cells sample nearly the same
 * source pixel and pick nearly the same glyph, so the result reads as a blurry photo with
 * faint character-shaped texture, not bold ASCII art. Real ASCII art works BECAUSE many
 * source pixels collapse into one clear, high-contrast character -- that compression is
 * the whole effect. Cell size and the gap are both reset to bold, clearly legible values,
 * on purpose reversing several of the "smoother/denser" changes from earlier rounds.
 *
 * Also replaces the previous ground-contact shadow blobs with real directional shadow
 * mapping (an orthographic light-space depth pass + sampled comparison), since a flat
 * dark disc under every object was never a real shadow.
 */
class GlyphRenderer(private val context: Context) : GLSurfaceView.Renderer {

    // input state, written from MainActivity's touch handling
    @Volatile var moveX = 0f
    @Volatile var moveZ = 0f
    private val lookLock = Any()
    private var pendingLookDx = 0f
    private var pendingLookDy = 0f
    fun addLook(dx: Float, dy: Float) {
        synchronized(lookLock) { pendingLookDx += dx; pendingLookDy += dy }
    }

    var glyphCellPx: Float = -1f
    var gapFraction: Float = 0.14f // bold, clearly visible glyph cells -- see class doc

    private val world = World()
    private var camX = 0f
    private var camZ = -30f
    private var camY = 1.7f
    private val eyeHeight = 1.7f
    private var yaw = 0.4f
    private var pitch = 0f

    private var screenW = 1
    private var screenH = 1
    private var startTime = 0L
    private var lastFrameTime = 0L

    private var sceneProgram = 0
    private var glyphProgram = 0
    private var shadowProgram = 0
    private var sceneVbo = 0
    private var sceneVertCount = 0
    private var sceneFbo = 0
    private var sceneColorTex = 0
    private var sceneDepthRb = 0
    private var shadowFbo = 0
    private var shadowTex = 0
    private var atlasTex = 0
    private var fsQuadVbo = 0

    private val lw = 480
    private val lh = 270
    private val SHADOW_SIZE = 1024

    private val lightView = FloatArray(16)
    private val lightProj = FloatArray(16)
    private val lightVP = FloatArray(16)

    // A much larger, finer sparse->dense density ramp (70 characters) for smoother
    // gradation, plus the 4 shade blocks, before the 6 structural glyphs below.
    private val toneGlyphs = listOf(
        ' ', '.', '\'', '\\', '`', '^', '"', ',', ':', ';', 'I', 'l', '!', 'i', '>', '<', '~', '+', '_', '-',
        '?', ']', '[', '}', '{', '1', ')', '(', '|', '/', 't', 'f', 'j', 'r', 'x', 'n', 'u', 'v', 'c', 'z',
        'X', 'Y', 'U', 'J', 'C', 'L', 'Q', '0', 'O', 'Z', 'm', 'w', 'q', 'p', 'd', 'b', 'k', 'h', 'a', 'o',
        '*', '#', 'M', 'W', '&', '8', '%', 'B', '@', '$',
        '\u25E6', '\u25CB', '\u25AA', '\u25CF', '\u2596', '\u2597', '\u2598', '\u259D',
        '\u2801', '\u2803', '\u2807', '\u280F', '\u281F', '\u283F', '\u287F', '\u28FF',
        '\u2591', '\u2592', '\u2593', '\u2588'
    )
    private val structureGlyphs = listOf('\u2580', '\u2584', '\u258C', '\u2590', '\u259A', '\u259E')
    private val allGlyphs = toneGlyphs + structureGlyphs
    private val cellPxAtlas = 48

    private val proj = FloatArray(16)
    private val view = FloatArray(16)

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)

        sceneProgram = buildProgram(SCENE_VS, SCENE_FS)
        glyphProgram = buildProgram(GLYPH_VS, GLYPH_FS)
        shadowProgram = buildProgram(SHADOW_VS, SHADOW_FS)

        val verts = world.vertices.toFloatArray()
        sceneVertCount = verts.size / 9
        val vb = ByteBuffer.allocateDirect(verts.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        vb.put(verts).position(0)
        val vboArr = IntArray(1); GLES30.glGenBuffers(1, vboArr, 0); sceneVbo = vboArr[0]
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, sceneVbo)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, verts.size * 4, vb, GLES30.GL_STATIC_DRAW)

        val texArr = IntArray(1); GLES30.glGenTextures(1, texArr, 0); sceneColorTex = texArr[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, sceneColorTex)
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, lw, lh, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)

        val rbArr = IntArray(1); GLES30.glGenRenderbuffers(1, rbArr, 0); sceneDepthRb = rbArr[0]
        GLES30.glBindRenderbuffer(GLES30.GL_RENDERBUFFER, sceneDepthRb)
        GLES30.glRenderbufferStorage(GLES30.GL_RENDERBUFFER, GLES30.GL_DEPTH_COMPONENT16, lw, lh)

        val fboArr = IntArray(1); GLES30.glGenFramebuffers(1, fboArr, 0); sceneFbo = fboArr[0]
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, sceneFbo)
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, sceneColorTex, 0)
        GLES30.glFramebufferRenderbuffer(GLES30.GL_FRAMEBUFFER, GLES30.GL_DEPTH_ATTACHMENT, GLES30.GL_RENDERBUFFER, sceneDepthRb)
        val status = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER)
        if (status != GLES30.GL_FRAMEBUFFER_COMPLETE) {
            android.util.Log.e("GlyphRenderer", "scene FBO incomplete: $status")
        }
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)

        // shadow map: depth-only texture + FBO, no color attachment (glDrawBuffers/
        // glReadBuffer set to NONE). DEPTH_COMPONENT24 texture sampling is core GLES 3.0
        // functionality (not an optional extension), and this app already requires
        // GLES 3.0 in the manifest, so this is supported wherever the app runs at all.
        val shadowTexArr = IntArray(1); GLES30.glGenTextures(1, shadowTexArr, 0); shadowTex = shadowTexArr[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, shadowTex)
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_DEPTH_COMPONENT24, SHADOW_SIZE, SHADOW_SIZE, 0, GLES30.GL_DEPTH_COMPONENT, GLES30.GL_UNSIGNED_INT, null)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)

        val shadowFboArr = IntArray(1); GLES30.glGenFramebuffers(1, shadowFboArr, 0); shadowFbo = shadowFboArr[0]
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, shadowFbo)
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_DEPTH_ATTACHMENT, GLES30.GL_TEXTURE_2D, shadowTex, 0)
        GLES30.glDrawBuffers(1, intArrayOf(GLES30.GL_NONE), 0)
        GLES30.glReadBuffer(GLES30.GL_NONE)
        val shadowStatus = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER)
        if (shadowStatus != GLES30.GL_FRAMEBUFFER_COMPLETE) {
            android.util.Log.e("GlyphRenderer", "shadow FBO incomplete: $shadowStatus")
        }
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)

        buildGlyphAtlas()

        val fsData = floatArrayOf(-1f, -1f, 0f, 0f, 3f, -1f, 2f, 0f, -1f, 3f, 0f, 2f)
        val fb = ByteBuffer.allocateDirect(fsData.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        fb.put(fsData).position(0)
        val fsArr = IntArray(1); GLES30.glGenBuffers(1, fsArr, 0); fsQuadVbo = fsArr[0]
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, fsQuadVbo)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, fsData.size * 4, fb, GLES30.GL_STATIC_DRAW)

        startTime = System.nanoTime()
        lastFrameTime = startTime
    }

    private fun buildGlyphAtlas() {
        val typeface = try {
            Typeface.createFromAsset(context.assets, "fonts/CascadiaMono-Regular.ttf")
        } catch (e: Exception) {
            android.util.Log.w("GlyphRenderer", "Cascadia Mono asset missing, falling back to monospace", e)
            Typeface.MONOSPACE
        }
        val atlasW = allGlyphs.size * cellPxAtlas
        val atlasH = cellPxAtlas
        val bmp = Bitmap.createBitmap(atlasW, atlasH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.BLACK)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = Color.WHITE
        paint.typeface = typeface
        paint.textSize = cellPxAtlas * 0.82f
        paint.textAlign = Paint.Align.CENTER
        for ((i, g) in allGlyphs.withIndex()) {
            val cx = i * cellPxAtlas + cellPxAtlas / 2f
            val cy = cellPxAtlas / 2f - (paint.ascent() + paint.descent()) / 2f
            canvas.drawText(g.toString(), cx, cy, paint)
        }
        val atlasArr = IntArray(1); GLES30.glGenTextures(1, atlasArr, 0); atlasTex = atlasArr[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, atlasTex)
        GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bmp, 0)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        bmp.recycle()
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        screenW = width.coerceAtLeast(1)
        screenH = height.coerceAtLeast(1)
        GLES30.glViewport(0, 0, screenW, screenH)
    }

    override fun onDrawFrame(gl: GL10?) {
        val now = System.nanoTime()
        val dt = ((now - lastFrameTime) / 1_000_000_000.0).toFloat().coerceAtMost(0.05f)
        lastFrameTime = now
        val t = ((now - startTime) / 1_000_000_000.0).toFloat()

        synchronized(lookLock) {
            yaw += pendingLookDx * 0.0035f
            pitch -= pendingLookDy * 0.0035f
            pitch = pitch.coerceIn(-1.3f, 1.3f)
            pendingLookDx = 0f; pendingLookDy = 0f
        }

        val fwdX = sin(yaw); val fwdZ = -cos(yaw)
        val rightX = cos(yaw); val rightZ = sin(yaw)
        val mx = moveX; val mz = moveZ
        val len = sqrt(mx * mx + mz * mz).coerceAtLeast(1f)
        val speed = 4.2f
        val dx = (rightX * mx / len + fwdX * mz / len) * speed * dt
        val dz = (rightZ * mx / len + fwdZ * mz / len) * speed * dt
        if (!world.collides(camX + dx, camZ)) camX += dx
        if (!world.collides(camX, camZ + dz)) camZ += dz
        camY = (world.bridgeHeightIfOn(camX, camZ) ?: world.heightAt(camX, camZ)) + eyeHeight

        val cyc = (t / 220f) % 1f
        val day = ((cos(cyc * (Math.PI * 2).toFloat()) + 1f) / 2f) // starts at full daylight (day=1) at t=0
        val ambient = mix3(AMBIENT_NIGHT, AMBIENT_DAY, day)
        val fogCol = mix3(FOG_NIGHT, FOG_DAY, day)
        val sunCol = mix3(SUN_NIGHT, SUN_DAY, day)
        val fogDensity = 0.010f + (1f - day) * 0.008f
        val lightBoost = 0.5f + (1f - day) * 0.9f

        Matrix.perspectiveM(proj, 0, 66f, screenW.toFloat() / screenH.toFloat(), 0.1f, 500f)
        val fx = sin(yaw) * cos(pitch); val fy = sin(pitch); val fz = -cos(yaw) * cos(pitch)
        Matrix.setLookAtM(view, 0, camX, camY, camZ, camX + fx, camY + fy, camZ + fz, 0f, 1f, 0f)

        // directional (sun) shadow: orthographic light view-projection centered on the
        // player, looking back along the same direction as uSunDir below
        val sunLen = sqrt(0.4f * 0.4f + 0.9f * 0.9f + 0.3f * 0.3f)
        val sdx = 0.4f / sunLen; val sdy = 0.9f / sunLen; val sdz = 0.3f / sunLen
        val shadowRange = 70f
        val eyeDist = 120f
        Matrix.setLookAtM(
            lightView, 0,
            camX + sdx * eyeDist, sdy * eyeDist, camZ + sdz * eyeDist,
            camX, 0f, camZ,
            0f, 1f, 0f
        )
        Matrix.orthoM(lightProj, 0, -shadowRange, shadowRange, -shadowRange, shadowRange, 1f, 260f)
        Matrix.multiplyMM(lightVP, 0, lightProj, 0, lightView, 0)

        // pass 1: depth-only render from the sun's point of view
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, shadowFbo)
        GLES30.glViewport(0, 0, SHADOW_SIZE, SHADOW_SIZE)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glClear(GLES30.GL_DEPTH_BUFFER_BIT)
        GLES30.glUseProgram(shadowProgram)
        setMat4(shadowProgram, "uLightVP", lightVP)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, sceneVbo)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, 36, 0)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, sceneVertCount)

        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, sceneFbo)
        GLES30.glViewport(0, 0, lw, lh)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glClearColor(fogCol[0], fogCol[1], fogCol[2], 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
        GLES30.glUseProgram(sceneProgram)
        setMat4(sceneProgram, "uProj", proj)
        setMat4(sceneProgram, "uView", view)
        setMat4(sceneProgram, "uLightVP", lightVP)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, shadowTex)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(sceneProgram, "uShadowMap"), 0)
        setVec3(sceneProgram, "uCam", camX, camY, camZ)
        setVec3(sceneProgram, "uAmbient", ambient[0], ambient[1], ambient[2])
        setVec3(sceneProgram, "uSunDir", 0.4f, 0.9f, 0.3f)
        setVec3(sceneProgram, "uSunCol", sunCol[0], sunCol[1], sunCol[2])
        setVec3(sceneProgram, "uFog", fogCol[0], fogCol[1], fogCol[2])
        GLES30.glUniform1f(GLES30.glGetUniformLocation(sceneProgram, "uFogD"), fogDensity)
        val lp = floatArrayOf(-3f, 3.4f, -50f, 3f, 3.4f, 10f, -3f, 3.4f, 70f)
        val lc = floatArrayOf(
            1f * lightBoost, 0.65f * lightBoost, 0.25f * lightBoost,
            1f * lightBoost, 0.55f * lightBoost, 0.2f * lightBoost,
            0.95f * lightBoost, 0.7f * lightBoost, 0.35f * lightBoost
        )
        GLES30.glUniform3fv(GLES30.glGetUniformLocation(sceneProgram, "uLP"), 3, lp, 0)
        GLES30.glUniform3fv(GLES30.glGetUniformLocation(sceneProgram, "uLC"), 3, lc, 0)

        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, sceneVbo)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glEnableVertexAttribArray(2)
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, 36, 0)
        GLES30.glVertexAttribPointer(1, 3, GLES30.GL_FLOAT, false, 36, 12)
        GLES30.glVertexAttribPointer(2, 3, GLES30.GL_FLOAT, false, 36, 24)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, sceneVertCount)

        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glViewport(0, 0, screenW, screenH)
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glUseProgram(glyphProgram)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, sceneColorTex)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(glyphProgram, "uScene"), 0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, atlasTex)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(glyphProgram, "uAtlas"), 1)
        GLES30.glUniform2f(GLES30.glGetUniformLocation(glyphProgram, "uRes"), screenW.toFloat(), screenH.toFloat())
        val cellPx = if (glyphCellPx > 0f) glyphCellPx else (screenW / 55).coerceIn(10, 16).toFloat()
        GLES30.glUniform1f(GLES30.glGetUniformLocation(glyphProgram, "uCellPx"), cellPx)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(glyphProgram, "uToneCount"), toneGlyphs.size.toFloat())
        GLES30.glUniform1f(GLES30.glGetUniformLocation(glyphProgram, "uAtlasCount"), allGlyphs.size.toFloat())
        GLES30.glUniform1f(GLES30.glGetUniformLocation(glyphProgram, "uGapFrac"), gapFraction)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(glyphProgram, "uTime"), t)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, fsQuadVbo)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 16, 0)
        GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, 16, 8)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
    }

    private fun mix3(a: FloatArray, b: FloatArray, t: Float) = floatArrayOf(
        a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t, a[2] + (b[2] - a[2]) * t
    )

    private fun setMat4(program: Int, name: String, m: FloatArray) {
        GLES30.glUniformMatrix4fv(GLES30.glGetUniformLocation(program, name), 1, false, m, 0)
    }
    private fun setVec3(program: Int, name: String, x: Float, y: Float, z: Float) {
        GLES30.glUniform3f(GLES30.glGetUniformLocation(program, name), x, y, z)
    }

    private fun compileShader(type: Int, src: String): Int {
        val s = GLES30.glCreateShader(type)
        GLES30.glShaderSource(s, src)
        GLES30.glCompileShader(s)
        val status = IntArray(1)
        GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES30.glGetShaderInfoLog(s)
            GLES30.glDeleteShader(s)
            throw RuntimeException("Shader compile error: $log")
        }
        return s
    }

    private fun buildProgram(vs: String, fs: String): Int {
        val v = compileShader(GLES30.GL_VERTEX_SHADER, vs)
        val f = compileShader(GLES30.GL_FRAGMENT_SHADER, fs)
        val p = GLES30.glCreateProgram()
        GLES30.glAttachShader(p, v)
        GLES30.glAttachShader(p, f)
        GLES30.glLinkProgram(p)
        val status = IntArray(1)
        GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES30.glGetProgramInfoLog(p)
            GLES30.glDeleteProgram(p)
            throw RuntimeException("Program link error: $log")
        }
        return p
    }

    companion object {
        // raised from the first pass: dark fantasy at night, but never crushed to black
        private val AMBIENT_DAY = floatArrayOf(0.36f, 0.42f, 0.48f)
        private val AMBIENT_NIGHT = floatArrayOf(0.11f, 0.12f, 0.20f)
        private val FOG_DAY = floatArrayOf(0.55f, 0.72f, 0.90f)
        private val FOG_NIGHT = floatArrayOf(0.12f, 0.13f, 0.24f)
        private val SUN_DAY = floatArrayOf(1.0f, 0.92f, 0.80f)
        private val SUN_NIGHT = floatArrayOf(0.16f, 0.19f, 0.30f)

        const val SCENE_VS = """#version 300 es
            layout(location=0) in vec3 aPos;
            layout(location=1) in vec3 aNormal;
            layout(location=2) in vec3 aColor;
            uniform mat4 uProj, uView, uLightVP;
            out vec3 vN, vCol, vWP;
            out vec4 vLightSpacePos;
            void main(){
                vWP = aPos; vN = aNormal; vCol = aColor;
                vLightSpacePos = uLightVP * vec4(aPos, 1.0);
                gl_Position = uProj * uView * vec4(aPos, 1.0);
            }
        """

        const val SHADOW_VS = """#version 300 es
            layout(location=0) in vec3 aPos;
            uniform mat4 uLightVP;
            void main(){ gl_Position = uLightVP * vec4(aPos, 1.0); }
        """

        const val SHADOW_FS = """#version 300 es
            precision mediump float;
            void main(){ }
        """

        const val SCENE_FS = """#version 300 es
            precision highp float;
            in vec3 vN, vCol, vWP;
            in vec4 vLightSpacePos;
            uniform vec3 uCam, uAmbient, uSunDir, uSunCol, uFog;
            uniform float uFogD;
            uniform vec3 uLP[3];
            uniform vec3 uLC[3];
            uniform sampler2D uShadowMap;
            out vec4 outColor;

            float shadowFactor(){
                vec3 proj = vLightSpacePos.xyz / vLightSpacePos.w;
                proj = proj * 0.5 + 0.5;
                if (proj.x < 0.0 || proj.x > 1.0 || proj.y < 0.0 || proj.y > 1.0 || proj.z > 1.0 || proj.z < 0.0) return 1.0;
                float shadowDepth = texture(uShadowMap, proj.xy).r;
                float bias = 0.0035;
                return (proj.z - bias > shadowDepth) ? 0.35 : 1.0;
            }

            void main(){
                vec3 n = normalize(vN);
                vec3 light = uAmbient;
                float shadow = shadowFactor();
                light += uSunCol * max(dot(n, normalize(uSunDir)), 0.0) * shadow;
                for (int i = 0; i < 3; i++) {
                    vec3 toL = uLP[i] - vWP;
                    float d = length(toL);
                    light += uLC[i] * max(dot(n, normalize(toL)), 0.0) / (1.0 + 0.04*d + 0.006*d*d);
                }
                vec3 col = vCol * light;
                float f = 1.0 - exp(-uFogD * length(vWP - uCam));
                col = mix(col, uFog, clamp(f, 0.0, 1.0));
                outColor = vec4(col, 1.0);
            }
        """

        const val GLYPH_VS = """#version 300 es
            layout(location=0) in vec2 aPos;
            layout(location=1) in vec2 aUV;
            out vec2 vUV;
            void main(){ vUV = aUV; gl_Position = vec4(aPos, 0.0, 1.0); }
        """

        const val GLYPH_FS = """#version 300 es
            precision highp float;
            in vec2 vUV;
            uniform sampler2D uScene, uAtlas;
            uniform vec2 uRes;
            uniform float uCellPx, uToneCount, uAtlasCount, uTime, uGapFrac;
            out vec4 outColor;

            float lum(vec3 c){ return dot(c, vec3(0.299, 0.587, 0.114)); }

            void main(){
                vec2 frag = vUV * uRes;
                vec2 cellId = floor(frag / uCellPx);
                vec2 cellUV0 = cellId * uCellPx / uRes;
                vec2 cellUV1 = (cellId + 1.0) * uCellPx / uRes;
                vec2 cellSize = cellUV1 - cellUV0;

                vec3 sTL = texture(uScene, cellUV0 + cellSize*vec2(0.2,0.2)).rgb;
                vec3 sTC = texture(uScene, cellUV0 + cellSize*vec2(0.5,0.2)).rgb;
                vec3 sTR = texture(uScene, cellUV0 + cellSize*vec2(0.8,0.2)).rgb;
                vec3 sML = texture(uScene, cellUV0 + cellSize*vec2(0.2,0.5)).rgb;
                vec3 sC  = texture(uScene, cellUV0 + cellSize*vec2(0.5,0.5)).rgb;
                vec3 sMR = texture(uScene, cellUV0 + cellSize*vec2(0.8,0.5)).rgb;
                vec3 sBL = texture(uScene, cellUV0 + cellSize*vec2(0.2,0.8)).rgb;
                vec3 sBC = texture(uScene, cellUV0 + cellSize*vec2(0.5,0.8)).rgb;
                vec3 sBR = texture(uScene, cellUV0 + cellSize*vec2(0.8,0.8)).rgb;

                vec3 avgColor = (sTL+sTC+sTR+sML+sC+sMR+sBL+sBC+sBR) / 9.0;
                float lTL=lum(sTL), lTC=lum(sTC), lTR=lum(sTR), lML=lum(sML), lC=lum(sC);
                float lMR=lum(sMR), lBL=lum(sBL), lBC=lum(sBC), lBR=lum(sBR);

                float top = (lTL+lTC+lTR)/3.0;
                float bottom = (lBL+lBC+lBR)/3.0;
                float left = (lTL+lML+lBL)/3.0;
                float right = (lTR+lMR+lBR)/3.0;
                float diagA = (lTL+lBR)/2.0;
                float diagB = (lTR+lBL)/2.0;

                float mn = lTL; mn = min(mn, lTC); mn = min(mn, lTR); mn = min(mn, lML);
                mn = min(mn, lC); mn = min(mn, lMR); mn = min(mn, lBL); mn = min(mn, lBC); mn = min(mn, lBR);
                float mx = lTL; mx = max(mx, lTC); mx = max(mx, lTR); mx = max(mx, lML);
                mx = max(mx, lC); mx = max(mx, lMR); mx = max(mx, lBL); mx = max(mx, lBC); mx = max(mx, lBR);
                float variance = mx - mn;

                float hDiff = top - bottom;
                float vDiff = left - right;
                float dDiff = diagA - diagB;
                float ah = abs(hDiff), av = abs(vDiff), ad = abs(dDiff);

                // NOTE: this used to cross-fade between the two nearest tone-ramp glyphs'
                // ink masks by fractional brightness, intended to remove banding. In practice
                // blending two DIFFERENT glyph SHAPES' alpha masks doesn't read as a clean
                // in-between character -- their ink doesn't spatially overlap, so it shows up
                // as a faint doubled/ghosted smear instead, which likely made things look
                // worse rather than smoother. Reverted to a single, crisp glyph per cell.
                float glyphIdx;
                float structureThreshold = 0.16;
                if (variance < structureThreshold) {
                    glyphIdx = floor(clamp(lC, 0.0, 0.999) * uToneCount);
                } else if (ah >= av && ah >= ad) {
                    glyphIdx = uToneCount + (hDiff > 0.0 ? 0.0 : 1.0);
                } else if (av >= ah && av >= ad) {
                    glyphIdx = uToneCount + (vDiff > 0.0 ? 2.0 : 3.0);
                } else {
                    glyphIdx = uToneCount + (dDiff > 0.0 ? 4.0 : 5.0);
                }

                vec2 local = fract(frag / uCellPx);
                float gap = uGapFrac;
                vec3 bg = avgColor * 0.42;
                vec3 ink = avgColor * 1.8;
                vec3 col;
                if (local.x < gap || local.x > 1.0-gap || local.y < gap || local.y > 1.0-gap) {
                    col = bg;
                } else {
                    vec2 inner = (local - gap) / (1.0 - 2.0*gap);
                    float mask = texture(uAtlas, vec2((glyphIdx+inner.x)/uAtlasCount, inner.y)).r;
                    col = mix(bg, ink, mask);
                }
                col *= 0.94 + 0.06*sin(frag.y*3.14159265);
                vec2 c = vUV - 0.5;
                col *= 1.0 - dot(c,c)*0.30;
                col *= 0.98 + 0.02*sin(uTime*23.0 + frag.x*0.01);
                outColor = vec4(col, 1.0);
            }
        """
    }
}
