package com.glyps.game

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

// world/scene geometry for a small medieval realm: rolling hills, colored fields, a
// winding river with a stone bridge, a village of roofed cottages, a castle with corner
// turrets, roads that follow the terrain, and a scattered forest -- all still one fixed,
// hand-placed layout (not chunked/streamed/seed-generated yet, see the note at the end
// of this file for what's still a separate follow-up).

data class Vec3(val x: Float, val y: Float, val z: Float)
data class Building(val x: Float, val z: Float, val w: Float, val d: Float, val h: Float)
data class Tree(val x: Float, val z: Float, val r: Float)

class World {
    // interleaved pos3, normal3, color3 per vertex
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
    // per-vertex-colored versions, used for the terrain grid so grass/farmland/rock
    // patches blend smoothly instead of each grid cell being one flat color
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
    private fun pushBox(cx: Float, cz: Float, w: Float, d: Float, h: Float, baseY: Float, col: Triple<Float, Float, Float>) {
        val x0 = cx - w / 2; val x1 = cx + w / 2; val z0 = cz - d / 2; val z1 = cz + d / 2
        val y0 = baseY; val y1 = baseY + h
        pushQuad(Vec3(x0, y0, z1), Vec3(x1, y0, z1), Vec3(x1, y1, z1), Vec3(x0, y1, z1), col)
        pushQuad(Vec3(x1, y0, z0), Vec3(x0, y0, z0), Vec3(x0, y1, z0), Vec3(x1, y1, z0), col)
        pushQuad(Vec3(x0, y0, z0), Vec3(x0, y0, z1), Vec3(x0, y1, z1), Vec3(x0, y1, z0), col)
        pushQuad(Vec3(x1, y0, z1), Vec3(x1, y0, z0), Vec3(x1, y1, z0), Vec3(x1, y1, z1), col)
        pushQuad(Vec3(x0, y1, z1), Vec3(x1, y1, z1), Vec3(x1, y1, z0), Vec3(x0, y1, z0), col)
    }
    private fun pushCone(cx: Float, cz: Float, y0: Float, h: Float, r: Float, col: Triple<Float, Float, Float>, seg: Int) {
        val apex = Vec3(cx, y0 + h, cz)
        for (i in 0 until seg) {
            val a0 = i.toFloat() / seg * (PI * 2).toFloat()
            val a1 = (i + 1).toFloat() / seg * (PI * 2).toFloat()
            pushTri(
                Vec3(cx + cos(a0) * r, y0, cz + sin(a0) * r),
                Vec3(cx + cos(a1) * r, y0, cz + sin(a1) * r),
                apex, col
            )
        }
    }

    /** Gentle rolling hills. Cheap two-octave sine field -- no noise library needed. */
    fun heightAt(x: Float, z: Float): Float {
        return 0.9f * sin(x * 0.035f) * cos(z * 0.045f) + 0.4f * sin(x * 0.09f + 1.3f) * cos(z * 0.07f + 0.6f)
    }

    /** Grass green in some patches, farmland/dirt brown in others -- driven off a second,
     *  independent sine field so color patches don't just track the hill shape 1:1. */
    private fun terrainColor(x: Float, z: Float): Triple<Float, Float, Float> {
        val patch = sin(x * 0.02f) * cos(z * 0.025f) + sin(x * 0.006f + 2f) * cos(z * 0.014f + 1f)
        val green = ((patch + 1.4f) / 2.8f).coerceIn(0f, 1f)
        val r = 0.16f + 0.20f * (1f - green)
        val g = 0.30f + 0.24f * green
        val b = 0.10f + 0.08f * green
        return Triple(r, g, b)
    }

    private fun buildTerrain() {
        val half = 200f
        val step = 10f
        var x = -half
        while (x < half) {
            var z = -half
            while (z < half) {
                val x0 = x; val x1 = x + step; val z0 = z; val z1 = z + step
                pushQuadVC(
                    Vec3(x0, heightAt(x0, z0), z0), terrainColor(x0, z0),
                    Vec3(x0, heightAt(x0, z1), z1), terrainColor(x0, z1),
                    Vec3(x1, heightAt(x1, z1), z1), terrainColor(x1, z1),
                    Vec3(x1, heightAt(x1, z0), z0), terrainColor(x1, z0)
                )
                z += step
            }
            x += step
        }
    }

    private fun buildRoad() {
        val half = 300f; val step = 10f; val halfWidth = 4f
        val col = Triple(0.42f, 0.40f, 0.36f) // packed dirt/stone, warmer than plain gray
        var z = -half
        while (z < half) {
            val z0 = z; val z1 = z + step
            val y0 = heightAt(0f, z0) + 0.06f
            val y1 = heightAt(0f, z1) + 0.06f
            pushQuad(Vec3(-halfWidth, y0, z0), Vec3(-halfWidth, y1, z1), Vec3(halfWidth, y1, z1), Vec3(halfWidth, y0, z0), col)
            z += step
        }
    }

