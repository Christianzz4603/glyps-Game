package com.glyps.game

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
 * Two-pass glyph-grid renderer.
 *  Pass 1 (SCENE_*):  real 3D geometry -> offscreen low-res color+depth texture.
 *  Pass 2 (GLYPH_*):  fullscreen shader samples that texture per glyph cell, picks a
 *                      glyph by brightness, samples the glyph atlas. This pass's output
 *                      IS the final image shown on screen.
 */
class GlyphRenderer : GLSurfaceView.Renderer {

    // input state, written from MainActivity's touch handling
    @Volatile var moveX = 0f
    @Volatile var moveZ = 0f
    private val lookLock = Any()
    private var pendingLookDx = 0f
    private var pendingLookDy = 0f
    fun addLook(dx: Float, dy: Float) {
        synchronized(lookLock) { pendingLookDx += dx; pendingLookDy += dy }
    }

    private val world = World()
    private var camX = 0f
    private var camY = 1.7f
    private var camZ = -30f
    private var yaw = 0.4f
    private var pitch = 0f

    private var screenW = 1
    private var screenH = 1
    private var startTime = 0L
    private var lastFrameTime = 0L

    private var sceneProgram = 0
    private var glyphProgram = 0
    private var sceneVbo = 0
    private var sceneVertCount = 0
    private var sceneFbo = 0
    private var sceneColorTex = 0
    private var sceneDepthRb = 0
    private var atlasTex = 0
    private var fsQuadVbo = 0

    private val lw = 280
    private val lh = 158
    private val glyphs = listOf(' ', '.', ':', ';', '!', 'i', 'l', '+', '*', 'x', 'o', 'O', '#', '%', '@', '\u2591', '\u2592', '\u2593', '\u2588')
    private val cellPxAtlas = 48

