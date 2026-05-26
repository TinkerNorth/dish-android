// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.common

import android.app.Activity
import android.graphics.Rect
import android.view.View
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface Posture {
    object Flat : Posture

    data class Book(
        val hingeBounds: Rect,
        val isHalfOpened: Boolean,
    ) : Posture

    data class Tabletop(
        val hingeBounds: Rect,
        val isHalfOpened: Boolean,
    ) : Posture
}

class FoldAwareSession(
    activity: Activity,
    owner: LifecycleOwner,
) {
    private val tracker = WindowInfoTracker.getOrCreate(activity)

    private val _posture = MutableStateFlow<Posture>(Posture.Flat)
    val posture: StateFlow<Posture> = _posture.asStateFlow()

    init {
        owner.lifecycleScope.launch {
            owner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                tracker.windowLayoutInfo(activity).collect { info ->
                    val fold =
                        info.displayFeatures
                            .filterIsInstance<FoldingFeature>()
                            .firstOrNull()
                    _posture.value = fold?.toPosture() ?: Posture.Flat
                }
            }
        }
    }
}

private fun FoldingFeature.toPosture(): Posture {
    val halfOpened = state == FoldingFeature.State.HALF_OPENED
    return when (orientation) {
        FoldingFeature.Orientation.HORIZONTAL -> Posture.Tabletop(bounds, halfOpened)
        FoldingFeature.Orientation.VERTICAL -> Posture.Book(bounds, halfOpened)
        else -> Posture.Flat
    }
}

data class HingeInsets(
    val left: Int = 0,
    val top: Int = 0,
    val right: Int = 0,
    val bottom: Int = 0,
) {
    companion object {
        val NONE = HingeInsets()
    }
}

fun Posture.hingeInsetsFor(view: View): HingeInsets {
    if (this !is Posture.Tabletop) return HingeInsets.NONE
    val loc = IntArray(2)
    view.getLocationInWindow(loc)
    val viewTop = loc[1]
    val hingeBottomInView = hingeBounds.bottom - viewTop
    if (hingeBottomInView <= 0) return HingeInsets.NONE
    return HingeInsets(top = hingeBottomInView)
}
