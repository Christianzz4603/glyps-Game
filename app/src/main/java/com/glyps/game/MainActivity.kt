package com.glyps.game

import android.app.Activity
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager

/**
 * Fullscreen host for the glyph-grid renderer. Input is split into two virtual
 * touch zones since there's no keyboard/mouse on Android:
 *  - left half of the screen: drag = movement joystick (x/z)
 *  - right half of the screen: drag = look (yaw/pitch)
 */
class MainActivity : Activity() {

    private lateinit var glView: GLSurfaceView
    private lateinit var renderer: GlyphRenderer

    private var moveId = -1
    private var moveStartX = 0f
    private var moveStartY = 0f
    private var lookId = -1
    private var lastLookX = 0f
    private var lastLookY = 0f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        )

        renderer = GlyphRenderer(this)
        glView = object : GLSurfaceView(this) {
            override fun onTouchEvent(event: MotionEvent): Boolean {
                handleTouch(event)
                return true
            }
        }
        glView.setEGLContextClientVersion(3)
        glView.setRenderer(renderer)
        glView.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        setContentView(glView)
    }

    private fun handleTouch(event: MotionEvent) {
        val halfW = glView.width / 2f
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val idx = event.actionIndex
                val id = event.getPointerId(idx)
                val x = event.getX(idx)
                val y = event.getY(idx)
                if (x < halfW && moveId == -1) {
                    moveId = id; moveStartX = x; moveStartY = y
                } else if (x >= halfW && lookId == -1) {
                    lookId = id; lastLookX = x; lastLookY = y
                }
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until event.pointerCount) {
                    val id = event.getPointerId(i)
                    val x = event.getX(i)
                    val y = event.getY(i)
                    if (id == moveId) {
                        val dx = (x - moveStartX).coerceIn(-80f, 80f)
                        val dy = (y - moveStartY).coerceIn(-80f, 80f)
                        renderer.moveX = dx / 80f
                        renderer.moveZ = -dy / 80f
                    } else if (id == lookId) {
                        renderer.addLook(x - lastLookX, y - lastLookY)
                        lastLookX = x; lastLookY = y
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val idx = event.actionIndex
                val id = event.getPointerId(idx)
                if (id == moveId) { moveId = -1; renderer.moveX = 0f; renderer.moveZ = 0f }
                if (id == lookId) { lookId = -1 }
            }
            MotionEvent.ACTION_CANCEL -> {
                moveId = -1; lookId = -1
                renderer.moveX = 0f; renderer.moveZ = 0f
            }
        }
    }

    override fun onResume() { super.onResume(); glView.onResume() }
    override fun onPause() { super.onPause(); glView.onPause() }
}
