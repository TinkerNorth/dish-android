// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.util

import android.content.Context
import android.os.PowerManager
import android.view.Window
import android.view.WindowManager
import android.widget.TextView
import com.tinkernorth.dish.R

/**
 * Manages FLAG_KEEP_SCREEN_ON and a partial wake lock.
 *
 * Call [update] whenever the "should we hold locks?" condition changes
 * (e.g. server connected + has active controllers). The manager
 * acquires or releases locks as needed and notifies [onLockStateChanged].
 */
class WakeLockManager(
    private val context: Context,
    private val window: Window,
) {
    /** Views to write lock indicators into. */
    data class Views(
        val tvScreenLock: TextView,
        val tvWakeLock: TextView,
    )

    var views: Views? = null

    /** Called whenever the combined lock state changes (both active or not). */
    var onLockStateChanged: ((active: Boolean) -> Unit)? = null

    var screenLockActive = false
        private set
    var wakeLockActive = false
        private set
    private var wakeLock: PowerManager.WakeLock? = null

    val isActive get() = screenLockActive && wakeLockActive

    /**
     * Evaluate whether locks should be held. Acquires or releases as needed.
     */
    fun update(shouldLock: Boolean) {
        if (shouldLock) acquire() else release()
    }

    fun release() {
        val wasActive = isActive
        if (screenLockActive) {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            screenLockActive = false
        }
        wakeLock?.let {
            if (it.isHeld) it.release()
            wakeLock = null
            wakeLockActive = false
        }
        updateIndicators()
        if (wasActive) onLockStateChanged?.invoke(false)
    }

    private fun acquire() {
        val wasActive = isActive
        if (!screenLockActive) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            screenLockActive = true
        }
        if (wakeLock == null) {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock =
                pm
                    .newWakeLock(
                        PowerManager.PARTIAL_WAKE_LOCK,
                        "Dish::ControllerStream",
                    ).apply { acquire() }
            wakeLockActive = true
        }
        updateIndicators()
        if (!wasActive && isActive) onLockStateChanged?.invoke(true)
    }

    private fun updateIndicators() {
        val v = views ?: return
        v.tvScreenLock.text = if (screenLockActive) "ON" else "OFF"
        v.tvScreenLock.setTextColor(
            context.getColor(if (screenLockActive) R.color.colorSuccess else R.color.colorMuted),
        )
        v.tvWakeLock.text = if (wakeLockActive) "ON" else "OFF"
        v.tvWakeLock.setTextColor(
            context.getColor(if (wakeLockActive) R.color.colorSuccess else R.color.colorMuted),
        )
    }
}
