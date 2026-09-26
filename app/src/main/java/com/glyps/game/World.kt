package com.glyps.game

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

// world/scene geometry: ground, road, buildings, trees, plus their collision shapes.
// Mirrors the web and native-Rust prototypes of this renderer.

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
    private fun pushBox(cx: Float, cz: Float, w: Float, d: Float, h: Float, col: Triple<Float, Float, Float>) {
        val x0 = cx - w / 2; val x1 = cx + w / 2; val z0 = cz - d / 2; val z1 = cz + d / 2
        pushQuad(Vec3(x0, 0f, z1), Vec3(x1, 0f, z1), Vec3(x1, h, z1), Vec3(x0, h, z1), col)
        pushQuad(Vec3(x1, 0f, z0), Vec3(x0, 0f, z0), Vec3(x0, h, z0), Vec3(x1, h, z0), col)
        pushQuad(Vec3(x0, 0f, z0), Vec3(x0, 0f, z1), Vec3(x0, h, z1), Vec3(x0, h, z0), col)
        pushQuad(Vec3(x1, 0f, z1), Vec3(x1, 0f, z0), Vec3(x1, h, z0), Vec3(x1, h, z1), col)
        pushQuad(Vec3(x0, h, z1), Vec3(x1, h, z1), Vec3(x1, h, z0), Vec3(x0, h, z0), col)
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

    init {
        pushQuad(Vec3(-200f, 0f, -200f), Vec3(-200f, 0f, 200f), Vec3(200f, 0f, 200f), Vec3(200f, 0f, -200f), Triple(0.04f, 0.07f, 0.045f)) // ground
        pushQuad(Vec3(-4f, 0.02f, -300f), Vec3(-4f, 0.02f, 300f), Vec3(4f, 0.02f, 300f), Vec3(4f, 0.02f, -300f), Triple(0.14f, 0.14f, 0.15f)) // road

        val bList = listOf(
            Building(-16f, -25f, 9f, 8f, 7f), Building(16f, -45f, 11f, 6f, 10f), Building(-13f, 30f, 6f, 10f, 5f),
            Building(19f, 65f, 9f, 9f, 8f), Building(-20f, 95f, 7f, 7f, 6f), Building(15f, -95f, 8f, 5f, 9f)
        )
        for (b in bList) { buildings.add(b); pushBox(b.x, b.z, b.w, b.d, b.h, Triple(0.10f, 0.09f, 0.10f)) }

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
            pushBox(x, z, 0.35f, 0.35f, 1.1f, Triple(0.10f, 0.07f, 0.05f))
            pushCone(x, z, 0.9f, 2.6f, 1.4f, Triple(0.03f, 0.09f, 0.04f), 7)
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
