// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.ui.common

import android.animation.LayoutTransition
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import com.tinkernorth.dish.R
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Top-of-screen host for [DishNotification]s. The single replacement for
 * [android.widget.Toast] across the app.
 *
 * Visual contract:
 *  - Banners stack vertically, top-aligned, with `bg_pill` surface and a
 *    severity-keyed left stroke (a 3dp accent rail rather than the row's
 *    natural outline, so error / warning read at a glance).
 *  - Each banner shows: brand glyph (28dp) | title (bold) + optional
 *    monospace body | optional action button (outlined, theme button) | close X.
 *  - Slide-in / slide-out on add and remove via [LayoutTransition].
 *  - Horizontal swipe dismisses (translation tracks finger, releases past
 *    half-width).
 *  - Auto-dismiss fires from a `Handler` after `durationMs`; persistent
 *    banners stay until programmatic or user dismissal.
 *
 * Wiring: drop a `<include layout="@layout/overlay_notifications" />` into an
 * activity's root FrameLayout (after the low-power overlay so it draws on top
 * of nothing the user cares to interact with). Call [bindLifecycle] from the
 * activity's `onCreate` with the lifecycle owner + the queue.
 *
 * The host is a passive renderer — it does not own the bus. The activity
 * collects from [DishNotificationQueue] and forwards into [show] / [dismiss].
 * That keeps the host free of Hilt couplings and lets per-activity behaviours
 * (e.g. snoozing the queue while the PIN dialog is open) layer on top.
 */
