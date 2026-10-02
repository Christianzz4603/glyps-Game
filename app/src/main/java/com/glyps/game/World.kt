package com.glyps.game

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

// world/scene geometry for a small medieval realm.
//
// This revision specifically fixes: (1) roofs that were rotated 45 degrees off their
// walls (pushCone's 4-segment "pyramid" placed base vertices on the box's EDGE
// midpoints, not its CORNERS -- angleOffset below fixes this); (2) houses that were a
// single box + cone are now real structures: split walls with an actual door gap you
// can walk through (using several thin collision rectangles instead of one solid box --
// no new collision code needed, just more precise Building entries), two windows with a
// warm glow, an interior floor visible through the door, a proper gable roof with
// triangular gable ends (not a pyramid) plus a chimney; (3) the river was carved into
// the terrain last revision but the carve was too narrow for the terrain grid to
// resolve accurately and the river surface sat only 0.05 below the (imprecisely
// interpolated) ground -- terrain grid is now much finer and the river sits much
// further below it, so it can't be swallowed by interpolation error again; (4) trees
// are now organic multi-blob broadleaf canopies with per-tree size variation instead of
// one perfectly symmetric cone.

data class Vec3(val x: Float, val y: Float, val z: Float)
data class Building(val x: Float, val z: Float, val w: Float, val d: Float, val h: Float)
data class Tree(val x: Float, val z: Float, val r: Float)
private data class Mountain(val x: Float, val z: Float, val radius: Float, val peak: Float)

class World {
    val vertices = ArrayList<Float>()
    val buildings = ArrayList<Building>()
    val trees = ArrayList<Tree>()

    private fun sub(a: Vec3, b: Vec3) = Vec3(a.x - b.x, a.y - b.y, a.z - b.z)
    private fun cross(a: Vec3, b: Vec3) =
        Vec3(a.y * b.z - a.z * b.y, a.z * b.x - a.x * b.z, a.x * b.y - a.y * b.x)
    private fun norm(v: Vec3): Vec3 {
        val l = sqrt(v.x * v.x + v.y * v.y + v.z * v.z).coerceAtLeast(1e-6f)
        return Vec3(v.x / l, v.y / l, v.z / l)
    }

    private fun pushTri(a: Vec3, b: Vec3, c: Vec3, col: Triple<Float, Float, Float>) {
        val n = norm(cross(sub(b, a), sub(c, a)))
        for (p in listOf(a, b, c)) {
            vertices.add(p.x); vertices.add(p.y); vertices.add(p.z)
            vertices.add(n.x); vertices.add(n.y); vertices.add(n.z)
            vertices.add(col.first); vertices.add(col.second); vertices.add(col.third)
        }
    }
    private fun pushQuad(a: Vec3, b: Vec3, c: Vec3, d: Vec3, col: Triple<Float, Float, Float>) {
        pushTri(a, b, c, col); pushTri(a, c, d, col)
    }
    private fun pushTriVC(a: Vec3, ca: Triple<Float, Float, Float>, b: Vec3, cb: Triple<Float, Float, Float>, c: Vec3, cc: Triple<Float, Float, Float>) {
        val n = norm(cross(sub(b, a), sub(c, a)))
        vertices.add(a.x); vertices.add(a.y); vertices.add(a.z); vertices.add(n.x); vertices.add(n.y); vertices.add(n.z); vertices.add(ca.first); vertices.add(ca.second); vertices.add(ca.third)
        vertices.add(b.x); vertices.add(b.y); vertices.add(b.z); vertices.add(n.x); vertices.add(n.y); vertices.add(n.z); vertices.add(cb.first); vertices.add(cb.second); vertices.add(cb.third)
        vertices.add(c.x); vertices.add(c.y); vertices.add(c.z); vertices.add(n.x); vertices.add(n.y); vertices.add(n.z); vertices.add(cc.first); vertices.add(cc.second); vertices.add(cc.third)
    }
    private fun pushQuadVC(
        a: Vec3, ca: Triple<Float, Float, Float>, b: Vec3, cb: Triple<Float, Float, Float>,
        c: Vec3, cc: Triple<Float, Float, Float>, d: Vec3, cd: Triple<Float, Float, Float>
    ) {
        pushTriVC(a, ca, b, cb, c, cc); pushTriVC(a, ca, c, cc, d, cd)
    }
    // explicit-per-vertex-normal versions -- used only for terrain, so its shading comes
    // from the smooth ANALYTIC surface normal of the height field (see terrainNormalAt)
    // instead of one flat normal per triangle, which is what was making the ground look
    // like faceted low-poly glass instead of a continuous landscape.
    private fun pushVertexRaw(p: Vec3, n: Vec3, col: Triple<Float, Float, Float>) {
        vertices.add(p.x); vertices.add(p.y); vertices.add(p.z)
        vertices.add(n.x); vertices.add(n.y); vertices.add(n.z)
        vertices.add(col.first); vertices.add(col.second); vertices.add(col.third)
    }
    private fun pushTriVCN(a: Vec3, na: Vec3, ca: Triple<Float, Float, Float>, b: Vec3, nb: Vec3, cb: Triple<Float, Float, Float>, c: Vec3, nc: Vec3, cc: Triple<Float, Float, Float>) {
        pushVertexRaw(a, na, ca); pushVertexRaw(b, nb, cb); pushVertexRaw(c, nc, cc)
    }
    private fun pushQuadVCN(
        a: Vec3, na: Vec3, ca: Triple<Float, Float, Float>, b: Vec3, nb: Vec3, cb: Triple<Float, Float, Float>,
        c: Vec3, nc: Vec3, cc: Triple<Float, Float, Float>, d: Vec3, nd: Vec3, cd: Triple<Float, Float, Float>
    ) {
        pushTriVCN(a, na, ca, b, nb, cb, c, nc, cc); pushTriVCN(a, na, ca, c, nc, cc, d, nd, cd)
    }

