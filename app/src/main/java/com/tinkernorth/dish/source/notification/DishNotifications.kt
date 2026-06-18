// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.source.notification

import android.content.Context
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.AbsoluteSizeSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.util.TypedValue
import android.view.View
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.snackbar.Snackbar
import com.tinkernorth.dish.R
import com.tinkernorth.dish.core.model.DishNotification
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DishNotifications
    @Inject
    constructor() {
        private val _posts =
            MutableSharedFlow<DishNotification>(
                replay = 0,
                extraBufferCapacity = BUFFER,
                onBufferOverflow = BufferOverflow.DROP_OLDEST,
            )
        val posts: SharedFlow<DishNotification> = _posts.asSharedFlow()

        private val _dismissals =
            MutableSharedFlow<Long>(
                replay = 0,
                extraBufferCapacity = BUFFER,
                onBufferOverflow = BufferOverflow.DROP_OLDEST,
            )
        val dismissals: SharedFlow<Long> = _dismissals.asSharedFlow()

        private val nextId = AtomicLong(1L)
        private val deferred = AtomicReference<DishNotification?>(null)

        fun post(
            severity: DishNotification.Severity = DishNotification.Severity.INFO,
            title: String,
            body: String? = null,
            @DrawableRes glyph: Int? = null,
            action: DishNotification.Action? = null,
            key: String? = null,
            durationMs: Long = defaultDurationFor(severity),
        ): Long {
            val id = nextId.getAndIncrement()
            val n =
                DishNotification(
                    id = id,
                    severity = severity,
                    title = title,
                    body = body,
                    glyph = glyph,
                    action = action,
                    key = key,
                    durationMs = durationMs,
                )
            _posts.tryEmit(n)
            return id
        }

        fun dismiss(id: Long) {
            _dismissals.tryEmit(id)
        }

        // Held for the NEXT screen to render, so an activity can post a result then finish() (a live post dies with its view).
        fun postDeferred(
            severity: DishNotification.Severity = DishNotification.Severity.INFO,
            title: String,
            body: String? = null,
            @DrawableRes glyph: Int? = null,
            durationMs: Long = defaultDurationFor(severity),
        ) {
            deferred.set(
                DishNotification(
                    id = nextId.getAndIncrement(),
                    severity = severity,
                    title = title,
                    body = body,
                    glyph = glyph,
                    action = null,
                    key = null,
                    durationMs = durationMs,
                ),
            )
        }

        fun info(
            title: String,
            body: String? = null,
            @DrawableRes glyph: Int? = null,
            action: DishNotification.Action? = null,
            key: String? = null,
            durationMs: Long = DishNotification.DURATION_SHORT,
        ): Long = post(DishNotification.Severity.INFO, title, body, glyph, action, key, durationMs)

        fun success(
            title: String,
            body: String? = null,
            @DrawableRes glyph: Int? = null,
            action: DishNotification.Action? = null,
            key: String? = null,
            durationMs: Long = DishNotification.DURATION_SHORT,
        ): Long = post(DishNotification.Severity.SUCCESS, title, body, glyph, action, key, durationMs)

        fun warn(
            title: String,
            body: String? = null,
            @DrawableRes glyph: Int? = null,
            action: DishNotification.Action? = null,
            key: String? = null,
            durationMs: Long = DishNotification.DURATION_LONG,
        ): Long = post(DishNotification.Severity.WARN, title, body, glyph, action, key, durationMs)

        fun error(
            title: String,
            body: String? = null,
            @DrawableRes glyph: Int? = null,
            action: DishNotification.Action? = null,
            key: String? = null,
            durationMs: Long = DishNotification.DURATION_LONG,
        ): Long = post(DishNotification.Severity.ERROR, title, body, glyph, action, key, durationMs)

        fun attach(
            owner: LifecycleOwner,
            rootView: View,
        ): Attachment = attachWithRenderer(owner, MaterialSnackbarRenderer(rootView), owner.lifecycleScope)

        fun attachWithRenderer(
            owner: LifecycleOwner,
            renderer: Renderer,
            scope: CoroutineScope = owner.lifecycleScope,
        ): Attachment {
            val attachment = Attachment(renderer)

            scope.launch {
                owner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                    deferred.getAndSet(null)?.let { attachment.handlePost(it) }
                    launch {
                        posts.onEach { n -> attachment.handlePost(n) }.launchIn(this)
                    }
                    launch {
                        dismissals.onEach { id -> attachment.handleDismiss(id) }.launchIn(this)
                    }
                }
            }

            owner.lifecycle.addObserver(
                object : DefaultLifecycleObserver {
                    override fun onDestroy(o: LifecycleOwner) {
                        attachment.dismissAll()
                    }
                },
            )

            return attachment
        }

        private fun defaultDurationFor(severity: DishNotification.Severity): Long =
            when (severity) {
                DishNotification.Severity.INFO,
                DishNotification.Severity.SUCCESS,
                -> DishNotification.DURATION_SHORT
                DishNotification.Severity.WARN,
                DishNotification.Severity.ERROR,
                -> DishNotification.DURATION_LONG
            }

        interface Renderer {
            fun show(
                notification: DishNotification,
                anchor: View?,
            ): Handle

            interface Handle {
                fun dismiss()
            }
        }

        class Attachment internal constructor(
            private val renderer: Renderer,
        ) {
            @Volatile var anchorView: View? = null

            // byKey is a side index into byId; every mutation touches both under lock to stay coherent.
            private val byId = mutableMapOf<Long, Renderer.Handle>()
            private val byKey = mutableMapOf<String, Long>()
            private val lock = Any()

            internal fun handlePost(notification: DishNotification) {
                val priorHandle: Renderer.Handle? =
                    synchronized(lock) {
                        val key = notification.key
                        if (key != null) {
                            val priorId = byKey.remove(key)
                            if (priorId != null) byId.remove(priorId) else null
                        } else {
                            null
                        }
                    }
                priorHandle?.dismiss()

                val handle = renderer.show(notification, anchorView)

                synchronized(lock) {
                    byId[notification.id] = handle
                    notification.key?.let { byKey[it] = notification.id }
                }
            }

            internal fun handleDismiss(id: Long) {
                val handle: Renderer.Handle? =
                    synchronized(lock) {
                        val h = byId.remove(id)
                        if (h != null) {
                            byKey.entries.removeAll { it.value == id }
                        }
                        h
                    }
                handle?.dismiss()
            }

            internal fun dismissAll() {
                val toDismiss: List<Renderer.Handle> =
                    synchronized(lock) {
                        val snapshot = byId.values.toList()
                        byId.clear()
                        byKey.clear()
                        snapshot
                    }
                toDismiss.forEach { it.dismiss() }
            }

            internal fun liveById(): Int = synchronized(lock) { byId.size }

            internal fun liveByKey(): Int = synchronized(lock) { byKey.size }
        }

        private companion object {
            const val BUFFER = 16
        }
    }

