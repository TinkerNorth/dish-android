// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.source.lowpower

import com.tinkernorth.dish.architecture.abstracts.AbstractStateSource
import javax.inject.Inject
import javax.inject.Singleton

// Process-wide mirror of the foreground overlay's LowPowerManager ACTIVE state. LowPowerManager is
// per-window (it owns that window's brightness), so singletons that must pause while the screen is
// dimmed observe this instead.
@Singleton
class LowPowerSignal
    @Inject
    constructor() : AbstractStateSource<Boolean>(false) {
        fun setActive(active: Boolean) {
            setState(active)
        }
    }
