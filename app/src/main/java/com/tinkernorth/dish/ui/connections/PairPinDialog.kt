// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.ui.connections

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.Window
import android.widget.FrameLayout
import com.tinkernorth.dish.R
import com.tinkernorth.dish.databinding.DialogPairPinBinding
import com.tinkernorth.dish.ui.common.setLoading

/**
 * Custom PIN dialog used by [ConnectionsActivity]. Replaces the
 * `MaterialAlertDialogBuilder` flow so:
 *
 *  - The dialog **does not auto-dismiss on submit**. The Pair button flips
 *    to an in-button [com.tinkernorth.dish.ui.common.DishSpinnerDrawable]
 *    while the round-trip is in flight; success closes the dialog, failure
 *    surfaces an inline error under the field so the user keeps their
 *    typed PIN.
 *  - The surface uses `bg_pill` — same idiom every other floating banner
 *    in the app uses — and the labels follow the v6 chrome conventions
 *    (mono / letter-spaced uppercase header, bold title, muted subtitle).
 *  - Buttons use the shared [com.tinkernorth.dish.ui.common] styling, so
 *    the disabled-during-submit treatment matches the satellites list.
 *
 * Lifecycle: the caller owns the dialog and dismisses it on cancel or
 * success. The handler ([onSubmit]) is invoked with the PIN string; the
 * caller calls [setBusy] / [showError] to drive the in-flight state from
 * outside. This keeps the dialog free of any session/network knowledge.
 */
class PairPinDialog(
    context: Context,
    private val onSubmit: (pin: String) -> Unit,
) : Dialog(context, R.style.Theme_Dish_Dialog) {
    private lateinit var binding: DialogPairPinBinding

    /** Title displayed at the top — defaults to "Pair with satellite". */
    var dishTitle: CharSequence
        get() = binding.tvPairTitle.text
        set(value) {
            binding.tvPairTitle.text = value
        }

    /** Subtitle under the title — defaults to "Enter the PIN shown on your satellite." */
    var dishSubtitle: CharSequence
        get() = binding.tvPairSubtitle.text
        set(value) {
            binding.tvPairSubtitle.text = value
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        binding = DialogPairPinBinding.inflate(LayoutInflater.from(context))
        setContentView(binding.root)
        // Dim and centred — the dialog sits over a CardView-styled surface so
        // a transparent window keeps the bg_pill border crisp.
        window?.setBackgroundDrawableResource(android.R.color.transparent)
        window?.setLayout(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
        )

        binding.btnPairCancel.setOnClickListener { cancel() }
        binding.btnPairSubmit.setOnClickListener {
            val pin =
                binding.etPin.text
                    .toString()
                    .ifEmpty { "0000" }
            // Clear the inline error if the user is resubmitting after a bad PIN.
            showError(null)
            onSubmit(pin)
        }
    }

    /**
     * Flip the dialog between resting and in-flight. While busy the Pair
     * button shows an in-button spinner with the "PAIRING…" label and the
     * field is disabled to prevent edits mid-handshake.
     */
    fun setBusy(busy: Boolean) {
        binding.btnPairSubmit.setLoading(
            loading = busy,
            loadingText = context.getString(R.string.pair_dialog_pairing),
            restingText = context.getString(R.string.pair_dialog_submit),
        )
        binding.btnPairCancel.isEnabled = !busy
        binding.etPin.isEnabled = !busy
    }

    /**
     * Show or clear the inline error under the PIN field. Passing null
     * hides the error row entirely.
     */
    fun showError(message: CharSequence?) {
        if (message.isNullOrBlank()) {
            binding.tvPairError.visibility = View.GONE
            return
        }
        binding.tvPairError.text = message
        binding.tvPairError.visibility = View.VISIBLE
    }
}