    private fun pushBox(cx: Float, cz: Float, w: Float, d: Float, h: Float, baseY: Float, col: Triple<Float, Float, Float>) {
        val x0 = cx - w / 2; val x1 = cx + w / 2; val z0 = cz - d / 2; val z1 = cz + d / 2
        val y0 = baseY; val y1 = baseY + h
        pushQuad(Vec3(x0, y0, z1), Vec3(x1, y0, z1), Vec3(x1, y1, z1), Vec3(x0, y1, z1), col)
        pushQuad(Vec3(x1, y0, z0), Vec3(x0, y0, z0), Vec3(x0, y1, z0), Vec3(x1, y1, z0), col)
        pushQuad(Vec3(x0, y0, z0), Vec3(x0, y0, z1), Vec3(x0, y1, z1), Vec3(x0, y1, z0), col)
        pushQuad(Vec3(x1, y0, z1), Vec3(x1, y0, z0), Vec3(x1, y1, z0), Vec3(x1, y1, z1), col)
        pushQuad(Vec3(x0, y1, z1), Vec3(x1, y1, z1), Vec3(x1, y1, z0), Vec3(x0, y1, z0), col)
    }
    /** angleOffset lets a 4-segment cone's base vertices land on a square's CORNERS
     *  (offset = PI/4) instead of its edge midpoints (offset = 0), which is what was
     *  making pyramid roofs look rotated 45 degrees off the walls beneath them. */
    private fun pushCone(cx: Float, cz: Float, y0: Float, h: Float, r: Float, col: Triple<Float, Float, Float>, seg: Int, angleOffset: Float = 0f) {
        val apex = Vec3(cx, y0 + h, cz)
        for (i in 0 until seg) {
            val a0 = angleOffset + i.toFloat() / seg * (PI * 2).toFloat()
            val a1 = angleOffset + (i + 1).toFloat() / seg * (PI * 2).toFloat()
            pushTri(
                Vec3(cx + cos(a0) * r, y0, cz + sin(a0) * r),
                Vec3(cx + cos(a1) * r, y0, cz + sin(a1) * r),
                apex, col
            )
        }
    }
    private val SHADOW_COL = Triple(0.045f, 0.06f, 0.045f)
    private fun pushShadowBlob(cx: Float, cz: Float, radius: Float) {
        pushCone(cx, cz, heightAt(cx, cz) + 0.02f, 0f, radius, SHADOW_COL, 10)
    }

    /** A truncated cone (bottom radius r0, top radius r1) -- used for round towers,
     *  the windmill, and the well, none of which taper all the way to a point. */
    private fun pushFrustum(cx: Float, cz: Float, y0: Float, h: Float, r0: Float, r1: Float, col: Triple<Float, Float, Float>, seg: Int, angleOffset: Float = 0f) {
        for (i in 0 until seg) {
            val a0 = angleOffset + i.toFloat() / seg * (PI * 2).toFloat()
            val a1 = angleOffset + (i + 1).toFloat() / seg * (PI * 2).toFloat()
            val p0b = Vec3(cx + cos(a0) * r0, y0, cz + sin(a0) * r0)
            val p1b = Vec3(cx + cos(a1) * r0, y0, cz + sin(a1) * r0)
            val p0t = Vec3(cx + cos(a0) * r1, y0 + h, cz + sin(a0) * r1)
            val p1t = Vec3(cx + cos(a1) * r1, y0 + h, cz + sin(a1) * r1)
            pushQuad(p0b, p1b, p1t, p0t, col)
        }
    }
    /** A ring of small merlon boxes along the top of a wall/tower for a real castle
     *  battlement look, instead of a plain flat-topped wall. */
    private fun pushCrenellations(cx: Float, cz: Float, length: Float, topY: Float, col: Triple<Float, Float, Float>, alongX: Boolean) {
        val merlon = 0.85f; val spacing = 1.7f
        val count = (length / spacing).toInt().coerceAtLeast(1)
        val start = -length / 2f + spacing / 2f
        for (i in 0 until count) {
            val off = start + i * spacing
            val mx = if (alongX) cx + off else cx
            val mz = if (alongX) cz else cz + off
            pushBox(mx, mz, merlon, merlon, merlon, topY, col)
        }
    }
    /** Thin dark "exposed beam" boxes overlaid on a wall face for a half-timbered
     *  (Tudor-style) look -- offset slightly proud of the wall to avoid z-fighting. */
    private fun pushTimberAccent(cx: Float, faceZ: Float, wallW: Float, y0: Float, y1: Float, faceSign: Float) {
        val beamCol = Triple(0.14f, 0.09f, 0.05f)
        val zz = faceZ + 0.03f * faceSign
        val t = 0.10f
        pushQuad(Vec3(cx - wallW * 0.28f, y0, zz), Vec3(cx - wallW * 0.28f + t, y0, zz), Vec3(cx - wallW * 0.28f + t, y1, zz), Vec3(cx - wallW * 0.28f, y1, zz), beamCol)
        pushQuad(Vec3(cx + wallW * 0.28f, y0, zz), Vec3(cx + wallW * 0.28f + t, y0, zz), Vec3(cx + wallW * 0.28f + t, y1, zz), Vec3(cx + wallW * 0.28f, y1, zz), beamCol)
        val midY = (y0 + y1) / 2f
        pushQuad(Vec3(cx - wallW * 0.28f, y0, zz), Vec3(cx - wallW * 0.28f + t, y0, zz), Vec3(cx + wallW * 0.20f + t, midY, zz), Vec3(cx + wallW * 0.20f, midY, zz), beamCol)
        pushQuad(Vec3(cx + wallW * 0.20f, midY, zz), Vec3(cx + wallW * 0.20f + t, midY, zz), Vec3(cx - wallW * 0.28f + t, y1, zz), Vec3(cx - wallW * 0.28f, y1, zz), beamCol)
    }

