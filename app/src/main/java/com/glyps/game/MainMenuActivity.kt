package com.glyps.game

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** Simple main menu shown before gameplay starts -- built programmatically (no XML layout)
 *  to keep this self-contained and low-risk. Styled to match the glyph-grid aesthetic:
 *  black background, green monospace text. */
class MainMenuActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        )

        val accent = Color.rgb(90, 220, 110)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.BLACK)
            setPadding(64, 64, 64, 64)
        }

        val title = TextView(this).apply {
            text = "GLYPH GRID\nMEDIEVAL REALM"
            setTextColor(accent)
            textSize = 30f
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 20)
        }

        val subtitle = TextView(this).apply {
            text = "a 3D world rendered entirely in characters"
            setTextColor(Color.rgb(140, 170, 150))
            textSize = 14f
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 56)
        }

        val playButton = Button(this).apply {
            text = "PLAY"
            textSize = 20f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.BLACK)
            setBackgroundColor(accent)
            setPadding(72, 28, 72, 28)
            setOnClickListener {
                startActivity(Intent(this@MainMenuActivity, MainActivity::class.java))
            }
        }

        val controls = TextView(this).apply {
            text = "left side of screen: move\nright side / drag: look"
            setTextColor(Color.rgb(110, 130, 120))
            textSize = 13f
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER
            setPadding(0, 48, 0, 0)
        }

        root.addView(title)
        root.addView(subtitle)
        root.addView(playButton)
        root.addView(controls)
        setContentView(root)
    }
}
