package com.glyps.game

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

// world/scene geometry for a small medieval realm: mountains with rock/snow coloring,
// rolling fields, a real carved river valley crossed by a stone bridge, a village, a
// walled castle with turrets, ruins, and both scattered and dense tall forest -- ground
// contact shadows under every object. Still one fixed, hand-placed layout (not
// chunked/streamed/seed-generated), see the note at the end of this file.

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
    private val SHADOW_COL = Triple(0.045f, 0.06f, 0.045f)
    private fun pushShadowBlob(cx: Float, cz: Float, radius: Float) {
        pushCone(cx, cz, heightAt(cx, cz) + 0.02f, 0f, radius, SHADOW_COL, 10)
    }

    private val MOUNTAINS = listOf(
        Mountain(-170f, -160f, 65f, 32f),
        Mountain(160f, 175f, 60f, 28f),
        Mountain(90f, 195f, 50f, 16f)
    )
    private fun baseHeightAt(x: Float, z: Float): Float {
        var h = 0.9f * sin(x * 0.035f) * cos(z * 0.045f) + 0.4f * sin(x * 0.09f + 1.3f) * cos(z * 0.07f + 0.6f)
        for (m in MOUNTAINS) {
            val dx = x - m.x; val dz = z - m.z
            val d2 = dx * dx + dz * dz
            h += m.peak * exp(-d2 / (2f * m.radius * m.radius))
        }
        return h
    }

    private val RIVER_BASE_Z = 45f
    private val RIVER_HALF_W = 3.5f
    private val BRIDGE_X_HALF = 4.5f
    private val BRIDGE_Z_HALF = 6.0f
    private fun riverZAt(x: Float): Float = RIVER_BASE_Z + sin(x * 0.02f) * 15f

    fun heightAt(x: Float, z: Float): Float {
        val h = baseHeightAt(x, z)
        val distToRiver = abs(z - riverZAt(x))
        val dipT = (1f - (distToRiver / 7f).coerceIn(0f, 1f))
        return h - dipT * dipT * 1.3f
    }

    private fun terrainColor(x: Float, z: Float, h: Float): Triple<Float, Float, Float> {
        val patch = sin(x * 0.02f) * cos(z * 0.025f) + sin(x * 0.006f + 2f) * cos(z * 0.014f + 1f)
        val green = ((patch + 1.4f) / 2.8f).coerceIn(0f, 1f)
        var r = 0.16f + 0.22f * (1f - green)
        var g = 0.34f + 0.28f * green
        var b = 0.10f + 0.09f * green
        val rockT = ((h - 6f) / 10f).coerceIn(0f, 1f)
        r += (0.42f - r) * rockT; g += (0.40f - g) * rockT; b += (0.40f - b) * rockT
        val snowT = ((h - 16f) / 8f).coerceIn(0f, 1f)
        r += (0.88f - r) * snowT; g += (0.90f - g) * snowT; b += (0.92f - b) * snowT
        return Triple(r, g, b)
    }

    private fun buildTerrain() {
        val half = 200f; val step = 8f
        var x = -half
        while (x < half) {
            var z = -half
            while (z < half) {
                val x0 = x; val x1 = x + step; val z0 = z; val z1 = z + step
                val hA = heightAt(x0, z0); val hB = heightAt(x0, z1); val hC = heightAt(x1, z1); val hD = heightAt(x1, z0)
                pushQuadVC(
                    Vec3(x0, hA, z0), terrainColor(x0, z0, hA),
                    Vec3(x0, hB, z1), terrainColor(x0, z1, hB),
                    Vec3(x1, hC, z1), terrainColor(x1, z1, hC),
                    Vec3(x1, hD, z0), terrainColor(x1, z0, hD)
                )
                z += step
            }
            x += step
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

    private val bridgeZ = riverZAt(0f)
    private val bridgeY = baseHeightAt(0f, bridgeZ) + 0.35f

    private fun buildRiverAndBridge() {
        val steps = 60
        val xStart = -200f; val xEnd = 200f
        val riverColor = Triple(0.15f, 0.48f, 0.66f)
        var prevX = xStart
        var prevZ = riverZAt(prevX)
        var prevY = heightAt(prevX, prevZ) - 0.05f
        for (i in 1..steps) {
            val x = xStart + (xEnd - xStart) * i / steps
            val z = riverZAt(x)
            val y = heightAt(x, z) - 0.05f
            pushQuad(Vec3(prevX, prevY, prevZ - RIVER_HALF_W), Vec3(prevX, prevY, prevZ + RIVER_HALF_W), Vec3(x, y, z + RIVER_HALF_W), Vec3(x, y, z - RIVER_HALF_W), riverColor)
            prevX = x; prevZ = z; prevY = y
        }
        val bridgeStone = Triple(0.58f, 0.56f, 0.52f)
        pushBox(0f, bridgeZ, 8f, 11f, 0.6f, bridgeY, bridgeStone)
        pushBox(0f, bridgeZ - 5f, 8.5f, 0.6f, 0.5f, bridgeY + 0.6f, bridgeStone)
        pushBox(0f, bridgeZ + 5f, 8.5f, 0.6f, 0.5f, bridgeY + 0.6f, bridgeStone)
    }

    fun bridgeHeightIfOn(x: Float, z: Float): Float? =
        if (abs(x) < BRIDGE_X_HALF && abs(z - bridgeZ) < BRIDGE_Z_HALF) bridgeY else null

    init {
        buildTerrain()
        buildRoad()
        buildRiverAndBridge()

        val bList = listOf(
            Building(-16f, -25f, 9f, 8f, 7f), Building(16f, -45f, 11f, 6f, 10f), Building(-13f, 30f, 6f, 10f, 5f),
            Building(19f, 65f, 9f, 9f, 8f), Building(-20f, 95f, 7f, 7f, 6f), Building(15f, -95f, 8f, 5f, 9f)
        )
        val houseColors = listOf(
            Triple(0.42f, 0.26f, 0.14f), Triple(0.56f, 0.54f, 0.50f), Triple(0.40f, 0.24f, 0.13f),
            Triple(0.58f, 0.56f, 0.52f), Triple(0.38f, 0.22f, 0.12f), Triple(0.54f, 0.52f, 0.48f)
        )
        for ((i, b) in bList.withIndex()) {
            buildings.add(b)
            val by = heightAt(b.x, b.z)
            pushBox(b.x, b.z, b.w, b.d, b.h, by, houseColors[i])
            pushCone(b.x, b.z, by + b.h, b.h * 0.5f, sqrt(b.w * b.w + b.d * b.d) / 2f * 0.9f, Triple(0.60f, 0.22f, 0.15f), 4)
            pushShadowBlob(b.x, b.z, sqrt(b.w * b.w + b.d * b.d) / 2f * 1.2f)
        }

        val cottages = listOf(
            Triple(-14f, 8f, Triple(4f, 4f, 2.6f)), Triple(-9f, 14f, Triple(3.5f, 3.5f, 2.3f)),
            Triple(13f, 6f, Triple(4.2f, 3.8f, 2.6f)), Triple(18f, 15f, Triple(3.6f, 3.6f, 2.4f)),
            Triple(9f, -8f, Triple(4f, 4f, 2.6f))
        )
        for ((cx, cz, dims) in cottages) {
            val (w, d, h) = dims
            buildings.add(Building(cx, cz, w, d, h))
            val by = heightAt(cx, cz)
            pushBox(cx, cz, w, d, h, by, Triple(0.40f, 0.24f, 0.12f))
            pushCone(cx, cz, by + h, h * 0.6f, sqrt(w * w + d * d) / 2f * 0.95f, Triple(0.60f, 0.22f, 0.15f), 4)
            pushShadowBlob(cx, cz, sqrt(w * w + d * d) / 2f * 1.2f)
        }

        val castle = listOf(
            Building(0f, 140f, 14f, 14f, 10f),
            Building(-7f, 133f, 3f, 3f, 13f), Building(7f, 133f, 3f, 3f, 13f),
            Building(-7f, 147f, 3f, 3f, 13f), Building(7f, 147f, 3f, 3f, 13f)
        )
        val stoneCol = Triple(0.62f, 0.60f, 0.56f)
        val turretRoof = Triple(0.55f, 0.20f, 0.16f)
        for (b in castle) {
            buildings.add(b)
            val by = heightAt(b.x, b.z)
            pushBox(b.x, b.z, b.w, b.d, b.h, by, stoneCol)
            pushShadowBlob(b.x, b.z, sqrt(b.w * b.w + b.d * b.d) / 2f * 1.3f)
        }
        for (i in 1..4) {
            val b = castle[i]
            pushCone(b.x, b.z, heightAt(b.x, b.z) + b.h, 2.4f, 2.6f, turretRoof, 4)
        }
        val curtainH = 6f
        val curtainY = heightAt(0f, 140f)
        buildings.add(Building(0f, 133f, 14f, 1.2f, curtainH)); pushBox(0f, 133f, 14f, 1.2f, curtainH, curtainY, stoneCol)
        buildings.add(Building(0f, 147f, 14f, 1.2f, curtainH)); pushBox(0f, 147f, 14f, 1.2f, curtainH, curtainY, stoneCol)
        buildings.add(Building(-7f, 140f, 1.2f, 14f, curtainH)); pushBox(-7f, 140f, 1.2f, 14f, curtainH, curtainY, stoneCol)
        buildings.add(Building(7f, 140f, 1.2f, 14f, curtainH)); pushBox(7f, 140f, 1.2f, 14f, curtainH, curtainY, stoneCol)

        val ruinStoneCol = Triple(0.40f, 0.42f, 0.34f)
        val ruinSegs = listOf(
            Triple(-45f, 108f, 4.2f), Triple(-41f, 112f, 2.0f), Triple(-49f, 112f, 1.4f), Triple(-45f, 116f, 3.0f)
        )
        for ((rx, rz, rh) in ruinSegs) {
            buildings.add(Building(rx, rz, 2.2f, 2.2f, rh))
            pushBox(rx, rz, 2.2f, 2.2f, rh, heightAt(rx, rz), ruinStoneCol)
            pushShadowBlob(rx, rz, 1.8f)
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
            trees.add(Tree(x, z, 0.6f))
            val by = heightAt(x, z)
            pushBox(x, z, 0.4f, 0.4f, 2.0f, by, Triple(0.20f, 0.13f, 0.08f))
            pushCone(x, z, by + 1.6f, 4.0f, 1.7f, Triple(0.14f, 0.42f, 0.16f), 7)
            pushCone(x, z, by + 4.2f, 2.0f, 1.05f, Triple(0.10f, 0.36f, 0.14f), 7)
            pushShadowBlob(x, z, 1.9f)
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
            trees.add(Tree(x, z, 0.5f))
            val by = heightAt(x, z)
            pushBox(x, z, 0.32f, 0.32f, 1.6f, by, Triple(0.18f, 0.12f, 0.07f))
            pushCone(x, z, by + 1.3f, 3.2f, 1.3f, Triple(0.09f, 0.30f, 0.13f), 6)
            pushCone(x, z, by + 3.3f, 1.6f, 0.85f, Triple(0.07f, 0.26f, 0.11f), 6)
            pushShadowBlob(x, z, 1.5f)
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
            val rz = riverZAt(x)
            if (abs(z - rz) < RIVER_HALF_W + playerR) return true
        }
        return false
    }
}

// STATUS: still one fixed, hand-authored layout, not the chunk-based/streamed/seed-driven
// infinite open world the full brief calls for. Shadows here are cheap ground-contact
// blobs, not true sun-cast shadow mapping -- that needs a second depth-only render pass,
// a light-space projection matrix, and bias tuning that is notoriously hard to get right
// without ever seeing the actual rendered frame, so a guaranteed-to-look-reasonable
// technique was the safer choice this round. Real shadow mapping is a good next step if
// wanted, ideally with a screenshot in the loop to tune it against.