class DishNotificationHost
    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
        defStyle: Int = 0,
    ) : FrameLayout(context, attrs, defStyle) {
        private val container: LinearLayout
        private val handler = Handler(Looper.getMainLooper())
        private val visible = LinkedHashMap<Long, BannerView>()
        private val keyToId = HashMap<String, Long>()
        private var collectJob: Job? = null
        private val baseBottomMarginPx = dp(16f)

        init {
            container =
                LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    // Slide-in / fade-out for adds and removes. The default
                    // LayoutTransition animates appearing/disappearing children,
                    // which is exactly the banner stack semantic.
                    layoutTransition =
                        LayoutTransition().apply {
                            enableTransitionType(LayoutTransition.CHANGING)
                            setDuration(220L)
                        }
                    val pad = dp(8f)
                    setPadding(pad, 0, pad, dp(8f))
                }
            // Bottom-anchored so banners sit in the same general region as
            // the low-power countdown pill and any other floating chrome —
            // the user's mental model is "pills live at the bottom edge".
            // Baseline marginBottom = 16dp puts banners just above the
            // bottom edge when no countdown pill is showing. When the
            // countdown engages, [bindLifecycle]'s bottomInsetFlow shifts the
            // host up so the two stack cleanly without overlap.
            addView(
                container,
                LayoutParams(
                    LayoutParams.MATCH_PARENT,
                    LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM,
                ).apply {
                    bottomMargin = baseBottomMarginPx
                },
            )
            // The host is decorative — touches that don't hit a banner row
            // should pass through to the underlying activity content.
            clipChildren = false
            clipToPadding = false
        }

        /**
         * Subscribe the host to the queue's emit + dismissal streams, gated on
         * the owner's STARTED state so the banners only flow into the host
         * while the activity is foreground. Idempotent: re-binding cancels the
         * prior collector.
         *
         * [bottomInsetFlow] (optional) supplies an extra bottom margin (px)
         * so the banner stack can dodge other floating chrome — the
         * LowPowerManager countdown pill in practice. When the flow emits 0
         * the host snaps back to its baseline 16dp margin; non-zero values
         * are added on top so the two stack with a small gap rather than
         * overlapping.
         */
        fun bindLifecycle(
            owner: LifecycleOwner,
            queue: DishNotificationQueue,
            bottomInsetFlow: kotlinx.coroutines.flow.Flow<Int>? = null,
        ) {
            collectJob?.cancel()
            collectJob =
                owner.lifecycleScope.launch {
                    owner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                        launch {
                            queue.posts
                                .onEach(::show)
                                .launchIn(this)
                        }
                        launch {
                            queue.dismissals
                                .onEach(::dismiss)
                                .launchIn(this)
                        }
                        if (bottomInsetFlow != null) {
                            launch {
                                bottomInsetFlow
                                    .onEach(::setExtraBottomInset)
                                    .launchIn(this)
                            }
                        }
                    }
                }
        }

        private fun setExtraBottomInset(extraPx: Int) {
            val lp = container.layoutParams as? LayoutParams ?: return
            val target = baseBottomMarginPx + extraPx
            if (lp.bottomMargin == target) return
            lp.bottomMargin = target
            container.layoutParams = lp
        }

        /** Render or replace a banner. Same-key posts replace any earlier entry. */
        fun show(notification: DishNotification) {
            // De-dup by key first so a same-key replacement re-uses the slot
            // (no jarring slide-out → slide-in for state-driven updates).
            val key = notification.key
            val priorId = if (key != null) keyToId[key] else null
            if (priorId != null) {
                dismissInternal(priorId, animate = false)
            }
            val banner = BannerView(context, notification, ::dismiss)
            visible[notification.id] = banner
            if (key != null) keyToId[key] = notification.id
            container.addView(banner)
            if (notification.durationMs > 0L) {
                handler.postDelayed({ dismiss(notification.id) }, notification.durationMs)
            }
        }

        fun dismiss(id: Long) {
            dismissInternal(id, animate = true)
        }

        private fun dismissInternal(
            id: Long,
            animate: Boolean,
        ) {
            val banner = visible.remove(id) ?: return
            // Clear from the key index too so the next post can replace it
            // cleanly without inheriting a stale id.
            val keyForId = banner.notification.key
            if (keyForId != null && keyToId[keyForId] == id) keyToId.remove(keyForId)
            handler.removeCallbacksAndMessages(banner)
            if (animate) {
                // Dismiss slides DOWN — banners enter from the bottom and exit
                // the same way, so the motion vocabulary stays consistent with
                // the bottom-anchored stack.
                banner
                    .animate()
                    .alpha(0f)
                    .translationY(banner.height.toFloat())
                    .setDuration(180L)
                    .withEndAction { container.removeView(banner) }
                    .start()
            } else {
                container.removeView(banner)
            }
        }

        private fun dp(v: Float): Int = (v * resources.displayMetrics.density).toInt()

        // ─── One banner row ────────────────────────────────────────────────

        private inner class BannerView(
            context: Context,
            val notification: DishNotification,
            private val onDismiss: (Long) -> Unit,
        ) : LinearLayout(context) {
            private var dragStartX = 0f
            private var dragging = false

            // GestureDetectorCompat ctor needs `swipeListener` so this
            // initializer must run AFTER `swipeListener` below. Keep both as
            // property initializers (not in init) so source order is the
            // initialization order — moving this into the init block would
            // re-introduce the forward-reference compile error.
            private val swipeListener =
                object : GestureDetector.SimpleOnGestureListener() {
                    override fun onFling(
                        e1: MotionEvent?,
                        e2: MotionEvent,
                        velocityX: Float,
                        velocityY: Float,
                    ): Boolean {
                        if (abs(velocityX) > 1200f) {
                            onDismiss(notification.id)
                            return true
                        }
                        return false
                    }
                }

            private val swipeDetector = GestureDetector(context, swipeListener)

            init {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = buildBackground()
                val padH = dp(14f)
                val padV = dp(10f)
                setPadding(padH, padV, padH, padV)
                val lp =
                    LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                        bottomMargin = dp(8f)
                    }
                layoutParams = lp
                addLeftAccentRail(notification.severity)
                addGlyph(notification)
                addTextColumn(notification)
                notification.action?.let { addActionButton(it) }
                addCloseButton()
            }

            private fun addLeftAccentRail(severity: DishNotification.Severity) {
                val rail =
                    View(context).apply {
                        layoutParams =
                            LayoutParams(dp(3f), LayoutParams.MATCH_PARENT).apply {
                                marginEnd = dp(10f)
                            }
                        background =
                            GradientDrawable().apply {
                                cornerRadius = dp(2f).toFloat()
                                setColor(context.getColor(severityAccent(severity)))
                            }
                    }
                addView(rail)
            }

            private fun addGlyph(notification: DishNotification) {
                val glyphRes = notification.glyph ?: defaultGlyphFor(notification.severity)
                val iv =
                    ImageView(context).apply {
                        setImageResource(glyphRes)
                        layoutParams =
                            LayoutParams(dp(24f), dp(24f)).apply { marginEnd = dp(10f) }
                        // Brand cyan, not the severity color. The v6 palette
                        // doesn't carry gold/amber as a brand tone, so a WARN
                        // banner with a gold icon read as out-of-theme. The
                        // accent rail at the left edge is the severity signal;
                        // the icon stays on-brand and visually consistent
                        // across all four severities.
                        imageTintList =
                            android.content.res.ColorStateList.valueOf(
                                context.getColor(R.color.colorPrimary),
                            )
                        contentDescription = null
                    }
                addView(iv)
            }

            private fun addTextColumn(notification: DishNotification) {
                val column =
                    LinearLayout(context).apply {
                        orientation = VERTICAL
                        layoutParams =
                            LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
                    }
                column.addView(
                    TextView(context).apply {
                        text = notification.title
                        setTextColor(context.getColor(R.color.colorOnSurface))
                        textSize = 13f
                        typeface = Typeface.DEFAULT_BOLD
                    },
                )
                val body = notification.body
                if (!body.isNullOrBlank()) {
                    column.addView(
                        TextView(context).apply {
                            text = body
                            setTextColor(context.getColor(R.color.colorMuted))
                            textSize = 11f
                            typeface = Typeface.MONOSPACE
                            layoutParams =
                                LayoutParams(
                                    LayoutParams.WRAP_CONTENT,
                                    LayoutParams.WRAP_CONTENT,
                                ).apply { topMargin = dp(2f) }
                        },
                    )
                }
                addView(column)
            }

            private fun addActionButton(action: DishNotification.Action) {
                val btn =
                    MaterialButton(
                        themedFor(context, R.style.Widget_Dish_Button_Outlined),
                        null,
                        com.google.android.material.R.attr.materialButtonOutlinedStyle,
                    ).apply {
                        text = action.label
                        textSize = 11f
                        isAllCaps = true
                        letterSpacing = 0.04f
                        minWidth = dp(64f)
                        minimumWidth = dp(64f)
                        cornerRadius = dp(6f)
                        val mh = dp(10f)
                        val mv = dp(4f)
                        setPadding(mh, mv, mh, mv)
                        layoutParams =
                            LayoutParams(
                                LayoutParams.WRAP_CONTENT,
                                LayoutParams.WRAP_CONTENT,
                            ).apply { marginStart = dp(8f) }
                        setOnClickListener {
                            action.handler.invoke()
                            onDismiss(notification.id)
                        }
                    }
                addView(btn)
            }

            private fun addCloseButton() {
                val close =
                    ImageView(context).apply {
                        setImageResource(R.drawable.ic_close)
                        layoutParams =
                            LayoutParams(dp(20f), dp(20f)).apply {
                                marginStart = dp(8f)
                            }
                        imageTintList =
                            android.content.res.ColorStateList.valueOf(
                                context.getColor(R.color.colorMuted),
                            )
                        contentDescription = context.getString(R.string.notification_dismiss)
                        // Larger touch target than the icon's visible bounds:
                        // pad the view so a fingertip-sized hit zone surrounds it.
                        val pad = dp(6f)
                        setPadding(pad, pad, pad, pad)
                        isClickable = true
                        isFocusable = true
                        setOnClickListener { onDismiss(notification.id) }
                    }
                addView(close)
            }

            override fun onTouchEvent(ev: MotionEvent): Boolean {
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        dragStartX = ev.rawX
                        dragging = false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = ev.rawX - dragStartX
                        if (!dragging && abs(dx) > dp(8f)) dragging = true
                        if (dragging) {
                            translationX = dx
                            alpha = 1f - (abs(dx) / width.toFloat()).coerceIn(0f, 1f)
                        }
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        if (dragging) {
                            val dx = ev.rawX - dragStartX
                            if (abs(dx) > width / 2f) {
                                onDismiss(notification.id)
                            } else {
                                animate()
                                    .translationX(0f)
                                    .alpha(1f)
                                    .setDuration(150L)
                                    .start()
                            }
                            dragging = true
                        }
                    }
                }
                swipeDetector.onTouchEvent(ev)
                return true
            }

            /**
             * Pill-style surface for the banner body. Severity-independent —
             * the severity is conveyed by the leading accent rail (see
             * [addLeftAccentRail]), not by the background color, which keeps
             * the v6 deep-space treatment consistent across all four cases.
             */
            private fun buildBackground(): GradientDrawable =
                GradientDrawable().apply {
                    setColor(context.getColor(R.color.colorSurface))
                    cornerRadius = dp(10f).toFloat()
                    setStroke(dp(1f), context.getColor(R.color.colorOutline))
                }

            private fun severityAccent(severity: DishNotification.Severity): Int =
                when (severity) {
                    DishNotification.Severity.INFO -> R.color.colorPrimary
                    DishNotification.Severity.SUCCESS -> R.color.colorSuccess
                    DishNotification.Severity.WARN -> R.color.colorWarning
                    DishNotification.Severity.ERROR -> R.color.colorError
                }

            private fun defaultGlyphFor(severity: DishNotification.Severity): Int =
                when (severity) {
                    DishNotification.Severity.INFO -> R.drawable.ic_notification_info
                    DishNotification.Severity.SUCCESS -> R.drawable.ic_notification_success
                    DishNotification.Severity.WARN -> R.drawable.ic_notification_warn
                    DishNotification.Severity.ERROR -> R.drawable.ic_notification_error
                }
        }

        /**
         * Wrap [base] in a [android.view.ContextThemeWrapper] so a
         * MaterialButton constructed against this context picks up
         * `Widget.Dish.Button.Outlined` styling without an XML inflate.
         */
        private fun themedFor(
            base: Context,
            styleRes: Int,
        ): Context = android.view.ContextThemeWrapper(base, styleRes)
    }