    private val MOUNTAINS = listOf(
        Mountain(-170f, -160f, 65f, 32f),
        Mountain(160f, 175f, 60f, 28f),
        Mountain(90f, 195f, 50f, 16f)
    )
    // coherent value noise (hash + smoothstep interpolation), no external library needed.
    // This replaces a pure sine-wave height field, which -- however many octaves you
    // stack -- always looks visibly periodic/regular. Noise does not repeat, which is
    // most of what separates "natural-looking terrain" from "obviously procedural ripple".
    private fun hash2(xi: Int, zi: Int): Float {
        var h = xi * 374761393 + zi * 668265263
        h = (h xor (h shr 13)) * 1274126177
        h = h xor (h shr 16)
        return (h and 0x7fffffff).toFloat() / 0x7fffffff.toFloat()
    }
    private fun valueNoise(x: Float, z: Float): Float {
        val xi = floor(x).toInt(); val zi = floor(z).toInt()
        val xf = x - xi; val zf = z - zi
        val v00 = hash2(xi, zi); val v10 = hash2(xi + 1, zi)
        val v01 = hash2(xi, zi + 1); val v11 = hash2(xi + 1, zi + 1)
        val sx = xf * xf * (3f - 2f * xf); val sz = zf * zf * (3f - 2f * zf)
        val a = v00 + (v10 - v00) * sx
        val b = v01 + (v11 - v01) * sx
        return a + (b - a) * sz
    }
    private fun fbm(x: Float, z: Float, octaves: Int): Float {
        var total = 0f; var amp = 1f; var freq = 1f; var maxAmp = 0f
        for (i in 0 until octaves) {
            total += valueNoise(x * freq, z * freq) * amp
            maxAmp += amp
            amp *= 0.5f; freq *= 2f
        }
        return total / maxAmp
    }
    private fun baseHeightAt(x: Float, z: Float): Float {
        // NOTE: a second, higher-frequency noise octave (wavelength ~4 units) used to be
        // added here for "small-scale roughness". The terrain grid step is 5 units, which
        // is COARSER than that wavelength, so adjacent grid vertices sampled essentially
        // uncorrelated noise -- i.e. visible per-vertex jitter/spikes, not roughness. This
        // was very likely the main cause of the terrain looking worse. Removed.
        var h = (fbm(x * 0.045f, z * 0.045f, 5) - 0.5f) * 5.0f
        for (m in MOUNTAINS) {
            val dx = x - m.x; val dz = z - m.z
            val d2 = dx * dx + dz * dz
            h += m.peak * exp(-d2 / (2f * m.radius * m.radius))
        }
        return h
    }
    /** Analytic surface normal of the (full, river-dip-included) height field via central
     *  differences -- smooth per-vertex shading without needing to average an explicit
     *  mesh's face normals. */
    private fun terrainNormalAt(x: Float, z: Float): Vec3 {
        val eps = 0.4f
        val hL = heightAt(x - eps, z); val hR = heightAt(x + eps, z)
        val hD = heightAt(x, z - eps); val hU = heightAt(x, z + eps)
        val dX = Vec3(2f * eps, hR - hL, 0f)
        val dZ = Vec3(0f, hU - hD, 2f * eps)
        return norm(cross(dZ, dX))
    }

    // --- rivers via flow accumulation instead of a scripted path ---
    // Every grid cell starts with uniform "rainfall"; processed highest-elevation-first,
    // each cell hands its accumulated flow to whichever of its 8 neighbors is lowest.
    // Cells with enough accumulated flow become stream/river, with depth growing with
    // flow -- so rivers emerge from wherever the terrain (mountains included) actually
    // drains, and get wider downstream, instead of being drawn along a fixed path.
    private val GRID_HALF = 200f
    private val GRID_STEP = 5f
    private val GRID_N = (2 * GRID_HALF / GRID_STEP).toInt() + 1 // 81

    private fun gridX(i: Int) = -GRID_HALF + i * GRID_STEP
    private fun gridZ(j: Int) = -GRID_HALF + j * GRID_STEP

    private val flowDepth: Array<FloatArray> by lazy {
        val n = GRID_N
        val height = Array(n) { i -> FloatArray(n) { j -> baseHeightAt(gridX(i), gridZ(j)) } }
        val flow = Array(n) { FloatArray(n) { 1f } }

        val order = ArrayList<Int>(n * n)
        for (idx in 0 until n * n) order.add(idx)
        order.sortByDescending { idx -> height[idx / n][idx % n] }

        for (idx in order) {
            val i = idx / n; val j = idx % n
            var bestI = -1; var bestJ = -1; var bestH = height[i][j]
            for (di in -1..1) for (dj in -1..1) {
                if (di == 0 && dj == 0) continue
                val ni = i + di; val nj = j + dj
                if (ni < 0 || ni >= n || nj < 0 || nj >= n) continue
                if (height[ni][nj] < bestH) { bestH = height[ni][nj]; bestI = ni; bestJ = nj }
            }
            if (bestI >= 0) flow[bestI][bestJ] += flow[i][j]
        }

        Array(n) { i -> FloatArray(n) { j ->
            val f = flow[i][j]
            if (f > 35f) ((f - 35f) / 40f).coerceIn(0f, 1f) * 1.6f else 0f
        } }
    }