internal fun dishSnackbar(
    rootView: View,
    severity: DishNotification.Severity,
    title: String,
    body: String?,
    durationMs: Long,
): Snackbar =
    Snackbar
        .make(rootView, buildStyledText(rootView.context, title, body), durationForMs(durationMs))
        .applyDishTheme(severity)

internal class MaterialSnackbarRenderer(
    private val rootView: View,
) : DishNotifications.Renderer {
    override fun show(
        notification: DishNotification,
        anchor: View?,
    ): DishNotifications.Renderer.Handle {
        val snackbar =
            dishSnackbar(
                rootView,
                notification.severity,
                notification.title,
                notification.body,
                notification.durationMs,
            )
        anchor?.let { snackbar.anchorView = it }
        notification.action?.let { action ->
            snackbar.setAction(action.label) { action.handler() }
        }
        snackbar.show()
        return SnackbarHandle(snackbar)
    }

    private class SnackbarHandle(
        private val snackbar: Snackbar,
    ) : DishNotifications.Renderer.Handle {
        override fun dismiss() {
            snackbar.dismiss()
        }
    }
}

private fun durationForMs(durationMs: Long): Int =
    when {
        durationMs == DishNotification.DURATION_PERSISTENT -> Snackbar.LENGTH_INDEFINITE
        durationMs >= DishNotification.DURATION_LONG -> Snackbar.LENGTH_LONG
        else -> Snackbar.LENGTH_SHORT
    }

