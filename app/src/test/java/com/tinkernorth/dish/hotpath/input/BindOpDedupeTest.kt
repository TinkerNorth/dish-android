// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.hotpath.input

import org.junit.Assert.assertEquals
import org.junit.Test

// Locks the bind-deduplication that stops an unchanged slot re-bind from being re-applied. The bug it
// guards: bindPhysicalSlotSatellite re-runs syncSlotBaseline, which resets the device to neutral and
// publishes it, so replaying an identical bind every reconcile pass (a few Hz) momentarily releases
// every held button/trigger -- the "tiny gap" seen on a held ZL in USB-direct mode.
class BindOpDedupeTest {
    private val satA = BindOp.BindSatellite(deviceId = 5, handle = 9, controllerIndex = 2)
    private val btA = BindOp.BindBluetooth(deviceId = 5, connectionId = "bt:aa")

    @Test
    fun `the first bind for a device is emitted and recorded as applied`() {
        val r = dedupeBindOps(ops = listOf(satA), lastApplied = emptyMap())
        assertEquals(listOf(satA), r.ops)
        assertEquals(mapOf(5 to satA), r.applied)
    }

    @Test
    fun `a satellite bind identical to the last applied is dropped`() {
        // The headline failure: a steady, already-bound controller must not be re-bound.
        val r = dedupeBindOps(ops = listOf(satA), lastApplied = mapOf(5 to satA))
        assertEquals(emptyList<BindOp>(), r.ops)
        assertEquals(mapOf(5 to satA), r.applied)
    }

    @Test
    fun `a bluetooth bind identical to the last applied is dropped`() {
        val r = dedupeBindOps(ops = listOf(btA), lastApplied = mapOf(5 to btA))
        assertEquals(emptyList<BindOp>(), r.ops)
        assertEquals(mapOf(5 to btA), r.applied)
    }

    @Test
    fun `a satellite bind whose controller index changed is re-emitted`() {
        val moved = satA.copy(controllerIndex = 3)
        val r = dedupeBindOps(ops = listOf(moved), lastApplied = mapOf(5 to satA))
        assertEquals(listOf(moved), r.ops)
        assertEquals(mapOf(5 to moved), r.applied)
    }

    @Test
    fun `a satellite bind whose session handle changed is re-emitted`() {
        // A reconnect hands out a new session handle; the slot must re-bind onto it.
        val rehandled = satA.copy(handle = 10)
        val r = dedupeBindOps(ops = listOf(rehandled), lastApplied = mapOf(5 to satA))
        assertEquals(listOf(rehandled), r.ops)
        assertEquals(mapOf(5 to rehandled), r.applied)
    }

    @Test
    fun `a bluetooth bind whose connection id changed is re-emitted`() {
        val moved = btA.copy(connectionId = "bt:bb")
        val r = dedupeBindOps(ops = listOf(moved), lastApplied = mapOf(5 to btA))
        assertEquals(listOf(moved), r.ops)
        assertEquals(mapOf(5 to moved), r.applied)
    }

    @Test
    fun `switching a device from satellite to bluetooth re-emits`() {
        val r = dedupeBindOps(ops = listOf(btA), lastApplied = mapOf(5 to satA))
        assertEquals(listOf(btA), r.ops)
        assertEquals(mapOf(5 to btA), r.applied)
    }

    @Test
    fun `an unbind is always emitted and clears the applied entry`() {
        val r = dedupeBindOps(ops = listOf(BindOp.Unbind(5)), lastApplied = mapOf(5 to satA))
        assertEquals(listOf(BindOp.Unbind(5)), r.ops)
        assertEquals(emptyMap<Int, BindOp>(), r.applied)
    }

    @Test
    fun `a departed id's unbind forget and release clear it from applied`() {
        val ops = listOf(BindOp.Unbind(7), BindOp.Forget(7), BindOp.ReleaseHubBinding(7))
        val r = dedupeBindOps(ops = ops, lastApplied = mapOf(7 to BindOp.BindSatellite(7, 9, 0)))
        assertEquals(ops, r.ops)
        assertEquals(emptyMap<Int, BindOp>(), r.applied)
    }

    @Test
    fun `independent devices dedupe independently`() {
        // Device 5 unchanged (dropped); device 6 newly bound (kept).
        val sat6 = BindOp.BindSatellite(deviceId = 6, handle = 9, controllerIndex = 3)
        val r = dedupeBindOps(ops = listOf(satA, sat6), lastApplied = mapOf(5 to satA))
        assertEquals(listOf(sat6), r.ops)
        assertEquals(mapOf(5 to satA, 6 to sat6), r.applied)
    }

    @Test
    fun `emitted op order is preserved when only some binds are dropped`() {
        // Departed id 7 ops must still precede the one kept bind; the deduped bind for 5 is gone.
        val sat6 = BindOp.BindSatellite(deviceId = 6, handle = 9, controllerIndex = 3)
        val ops =
            listOf(
                BindOp.Unbind(7),
                BindOp.Forget(7),
                BindOp.ReleaseHubBinding(7),
                satA,
                sat6,
            )
        val r = dedupeBindOps(ops = ops, lastApplied = mapOf(5 to satA))
        assertEquals(
            listOf(BindOp.Unbind(7), BindOp.Forget(7), BindOp.ReleaseHubBinding(7), sat6),
            r.ops,
        )
    }

    @Test
    fun `repeated identical reconcile passes re-bind a held controller only once`() {
        // End-to-end reproduction: the same reconcile output replayed many times (every upstream
        // re-emit during steady play) binds once, then emits nothing -- no neutral-publish flicker.
        val reconcileOutput = listOf(satA)
        var applied = emptyMap<Int, BindOp>()

        val first = dedupeBindOps(reconcileOutput, applied)
        applied = first.applied
        assertEquals(listOf(satA), first.ops)

        repeat(50) {
            val pass = dedupeBindOps(reconcileOutput, applied)
            applied = pass.applied
            assertEquals(emptyList<BindOp>(), pass.ops)
        }
    }

    @Test
    fun `a device that unbinds then rebinds is emitted again`() {
        // Drop-out (slot went unregistered) then recovery must re-issue the bind, not stay deduped.
        var applied = dedupeBindOps(listOf(satA), emptyMap()).applied
        applied = dedupeBindOps(listOf(BindOp.Unbind(5)), applied).applied
        assertEquals(emptyMap<Int, BindOp>(), applied)

        val rebind = dedupeBindOps(listOf(satA), applied)
        assertEquals(listOf(satA), rebind.ops)
        assertEquals(mapOf(5 to satA), rebind.applied)
    }

    @Test
    fun `no ops yields no ops and preserves the applied map`() {
        val r = dedupeBindOps(ops = emptyList(), lastApplied = mapOf(5 to satA))
        assertEquals(emptyList<BindOp>(), r.ops)
        assertEquals(mapOf(5 to satA), r.applied)
    }
}