    /** Bilinear lookup (not nearest-cell) so heightAt stays continuous -- a hard per-cell
     *  step would make the central-difference normals in terrainNormalAt noisy at grid
     *  boundaries, the same class of bug as the earlier undersampled-noise jitter. */
    private fun riverDepthAt(x: Float, z: Float): Float {
        val fx = (x + GRID_HALF) / GRID_STEP
        val fz = (z + GRID_HALF) / GRID_STEP
        val i0 = floor(fx).toInt().coerceIn(0, GRID_N - 1)
        val j0 = floor(fz).toInt().coerceIn(0, GRID_N - 1)
        val i1 = (i0 + 1).coerceIn(0, GRID_N - 1)
        val j1 = (j0 + 1).coerceIn(0, GRID_N - 1)
        val tx = (fx - i0).coerceIn(0f, 1f)
        val tz = (fz - j0).coerceIn(0f, 1f)
        val d00 = flowDepth[i0][j0]; val d10 = flowDepth[i1][j0]
        val d01 = flowDepth[i0][j1]; val d11 = flowDepth[i1][j1]
        val a = d00 + (d10 - d00) * tx
        val b = d01 + (d11 - d01) * tx
        return a + (b - a) * tz
    }

    fun heightAt(x: Float, z: Float): Float {
        return baseHeightAt(x, z) - riverDepthAt(x, z)
    }

    private fun terrainColor(x: Float, z: Float, h: Float): Triple<Float, Float, Float> {
        val patch = sin(x * 0.02f) * cos(z * 0.025f) + sin(x * 0.006f + 2f) * cos(z * 0.014f + 1f)
        val green = ((patch + 1.4f) / 2.8f).coerceIn(0f, 1f)
        var r = 0.16f + 0.22f * (1f - green)
        var g = 0.34f + 0.28f * green
        var b = 0.10f + 0.09f * green
        val fine = (fbm(x * 0.3f, z * 0.3f, 2) - 0.5f) * 0.10f // subtle mottling, breaks up flat color patches
        r += fine; g += fine * 0.8f; b += fine * 0.6f
        val rockT = ((h - 6f) / 10f).coerceIn(0f, 1f)
        r += (0.42f - r) * rockT; g += (0.40f - g) * rockT; b += (0.40f - b) * rockT
        val snowT = ((h - 16f) / 8f).coerceIn(0f, 1f)
        r += (0.88f - r) * snowT; g += (0.90f - g) * snowT; b += (0.92f - b) * snowT
        val river = riverDepthAt(x, z)
        if (river > 0f) {
            val t = (river / 1.6f).coerceIn(0f, 1f)
            r += (0.15f - r) * t; g += (0.48f - g) * t; b += (0.66f - b) * t
        }
        return Triple(r.coerceIn(0f, 1f), g.coerceIn(0f, 1f), b.coerceIn(0f, 1f))
    }

    // Mountain data, flattened for passing to native code (see NativeTerrain.kt). Native
    // terrain generation hardcodes this same data internally (see terrain.cpp) rather than
    // taking it as a parameter, to keep the JNI surface smaller; this array exists only in
    // case other native calls need it later.
    private val mountainsFlat: FloatArray by lazy {
        val arr = FloatArray(MOUNTAINS.size * 4)
        for ((i, m) in MOUNTAINS.withIndex()) {
            arr[i * 4] = m.x; arr[i * 4 + 1] = m.z; arr[i * 4 + 2] = m.radius; arr[i * 4 + 3] = m.peak
        }
        arr
    }

    // NOTE: temporarily NOT using the native path here. The flow-accumulation river system
    // above is new and still settling, and terrain.cpp's native implementation does not yet
    // know about it (it still has the old scripted-path river baked in) -- running native
    // right now would silently show the OLD river instead of this one. Keeping both in sync
    // blind is exactly the kind of mismatch that caused problems before, so this stays
    // Kotlin-only until the new system is confirmed good; porting it to native afterward is
    // straightforward (same approach as before, plus passing the flowDepth grid across).
    private fun buildTerrain() {
        val half = 200f; val step = 5f
        buildTerrainKotlin(half, step)
    }

    private fun buildTerrainKotlin(half: Float, step: Float) {
        var x = -half
        while (x < half) {
            var z = -half
            while (z < half) {
                val x0 = x; val x1 = x + step; val z0 = z; val z1 = z + step
                val hA = heightAt(x0, z0); val hB = heightAt(x0, z1); val hC = heightAt(x1, z1); val hD = heightAt(x1, z0)
                pushQuadVCN(
                    Vec3(x0, hA, z0), terrainNormalAt(x0, z0), terrainColor(x0, z0, hA),
                    Vec3(x0, hB, z1), terrainNormalAt(x0, z1), terrainColor(x0, z1, hB),
                    Vec3(x1, hC, z1), terrainNormalAt(x1, z1), terrainColor(x1, z1, hC),
                    Vec3(x1, hD, z0), terrainNormalAt(x1, z0), terrainColor(x1, z0, hD)
                )
                z += step
            }
            x += step
        }
    }
    /** Small scattered rock clusters across open terrain, so flat ground isn't a bare
     *  color void -- skipped near the road and river so nothing spawns on top of them. */
    private fun buildRocks() {
        var seedR = 0x5EED5EED.toInt()
        fun rndR(): Float {
            seedR = seedR xor (seedR shl 13); seedR = seedR xor (seedR ushr 17); seedR = seedR xor (seedR shl 5)
            return (seedR.toLong() and 0xFFFFFFFFL).toFloat() / 4294967295f
        }
        var placed = 0
        var tries = 0
        while (placed < 40 && tries < 400) {
            tries++
            val z = rndR() * 380f - 190f
            val side = if (rndR() < 0.5f) -1f else 1f
            val x = side * (8f + rndR() * 170f)
            if (abs(x) < 6f) continue
            if (riverDepthAt(x, z) > 0.05f) continue
            val by = heightAt(x, z)
            val baseShade = 0.34f + rndR() * 0.10f
            val col = Triple(baseShade, baseShade * 0.97f, baseShade * 0.90f)
            val clump = 2 + (rndR() * 2f).toInt()
            for (k in 0 until clump) {
                val ox = x + (rndR() - 0.5f) * 0.9f
                val oz = z + (rndR() - 0.5f) * 0.9f
                val rs = 0.25f + rndR() * 0.35f
                pushBox(ox, oz, rs, rs * (0.8f + rndR() * 0.4f), rs * (0.7f + rndR() * 0.5f), heightAt(ox, oz), col)
            }
            pushShadowBlob(x, z, 0.9f)
            placed++
        }
    }

