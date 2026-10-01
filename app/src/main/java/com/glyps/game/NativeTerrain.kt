package com.glyps.game

/**
 * Thin JNI bridge to native (C++) terrain-mesh generation -- see app/src/main/cpp/terrain.cpp.
 *
 * `available` is computed once, catching Throwable (not just Exception) specifically because
 * a failed System.loadLibrary throws UnsatisfiedLinkError, which is an Error, not an
 * Exception -- a plain catch-Exception would NOT catch it and the app would crash before a
 * single frame renders. If the library can't load for any reason on a given device/build,
 * `available` is simply false and every caller falls back to the existing Kotlin terrain
 * code (see World.kt's buildTerrain). A native-code problem should disable an optimization,
 * never take down the app.
 */
object NativeTerrain {
    val available: Boolean = try {
        System.loadLibrary("glyphterrain")
        true
    } catch (e: Throwable) {
        android.util.Log.w("NativeTerrain", "native terrain library unavailable, using Kotlin fallback", e)
        false
    }

    /** Returns the full terrain mesh (interleaved pos3/normal3/color3 per vertex, two
     *  triangles per grid quad) for the square from -half..half in both axes, at the given
     *  grid step -- identical math to World.kt's (Kotlin-fallback) terrain generation. */
    external fun nativeBuildTerrain(half: Float, step: Float): FloatArray
}
