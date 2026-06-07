// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.source.usb

// Explicit lifecycle for one USB controller's input path. Pure and exhaustively tested: every
// (phase x event) is defined here, so a failed/odd transition can never silently drop the slot the
// way the previous scattered logic did. The coordinator (UsbGamepadManager) turns world changes into
// events, runs `reduce`, and executes the returned effects against the real subsystems.

enum class UsbPhase {
    Routed, // Standard: a framework InputDevice is present.
    Claiming, // a direct-mode claim is in flight (loader, toggle disabled).
    Direct, // claimed and streaming.
    AwaitingFramework, // released or claim-failed; waiting for the framework device to re-enumerate.
    RestoreStuck, // a return-to-Standard never re-enumerated; the user picks Direct, retry, or replug.
    NeedsReplug, // physically present but the OS never gave the device back; needs a manual replug.
}

data class UsbController(
    val vendorId: Int,
    val productId: Int,
    val name: String,
    val phase: UsbPhase,
    val usbPresent: Boolean = true,
    val frameworkId: Int? = null,
    val syntheticId: Int? = null,
    val hasPermission: Boolean = false,
    val desired: PathChoice = PathChoice.Standard,
    // Whether the in-flight switch was an explicit user action (gates user-facing notices).
    val userInitiated: Boolean = false,
    // Bound connection, carried with the controller across path switches.
    val connId: String? = null,
    val type: Int? = null,
    // Why the last Direct claim failed; remembered between the failure and the Standard re-settle so the
    // re-enumerated framework card can show the cause.
    val failure: DirectClaimFailure? = null,
)

sealed interface UsbEvent {
    data class FrameworkUp(
        val id: Int,
    ) : UsbEvent

    object FrameworkDown : UsbEvent

    object UsbUnplugged : UsbEvent

    object PermissionGranted : UsbEvent

    data class Choose(
        val choice: PathChoice,
        val userInitiated: Boolean,
    ) : UsbEvent

    data class ClaimSucceeded(
        val syntheticId: Int,
    ) : UsbEvent

    // frameworkStolen: the interface was claimed (kernel HID driver detached) before the failure, so the
    // framework device must re-enumerate before we can settle on Standard. When false the framework was
    // never touched, so the slot is already usable on Standard.
    data class ClaimFailed(
        val reason: DirectClaimFailure,
        val frameworkStolen: Boolean,
    ) : UsbEvent

    object Timeout : UsbEvent
}

// User-facing banner reasons; the coordinator maps these to localized strings.
enum class UsbNotice { SwitchToDirectFailed, NeedsReplug, RolledBackToDirect, RestoreFailed }

sealed interface UsbEffect {
    // Coarse: open + claim interface + native attach + register synthetic + bind. The coordinator
    // feeds the outcome back as ClaimSucceeded/ClaimFailed.
    object Claim : UsbEffect

    // Coarse rollback: re-claim Direct (known-good), dropping the synthetic placeholder. Feeds
    // ClaimSucceeded/ClaimFailed back. Only emitted when the user picks Direct out of RestoreStuck.
    object Reclaim : UsbEffect

    // Detach native + release interface + keep the synthetic entry as a held loader placeholder.
    object Release : UsbEffect

    object RequestPermission : UsbEffect

    object PromptTryDirect : UsbEffect

    // Bind the carried connection to a device id (framework or synthetic).
    data class BindFramework(
        val frameworkId: Int,
    ) : UsbEffect

    data class RemoveSynthetic(
        val syntheticId: Int,
    ) : UsbEffect

    // Registry transition hold for the framework device (suppress the grace reaper).
    object BeginHold : UsbEffect

    object EndHold : UsbEffect

    // Flip the held framework placeholder to a visible "needs replug" card (the OS dropped the device
    // and never gave it back), instead of removing it.
    object MarkNeedsReplug : UsbEffect

    // Flip the held synthetic placeholder to a visible "Standard isn't responding" card whose toggle
    // stays live, so the user picks Direct / retry / replug instead of the app silently reverting.
    object MarkRestoreStuck : UsbEffect

    object ClearRestoreStuck : UsbEffect

    object StartTimeout : UsbEffect

    data class Notify(
        val notice: UsbNotice,
    ) : UsbEffect

    data class SetPref(
        val choice: PathChoice,
    ) : UsbEffect

    // Surface why Direct failed on the visible card (and suppress auto-retry for the model); cleared
    // whenever a fresh attempt starts or Direct succeeds.
    data class MarkFailure(
        val reason: DirectClaimFailure,
    ) : UsbEffect

    object ClearFailure : UsbEffect
}

// next == null means "remove this controller from tracking".
data class Reduction(
    val next: UsbController?,
    val effects: List<UsbEffect>,
)

private fun stay(c: UsbController): Reduction = Reduction(c, emptyList())

// A fresh Direct attempt: clear any stale failure, hold the framework, claim.
private fun startClaim(c: UsbController): Reduction =
    Reduction(
        c.copy(phase = UsbPhase.Claiming, failure = null),
        listOf(UsbEffect.ClearFailure, UsbEffect.BeginHold, UsbEffect.Claim),
    )

