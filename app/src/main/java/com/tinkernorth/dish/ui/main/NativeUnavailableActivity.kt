// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.ui.main

import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.tinkernorth.dish.R

/**
 * Fatal-but-actionable screen shown when the satellite native library fails
 * to load. Previously the app would crash on the first `System.loadLibrary`
 * call (most often an ABI mismatch on an unsupported architecture). This
 * screen replaces that crash with a themed message the user can read +
 * forward to support.
 *
 * No injected dependencies — Hilt may not have initialised. Pure View, pure
 * resources. Caller (DishApplication) sets a flag and routes the first
 * activity launch here instead of MainActivity.
 */
class NativeUnavailableActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                val pad = (24 * resources.displayMetrics.density).toInt()
                setPadding(pad, pad, pad, pad)
                setBackgroundColor(getColor(R.color.colorBackground))
            }
        root.addView(
            TextView(this).apply {
                text = "DISH"
                setTextColor(getColor(R.color.colorPrimary))
                textSize = 24f
                typeface = Typeface.DEFAULT_BOLD
                letterSpacing = 0.12f
            },
        )
        root.addView(
            TextView(this).apply {
                text = getString(R.string.fatal_native_title)
                setTextColor(getColor(R.color.colorOnSurface))
                textSize = 16f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setPadding(0, (20 * resources.displayMetrics.density).toInt(), 0, 0)
            },
        )
        root.addView(
            TextView(this).apply {
                text = getString(R.string.fatal_native_body)
                setTextColor(getColor(R.color.colorMuted))
                textSize = 13f
                gravity = Gravity.CENTER
                setPadding(0, (10 * resources.displayMetrics.density).toInt(), 0, 0)
            },
        )
        setContentView(root)
    }
}
