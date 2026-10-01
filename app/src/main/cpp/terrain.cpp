// Native terrain-mesh generation, mirroring World.kt's (Kotlin-fallback) math exactly:
// coherent value noise for rolling hills, gaussian mountain bumps, a carved river valley,
// grass/rock/snow color blending, and an analytic per-vertex normal via central differences.
// Mountain data below MUST stay in sync with World.kt's MOUNTAINS list if either changes.
//
// Called once at app startup via NativeTerrain.nativeBuildTerrain (see NativeTerrain.kt),
// which Kotlin falls back away from automatically if this library fails to load or throws.

#include <jni.h>
#include <cmath>
#include <cstdint>
#include <vector>

namespace {

struct Mountain { float x, z, radius, peak; };

// Must match World.kt's `private val MOUNTAINS` list exactly.
const Mountain MOUNTAINS[] = {
    {-170.0f, -160.0f, 65.0f, 32.0f},
    {160.0f, 175.0f, 60.0f, 28.0f},
    {90.0f, 195.0f, 50.0f, 16.0f},
};
const int MOUNTAIN_COUNT = 3;

const float RIVER_BASE_Z = 45.0f;

float hash2(int32_t xi, int32_t zi) {
    int32_t h = xi * 374761393 + zi * 668265263;
    h = (h ^ (h >> 13)) * 1274126177;
    h = h ^ (h >> 16);
    return static_cast<float>(h & 0x7fffffff) / static_cast<float>(0x7fffffff);
}

float valueNoise(float x, float z) {
    int xi = static_cast<int>(std::floor(x));
    int zi = static_cast<int>(std::floor(z));
    float xf = x - static_cast<float>(xi);
    float zf = z - static_cast<float>(zi);
    float v00 = hash2(xi, zi);
    float v10 = hash2(xi + 1, zi);
    float v01 = hash2(xi, zi + 1);
    float v11 = hash2(xi + 1, zi + 1);
    float sx = xf * xf * (3.0f - 2.0f * xf);
    float sz = zf * zf * (3.0f - 2.0f * zf);
    float a = v00 + (v10 - v00) * sx;
    float b = v01 + (v11 - v01) * sx;
    return a + (b - a) * sz;
}

float fbm(float x, float z, int octaves) {
    float total = 0.0f, amp = 1.0f, freq = 1.0f, maxAmp = 0.0f;
    for (int i = 0; i < octaves; i++) {
        total += valueNoise(x * freq, z * freq) * amp;
        maxAmp += amp;
        amp *= 0.5f;
        freq *= 2.0f;
    }
    return total / maxAmp;
}

float riverZAt(float x) {
    return RIVER_BASE_Z + sinf(x * 0.02f) * 15.0f;
}

// NOTE: no second high-frequency octave here -- that was the bug in the Kotlin version
// (a noise wavelength finer than the terrain grid step, producing visible per-vertex
// jitter). Keep this in sync with World.kt's (fixed) baseHeightAt.
float baseHeightAt(float x, float z) {
    float h = (fbm(x * 0.045f, z * 0.045f, 5) - 0.5f) * 5.0f;
    for (int i = 0; i < MOUNTAIN_COUNT; i++) {
        float dx = x - MOUNTAINS[i].x;
        float dz = z - MOUNTAINS[i].z;
        float d2 = dx * dx + dz * dz;
        h += MOUNTAINS[i].peak * expf(-d2 / (2.0f * MOUNTAINS[i].radius * MOUNTAINS[i].radius));
    }
    return h;
}

float heightAt(float x, float z) {
    float h = baseHeightAt(x, z);
    float distToRiver = fabsf(z - riverZAt(x));
    float t = 1.0f - fminf(fmaxf(distToRiver / 7.0f, 0.0f), 1.0f);
    return h - t * t * 1.3f;
}

void terrainColor(float x, float z, float h, float &r, float &g, float &b) {
    float patch = sinf(x * 0.02f) * cosf(z * 0.025f) + sinf(x * 0.006f + 2.0f) * cosf(z * 0.014f + 1.0f);
    float green = fminf(fmaxf((patch + 1.4f) / 2.8f, 0.0f), 1.0f);
    r = 0.16f + 0.22f * (1.0f - green);
    g = 0.34f + 0.28f * green;
    b = 0.10f + 0.09f * green;
    float fine = (fbm(x * 0.3f, z * 0.3f, 2) - 0.5f) * 0.10f;
    r += fine; g += fine * 0.8f; b += fine * 0.6f;
    float rockT = fminf(fmaxf((h - 6.0f) / 10.0f, 0.0f), 1.0f);
    r += (0.42f - r) * rockT; g += (0.40f - g) * rockT; b += (0.40f - b) * rockT;
    float snowT = fminf(fmaxf((h - 16.0f) / 8.0f, 0.0f), 1.0f);
    r += (0.88f - r) * snowT; g += (0.90f - g) * snowT; b += (0.92f - b) * snowT;
    r = fminf(fmaxf(r, 0.0f), 1.0f);
    g = fminf(fmaxf(g, 0.0f), 1.0f);
    b = fminf(fmaxf(b, 0.0f), 1.0f);
}

void normalAt(float x, float z, float &nx, float &ny, float &nz) {
    const float eps = 0.4f;
    float hL = heightAt(x - eps, z);
    float hR = heightAt(x + eps, z);
    float hD = heightAt(x, z - eps);
    float hU = heightAt(x, z + eps);
    // dX = (2eps, hR-hL, 0), dZ = (0, hU-hD, 2eps); normal = normalize(cross(dZ, dX))
    float dXx = 2.0f * eps, dXy = hR - hL, dXz = 0.0f;
    float dZx = 0.0f, dZy = hU - hD, dZz = 2.0f * eps;
    float cx = dZy * dXz - dZz * dXy;
    float cy = dZz * dXx - dZx * dXz;
    float cz = dZx * dXy - dZy * dXx;
    float len = sqrtf(cx * cx + cy * cy + cz * cz);
    if (len < 1e-6f) len = 1e-6f;
    nx = cx / len; ny = cy / len; nz = cz / len;
}

inline void pushVertex(std::vector<float> &out, float px, float py, float pz,
                        float nx, float ny, float nz, float r, float g, float b) {
    out.push_back(px); out.push_back(py); out.push_back(pz);
    out.push_back(nx); out.push_back(ny); out.push_back(nz);
    out.push_back(r); out.push_back(g); out.push_back(b);
}

}  // namespace