    private val proj = FloatArray(16)
    private val view = FloatArray(16)

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)

        sceneProgram = buildProgram(SCENE_VS, SCENE_FS)
        glyphProgram = buildProgram(GLYPH_VS, GLYPH_FS)

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

        // real glyphs, drawn with Android's own Canvas/Paint/Typeface (no font asset needed)
        val atlasW = glyphs.size * cellPxAtlas
        val atlasH = cellPxAtlas
        val bmp = Bitmap.createBitmap(atlasW, atlasH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.BLACK)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = Color.WHITE
        paint.typeface = Typeface.MONOSPACE
        paint.textSize = cellPxAtlas * 0.8f
        paint.textAlign = Paint.Align.CENTER
        for ((i, g) in glyphs.withIndex()) {
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

        val fsData = floatArrayOf(-1f, -1f, 0f, 0f, 3f, -1f, 2f, 0f, -1f, 3f, 0f, 2f)
        val fb = ByteBuffer.allocateDirect(fsData.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        fb.put(fsData).position(0)
        val fsArr = IntArray(1); GLES30.glGenBuffers(1, fsArr, 0); fsQuadVbo = fsArr[0]
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, fsQuadVbo)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, fsData.size * 4, fb, GLES30.GL_STATIC_DRAW)

        startTime = System.nanoTime()
        lastFrameTime = startTime
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

        val cyc = (t / 220f) % 1f
        val day = ((sin(cyc * (Math.PI * 2).toFloat() - (Math.PI / 2).toFloat()) + 1f) / 2f) * 0.55f
        val ambient = floatArrayOf(0.02f + 0.05f * day, 0.03f + 0.06f * day, 0.05f + 0.08f * day)
        val fogCol = floatArrayOf(0.02f + 0.10f * day, 0.03f + 0.11f * day, 0.06f + 0.16f * day)
        val sunCol = floatArrayOf(0.15f * day, 0.14f * day, 0.13f * day)

        Matrix.perspectiveM(proj, 0, 66f, screenW.toFloat() / screenH.toFloat(), 0.1f, 500f)
        val fx = sin(yaw) * cos(pitch); val fy = sin(pitch); val fz = -cos(yaw) * cos(pitch)
        Matrix.setLookAtM(view, 0, camX, camY, camZ, camX + fx, camY + fy, camZ + fz, 0f, 1f, 0f)

        // pass 1: 3D scene -> offscreen texture
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, sceneFbo)
        GLES30.glViewport(0, 0, lw, lh)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glClearColor(fogCol[0], fogCol[1], fogCol[2], 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
        GLES30.glUseProgram(sceneProgram)
        setMat4(sceneProgram, "uProj", proj)
        setMat4(sceneProgram, "uView", view)
        setVec3(sceneProgram, "uCam", camX, camY, camZ)
        setVec3(sceneProgram, "uAmbient", ambient[0], ambient[1], ambient[2])
        setVec3(sceneProgram, "uSunDir", 0.4f, 0.9f, 0.3f)
        setVec3(sceneProgram, "uSunCol", sunCol[0], sunCol[1], sunCol[2])
        setVec3(sceneProgram, "uFog", fogCol[0], fogCol[1], fogCol[2])
        GLES30.glUniform1f(GLES30.glGetUniformLocation(sceneProgram, "uFogD"), 0.018f)
        val lp = floatArrayOf(-3f, 3.4f, -50f, 3f, 3.4f, 10f, -3f, 3.4f, 70f)
        val lc = floatArrayOf(1f, 0.65f, 0.25f, 1f, 0.55f, 0.2f, 0.95f, 0.7f, 0.35f)
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

        // pass 2: glyph grid -> screen (this is the final rendering primitive)
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
        GLES30.glUniform1f(GLES30.glGetUniformLocation(glyphProgram, "uCellPx"), (screenW / 40).coerceIn(6, 18).toFloat())
        GLES30.glUniform1f(GLES30.glGetUniformLocation(glyphProgram, "uGlyphN"), glyphs.size.toFloat())
        GLES30.glUniform1f(GLES30.glGetUniformLocation(glyphProgram, "uTime"), t)
        setVec3(glyphProgram, "uFog", fogCol[0], fogCol[1], fogCol[2])

        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, fsQuadVbo)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 16, 0)
        GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, 16, 8)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
    }

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
        const val SCENE_VS = """#version 300 es
            layout(location=0) in vec3 aPos;
            layout(location=1) in vec3 aNormal;
            layout(location=2) in vec3 aColor;
            uniform mat4 uProj, uView;
            out vec3 vN, vCol, vWP;
            void main(){
                vWP = aPos; vN = aNormal; vCol = aColor;
                gl_Position = uProj * uView * vec4(aPos, 1.0);
            }
        """

        const val SCENE_FS = """#version 300 es
            precision highp float;
            in vec3 vN, vCol, vWP;
            uniform vec3 uCam, uAmbient, uSunDir, uSunCol, uFog;
            uniform float uFogD;
            uniform vec3 uLP[3];
            uniform vec3 uLC[3];
            out vec4 outColor;
            void main(){
                vec3 n = normalize(vN);
                vec3 light = uAmbient;
                light += uSunCol * max(dot(n, normalize(uSunDir)), 0.0);
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
            uniform float uCellPx, uGlyphN, uTime;
            uniform vec3 uFog;
            out vec4 outColor;
            void main(){
                vec2 frag = vUV * uRes;
                vec2 cellId = floor(frag / uCellPx);
                vec2 sceneUV = (cellId + 0.5) * uCellPx / uRes;
                vec3 sc = texture(uScene, sceneUV).rgb;
                float lum = dot(sc, vec3(0.299, 0.587, 0.114));
                float idx = floor(clamp(lum, 0.0, 0.999) * uGlyphN);
                vec2 local = fract(frag / uCellPx);
                float gap = 0.10;
                vec3 col;
                if (local.x < gap || local.x > 1.0-gap || local.y < gap || local.y > 1.0-gap) {
                    col = uFog * 0.35;
                } else {
                    vec2 inner = (local - gap) / (1.0 - 2.0*gap);
                    float mask = texture(uAtlas, vec2((idx+inner.x)/uGlyphN, inner.y)).r;
                    col = mix(uFog*0.35, sc*1.5, mask);
                }
                col *= 0.9 + 0.1*sin(frag.y*3.14159265);
                vec2 c = vUV - 0.5;
                col *= 1.0 - dot(c,c)*0.55;
                col *= 0.965 + 0.035*sin(uTime*23.0 + frag.x*0.01);
                outColor = vec4(col, 1.0);
            }
        """
    }
}
