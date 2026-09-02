// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.common

import android.content.res.ColorStateList
import android.view.View
import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.tinkernorth.dish.R
import com.tinkernorth.dish.composer.MicIndicatorCoordinator
import com.tinkernorth.dish.databinding.OverlayMicChipBinding
import com.tinkernorth.dish.source.audio.MicIndicatorState
import kotlinx.coroutines.launch

/**
 * How the app-wide mic chip renders one [MicIndicatorState], kept out of the controller so the
 * mapping can be pinned without views. LIVE reads as "the mic is hot": the error tone, because
 * that is the one colour this app reserves for things that demand attention, and an open
 * microphone is exactly that. MUTED keeps the chip visible but calm — the mic still exists and
 * the user must be able to find and undo the mute — with a slashed glyph so the two states
 * differ by shape as well as colour.
 */
internal data class MicChipPaint(
    val visible: Boolean,
    @param:DrawableRes val iconRes: Int,
    @param:ColorRes val tintRes: Int,
    @param:StringRes val labelRes: Int,
    @param:StringRes val descriptionRes: Int,
)

internal fun micChipPaintFor(state: MicIndicatorState): MicChipPaint =
    when (state) {
        MicIndicatorState.HIDDEN ->
            MicChipPaint(
                visible = false,
                iconRes = R.drawable.ic_mic_off,
                tintRes = R.color.colorMuted,
                labelRes = R.string.mic_state_muted,
                descriptionRes = R.string.mic_chip_muted_desc,
            )
        MicIndicatorState.LIVE ->
            MicChipPaint(
                visible = true,
                iconRes = R.drawable.ic_mic,
                tintRes = R.color.colorError,
                labelRes = R.string.mic_state_live,
                descriptionRes = R.string.mic_chip_live_desc,
            )
        MicIndicatorState.MUTED ->
            MicChipPaint(
                visible = true,
                iconRes = R.drawable.ic_mic_off,
                tintRes = R.color.colorMuted,
                labelRes = R.string.mic_state_muted,
                descriptionRes = R.string.mic_chip_muted_desc,
            )
    }

/**
 * Binds the floating mic chip (`overlay_mic_chip`) a screen's root layout carries and keeps it
 * painted from [MicIndicatorCoordinator]. Installed by `attachGamepadHost` so it exists exactly
 * once per screen, in the same shared scaffolding that binds the low-power chrome; a tap goes
 * back through the coordinator, so it means the same thing as the notification's action.
 */
internal class MicChipController(
    private val activity: AppCompatActivity,
    rootView: View,
    private val micIndicator: MicIndicatorCoordinator,
) {
    private val chip = OverlayMicChipBinding.bind(rootView)

    fun install() {
        chip.llMicChip.setOnClickListener { micIndicator.toggleAll() }
        activity.lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
                micIndicator.state.collect(::paint)
            }
        }
    }

    private fun paint(state: MicIndicatorState) {
        val paint = micChipPaintFor(state)
        chip.llMicChip.visibility = if (paint.visible) View.VISIBLE else View.GONE
        if (!paint.visible) return
        val tint = ContextCompat.getColor(activity, paint.tintRes)
        chip.ivMicChipIcon.setImageResource(paint.iconRes)
        chip.ivMicChipIcon.imageTintList = ColorStateList.valueOf(tint)
        chip.tvMicChipLabel.setText(paint.labelRes)
        // Label follows the icon's tone so the whole chip reads as one state at a glance.
        chip.tvMicChipLabel.setTextColor(tint)
        chip.llMicChip.contentDescription = activity.getString(paint.descriptionRes)
    }
}
