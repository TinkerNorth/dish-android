// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.main

import com.tinkernorth.dish.hotpath.input.Transport
import com.tinkernorth.dish.source.usb.DirectClaimFailure
import com.tinkernorth.dish.source.usb.PathChoice

enum class InputPathMode { Standard, Direct }

// What the user is told about choosing Direct for this device.
enum class PathRisk {
    None,
    GuessedLayout,
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
    val transport: Transport,
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
    val suggestDirectForTouch: Boolean = false,
)

object PathCardMapper {
    @Suppress("LongParameterList")
    fun map(
        isClaimedDirect: Boolean,
        transport: Transport,
        recognized: Boolean,
        restoring: Boolean,
        standard: PathCapabilities,
        direct: PathCapabilities,
        directPollHz: Int,
        needsReplug: Boolean = false,
        restoreStuck: Boolean = false,
        directFailure: DirectClaimFailure? = null,
        padHasTouchpad: Boolean = false,
    ): PathCard {
        // The card reflects the mode the controller is ACTUALLY in: Direct only when a synthetic is live
        // (claimed, not mid-release, not stuck). Badge and toggle both derive from this so they can never
        // disagree; intent (stored pref / verified default) drives the auto-claim, not what the switch shows.
        val onDirect = isClaimedDirect && !restoring && !restoreStuck
        val risk =
            when {
                transport == Transport.Bluetooth -> PathRisk.None
                !recognized -> PathRisk.GuessedLayout
                else -> PathRisk.None
            }
        // A pad's own trackpad streams only on Direct and has no phone-overlay fallback: nudge only when
        // cleanly on Standard USB with Direct actually reachable (a recent failure is left to settle first).
        val suggestDirectForTouch =
            padHasTouchpad &&
                transport == Transport.Usb &&
                !isClaimedDirect &&
                !restoring &&
                !restoreStuck &&
                !needsReplug &&
                directFailure == null
        return PathCard(
            currentMode = if (onDirect) InputPathMode.Direct else InputPathMode.Standard,
            selected = if (onDirect) PathChoice.Direct else PathChoice.Standard,
            transport = transport,
            directAvailable = transport == Transport.Usb,
            recognized = recognized,
            restoring = restoring,
            standard = standard,
            direct = direct,
            directPollHz = directPollHz,
            risk = risk,
            needsReplug = needsReplug,
            restoreStuck = restoreStuck,
            failure = directFailure,
            suggestDirectForTouch = suggestDirectForTouch,
        )
    }
}