    private fun buildRoad() {
        val half = 300f; val step = 10f; val halfWidth = 4f
        val col = Triple(0.48f, 0.40f, 0.28f)
        var z = -half
        while (z < half) {
            val z0 = z; val z1 = z + step
            val y0 = heightAt(0f, z0) + 0.06f
            val y1 = heightAt(0f, z1) + 0.06f
            pushQuad(Vec3(-halfWidth, y0, z0), Vec3(-halfWidth, y1, z1), Vec3(halfWidth, y1, z1), Vec3(halfWidth, y0, z0), col)
            z += step
        }
    }

    private val BRIDGE_X_HALF = 4.5f
    private val BRIDGE_Z_HALF = 6.0f

    // Where the road (fixed at x=0) crosses the biggest actual simulated river, instead of
    // a hand-picked z coordinate -- found by scanning the flow grid along that column.
    private val bridgeZ: Float by lazy {
        val i0 = ((0f + GRID_HALF) / GRID_STEP).toInt().coerceIn(0, GRID_N - 1)
        var bestJ = GRID_N / 2; var bestDepth = -1f
        for (j in 0 until GRID_N) {
            if (flowDepth[i0][j] > bestDepth) { bestDepth = flowDepth[i0][j]; bestJ = j }
        }
        gridZ(bestJ)
    }
    private val bridgeY: Float by lazy { baseHeightAt(0f, bridgeZ) + 0.35f }

    /** Only the physical bridge deck + rails + end piers -- the river itself is now part
     *  of the terrain's own coloring/height (see terrainColor/heightAt above), not a
     *  separate mesh, which also removes the entire class of z-fighting/occlusion bug the
     *  old separate river mesh had against the ground. */
    private fun buildBridge() {
        val bridgeStone = Triple(0.58f, 0.56f, 0.52f)
        pushBox(0f, bridgeZ, 8f, 11f, 0.6f, bridgeY, bridgeStone)
        pushBox(0f, bridgeZ - 5f, 8.5f, 0.6f, 0.5f, bridgeY + 0.6f, bridgeStone)
        pushBox(0f, bridgeZ + 5f, 8.5f, 0.6f, 0.5f, bridgeY + 0.6f, bridgeStone)
        pushBox(0f, bridgeZ - 5.3f, 8.6f, 1.0f, 1.2f, heightAt(0f, bridgeZ - 6.3f), bridgeStone)
        pushBox(0f, bridgeZ + 5.3f, 8.6f, 1.0f, 1.2f, heightAt(0f, bridgeZ + 6.3f), bridgeStone)
    }

    fun bridgeHeightIfOn(x: Float, z: Float): Float? =
        if (abs(x) < BRIDGE_X_HALF && abs(z - bridgeZ) < BRIDGE_Z_HALF) bridgeY else null


