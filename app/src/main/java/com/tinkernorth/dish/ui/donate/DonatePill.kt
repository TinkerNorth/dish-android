// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.donate

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.tinkernorth.dish.R
import com.tinkernorth.dish.ui.common.animationsDisabled
import com.tinkernorth.dish.ui.common.slidePillIn
import com.tinkernorth.dish.ui.common.slidePillOut

private const val DONATE_PILL_PREFS = "user_preferences"
private const val DONATE_PILL_DISMISSED_AT = "donate_pill_dismissed_at"
private const val DONATE_PILL_DISMISS_WINDOW_MS = 24L * 60L * 60L * 1000L
private const val HEARTBEAT_SCALE = 1.12f

fun AppCompatActivity.attachDonatePill() {
    if (donatePillDismissed(this) || isSupporter()) return

    val docked = findViewById<View>(R.id.donatePill)
    val hide = if (docked != null) attachDockedDonatePill(docked) else attachFloatingDonatePill()
    if (hide != null) hideOnceSupporting(hide)
}

private fun AppCompatActivity.attachDockedDonatePill(docked: View): () -> Unit {
    docked.isVisible = true
    val hide = { slidePillOut(docked) { docked.isVisible = false } }
    wireDonatePill(docked, hide)
    return hide
}

private fun AppCompatActivity.attachFloatingDonatePill(): (() -> Unit)? {
    val content = findViewById<ViewGroup>(android.R.id.content) ?: return null
    val pill = layoutInflater.inflate(R.layout.view_donate_pill, content, false)
    val baseGap = resources.getDimensionPixelSize(R.dimen.spacing_5xl)
    placePillBottomEnd(pill, baseGap)

    content.addView(pill)
    val hide = { slidePillOut(pill) { content.removeView(pill) } }
    wireDonatePill(pill, hide)
    return hide
}

// The pill floats over the content, so it keeps its own gap clear of the system bars rather than
// relying on a parent that does not inset.
private fun placePillBottomEnd(
    pill: View,
    baseGap: Int,
) {
    (pill.layoutParams as? FrameLayout.LayoutParams)?.apply {
        gravity = Gravity.BOTTOM or Gravity.END
        marginEnd = baseGap
        bottomMargin = baseGap
    }
    ViewCompat.setOnApplyWindowInsetsListener(pill) { v, insets ->
        val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
        v.updateLayoutParams<FrameLayout.LayoutParams> {
            marginEnd = baseGap + bars.right
            bottomMargin = baseGap + bars.bottom
        }
        insets
    }
}

private fun AppCompatActivity.hideOnceSupporting(hide: () -> Unit) {
    lifecycle.addObserver(
        object : DefaultLifecycleObserver {
            override fun onResume(owner: LifecycleOwner) {
                if (!isSupporter()) return
                hide()
                owner.lifecycle.removeObserver(this)
            }
        },
    )
}

private fun AppCompatActivity.wireDonatePill(
    pill: View,
    onDismiss: () -> Unit,
) {
    pill.setOnClickListener { openDonateScreen() }
    pill.findViewById<View>(R.id.donatePillDismiss).setOnClickListener {
        dismissDonatePill(this)
        onDismiss()
    }
    slidePillIn(pill)
    startHeartbeat(pill.findViewById(R.id.donatePillHeart))
}

private fun donatePillDismissed(context: Context): Boolean {
    val dismissedAt =
        context
            .getSharedPreferences(DONATE_PILL_PREFS, Context.MODE_PRIVATE)
            .getLong(DONATE_PILL_DISMISSED_AT, 0L)
    return System.currentTimeMillis() - dismissedAt < DONATE_PILL_DISMISS_WINDOW_MS
}

private fun dismissDonatePill(context: Context) {
    context
        .getSharedPreferences(DONATE_PILL_PREFS, Context.MODE_PRIVATE)
        .edit { putLong(DONATE_PILL_DISMISSED_AT, System.currentTimeMillis()) }
}

// An infinite animator on a detached view keeps a frame callback alive for nothing, so the
// animation follows the view on and off screen.
private class RunWhileAttached(
    private val animator: ObjectAnimator,
) : View.OnAttachStateChangeListener {
    override fun onViewAttachedToWindow(v: View) = animator.start()

    override fun onViewDetachedFromWindow(v: View) = animator.cancel()
}

private fun AppCompatActivity.heartbeatAnimator(heart: View): ObjectAnimator =
    ObjectAnimator
        .ofPropertyValuesHolder(
            heart,
            PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, HEARTBEAT_SCALE),
            PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, HEARTBEAT_SCALE),
        ).apply {
            duration = resources.getInteger(R.integer.motion_duration_pulse).toLong()
            repeatCount = ObjectAnimator.INFINITE
            repeatMode = ObjectAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
        }

private fun AppCompatActivity.startHeartbeat(heart: View) {
    if (animationsDisabled()) return
    val animator = heartbeatAnimator(heart)
    heart.addOnAttachStateChangeListener(RunWhileAttached(animator))
    // A view already on screen never fires the attach callback, so it is started here.
    if (heart.isAttachedToWindow) animator.start()
}
