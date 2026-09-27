// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.ui.connections

import android.content.Context
import android.view.View
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.tinkernorth.dish.R
import com.tinkernorth.dish.core.model.DiscoveredServer
import com.tinkernorth.dish.core.model.DiscoverySource
import java.util.Locale

private const val DEFAULT_HTTPS_PORT = 9443
private const val DEFAULT_UDP_PORT = 9876

// The two dialogs that add a host by typing its address. Both keep themselves open on a
// field the user still has to fix, which is the one thing they share and the reason they
// sit together rather than on the Activity.
private class AddSatelliteFields(
    view: View,
) {
    val hostLayout: TextInputLayout = view.findViewById(R.id.tilSatelliteHost)
    val httpsLayout: TextInputLayout = view.findViewById(R.id.tilSatelliteHttpsPort)
    val udpLayout: TextInputLayout = view.findViewById(R.id.tilSatelliteUdpPort)
    val hostField: TextInputEditText = view.findViewById(R.id.etSatelliteHost)
    val httpsField: TextInputEditText = view.findViewById(R.id.etSatelliteHttpsPort)
    val udpField: TextInputEditText = view.findViewById(R.id.etSatelliteUdpPort)

    fun markRejected(
        context: Context,
        rejected: TypedSatelliteResult.Rejected,
    ) {
        hostLayout.error = if (rejected.hostMissing) context.getString(R.string.add_satellite_error_host) else null
        httpsLayout.error = if (rejected.httpsPortInvalid) context.getString(R.string.add_satellite_error_port) else null
        udpLayout.error = if (rejected.udpPortInvalid) context.getString(R.string.add_satellite_error_port) else null
    }

    fun clearMarks() {
        hostLayout.error = null
        httpsLayout.error = null
        udpLayout.error = null
    }
}

internal fun ConnectionsActivity.showAddSatelliteDialog() {
    val view = layoutInflater.inflate(R.layout.dialog_add_satellite, null)
    val fields = AddSatelliteFields(view)
    // Port fields parse back through toIntOrNull, so the defaults are written in ASCII digits.
    fields.httpsField.setText(String.format(Locale.ROOT, "%d", DEFAULT_HTTPS_PORT))
    fields.udpField.setText(String.format(Locale.ROOT, "%d", DEFAULT_UDP_PORT))

    val dialog =
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.action_add_custom_satellite)
            .setView(view)
            .setPositiveButton(R.string.action_connect, null)
            .setNegativeButton(R.string.action_cancel, null)
            .create()
    // The positive button is wired after show() so a failed validation keeps the dialog open.
    dialog.setOnShowListener {
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            if (connectToTypedSatellite(fields)) dialog.dismiss()
        }
    }
    dialog.show()
}

/** Answers whether the typed host was accepted, and so whether the dialog may close. */
private fun ConnectionsActivity.connectToTypedSatellite(fields: AddSatelliteFields): Boolean {
    val result =
        parseTypedSatellite(
            host = fields.hostField.text?.toString(),
            httpsPort = fields.httpsField.text?.toString(),
            udpPort = fields.udpField.text?.toString(),
        )
    return when (result) {
        is TypedSatelliteResult.Rejected -> {
            fields.markRejected(this, result)
            false
        }
        is TypedSatelliteResult.Accepted -> {
            fields.clearMarks()
            satellite.connect(manualServerFor(result.typed))
            true
        }
    }
}

// A typed address has no mDNS name behind it, so it stands in for its own label until the
// satellite answers with one.
private fun manualServerFor(typed: TypedSatellite): DiscoveredServer =
    DiscoveredServer(
        name = typed.host,
        ip = typed.host,
        udpPort = typed.udpPort,
        pairPort = typed.httpsPort,
        httpPort = typed.httpsPort,
        source = DiscoverySource.MANUAL,
    )

internal fun ConnectionsActivity.showAddMoonlightDialog() {
    val view = layoutInflater.inflate(R.layout.dialog_add_moonlight, null)
    val layout = view.findViewById<TextInputLayout>(R.id.tilMoonlightHost)
    val input = view.findViewById<TextInputEditText>(R.id.etMoonlightHost)
    val dialog =
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.action_add_moonlight_host)
            .setView(view)
            .setPositiveButton(R.string.action_add, null)
            .setNegativeButton(R.string.action_cancel, null)
            .create()
    // The positive button is wired after show() so a failed validation keeps the dialog open.
    dialog.setOnShowListener {
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            if (addTypedMoonlightHost(input, layout)) dialog.dismiss()
        }
    }
    dialog.show()
}

/** Answers whether the dialog may close: a blank address keeps it open, marked. */
private fun ConnectionsActivity.addTypedMoonlightHost(
    input: TextInputEditText,
    layout: TextInputLayout,
): Boolean {
    val address =
        input.text
            ?.toString()
            ?.trim()
            .orEmpty()
    if (address.isEmpty()) {
        layout.error = getString(R.string.add_moonlight_error_host)
        return false
    }
    moonlight.addManualHost(address)
    return true
}
