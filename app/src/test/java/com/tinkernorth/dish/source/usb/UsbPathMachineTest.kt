// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.source.usb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbPathMachineTest {
    private fun controller(
        phase: UsbPhase,
        frameworkId: Int? = null,
        syntheticId: Int? = null,
        hasPermission: Boolean = false,
        desired: PathChoice = PathChoice.Standard,
        userInitiated: Boolean = false,
        connId: String? = null,
        failure: DirectClaimFailure? = null,
    ) = UsbController(
        vendorId = 0x045E,
        productId = 0x028E,
        name = "Pad",
        phase = phase,
        frameworkId = frameworkId,
        syntheticId = syntheticId,
        hasPermission = hasPermission,
        desired = desired,
        userInitiated = userInitiated,
        connId = connId,
        failure = failure,
    )

    // ── Routed ───────────────────────────────────────────────────────────────

    @Test
    fun `routed + choose direct when permitted starts a held claim`() {
        val r =
            reduce(
                controller(UsbPhase.Routed, frameworkId = 7, hasPermission = true),
                UsbEvent.Choose(PathChoice.Direct, userInitiated = true),
            )
        assertEquals(UsbPhase.Claiming, r.next?.phase)
        assertEquals(listOf(UsbEffect.ClearFailure, UsbEffect.BeginHold, UsbEffect.Claim), r.effects)
    }

    @Test
    fun `routed + user choose direct without permission requests permission`() {
        val r = reduce(controller(UsbPhase.Routed, hasPermission = false), UsbEvent.Choose(PathChoice.Direct, userInitiated = true))
        assertEquals(UsbPhase.Routed, r.next?.phase)
        assertEquals(listOf(UsbEffect.RequestPermission), r.effects)
    }

    @Test
    fun `routed + auto choose direct without permission only prompts`() {
        val r = reduce(controller(UsbPhase.Routed, hasPermission = false), UsbEvent.Choose(PathChoice.Direct, userInitiated = false))
        assertEquals(UsbPhase.Routed, r.next?.phase)
        assertEquals(listOf(UsbEffect.PromptTryDirect), r.effects)
    }

    @Test
    fun `routed + permission granted while wanting direct starts the claim`() {
        val r = reduce(controller(UsbPhase.Routed, desired = PathChoice.Direct), UsbEvent.PermissionGranted)
        assertEquals(UsbPhase.Claiming, r.next?.phase)
        assertTrue(r.next!!.hasPermission)
        assertEquals(listOf(UsbEffect.ClearFailure, UsbEffect.BeginHold, UsbEffect.Claim), r.effects)
    }

    @Test
    fun `routed + framework down waits for re-enumeration`() {
        val r = reduce(controller(UsbPhase.Routed, frameworkId = 7), UsbEvent.FrameworkDown)
        assertEquals(UsbPhase.AwaitingFramework, r.next?.phase)
        assertNull(r.next?.frameworkId)
        assertEquals(listOf(UsbEffect.StartTimeout), r.effects)
    }

    // ── Claiming ─────────────────────────────────────────────────────────────

    @Test
    fun `claiming + success becomes direct and clears any failure`() {
        val r =
            reduce(
                controller(UsbPhase.Claiming, hasPermission = true, desired = PathChoice.Direct, failure = DirectClaimFailure.Busy),
                UsbEvent.ClaimSucceeded(-1000),
            )
        assertEquals(UsbPhase.Direct, r.next?.phase)
        assertEquals(-1000, r.next?.syntheticId)
        assertNull(r.next?.failure)
        assertEquals(listOf(UsbEffect.EndHold, UsbEffect.ClearFailure), r.effects)
    }

    @Test
    fun `claiming + busy failure drops straight back to standard with the reason`() {
        // Busy never stole the interface, so the framework slot is still live: no wait needed.
        val r =
            reduce(
                controller(UsbPhase.Claiming, userInitiated = true),
                UsbEvent.ClaimFailed(DirectClaimFailure.Busy, frameworkStolen = false),
            )
        assertEquals(UsbPhase.Routed, r.next?.phase)
        assertEquals(PathChoice.Standard, r.next?.desired)
        assertEquals(DirectClaimFailure.Busy, r.next?.failure)
        assertEquals(
            listOf(
                UsbEffect.EndHold,
                UsbEffect.MarkFailure(DirectClaimFailure.Busy),
                UsbEffect.Notify(UsbNotice.SwitchToDirectFailed),
            ),
            r.effects,
        )
    }

    @Test
    fun `claiming + auto busy failure is silent`() {
        val r =
            reduce(
                controller(UsbPhase.Claiming, userInitiated = false),
                UsbEvent.ClaimFailed(DirectClaimFailure.Busy, frameworkStolen = false),
            )
        assertEquals(UsbPhase.Routed, r.next?.phase)
        assertEquals(listOf(UsbEffect.EndHold, UsbEffect.MarkFailure(DirectClaimFailure.Busy)), r.effects)
    }

    @Test
    fun `claiming + init failure that stole the interface waits for the framework`() {
        val r =
            reduce(
                controller(UsbPhase.Claiming, userInitiated = true),
                UsbEvent.ClaimFailed(DirectClaimFailure.InitFailed, frameworkStolen = true),
            )
        assertEquals(UsbPhase.AwaitingFramework, r.next?.phase)
        assertNull(r.next?.syntheticId)
        assertEquals(DirectClaimFailure.InitFailed, r.next?.failure)
        assertEquals(listOf(UsbEffect.StartTimeout), r.effects)
    }

    // ── Direct ───────────────────────────────────────────────────────────────

    @Test
    fun `direct + choose standard releases and waits, keeping the placeholder`() {
        val r = reduce(controller(UsbPhase.Direct, syntheticId = -1000), UsbEvent.Choose(PathChoice.Standard, userInitiated = true))
        assertEquals(UsbPhase.AwaitingFramework, r.next?.phase)
        assertEquals(-1000, r.next?.syntheticId)
        assertNull(r.next?.failure)
        assertEquals(listOf(UsbEffect.Release, UsbEffect.StartTimeout), r.effects)
    }

    // ── AwaitingFramework ────────────────────────────────────────────────────

    @Test
    fun `awaiting from release + framework up returns to standard silently`() {
        val r = reduce(controller(UsbPhase.AwaitingFramework, syntheticId = -1000, connId = "c"), UsbEvent.FrameworkUp(9))
        assertEquals(UsbPhase.Routed, r.next?.phase)
        assertEquals(9, r.next?.frameworkId)
        assertNull(r.next?.syntheticId)
        assertEquals(
            listOf(
                UsbEffect.RemoveSynthetic(-1000),
                UsbEffect.BindFramework(9),
                UsbEffect.SetPref(PathChoice.Standard),
                UsbEffect.ClearFailure,
            ),
            r.effects,
        )
    }

    @Test
    fun `awaiting from user claim-fail + framework up returns to standard with the reason`() {
        val r =
            reduce(
                controller(UsbPhase.AwaitingFramework, syntheticId = null, userInitiated = true, failure = DirectClaimFailure.InitFailed),
                UsbEvent.FrameworkUp(9),
            )
        assertEquals(UsbPhase.Routed, r.next?.phase)
        assertEquals(
            listOf(
                UsbEffect.EndHold,
                UsbEffect.BindFramework(9),
                UsbEffect.SetPref(PathChoice.Standard),
                UsbEffect.MarkFailure(DirectClaimFailure.InitFailed),
                UsbEffect.Notify(UsbNotice.SwitchToDirectFailed),
            ),
            r.effects,
        )
    }

    @Test
    fun `awaiting from auto claim-fail + framework up surfaces the reason without a banner`() {
        val r =
            reduce(
                controller(UsbPhase.AwaitingFramework, syntheticId = null, userInitiated = false, failure = DirectClaimFailure.InitFailed),
                UsbEvent.FrameworkUp(9),
            )
        assertEquals(UsbPhase.Routed, r.next?.phase)
        assertEquals(
            listOf(
                UsbEffect.EndHold,
                UsbEffect.BindFramework(9),
                UsbEffect.SetPref(PathChoice.Standard),
                UsbEffect.MarkFailure(DirectClaimFailure.InitFailed),
            ),
            r.effects,
        )
    }

    @Test
    fun `awaiting from release + timeout stops in restore-stuck instead of reverting`() {
        val r = reduce(controller(UsbPhase.AwaitingFramework, syntheticId = -1000), UsbEvent.Timeout)
        assertEquals(UsbPhase.RestoreStuck, r.next?.phase)
        assertEquals(-1000, r.next?.syntheticId)
        assertEquals(listOf(UsbEffect.MarkRestoreStuck, UsbEffect.Notify(UsbNotice.RestoreFailed)), r.effects)
    }

    @Test
    fun `awaiting from claim-fail + timeout needs replug`() {
        val r = reduce(controller(UsbPhase.AwaitingFramework, syntheticId = null), UsbEvent.Timeout)
        assertEquals(UsbPhase.NeedsReplug, r.next?.phase)
        assertEquals(
            listOf(UsbEffect.MarkNeedsReplug, UsbEffect.SetPref(PathChoice.Standard), UsbEffect.Notify(UsbNotice.NeedsReplug)),
            r.effects,
        )
    }

    // ── RestoreStuck ─────────────────────────────────────────────────────────

    @Test
    fun `restore stuck + choose direct re-claims the known-good path`() {
        val r =
            reduce(
                controller(UsbPhase.RestoreStuck, syntheticId = -1000),
                UsbEvent.Choose(PathChoice.Direct, userInitiated = true),
            )
        assertEquals(UsbPhase.RestoreStuck, r.next?.phase)
        assertEquals(PathChoice.Direct, r.next?.desired)
        assertEquals(listOf(UsbEffect.Reclaim), r.effects)
    }

    @Test
    fun `restore stuck + reclaim success becomes direct again`() {
        val r = reduce(controller(UsbPhase.RestoreStuck, syntheticId = -1000), UsbEvent.ClaimSucceeded(-1001))
        assertEquals(UsbPhase.Direct, r.next?.phase)
        assertEquals(-1001, r.next?.syntheticId)
        assertEquals(
            listOf(UsbEffect.SetPref(PathChoice.Direct), UsbEffect.ClearFailure, UsbEffect.Notify(UsbNotice.RolledBackToDirect)),
            r.effects,
        )
    }

    @Test
    fun `restore stuck + reclaim failure needs replug`() {
        val r =
            reduce(
                controller(UsbPhase.RestoreStuck, syntheticId = -1000),
                UsbEvent.ClaimFailed(DirectClaimFailure.InitFailed, frameworkStolen = true),
            )
        assertEquals(UsbPhase.NeedsReplug, r.next?.phase)
        assertEquals(listOf(UsbEffect.Notify(UsbNotice.RestoreFailed)), r.effects)
    }

    @Test
    fun `restore stuck + choose standard retries the wait`() {
        val r =
            reduce(
                controller(UsbPhase.RestoreStuck, syntheticId = -1000),
                UsbEvent.Choose(PathChoice.Standard, userInitiated = true),
            )
        assertEquals(UsbPhase.AwaitingFramework, r.next?.phase)
        assertEquals(-1000, r.next?.syntheticId)
        assertEquals(listOf(UsbEffect.ClearRestoreStuck, UsbEffect.StartTimeout), r.effects)
    }

    @Test
    fun `restore stuck + framework up finally settles on standard`() {
        val r = reduce(controller(UsbPhase.RestoreStuck, syntheticId = -1000, connId = "c"), UsbEvent.FrameworkUp(9))
        assertEquals(UsbPhase.Routed, r.next?.phase)
        assertNull(r.next?.syntheticId)
        assertEquals(
            listOf(
                UsbEffect.RemoveSynthetic(-1000),
                UsbEffect.BindFramework(9),
                UsbEffect.SetPref(PathChoice.Standard),
                UsbEffect.ClearRestoreStuck,
                UsbEffect.ClearFailure,
            ),
            r.effects,
        )
    }

    @Test
    fun `unplug from restore stuck removes the synthetic and ends the hold`() {
        val r = reduce(controller(UsbPhase.RestoreStuck, syntheticId = -1000), UsbEvent.UsbUnplugged)
        assertNull(r.next)
        assertEquals(listOf(UsbEffect.RemoveSynthetic(-1000), UsbEffect.EndHold), r.effects)
    }

    // ── NeedsReplug ──────────────────────────────────────────────────────────

    @Test
    fun `needs replug + framework up returns to standard and clears the failure`() {
        val r = reduce(controller(UsbPhase.NeedsReplug, connId = "c", failure = DirectClaimFailure.Dropped), UsbEvent.FrameworkUp(12))
        assertEquals(UsbPhase.Routed, r.next?.phase)
        assertNull(r.next?.failure)
        assertEquals(listOf(UsbEffect.BindFramework(12), UsbEffect.ClearFailure), r.effects)
    }

    // ── Unplug from any phase ────────────────────────────────────────────────

    @Test
    fun `unplug removes the controller and cleans up a synthetic`() {
        // Direct holds nothing (the BeginHold was already ended on the claim), so only the synthetic goes.
        val r = reduce(controller(UsbPhase.Direct, syntheticId = -1000), UsbEvent.UsbUnplugged)
        assertNull(r.next)
        assertEquals(listOf(UsbEffect.RemoveSynthetic(-1000)), r.effects)
    }

    @Test
    fun `unplug while awaiting ends the hold`() {
        val r = reduce(controller(UsbPhase.AwaitingFramework, syntheticId = null), UsbEvent.UsbUnplugged)
        assertNull(r.next)
        assertEquals(listOf(UsbEffect.EndHold), r.effects)
    }

    @Test
    fun `unplug from routed just forgets it`() {
        val r = reduce(controller(UsbPhase.Routed, frameworkId = 3), UsbEvent.UsbUnplugged)
        assertNull(r.next)
        assertTrue(r.effects.isEmpty())
    }

    // ── Totality: no (phase x event) throws and entries stay coherent ─────────

    @Test
    fun `reduce is total over every phase and event`() {
        val events =
            listOf(
                UsbEvent.FrameworkUp(1),
                UsbEvent.FrameworkDown,
                UsbEvent.UsbUnplugged,
                UsbEvent.PermissionGranted,
                UsbEvent.Choose(PathChoice.Direct, userInitiated = true),
                UsbEvent.Choose(PathChoice.Standard, userInitiated = true),
                UsbEvent.ClaimSucceeded(-2000),
                UsbEvent.ClaimFailed(DirectClaimFailure.Busy, frameworkStolen = false),
                UsbEvent.ClaimFailed(DirectClaimFailure.InitFailed, frameworkStolen = true),
                UsbEvent.Timeout,
            )
        for (phase in UsbPhase.values()) {
            for (e in events) {
                val held = phase == UsbPhase.Direct || phase == UsbPhase.RestoreStuck
                // The assertion is that no (phase x event) throws; a surviving controller keeps a phase.
                val r = reduce(controller(phase, syntheticId = if (held) -1000 else null), e)
                assertTrue(r.next == null || r.next.name.isNotEmpty())
            }
        }
    }

    @Test
    fun `start claim clears a stale failure on the controller`() {
        val r =
            reduce(
                controller(UsbPhase.Routed, hasPermission = true, failure = DirectClaimFailure.Busy),
                UsbEvent.Choose(PathChoice.Direct, userInitiated = true),
            )
        assertFalse(r.effects.isEmpty())
        assertNull(r.next?.failure)
    }
}