    /** A winding river crossing the road once, plus a stone bridge at the crossing. */
    private fun buildRiverAndBridge() {
        val riverBaseZ = 45f
        val steps = 60
        val xStart = -200f; val xEnd = 200f
        val riverColor = Triple(0.10f, 0.30f, 0.44f)
        var prevX = xStart
        var prevZ = riverBaseZ + sin(prevX * 0.02f) * 15f
        var prevY = heightAt(prevX, prevZ) - 0.25f
        for (i in 1..steps) {
            val x = xStart + (xEnd - xStart) * i / steps
            val z = riverBaseZ + sin(x * 0.02f) * 15f
            val y = heightAt(x, z) - 0.25f
            val halfW = 3.5f
            pushQuad(Vec3(prevX, prevY, prevZ - halfW), Vec3(prevX, prevY, prevZ + halfW), Vec3(x, y, z + halfW), Vec3(x, y, z - halfW), riverColor)
            prevX = x; prevZ = z; prevY = y
        }
        val bridgeZ = riverBaseZ + sin(0f) * 15f
        val bridgeY = heightAt(0f, bridgeZ) + 0.3f
        pushBox(0f, bridgeZ, 8f, 11f, 0.6f, bridgeY, Triple(0.47f, 0.45f, 0.42f))
    }

    init {
        buildTerrain()
        buildRoad()
        buildRiverAndBridge()

        // the original six houses, now roofed, terrain-following, and given wood/stone variety
        val bList = listOf(
            Building(-16f, -25f, 9f, 8f, 7f), Building(16f, -45f, 11f, 6f, 10f), Building(-13f, 30f, 6f, 10f, 5f),
            Building(19f, 65f, 9f, 9f, 8f), Building(-20f, 95f, 7f, 7f, 6f), Building(15f, -95f, 8f, 5f, 9f)
        )
        val houseColors = listOf(
            Triple(0.30f, 0.19f, 0.10f), Triple(0.38f, 0.36f, 0.33f), Triple(0.28f, 0.17f, 0.09f),
            Triple(0.40f, 0.38f, 0.34f), Triple(0.27f, 0.16f, 0.09f), Triple(0.36f, 0.34f, 0.30f)
        )
        for ((i, b) in bList.withIndex()) {
            buildings.add(b)
            val by = heightAt(b.x, b.z)
            pushBox(b.x, b.z, b.w, b.d, b.h, by, houseColors[i])
            pushCone(b.x, b.z, by + b.h, b.h * 0.5f, sqrt(b.w * b.w + b.d * b.d) / 2f * 0.9f, Triple(0.40f, 0.13f, 0.10f), 4)
        }

        // a small village of huts near the start
        val cottages = listOf(
            Triple(-14f, 8f, Triple(4f, 4f, 2.6f)), Triple(-9f, 14f, Triple(3.5f, 3.5f, 2.3f)),
            Triple(13f, 6f, Triple(4.2f, 3.8f, 2.6f)), Triple(18f, 15f, Triple(3.6f, 3.6f, 2.4f)),
            Triple(9f, -8f, Triple(4f, 4f, 2.6f))
        )
        for ((cx, cz, dims) in cottages) {
            val (w, d, h) = dims
            buildings.add(Building(cx, cz, w, d, h))
            val by = heightAt(cx, cz)
            pushBox(cx, cz, w, d, h, by, Triple(0.32f, 0.20f, 0.11f))
            pushCone(cx, cz, by + h, h * 0.6f, sqrt(w * w + d * d) / 2f * 0.95f, Triple(0.42f, 0.14f, 0.10f), 4)
        }

        // castle keep + four corner towers with turret roofs
        val castle = listOf(
            Building(0f, 140f, 14f, 14f, 10f),
            Building(-7f, 133f, 3f, 3f, 13f), Building(7f, 133f, 3f, 3f, 13f),
            Building(-7f, 147f, 3f, 3f, 13f), Building(7f, 147f, 3f, 3f, 13f)
        )
        val stoneCol = Triple(0.52f, 0.50f, 0.47f)
        val turretRoof = Triple(0.32f, 0.11f, 0.10f)
        for (b in castle) {
            buildings.add(b)
            pushBox(b.x, b.z, b.w, b.d, b.h, heightAt(b.x, b.z), stoneCol)
        }
        for (i in 1..4) {
            val b = castle[i]
            pushCone(b.x, b.z, heightAt(b.x, b.z) + b.h, 2.4f, 2.6f, turretRoof, 4)
        }

        // deterministic pseudo-random tree scatter (no rng dependency needed)
        var seed = 0x9E3779B9.toInt()
        fun rnd(): Float {
            seed = seed xor (seed shl 13); seed = seed xor (seed ushr 17); seed = seed xor (seed shl 5)
            return (seed.toLong() and 0xFFFFFFFFL).toFloat() / 4294967295f
        }
        for (i in 0 until 55) {
            val z = rnd() * 420f - 210f
            val side = if (rnd() < 0.5f) -1f else 1f
            val x = side * (6f + rnd() * 32f)
            trees.add(Tree(x, z, 0.55f))
            val by = heightAt(x, z)
            pushBox(x, z, 0.35f, 0.35f, 1.1f, by, Triple(0.12f, 0.08f, 0.05f))
            pushCone(x, z, by + 0.9f, 2.6f, 1.4f, Triple(0.10f, 0.34f, 0.13f), 7) // brighter medieval-forest green
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
        return false
    }
}

// STATUS: still one fixed, hand-authored layout (terrain grid + placed props), not the
// chunk-based/streamed/seed-driven infinite open world the full brief calls for. That's
// still a separate, larger follow-up -- what changed here is real geometry (hills, a
// river, roofs, a village), not a re-skin of the old flat layout.
