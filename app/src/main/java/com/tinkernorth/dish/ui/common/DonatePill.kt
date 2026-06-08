// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.common

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
import com.tinkernorth.dish.R

private const val DONATE_PILL_PREFS = "user_preferences"
private const val DONATE_PILL_DISMISSED_AT = "donate_pill_dismissed_at"
private const val DONATE_PILL_DISMISS_WINDOW_MS = 24L * 60L * 60L * 1000L
private const val HEARTBEAT_SCALE = 1.12f

fun AppCompatActivity.attachDonatePill() {
    if (donatePillDismissed(this)) return

    val docked = findViewById<View>(R.id.donatePill)
    if (docked != null) {
        docked.isVisible = true
        wireDonatePill(docked) { hideDockedPill(docked) }
    } else {
        attachFloatingDonatePill()
    }
}

private fun AppCompatActivity.attachFloatingDonatePill() {
    val content = findViewById<ViewGroup>(android.R.id.content) ?: return
    val pill = layoutInflater.inflate(R.layout.view_donate_pill, content, false)
    val baseGap = resources.getDimensionPixelSize(R.dimen.spacing_5xl)

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

    content.addView(pill)
    wireDonatePill(pill) { dismissPill(pill, content) }
}

private fun AppCompatActivity.wireDonatePill(
    pill: View,
    onDismiss: () -> Unit,
) {
    pill.setOnClickListener { DishNavigator(this).toDonate() }
    pill.findViewById<View>(R.id.donatePillDismiss).setOnClickListener {
        dismissDonatePill(this)
        onDismiss()
    }
    animatePillIn(pill)
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

private fun AppCompatActivity.animatePillIn(pill: View) {
    if (animationsDisabled()) return
    pill.alpha = 0f
    pill.translationY = resources.getDimensionPixelSize(R.dimen.spacing_6xl).toFloat()
    pill
        .animate()
        .alpha(1f)
        .translationY(0f)
        .setDuration(resources.getInteger(R.integer.motion_duration_medium).toLong())
        .start()
}

private fun dismissPill(
    pill: View,
    content: ViewGroup,
) {
    pill
        .animate()
        .alpha(0f)
        .translationY(pill.height.toFloat())
        .setDuration(pill.resources.getInteger(R.integer.motion_duration_medium).toLong())
        .withEndAction { content.removeView(pill) }
        .start()
}

private fun hideDockedPill(pill: View) {
    pill
        .animate()
        .alpha(0f)
        .translationY(pill.height.toFloat())
        .setDuration(pill.resources.getInteger(R.integer.motion_duration_medium).toLong())
        .withEndAction { pill.isVisible = false }
        .start()
}

private fun AppCompatActivity.startHeartbeat(heart: View) {
    if (animationsDisabled()) return
    val animator =
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
    heart.addOnAttachStateChangeListener(
        object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                animator.start()
            }

            override fun onViewDetachedFromWindow(v: View) {
                animator.cancel()
            }
        },
    )
    if (heart.isAttachedToWindow) animator.start()
}
