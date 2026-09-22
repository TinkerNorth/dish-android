// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.hotpath.input

import android.os.Vibrator
import android.view.InputDevice

/**
 * A framework pad's own vibrator on API 24 to 30.
 *
 * Marker: InputDevice.getVibrator is deprecated from 31, where getVibratorManager replaces it
 * (every caller takes that branch on 31+). Below 31 it is the only vibrator a framework pad
 * exposes, and no AndroidX compat wraps it. The right fix is a minSdk of 31, which would drop
 * Android 7 to 11 devices; until then the deprecated call lives here, once, so the two
 * callers (the registry's rumble probe and the router's actuate/cancel) share it.
 */
@Suppress("DEPRECATION")
internal fun InputDevice.legacyVibrator(): Vibrator? = vibrator