    /** A real house: split walls with a walkable door gap, two glowing windows, an
     *  interior floor, a properly-aligned gable roof (not a rotated pyramid), and a
     *  chimney. Collision is 5 thin wall-strip rectangles (split around the door) added
     *  to the existing `buildings` AABB list -- reuses the existing collision code
     *  exactly, no new logic, and the door gap is simply where no strip exists. */
    private fun buildDetailedHouse(cx: Float, cz: Float, w: Float, d: Float, wallH: Float, wallCol: Triple<Float, Float, Float>, roofCol: Triple<Float, Float, Float>, timberAccent: Boolean = false) {
        val by = heightAt(cx, cz)
        val x0 = cx - w / 2; val x1 = cx + w / 2; val z0 = cz - d / 2; val z1 = cz + d / 2
        val y0 = by; val y1 = by + wallH
        val wallT = 0.3f

        val doorW = (w * 0.30f).coerceAtMost(1.6f)
        val doorH = (wallH * 0.72f).coerceAtMost(2.2f)
        val doorX0 = cx - doorW / 2; val doorX1 = cx + doorW / 2
        val doorTopY = y0 + doorH
        val doorCol = Triple(0.10f, 0.07f, 0.05f)

        // front wall (z1) split around the door
        pushQuad(Vec3(x0, y0, z1), Vec3(doorX0, y0, z1), Vec3(doorX0, y1, z1), Vec3(x0, y1, z1), wallCol)
        pushQuad(Vec3(doorX1, y0, z1), Vec3(x1, y0, z1), Vec3(x1, y1, z1), Vec3(doorX1, y1, z1), wallCol)
        pushQuad(Vec3(doorX0, doorTopY, z1), Vec3(doorX1, doorTopY, z1), Vec3(doorX1, y1, z1), Vec3(doorX0, y1, z1), wallCol)
        pushQuad(Vec3(doorX0, y0, z1), Vec3(doorX1, y0, z1), Vec3(doorX1, doorTopY, z1), Vec3(doorX0, doorTopY, z1), doorCol)
        buildings.add(Building((x0 + doorX0) / 2, z1, doorX0 - x0, wallT, wallH))
        buildings.add(Building((doorX1 + x1) / 2, z1, x1 - doorX1, wallT, wallH))

        // back wall, solid
        pushQuad(Vec3(x1, y0, z0), Vec3(x0, y0, z0), Vec3(x0, y1, z0), Vec3(x1, y1, z0), wallCol)
        buildings.add(Building(cx, z0, w, wallT, wallH))

        // side walls, each with a small glowing window
        val winW = w * 0.20f; val winH = wallH * 0.28f
        val winY0 = y0 + wallH * 0.4f; val winY1 = winY0 + winH
        val winCol = Triple(0.62f, 0.80f, 0.55f)
        val wz0 = cz - winW / 2; val wz1 = cz + winW / 2
        pushQuad(Vec3(x0, y0, z0), Vec3(x0, y0, wz0), Vec3(x0, y1, wz0), Vec3(x0, y1, z0), wallCol)
        pushQuad(Vec3(x0, y0, wz1), Vec3(x0, y0, z1), Vec3(x0, y1, z1), Vec3(x0, y1, wz1), wallCol)
        pushQuad(Vec3(x0, winY1, wz0), Vec3(x0, winY1, wz1), Vec3(x0, y1, wz1), Vec3(x0, y1, wz0), wallCol)
        pushQuad(Vec3(x0, y0, wz0), Vec3(x0, y0, wz1), Vec3(x0, winY0, wz1), Vec3(x0, winY0, wz0), wallCol)
        pushQuad(Vec3(x0, winY0, wz0), Vec3(x0, winY0, wz1), Vec3(x0, winY1, wz1), Vec3(x0, winY1, wz0), winCol)
        pushQuad(Vec3(x1, y0, z1), Vec3(x1, y0, wz1), Vec3(x1, y1, wz1), Vec3(x1, y1, z1), wallCol)
        pushQuad(Vec3(x1, y0, wz0), Vec3(x1, y0, z0), Vec3(x1, y1, z0), Vec3(x1, y1, wz0), wallCol)
        pushQuad(Vec3(x1, winY1, wz1), Vec3(x1, winY1, wz0), Vec3(x1, y1, wz0), Vec3(x1, y1, wz1), wallCol)
        pushQuad(Vec3(x1, y0, wz1), Vec3(x1, y0, wz0), Vec3(x1, winY0, wz0), Vec3(x1, winY0, wz1), wallCol)
        pushQuad(Vec3(x1, winY0, wz1), Vec3(x1, winY0, wz0), Vec3(x1, winY1, wz0), Vec3(x1, winY1, wz1), winCol)
        buildings.add(Building(x0, cz, wallT, d, wallH))
        buildings.add(Building(x1, cz, wallT, d, wallH))

        // interior floor, visible through the door and windows
        pushQuad(Vec3(x0, y0 + 0.02f, z0), Vec3(x0, y0 + 0.02f, z1), Vec3(x1, y0 + 0.02f, z1), Vec3(x1, y0 + 0.02f, z0), Triple(0.35f, 0.25f, 0.15f))

        // gable roof: sloped panels + triangular gable ends (a real cottage roof, not a pyramid)
        val roofH = wallH * 0.55f
        val ridgeY = y1 + roofH
        pushQuad(Vec3(x0, y1, z0), Vec3(x0, y1, z1), Vec3(cx, ridgeY, z1), Vec3(cx, ridgeY, z0), roofCol)
        pushQuad(Vec3(cx, ridgeY, z0), Vec3(cx, ridgeY, z1), Vec3(x1, y1, z1), Vec3(x1, y1, z0), roofCol)
        pushTri(Vec3(x0, y1, z0), Vec3(x1, y1, z0), Vec3(cx, ridgeY, z0), wallCol)
        pushTri(Vec3(x1, y1, z1), Vec3(x0, y1, z1), Vec3(cx, ridgeY, z1), wallCol)

        // chimney near the back gable
        val chimX = cx + w * 0.22f
        pushBox(chimX, z0 + d * 0.18f, 0.5f, 0.5f, roofH * 0.9f + 0.6f, y1, Triple(0.42f, 0.40f, 0.38f))

        if (timberAccent) pushTimberAccent(cx, z1, w, y0, y1, 1f)

        pushShadowBlob(cx, cz, sqrt(w * w + d * d) / 2f * 1.25f)
    }

    /** Organic, irregular broadleaf canopy (several offset blobs of varying size) with
     *  per-tree height/width variation, instead of one perfectly symmetric cone. */
    private fun buildBroadleafTree(x: Float, z: Float, scale: Float, rnd: () -> Float, canopyBase: Triple<Float, Float, Float>) {
        val by = heightAt(x, z)
        val trunkH = (1.6f + rnd() * 1.2f) * scale
        val trunkR = (0.16f + rnd() * 0.08f) * scale
        pushBox(x, z, trunkR * 2f, trunkR * 2f, trunkH, by, Triple(0.22f, 0.14f, 0.08f))
        val canopyBaseY = by + trunkH * 0.7f
        val blobs = 4
        for (i in 0 until blobs) {
            val ang = i.toFloat() / blobs * (PI * 2).toFloat() + rnd() * 0.7f
            val off = (0.5f + rnd() * 0.5f) * scale
            val bx = x + cos(ang) * off
            val bz = z + sin(ang) * off
            val br = (1.1f + rnd() * 0.7f) * scale
            val bh = (1.6f + rnd() * 0.9f) * scale
            val shade = 0.72f + rnd() * 0.34f
            pushCone(bx, bz, canopyBaseY + rnd() * 0.3f * scale, bh, br, Triple(canopyBase.first * shade, canopyBase.second * shade, canopyBase.third * shade), 6)
        }
        pushCone(x, z, canopyBaseY + 0.25f * scale, (2.2f + rnd() * 0.9f) * scale, (1.3f + rnd() * 0.35f) * scale, canopyBase, 7)
        val treeR = (1.6f + rnd() * 0.7f) * scale
        trees.add(Tree(x, z, treeR * 0.35f))
        pushShadowBlob(x, z, treeR)
    }

