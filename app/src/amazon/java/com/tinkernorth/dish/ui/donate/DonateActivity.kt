// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.ui.donate

import android.os.Bundle
import com.tinkernorth.dish.ui.common.BaseGamepadHostActivity
import dagger.hilt.android.AndroidEntryPoint

// The manifest and the nav graph in src/main name this screen, and NavInflater resolves every
// destination class.
@AndroidEntryPoint
class DonateActivity : BaseGamepadHostActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        finish()
    }
}