private fun buildStyledText(
    ctx: Context,
    title: String,
    body: String?,
): CharSequence {
    val builder = SpannableStringBuilder()
    val titleStart = builder.length
    builder.append(title)
    builder.setSpan(
        StyleSpan(Typeface.BOLD),
        titleStart,
        builder.length,
        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
    )
    if (!body.isNullOrBlank()) {
        builder.append("\n")
        val bodyStart = builder.length
        builder.append(body)
        builder.setSpan(
            ForegroundColorSpan(ctx.getColor(R.color.colorMuted)),
            bodyStart,
            builder.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
        builder.setSpan(
            AbsoluteSizeSpan(ctx.resources.getDimensionPixelSize(R.dimen.notification_text_body)),
            bodyStart,
            builder.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
        builder.setSpan(
            TypefaceSpan("monospace"),
            bodyStart,
            builder.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
    }
    return builder
}

private fun Snackbar.applyDishTheme(severity: DishNotification.Severity): Snackbar {
    val ctx = view.context
    val res = ctx.resources
    view.setBackgroundResource(backgroundForSeverity(severity))
    // Clear M3 colorInverseSurface tint so the dark notification_bg_<severity> drawable renders as-is.
    view.backgroundTintList = null
    view.elevation = res.getDimension(R.dimen.notification_elevation)
    val horizontalPad = res.getDimensionPixelSize(R.dimen.notification_padding_horizontal)
    val verticalPad = res.getDimensionPixelSize(R.dimen.notification_padding_vertical)
    view.setPadding(horizontalPad, verticalPad, horizontalPad, verticalPad)
    setTextColor(ctx.getColor(R.color.colorOnSurface))
    view.findViewById<TextView>(com.google.android.material.R.id.snackbar_text)?.apply {
        maxLines = MAX_TEXT_LINES
        // COMPLEX_UNIT_PX with getDimension(sp) avoids the implicit re-scale of textSize=Sp.
        setTextSize(TypedValue.COMPLEX_UNIT_PX, res.getDimension(R.dimen.notification_text_title))
        typeface = Typeface.DEFAULT_BOLD
        setPadding(res.getDimensionPixelSize(R.dimen.notification_text_leading_indent), 0, 0, 0)
    }
    val actionColor =
        when (severity) {
            DishNotification.Severity.INFO,
            DishNotification.Severity.SUCCESS,
            -> R.color.colorPrimary
            DishNotification.Severity.WARN -> R.color.colorWarning
            DishNotification.Severity.ERROR -> R.color.colorError
        }
    setActionTextColor(ctx.getColor(actionColor))
    view.findViewById<TextView>(com.google.android.material.R.id.snackbar_action)?.apply {
        typeface = Typeface.DEFAULT_BOLD
        setTextSize(TypedValue.COMPLEX_UNIT_PX, res.getDimension(R.dimen.notification_text_action))
        letterSpacing = ACTION_LETTER_SPACING
    }
    return this
}

@androidx.annotation.DrawableRes
private fun backgroundForSeverity(severity: DishNotification.Severity): Int =
    when (severity) {
        DishNotification.Severity.INFO -> R.drawable.notification_bg_info
        DishNotification.Severity.SUCCESS -> R.drawable.notification_bg_success
        DishNotification.Severity.WARN -> R.drawable.notification_bg_warn
        DishNotification.Severity.ERROR -> R.drawable.notification_bg_error
    }

private const val ACTION_LETTER_SPACING = 0.04f

private const val MAX_TEXT_LINES = 3
