// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.source.store

import com.tinkernorth.dish.composer.BundleCapture
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

// "Capture this controller", per slot, in memory for the run: the next diagnostics bundle carries
// each under its controller's capture.
@Singleton
class ControllerCaptureStore
    @Inject
    constructor() {
        private val bySlot = ConcurrentHashMap<String, BundleCapture>()

        fun keep(
            slotId: String,
            capture: BundleCapture,
        ) {
            bySlot[slotId] = capture
        }

        fun snapshot(): Map<String, BundleCapture> = HashMap(bySlot)
    }
