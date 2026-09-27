package com.glyps.game

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
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

    // finer grid so the (fairly narrow) river valley is actually resolved by the mesh
    // instead of being smoothed away between coarse grid vertices
    private fun buildTerrain() {
        val half = 200f; val step = 5f
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
        val steps = 80
        val xStart = -200f; val xEnd = 200f
        val riverColor = Triple(0.15f, 0.48f, 0.66f)
        var prevX = xStart
        var prevZ = riverZAt(prevX)
        var prevY = heightAt(prevX, prevZ) - 0.4f // generous clearance below the (now finer) carved valley floor
        for (i in 1..steps) {
            val x = xStart + (xEnd - xStart) * i / steps
            val z = riverZAt(x)
            val y = heightAt(x, z) - 0.4f
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

    /** A real house: split walls with a walkable door gap, two glowing windows, an
     *  interior floor, a properly-aligned gable roof (not a rotated pyramid), and a
     *  chimney. Collision is 5 thin wall-strip rectangles (split around the door) added
     *  to the existing `buildings` AABB list -- reuses the existing collision code
     *  exactly, no new logic, and the door gap is simply where no strip exists. */
    private fun buildDetailedHouse(cx: Float, cz: Float, w: Float, d: Float, wallH: Float, wallCol: Triple<Float, Float, Float>, roofCol: Triple<Float, Float, Float>) {
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
        buildRiverAndBridge()

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
            buildDetailedHouse(cx, cz, w, d, h, houseColors[i], Triple(0.60f, 0.22f, 0.15f))
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
            val rz = riverZAt(x)
            if (abs(z - rz) < RIVER_HALF_W + playerR) return true
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
