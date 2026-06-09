// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.source.usb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// Characterization tests for transitions that are intentionally inert or fall through to `stay`.
// They document the current contract so a future change to these corners is a visible diff, and
// they back the two FSM observations called out in the architecture summary.
class UsbPathMachineEdgeCasesTest {
    private fun controller(
        phase: UsbPhase,
        frameworkId: Int? = null,
        syntheticId: Int? = null,
        desired: PathChoice = PathChoice.Standard,
        userInitiated: Boolean = false,
        failure: DirectClaimFailure? = null,
    ) = UsbController(
        vendorId = 0x045E,
        productId = 0x028E,
        name = "Pad",
        phase = phase,
        frameworkId = frameworkId,
        syntheticId = syntheticId,
        desired = desired,
        userInitiated = userInitiated,
        failure = failure,
    )

    @Test
    fun `needs replug plus choose direct records the desire but emits no recovery effect`() {
        val r = reduce(controller(UsbPhase.NeedsReplug), UsbEvent.Choose(PathChoice.Direct, userInitiated = true))
        assertEquals(UsbPhase.NeedsReplug, r.next?.phase)
        assertEquals(PathChoice.Direct, r.next?.desired)
        // The live toggle on a NeedsReplug card produces no Reclaim/RequestPermission: only a physical
        // replug (FrameworkUp) recovers. Contrast RestoreStuck + Choose(Direct), which emits Reclaim.
        assertTrue(r.effects.isEmpty())
    }

    @Test
    fun `needs replug plus choose standard is equally inert`() {
        val r = reduce(controller(UsbPhase.NeedsReplug), UsbEvent.Choose(PathChoice.Standard, userInitiated = true))
        assertEquals(UsbPhase.NeedsReplug, r.next?.phase)
        assertTrue(r.effects.isEmpty())
    }

    @Test
    fun `needs replug recovers only when the framework device re-enumerates`() {
        val r = reduce(controller(UsbPhase.NeedsReplug), UsbEvent.FrameworkUp(12))
        assertEquals(UsbPhase.Routed, r.next?.phase)
        assertEquals(listOf(UsbEffect.BindFramework(12), UsbEffect.ClearFailure), r.effects)
    }

    @Test
    fun `claiming plus framework down is ignored, leaning on the coordinator having forgotten the framework`() {
        val r = reduce(controller(UsbPhase.Claiming), UsbEvent.FrameworkDown)
        assertEquals(UsbPhase.Claiming, r.next?.phase)
        assertTrue(r.effects.isEmpty())
    }

    @Test
    fun `direct plus framework down is ignored`() {
        val r = reduce(controller(UsbPhase.Direct, syntheticId = -1000), UsbEvent.FrameworkDown)
        assertEquals(UsbPhase.Direct, r.next?.phase)
        assertTrue(r.effects.isEmpty())
    }

    // ── Persistence-rollback asymmetry (see summary, USB bug B1) ──────────────
    // UsbGamepadManager.setPathChoice persists the user's Direct pick eagerly, BEFORE the claim runs.
    // Every transition that settles a controller on Standard is expected to roll that pref back with a
    // SetPref(Standard) effect, except this one: a non-stolen claim failure (Busy / open rejected).

    @Test
    fun `a non-stolen claim failure settles on Standard and persists Standard`() {
        val r =
            reduce(
                controller(UsbPhase.Claiming, userInitiated = true),
                UsbEvent.ClaimFailed(DirectClaimFailure.Busy, frameworkStolen = false),
            )
        assertEquals(UsbPhase.Routed, r.next?.phase)
        assertEquals(PathChoice.Standard, r.next?.desired)
        // The failed Direct pick is rolled back in storage, so resolvePath() does not auto-retry Direct
        // on every reconnect. Matches the permission-denied path below.
        assertTrue(r.effects.contains(UsbEffect.SetPref(PathChoice.Standard)))
    }

    @Test
    fun `by contrast the permission-denied fallback does persist Standard`() {
        // The sibling "Direct failed" transition rolls the pref back. The divergence is the smell.
        val r =
            reduce(
                controller(UsbPhase.Routed, desired = PathChoice.Direct, userInitiated = true),
                UsbEvent.PermissionDenied,
            )
        assertTrue(r.effects.contains(UsbEffect.SetPref(PathChoice.Standard)))
    }

    // ── Dead failure reason (see summary, USB bug B2) ─────────────────────────

    @Test
    fun `the timeout into NeedsReplug marks the reason as Dropped`() {
        // Dropped has a dedicated card string (path_needs_replug). The timeout that strands a failed
        // claim in NeedsReplug now sets it, so the card asks for a physical replug instead of echoing
        // the prior claim error.
        val r =
            reduce(
                controller(UsbPhase.AwaitingFramework, syntheticId = null, failure = DirectClaimFailure.InitFailed),
                UsbEvent.Timeout,
            )
        assertEquals(UsbPhase.NeedsReplug, r.next?.phase)
        assertEquals(DirectClaimFailure.Dropped, r.next?.failure)
        assertTrue(r.effects.contains(UsbEffect.MarkFailure(DirectClaimFailure.Dropped)))
    }
}
