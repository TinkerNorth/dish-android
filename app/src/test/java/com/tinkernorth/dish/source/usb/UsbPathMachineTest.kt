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
        frameworkExpected: Boolean = true,
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
        frameworkExpected = frameworkExpected,
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
    fun `routed + auto choose direct without permission stays put`() {
        val r = reduce(controller(UsbPhase.Routed, hasPermission = false), UsbEvent.Choose(PathChoice.Direct, userInitiated = false))
        assertEquals(UsbPhase.Routed, r.next?.phase)
        assertEquals(PathChoice.Direct, r.next?.desired)
        assertTrue(r.effects.isEmpty())
    }

    @Test
    fun `routed + permission granted while wanting direct starts the claim`() {
        val r = reduce(controller(UsbPhase.Routed, desired = PathChoice.Direct), UsbEvent.PermissionGranted)
        assertEquals(UsbPhase.Claiming, r.next?.phase)
        assertTrue(r.next!!.hasPermission)
        assertEquals(listOf(UsbEffect.ClearFailure, UsbEffect.BeginHold, UsbEffect.Claim), r.effects)
    }

    @Test
    fun `routed + permission denied while wanting direct falls back to standard with the reason`() {
        val r =
            reduce(
                controller(UsbPhase.Routed, desired = PathChoice.Direct, userInitiated = true),
                UsbEvent.PermissionDenied,
            )
        assertEquals(UsbPhase.Routed, r.next?.phase)
        assertEquals(PathChoice.Standard, r.next?.desired)
        assertEquals(DirectClaimFailure.PermissionDenied, r.next?.failure)
        assertEquals(
            listOf(
                UsbEffect.SetPref(PathChoice.Standard),
                UsbEffect.MarkFailure(DirectClaimFailure.PermissionDenied),
                UsbEffect.Notify(UsbNotice.SwitchToDirectFailed),
            ),
            r.effects,
        )
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
                UsbEffect.SetPref(PathChoice.Standard),
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
        assertEquals(
            listOf(UsbEffect.EndHold, UsbEffect.SetPref(PathChoice.Standard), UsbEffect.MarkFailure(DirectClaimFailure.Busy)),
            r.effects,
        )
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

    // Stealing the interface is irrelevant when no framework gamepad exists to re-enumerate: waiting
    // would only time out into a false "needs replug", so the failure settles Standard directly.
    @Test
    fun `claiming + stolen failure with no framework identity drops straight to standard`() {
        val r =
            reduce(
                controller(UsbPhase.Claiming, userInitiated = true, frameworkExpected = false),
                UsbEvent.ClaimFailed(DirectClaimFailure.InitFailed, frameworkStolen = true),
            )
        assertEquals(UsbPhase.Routed, r.next?.phase)
        assertEquals(PathChoice.Standard, r.next?.desired)
        assertEquals(DirectClaimFailure.InitFailed, r.next?.failure)
        assertEquals(
            listOf(
                UsbEffect.EndHold,
                UsbEffect.SetPref(PathChoice.Standard),
                UsbEffect.MarkFailure(DirectClaimFailure.InitFailed),
                UsbEffect.Notify(UsbNotice.SwitchToDirectFailed),
            ),
            r.effects,
        )
    }

    @Test
    fun `claiming + auto stolen failure with no framework identity is silent`() {
        val r =
            reduce(
                controller(UsbPhase.Claiming, userInitiated = false, frameworkExpected = false),
                UsbEvent.ClaimFailed(DirectClaimFailure.InitFailed, frameworkStolen = true),
            )
        assertEquals(UsbPhase.Routed, r.next?.phase)
        assertEquals(
            listOf(
                UsbEffect.EndHold,
                UsbEffect.SetPref(PathChoice.Standard),
                UsbEffect.MarkFailure(DirectClaimFailure.InitFailed),
            ),
            r.effects,
        )
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

    // The strand this guards against: a model that never re-enumerates as a framework gamepad (the
    // Steam Controller settles as keyboard/mouse) would sit in AwaitingFramework until the timeout
    // dumped every single release into RestoreStuck with a false "restore failed" banner.
    @Test
    fun `direct + choose standard with no framework identity settles routed at once`() {
        val r =
            reduce(
                controller(UsbPhase.Direct, syntheticId = -1000, frameworkExpected = false),
                UsbEvent.Choose(PathChoice.Standard, userInitiated = true),
            )
        assertEquals(UsbPhase.Routed, r.next?.phase)
        assertEquals(PathChoice.Standard, r.next?.desired)
        assertNull(r.next?.syntheticId)
        assertNull(r.next?.frameworkId)
        assertNull(r.next?.failure)
        assertEquals(
            listOf(
                UsbEffect.Release,
                UsbEffect.RemoveSynthetic(-1000),
                UsbEffect.SetPref(PathChoice.Standard),
                UsbEffect.ClearFailure,
            ),
            r.effects,
        )
    }

    @Test
    fun `a release with no framework identity never starts the stuck-detection timer`() {
        val r =
            reduce(
                controller(UsbPhase.Direct, syntheticId = -1000, frameworkExpected = false),
                UsbEvent.Choose(PathChoice.Standard, userInitiated = true),
            )
        assertFalse(r.effects.contains(UsbEffect.StartTimeout))
        // A stray Timeout (a stale timer from an earlier transition) must be a no-op on the settled state.
        val afterTimeout = reduce(r.next!!, UsbEvent.Timeout)
        assertEquals(UsbPhase.Routed, afterTimeout.next?.phase)
        assertTrue(afterTimeout.effects.isEmpty())
    }

    @Test
    fun `a released model with no framework identity can opt straight back into direct`() {
        val released =
            reduce(
                controller(UsbPhase.Direct, syntheticId = -1000, hasPermission = true, frameworkExpected = false),
                UsbEvent.Choose(PathChoice.Standard, userInitiated = true),
            ).next!!
        val r = reduce(released, UsbEvent.Choose(PathChoice.Direct, userInitiated = true))
        assertEquals(UsbPhase.Claiming, r.next?.phase)
        assertEquals(listOf(UsbEffect.ClearFailure, UsbEffect.BeginHold, UsbEffect.Claim), r.effects)
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
        assertEquals(DirectClaimFailure.Dropped, r.next?.failure)
        assertEquals(
            listOf(
                UsbEffect.MarkNeedsReplug,
                UsbEffect.MarkFailure(DirectClaimFailure.Dropped),
                UsbEffect.SetPref(PathChoice.Standard),
                UsbEffect.Notify(UsbNotice.NeedsReplug),
            ),
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
        assertEquals(DirectClaimFailure.Dropped, r.next?.failure)
        assertEquals(
            listOf(UsbEffect.MarkFailure(DirectClaimFailure.Dropped), UsbEffect.Notify(UsbNotice.RestoreFailed)),
            r.effects,
        )
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
                UsbEvent.PermissionDenied,
                UsbEvent.Choose(PathChoice.Direct, userInitiated = true),
                UsbEvent.Choose(PathChoice.Standard, userInitiated = true),
                UsbEvent.ClaimSucceeded(-2000),
                UsbEvent.ClaimFailed(DirectClaimFailure.Busy, frameworkStolen = false),
                UsbEvent.ClaimFailed(DirectClaimFailure.InitFailed, frameworkStolen = true),
                UsbEvent.Timeout,
            )
        for (expected in listOf(true, false)) {
            for (phase in UsbPhase.values()) {
                for (e in events) {
                    val held = phase == UsbPhase.Direct || phase == UsbPhase.RestoreStuck
                    // The assertion is that no (phase x event) throws; a surviving controller keeps a phase.
                    val r =
                        reduce(
                            controller(phase, syntheticId = if (held) -1000 else null, frameworkExpected = expected),
                            e,
                        )
                    assertTrue(r.next == null || r.next.name.isNotEmpty())
                }
            }
        }
    }

    @Test
    fun `direct claim count sees only direct-phase controllers`() {
        val map =
            mapOf(
                1 to controller(UsbPhase.Direct, syntheticId = -1000),
                2 to controller(UsbPhase.Claiming),
                3 to controller(UsbPhase.RestoreStuck, syntheticId = -1001),
                4 to controller(UsbPhase.Routed),
                5 to controller(UsbPhase.Direct, syntheticId = -1002, frameworkExpected = false),
            )
        assertEquals(2, map.directClaimCount())
        assertEquals(0, emptyMap<Int, UsbController>().directClaimCount())
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
