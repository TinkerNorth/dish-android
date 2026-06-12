// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.main

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.tinkernorth.dish.R

// No injected dependencies: Hilt may not have initialised when native load fails.
class NativeUnavailableActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_native_unavailable)
    }
}