fun reduce(
    c: UsbController,
    event: UsbEvent,
): Reduction {
    // Physical unplug wins from any phase: tear down and forget.
    if (event is UsbEvent.UsbUnplugged) {
        val effects =
            buildList {
                c.syntheticId?.let { add(UsbEffect.RemoveSynthetic(it)) }
                if (c.phase == UsbPhase.Claiming ||
                    c.phase == UsbPhase.AwaitingFramework ||
                    c.phase == UsbPhase.RestoreStuck ||
                    c.phase == UsbPhase.NeedsReplug
                ) {
                    add(UsbEffect.EndHold)
                }
            }
        return Reduction(null, effects)
    }
    return when (c.phase) {
        UsbPhase.Routed -> reduceRouted(c, event)
        UsbPhase.Claiming -> reduceClaiming(c, event)
        UsbPhase.Direct -> reduceDirect(c, event)
        UsbPhase.AwaitingFramework -> reduceAwaiting(c, event)
        UsbPhase.RestoreStuck -> reduceRestoreStuck(c, event)
        UsbPhase.NeedsReplug -> reduceNeedsReplug(c, event)
    }
}

private fun reduceRouted(
    c: UsbController,
    event: UsbEvent,
): Reduction =
    when (event) {
        is UsbEvent.FrameworkUp -> stay(c.copy(frameworkId = event.id))
        // Framework dropped while routed (cable jiggle or claim aftermath); wait for it to return.
        is UsbEvent.FrameworkDown ->
            Reduction(
                c.copy(phase = UsbPhase.AwaitingFramework, frameworkId = null, userInitiated = false),
                listOf(UsbEffect.StartTimeout),
            )
        is UsbEvent.PermissionGranted -> {
            val granted = c.copy(hasPermission = true)
            if (granted.desired == PathChoice.Direct) startClaim(granted) else stay(granted)
        }
        is UsbEvent.Choose ->
            when (event.choice) {
                PathChoice.Standard -> stay(c.copy(desired = PathChoice.Standard))
                PathChoice.Direct -> {
                    val wanting = c.copy(desired = PathChoice.Direct, userInitiated = event.userInitiated)
                    when {
                        wanting.hasPermission -> startClaim(wanting)
                        event.userInitiated -> Reduction(wanting, listOf(UsbEffect.RequestPermission))
                        else -> Reduction(wanting, listOf(UsbEffect.PromptTryDirect))
                    }
                }
            }
        else -> stay(c)
    }

private fun reduceClaiming(
    c: UsbController,
    event: UsbEvent,
): Reduction =
    when (event) {
        is UsbEvent.ClaimSucceeded ->
            Reduction(
                c.copy(phase = UsbPhase.Direct, syntheticId = event.syntheticId, frameworkId = null, failure = null),
                listOf(UsbEffect.EndHold, UsbEffect.ClearFailure),
            )
        is UsbEvent.ClaimFailed ->
            if (event.frameworkStolen) {
                // Kernel HID driver was detached by the claim; wait for the framework device to come
                // back so we can settle on Standard (and surface the reason once it does).
                Reduction(
                    c.copy(phase = UsbPhase.AwaitingFramework, syntheticId = null, failure = event.reason),
                    listOf(UsbEffect.StartTimeout),
                )
            } else {
                // Open/claim was rejected without ever stealing the interface, so the framework slot is
                // still live; drop straight back to Standard and say why Direct didn't happen.
                Reduction(
                    c.copy(phase = UsbPhase.Routed, desired = PathChoice.Standard, syntheticId = null, failure = event.reason),
                    buildList {
                        add(UsbEffect.EndHold)
                        add(UsbEffect.MarkFailure(event.reason))
                        if (c.userInitiated) add(UsbEffect.Notify(UsbNotice.SwitchToDirectFailed))
                    },
                )
            }
        is UsbEvent.FrameworkUp -> stay(c.copy(frameworkId = event.id))
        is UsbEvent.PermissionGranted -> stay(c.copy(hasPermission = true))
        else -> stay(c)
    }

private fun reduceDirect(
    c: UsbController,
    event: UsbEvent,
): Reduction =
    when (event) {
        is UsbEvent.Choose ->
            if (event.choice == PathChoice.Standard) {
                // Release the interface but keep the synthetic as a held placeholder while the framework
                // device comes back; if it doesn't we stop in RestoreStuck and let the user choose.
                Reduction(
                    c.copy(phase = UsbPhase.AwaitingFramework, userInitiated = event.userInitiated, failure = null),
                    listOf(UsbEffect.Release, UsbEffect.StartTimeout),
                )
            } else {
                stay(c.copy(desired = PathChoice.Direct))
            }
        is UsbEvent.FrameworkUp -> stay(c.copy(frameworkId = event.id))
        else -> stay(c)
    }

