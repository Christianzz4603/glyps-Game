# Glyph Grid Horror (Android)

A native Android port of the glyph-grid renderer: a real 3D scene is rasterized to an offscreen texture, then a fullscreen shader converts it into a grid of font glyphs (the glyph grid IS the final image, not a filter over one).

See `app/` for the Kotlin/OpenGL ES source and `.github/workflows/android-build.yml` for the CI build. Check the Actions tab after each push for a real compiled-APK verification.
