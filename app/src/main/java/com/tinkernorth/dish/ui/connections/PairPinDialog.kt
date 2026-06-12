// SPDX-License-Identifier: LGPL-3.0-or-later

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

// No auto-dismiss on submit: caller drives setBusy / showError around the in-flight pair call
// so a failure keeps the typed PIN and routes the message through TextInputLayout's setError
// (the M3 live-region announces it for TalkBack).
class PairPinDialog(
    context: Context,
    // The PIN this dish shows for Path-B (operator-accepts) pairing. "" hides
    // that whole section, leaving a plain enter-the-satellite's-PIN dialog.
    // That's why it and onRequestApproval default, keeping onSubmit the
    // trailing lambda for existing call sites.
    private val clientPin: String = "",
    private val onRequestApproval: () -> Unit = {},
    private val onSubmit: (pin: String) -> Unit,
) : Dialog(context, R.style.Theme_Dish_Dialog) {
    private lateinit var binding: DialogPairPinBinding

    // Callers set dishTitle/dishSubtitle before show(); binding inflates on first show().
    private var pendingTitle: CharSequence? = null
    private var pendingSubtitle: CharSequence? = null

    var dishTitle: CharSequence
        get() = if (::binding.isInitialized) binding.tvPairTitle.text else pendingTitle ?: ""
        set(value) {
            if (::binding.isInitialized) {
                binding.tvPairTitle.text = value
            } else {
                pendingTitle = value
            }
        }

    var dishSubtitle: CharSequence
        get() = if (::binding.isInitialized) binding.tvPairSubtitle.text else pendingSubtitle ?: ""
        set(value) {
            if (::binding.isInitialized) {
                binding.tvPairSubtitle.text = value
            } else {
                pendingSubtitle = value
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        binding = DialogPairPinBinding.inflate(LayoutInflater.from(context))
        setContentView(binding.root)
        pendingTitle?.let { binding.tvPairTitle.text = it }
        pendingSubtitle?.let { binding.tvPairSubtitle.text = it }
        window?.setBackgroundDrawableResource(android.R.color.transparent)
        window?.setLayout(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
        )

        binding.btnPairCancel.setOnClickListener { cancel() }
        binding.btnPairSubmit.setOnClickListener {
            val pin = binding.etPin.text.toString()
            if (pin.isEmpty()) {
                showError(context.getString(R.string.pair_dialog_error_empty))
                binding.etPin.requestFocus()
                return@setOnClickListener
            }
            showError(null)
            onSubmit(pin)
        }

        // Path B: show this dish's PIN for the operator to accept on the
        // satellite. Hidden entirely when no client PIN was supplied.
        binding.tvClientPin.text = clientPin
        binding.clientPinSection.visibility =
            if (clientPin.isNotEmpty()) View.VISIBLE else View.GONE
        binding.btnPairApprove.setOnClickListener { onRequestApproval() }
    }

    fun setBusy(busy: Boolean) {
        binding.btnPairSubmit.setLoading(
            loading = busy,
            loadingText = context.getString(R.string.pair_dialog_pairing),
            restingText = context.getString(R.string.pair_dialog_submit),
        )
        binding.btnPairCancel.isEnabled = !busy
        binding.etPin.isEnabled = !busy
        setCanceledOnTouchOutside(!busy)
        setCancelable(!busy)
    }

    // Path-B in-flight: the request is submitted and we're polling for the
    // operator's decision. Drives the approve button's spinner; keeps cancel
    // live so the user can back out of the wait.
    fun setAwaitingApproval(awaiting: Boolean) {
        binding.btnPairApprove.setLoading(
            loading = awaiting,
            loadingText = context.getString(R.string.pair_dialog_awaiting),
            restingText = context.getString(R.string.pair_dialog_request_approval),
        )
        // Deliberately leave the satellite-PIN field + Pair button enabled while
        // the request is in flight: it's sent automatically on open, but typing
        // the satellite's PIN stays a live fallback if the native prompt didn't show.
        setCanceledOnTouchOutside(!awaiting)
    }

    fun showError(message: CharSequence?) {
        binding.tilPin.error = message
    }
}
