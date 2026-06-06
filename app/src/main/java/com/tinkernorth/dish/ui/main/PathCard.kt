// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.main

import com.tinkernorth.dish.source.usb.DirectClaimFailure
import com.tinkernorth.dish.source.usb.PathChoice

enum class InputPathMode { Standard, Direct }

// What the user is told about choosing Direct for this device.
enum class PathRisk {
    None,
    GuessedLayout,
    BluetoothUnavailable,
}

data class PathCapabilities(
    val rumble: Boolean,
    val motion: Boolean,
)

// Everything the controller row needs to render the path comparison and toggle for one device. The
// adapter turns this into localized text and visuals; nothing here is Android- or string-bound.
data class PathCard(
    val currentMode: InputPathMode,
    val selected: PathChoice,
    val directAvailable: Boolean,
    val recognized: Boolean,
    val restoring: Boolean,
    val standard: PathCapabilities,
    val direct: PathCapabilities,
    val directPollHz: Int,
    val risk: PathRisk,
    // The OS dropped this controller on a failed claim and never returned it; needs a physical replug.
    val needsReplug: Boolean = false,
    // A return-to-Standard that never came back: the toggle stays live so the user picks Direct or replug.
    val restoreStuck: Boolean = false,
    // Why the last Direct claim failed, when not already covered by needsReplug/restoreStuck.
    val failure: DirectClaimFailure? = null,
)

object PathCardMapper {
    @Suppress("LongParameterList")
    fun map(
        isClaimedDirect: Boolean,
        usbPresent: Boolean,
        recognized: Boolean,
        restoring: Boolean,
        standard: PathCapabilities,
        direct: PathCapabilities,
        directPollHz: Int,
        needsReplug: Boolean = false,
        restoreStuck: Boolean = false,
        directFailure: DirectClaimFailure? = null,
    ): PathCard {
        // The card reflects the mode the controller is ACTUALLY in: Direct only when a synthetic is live
        // (claimed, not mid-release, not stuck). Badge and toggle both derive from this so they can never
        // disagree; intent (stored pref / verified default) drives the auto-claim, not what the switch shows.
        val onDirect = isClaimedDirect && !restoring && !restoreStuck
        val risk =
            when {
                !usbPresent -> PathRisk.BluetoothUnavailable
                !recognized -> PathRisk.GuessedLayout
                else -> PathRisk.None
            }
        return PathCard(
            currentMode = if (onDirect) InputPathMode.Direct else InputPathMode.Standard,
            selected = if (onDirect) PathChoice.Direct else PathChoice.Standard,
            directAvailable = usbPresent,
            recognized = recognized,
            restoring = restoring,
            standard = standard,
            direct = direct,
            directPollHz = directPollHz,
            risk = risk,
            needsReplug = needsReplug,
            restoreStuck = restoreStuck,
            failure = directFailure,
        )
    }
}