extern "C"
JNIEXPORT jfloatArray JNICALL
Java_com_glyps_game_NativeTerrain_nativeBuildTerrain(JNIEnv *env, jobject /* thiz */, jfloat half, jfloat step) {
    std::vector<float> out;
    out.reserve(400000);

    for (float x = -half; x < half; x += step) {
        float x0 = x, x1 = x + step;
        for (float z = -half; z < half; z += step) {
            float z0 = z, z1 = z + step;

            float hA = heightAt(x0, z0), hB = heightAt(x0, z1), hC = heightAt(x1, z1), hD = heightAt(x1, z0);
            float nAx, nAy, nAz, nBx, nBy, nBz, nCx, nCy, nCz, nDx, nDy, nDz;
            normalAt(x0, z0, nAx, nAy, nAz);
            normalAt(x0, z1, nBx, nBy, nBz);
            normalAt(x1, z1, nCx, nCy, nCz);
            normalAt(x1, z0, nDx, nDy, nDz);
            float rA, gA, bA, rB, gB, bB, rC, gC, bC, rD, gD, bD;
            terrainColor(x0, z0, hA, rA, gA, bA);
            terrainColor(x0, z1, hB, rB, gB, bB);
            terrainColor(x1, z1, hC, rC, gC, bC);
            terrainColor(x1, z0, hD, rD, gD, bD);

            // two triangles (a,b,c) and (a,c,d), matching World.kt's pushQuadVCN winding
            pushVertex(out, x0, hA, z0, nAx, nAy, nAz, rA, gA, bA);
            pushVertex(out, x0, hB, z1, nBx, nBy, nBz, rB, gB, bB);
            pushVertex(out, x1, hC, z1, nCx, nCy, nCz, rC, gC, bC);

            pushVertex(out, x0, hA, z0, nAx, nAy, nAz, rA, gA, bA);
            pushVertex(out, x1, hC, z1, nCx, nCy, nCz, rC, gC, bC);
            pushVertex(out, x1, hD, z0, nDx, nDy, nDz, rD, gD, bD);
        }
    }

    jfloatArray result = env->NewFloatArray(static_cast<jsize>(out.size()));
    if (result == nullptr) return nullptr;
    env->SetFloatArrayRegion(result, 0, static_cast<jsize>(out.size()), out.data());
    return result;
}