private fun reduceAwaiting(
    c: UsbController,
    event: UsbEvent,
): Reduction =
    when (event) {
        is UsbEvent.FrameworkUp -> {
            // Framework is back: settle on Standard, hand the binding over, drop the placeholder.
            val effects =
                buildList {
                    if (c.syntheticId != null) {
                        add(UsbEffect.RemoveSynthetic(c.syntheticId))
                    } else {
                        add(UsbEffect.EndHold)
                    }
                    add(UsbEffect.BindFramework(event.id))
                    add(UsbEffect.SetPref(PathChoice.Standard))
                    if (c.failure != null) {
                        // Came from a failed Direct claim: show why on the re-enumerated card.
                        add(UsbEffect.MarkFailure(c.failure))
                        if (c.userInitiated) add(UsbEffect.Notify(UsbNotice.SwitchToDirectFailed))
                    } else {
                        add(UsbEffect.ClearFailure)
                    }
                }
            Reduction(
                c.copy(phase = UsbPhase.Routed, frameworkId = event.id, syntheticId = null, desired = PathChoice.Standard, failure = null),
                effects,
            )
        }
        is UsbEvent.Timeout ->
            if (c.syntheticId != null) {
                // Return-to-Standard never re-enumerated. Don't silently re-claim Direct under the user;
                // surface the stuck state with a live toggle so they decide.
                Reduction(
                    c.copy(phase = UsbPhase.RestoreStuck),
                    listOf(UsbEffect.MarkRestoreStuck, UsbEffect.Notify(UsbNotice.RestoreFailed)),
                )
            } else {
                // Failed claim never recovered; the device is gone from the OS. Keep the held placeholder
                // visible as a "needs replug" card rather than letting it disappear.
                Reduction(
                    c.copy(phase = UsbPhase.NeedsReplug, desired = PathChoice.Standard),
                    listOf(UsbEffect.MarkNeedsReplug, UsbEffect.SetPref(PathChoice.Standard), UsbEffect.Notify(UsbNotice.NeedsReplug)),
                )
            }
        is UsbEvent.PermissionGranted -> stay(c.copy(hasPermission = true))
        else -> stay(c)
    }

private fun reduceRestoreStuck(
    c: UsbController,
    event: UsbEvent,
): Reduction =
    when (event) {
        is UsbEvent.Choose ->
            when (event.choice) {
                // Go back to the Direct we know works.
                PathChoice.Direct ->
                    Reduction(
                        c.copy(desired = PathChoice.Direct, userInitiated = event.userInitiated),
                        listOf(UsbEffect.Reclaim),
                    )
                // Try waiting for the framework once more (rarely succeeds without a replug, but it's
                // the user's call now).
                PathChoice.Standard ->
                    Reduction(
                        c.copy(phase = UsbPhase.AwaitingFramework, userInitiated = event.userInitiated),
                        listOf(UsbEffect.ClearRestoreStuck, UsbEffect.StartTimeout),
                    )
            }
        // Reclaim succeeded: back on Direct.
        is UsbEvent.ClaimSucceeded ->
            Reduction(
                c.copy(phase = UsbPhase.Direct, syntheticId = event.syntheticId, desired = PathChoice.Direct, failure = null),
                listOf(UsbEffect.SetPref(PathChoice.Direct), UsbEffect.ClearFailure, UsbEffect.Notify(UsbNotice.RolledBackToDirect)),
            )
        // Reclaim failed too: the device is gone. The synthetic placeholder is dropped by the Reclaim
        // effector, so there is nothing left to mark; the banner carries the news.
        is UsbEvent.ClaimFailed ->
            Reduction(
                c.copy(phase = UsbPhase.NeedsReplug, syntheticId = null),
                listOf(UsbEffect.Notify(UsbNotice.RestoreFailed)),
            )
        // The framework finally came back on its own: settle on Standard.
        is UsbEvent.FrameworkUp -> {
            val effects =
                buildList {
                    if (c.syntheticId != null) add(UsbEffect.RemoveSynthetic(c.syntheticId)) else add(UsbEffect.EndHold)
                    add(UsbEffect.BindFramework(event.id))
                    add(UsbEffect.SetPref(PathChoice.Standard))
                    add(UsbEffect.ClearRestoreStuck)
                    add(UsbEffect.ClearFailure)
                }
            Reduction(
                c.copy(phase = UsbPhase.Routed, frameworkId = event.id, syntheticId = null, desired = PathChoice.Standard, failure = null),
                effects,
            )
        }
        is UsbEvent.PermissionGranted -> stay(c.copy(hasPermission = true))
        else -> stay(c)
    }

private fun reduceNeedsReplug(
    c: UsbController,
    event: UsbEvent,
): Reduction =
    when (event) {
        // The OS finally gave the device back: return to Standard.
        is UsbEvent.FrameworkUp ->
            Reduction(
                c.copy(phase = UsbPhase.Routed, frameworkId = event.id, failure = null),
                listOf(UsbEffect.BindFramework(event.id), UsbEffect.ClearFailure),
            )
        is UsbEvent.Choose -> stay(c.copy(desired = event.choice, userInitiated = event.userInitiated))
        is UsbEvent.PermissionGranted -> stay(c.copy(hasPermission = true))
        else -> stay(c)
    }
