// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.ui.main

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.tinkernorth.dish.R

/**
 * Fatal-but-actionable screen shown when the satellite native library fails
 * to load. Previously the app would crash on the first `System.loadLibrary`
 * call (most often an ABI mismatch on an unsupported architecture). This
 * screen replaces that crash with a themed message the user can read +
 * forward to support.
 *
 * No injected dependencies — Hilt may not have initialised. Pure XML, pure
 * resources. Caller (DishApplication) sets a flag and routes the first
 * activity launch here instead of MainActivity.
 */
class NativeUnavailableActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_native_unavailable)
    }
}