    init {
        buildTerrain()
        buildRoad()
        buildBridge()
        buildRocks()

        val bList = listOf(
            Triple(-16f, -25f, Triple(9f, 8f, 7f)), Triple(16f, -45f, Triple(11f, 6f, 10f)), Triple(-13f, 30f, Triple(6f, 10f, 5f)),
            Triple(19f, 65f, Triple(9f, 9f, 8f)), Triple(-20f, 95f, Triple(7f, 7f, 6f)), Triple(15f, -95f, Triple(8f, 5f, 9f))
        )
        val houseColors = listOf(
            Triple(0.42f, 0.26f, 0.14f), Triple(0.56f, 0.54f, 0.50f), Triple(0.40f, 0.24f, 0.13f),
            Triple(0.58f, 0.56f, 0.52f), Triple(0.38f, 0.22f, 0.12f), Triple(0.54f, 0.52f, 0.48f)
        )
        for ((i, entry) in bList.withIndex()) {
            val (cx, cz, dims) = entry
            val (w, d, h) = dims
            buildDetailedHouse(cx, cz, w, d, h, houseColors[i], Triple(0.60f, 0.22f, 0.15f), timberAccent = (i == 1 || i == 3))
        }

        val cottages = listOf(
            Triple(-14f, 8f, Triple(4f, 4f, 2.6f)), Triple(-9f, 14f, Triple(3.5f, 3.5f, 2.3f)),
            Triple(13f, 6f, Triple(4.2f, 3.8f, 2.6f)), Triple(18f, 15f, Triple(3.6f, 3.6f, 2.4f)),
            Triple(9f, -8f, Triple(4f, 4f, 2.6f))
        )
        for ((cx, cz, dims) in cottages) {
            val (w, d, h) = dims
            buildDetailedHouse(cx, cz, w, d, h, Triple(0.40f, 0.24f, 0.12f), Triple(0.60f, 0.22f, 0.15f))
        }

        // castle keep + four corner towers (roofs now correctly aligned to their square towers)
        val castle = listOf(
            Building(0f, 140f, 14f, 14f, 10f),
            Building(-7f, 133f, 3f, 3f, 13f), Building(7f, 133f, 3f, 3f, 13f),
            Building(-7f, 147f, 3f, 3f, 13f), Building(7f, 147f, 3f, 3f, 13f)
        )
        val stoneCol = Triple(0.62f, 0.60f, 0.56f)
        val turretRoof = Triple(0.55f, 0.20f, 0.16f)
        val diag = (PI / 4).toFloat()
        for (b in castle) {
            buildings.add(b)
            val by = heightAt(b.x, b.z)
            pushBox(b.x, b.z, b.w, b.d, b.h, by, stoneCol)
            pushShadowBlob(b.x, b.z, sqrt(b.w * b.w + b.d * b.d) / 2f * 1.3f)
        }
        for (i in 1..4) {
            val b = castle[i]
            pushCone(b.x, b.z, heightAt(b.x, b.z) + b.h, 2.4f, sqrt(b.w * b.w + b.d * b.d) / 2f, turretRoof, 4, diag)
        }
        val curtainH = 6f
        val curtainY = heightAt(0f, 140f)
        buildings.add(Building(0f, 133f, 14f, 1.2f, curtainH)); pushBox(0f, 133f, 14f, 1.2f, curtainH, curtainY, stoneCol)
        buildings.add(Building(0f, 147f, 14f, 1.2f, curtainH)); pushBox(0f, 147f, 14f, 1.2f, curtainH, curtainY, stoneCol)
        buildings.add(Building(-7f, 140f, 1.2f, 14f, curtainH)); pushBox(-7f, 140f, 1.2f, 14f, curtainH, curtainY, stoneCol)
        buildings.add(Building(7f, 140f, 1.2f, 14f, curtainH)); pushBox(7f, 140f, 1.2f, 14f, curtainH, curtainY, stoneCol)
        pushCrenellations(0f, 133f, 14f, curtainY + curtainH, stoneCol, true)
        pushCrenellations(0f, 147f, 14f, curtainY + curtainH, stoneCol, true)
        pushCrenellations(-7f, 140f, 14f, curtainY + curtainH, stoneCol, false)
        pushCrenellations(7f, 140f, 14f, curtainY + curtainH, stoneCol, false)

        val ruinStoneCol = Triple(0.40f, 0.42f, 0.34f)
        val ruinSegs = listOf(
            Triple(-45f, 108f, 4.2f), Triple(-41f, 112f, 2.0f), Triple(-49f, 112f, 1.4f), Triple(-45f, 116f, 3.0f)
        )
        for ((rx, rz, rh) in ruinSegs) {
            buildings.add(Building(rx, rz, 2.2f, 2.2f, rh))
            pushBox(rx, rz, 2.2f, 2.2f, rh, heightAt(rx, rz), ruinStoneCol)
            pushShadowBlob(rx, rz, 1.8f)
        }

        // windmill: tapered stone frustum tower, wooden cap, four crossed sail blades
        run {
            val wx = -25f; val wz = -60f
            val wy = heightAt(wx, wz)
            pushFrustum(wx, wz, wy, 7f, 2.2f, 1.3f, Triple(0.50f, 0.46f, 0.40f), 10)
            pushCone(wx, wz, wy + 7f, 1.6f, 1.5f, Triple(0.30f, 0.20f, 0.12f), 10)
            val hubY = wy + 6.2f; val hubZ = wz + 1.5f
            val bladeLen = 4.0f; val bladeW = 0.35f
            val bladeCol = Triple(0.32f, 0.22f, 0.12f)
            for (k in 0 until 4) {
                val ang = (PI / 4).toFloat() + k * (PI / 2).toFloat()
                val dirX = cos(ang); val dirY = sin(ang)
                val perpX = -sin(ang); val perpY = cos(ang)
                val tx = wx + dirX * bladeLen; val ty = hubY + dirY * bladeLen
                pushQuad(
                    Vec3(wx - perpX * bladeW, hubY - perpY * bladeW, hubZ), Vec3(wx + perpX * bladeW, hubY + perpY * bladeW, hubZ),
                    Vec3(tx + perpX * bladeW * 0.3f, ty + perpY * bladeW * 0.3f, hubZ), Vec3(tx - perpX * bladeW * 0.3f, ty - perpY * bladeW * 0.3f, hubZ),
                    bladeCol
                )
            }
            buildings.add(Building(wx, wz, 4.4f, 4.4f, 8.6f))
            pushShadowBlob(wx, wz, 3.0f)
        }

        // village well: small stone ring, two posts, a tiny peaked roof
        run {
            val wx = 2f; val wz = 0f
            val wy = heightAt(wx, wz)
            pushFrustum(wx, wz, wy, 1.0f, 1.0f, 1.0f, Triple(0.50f, 0.48f, 0.44f), 10)
            pushBox(wx - 0.8f, wz, 0.18f, 0.18f, 1.9f, wy, Triple(0.28f, 0.18f, 0.10f))
            pushBox(wx + 0.8f, wz, 0.18f, 0.18f, 1.9f, wy, Triple(0.28f, 0.18f, 0.10f))
            pushCone(wx, wz, wy + 1.9f, 1.0f, 1.3f, Triple(0.42f, 0.16f, 0.12f), 4, (PI / 4).toFloat())
            buildings.add(Building(wx, wz, 2.0f, 2.0f, 1.0f))
            pushShadowBlob(wx, wz, 1.6f)
        }

        // guard tower: freestanding round watchtower along the road, with battlements
        run {
            val tx = -10f; val tz = -70f
            val ty = heightAt(tx, tz)
            val towerH = 9f
            pushFrustum(tx, tz, ty, towerH, 1.8f, 1.5f, Triple(0.56f, 0.54f, 0.50f), 10)
            pushCrenellations(tx, tz, 9.4f, ty + towerH, Triple(0.56f, 0.54f, 0.50f), true)
            pushCrenellations(tx, tz, 9.4f, ty + towerH, Triple(0.56f, 0.54f, 0.50f), false)
            buildings.add(Building(tx, tz, 3.6f, 3.6f, towerH))
            pushShadowBlob(tx, tz, 2.6f)
        }

        var seed = 0x9E3779B9.toInt()
        fun rnd(): Float {
            seed = seed xor (seed shl 13); seed = seed xor (seed ushr 17); seed = seed xor (seed shl 5)
            return (seed.toLong() and 0xFFFFFFFFL).toFloat() / 4294967295f
        }
        for (i in 0 until 55) {
            val z = rnd() * 420f - 210f
            val side = if (rnd() < 0.5f) -1f else 1f
            val x = side * (6f + rnd() * 32f)
            buildBroadleafTree(x, z, 1.0f, ::rnd, Triple(0.14f, 0.42f, 0.16f))
        }

        var seedF = 0x1234ABCD.toInt()
        fun rndF(): Float {
            seedF = seedF xor (seedF shl 13); seedF = seedF xor (seedF ushr 17); seedF = seedF xor (seedF shl 5)
            return (seedF.toLong() and 0xFFFFFFFFL).toFloat() / 4294967295f
        }
        val denseCenterX = -70f; val denseCenterZ = 70f; val denseRadius = 32f
        for (i in 0 until 90) {
            val ang = rndF() * (PI * 2).toFloat()
            val rad = sqrt(rndF()) * denseRadius
            val x = denseCenterX + cos(ang) * rad
            val z = denseCenterZ + sin(ang) * rad
            buildBroadleafTree(x, z, 0.8f, ::rndF, Triple(0.09f, 0.30f, 0.13f))
        }
    }

    private val playerR = 0.4f
    fun collides(x: Float, z: Float): Boolean {
        for (b in buildings) {
            if (x > b.x - b.w / 2 - playerR && x < b.x + b.w / 2 + playerR &&
                z > b.z - b.d / 2 - playerR && z < b.z + b.d / 2 + playerR
            ) return true
        }
        for (t in trees) {
            val dx = x - t.x; val dz = z - t.z
            if (dx * dx + dz * dz < (t.r + playerR) * (t.r + playerR)) return true
        }
        if (bridgeHeightIfOn(x, z) == null) {
            if (riverDepthAt(x, z) > 0.12f) return true
        }
        return false
    }
}

// STATUS: still one fixed, hand-authored layout, not the chunk-based/streamed/seed-driven
// infinite open world the full brief calls for. Shadows are ground-contact blobs, not
// true sun-cast shadow mapping (see the previous revision's note on why that's a
// separate, higher-risk feature to attempt blind). House interiors are a single open
// room (floor + walls visible through the door/windows), not furnished or multi-room --
// a reasonable next step if wanted.
