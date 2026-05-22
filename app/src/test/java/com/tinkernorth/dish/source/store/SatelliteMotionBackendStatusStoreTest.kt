// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.source.store

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [SatelliteMotionBackendStatusStore] + [SatelliteMotionBackendStatus].
 *
 * The store is a simple `(connectionId, slotId) → status` reactive map;
 * tests focus on the public contract every consumer relies on:
 *
 *  - [SatelliteMotionBackendStatus.fromFlags] decodes the wire byte exactly.
 *  - [SatelliteMotionBackendStatusStore.statusFor] returns null on miss
 *    (the "unknown, defer to dish heuristic" sentinel — never collapses
 *    onto false, which would mis-read a pre-extension satellite as broken).
 *  - [SatelliteMotionBackendStatusStore.setStatus] / .clear toggle the entry
 *    in place; the underlying state flow re-emits.
 *  - [SatelliteMotionBackendStatusStore.clearConnection] drops every slot
 *    for one connection without touching others.
 *  - [SatelliteMotionBackendStatusStore.slotStatusesFor] projects the
 *    per-connection slice the composer reads, returning only slots that
 *    appear in `boundSlotIds`.
 */
class SatelliteMotionBackendStatusStoreTest {
    // ── fromFlags — wire byte → typed status ────────────────────────────

    @Test
    fun `fromFlags decodes both bits clear`() {
        val s = SatelliteMotionBackendStatus.fromFlags(0)
        assertFalse(s.sinkSupportedForType)
        assertFalse(s.backendOk)
        assertFalse(s.effective)
    }

    @Test
    fun `fromFlags decodes only SINK_SUPPORTED_FOR_TYPE`() {
        // Bit 0 — the type supports IMU but the per-serial sink failed.
        // Important: effective must remain false (the bytes won't land).
        val s =
            SatelliteMotionBackendStatus.fromFlags(
                SatelliteMotionBackendStatus.FLAG_SINK_SUPPORTED_FOR_TYPE,
            )
        assertTrue(s.sinkSupportedForType)
        assertFalse(s.backendOk)
        assertFalse(s.effective)
    }

    @Test
    fun `fromFlags decodes only BACKEND_OK`() {
        // Bit 1 alone — the kernel sink succeeded but the type has no IMU
        // surface. Same effective=false: nothing to deliver.
        val s = SatelliteMotionBackendStatus.fromFlags(SatelliteMotionBackendStatus.FLAG_BACKEND_OK)
        assertFalse(s.sinkSupportedForType)
        assertTrue(s.backendOk)
        assertFalse(s.effective)
    }

    @Test
    fun `fromFlags decodes both bits set`() {
        val s =
            SatelliteMotionBackendStatus.fromFlags(
                SatelliteMotionBackendStatus.FLAG_SINK_SUPPORTED_FOR_TYPE or
                    SatelliteMotionBackendStatus.FLAG_BACKEND_OK,
            )
        assertTrue(s.sinkSupportedForType)
        assertTrue(s.backendOk)
        assertTrue(s.effective)
    }

    @Test
    fun `fromFlags ignores reserved upper bits`() {
        // Future-proof: a forward-compatible satellite could set additional
        // bits we don't know about. Decode should pick out only bits 0 + 1
        // and not blow up. (No way to assert "doesn't blow up" beyond
        // running, but this also pins that the two flags read true.)
        val s = SatelliteMotionBackendStatus.fromFlags(0xFF)
        assertTrue(s.sinkSupportedForType)
        assertTrue(s.backendOk)
    }

    // ── Store CRUD ──────────────────────────────────────────────────────

    @Test
    fun `statusFor is null on a fresh store`() {
        // Default state is empty — the absent-means-unknown sentinel must
        // hold so the composer falls back to the dish heuristic instead
        // of reading "broken." Pinning this protects against an accidental
        // shift to a non-null default.
        val store = SatelliteMotionBackendStatusStore()
        assertNull(store.statusFor("conn", "slot"))
    }

    @Test
    fun `setStatus then statusFor round-trips`() {
        val store = SatelliteMotionBackendStatusStore()
        val s = SatelliteMotionBackendStatus(sinkSupportedForType = true, backendOk = false)
        store.setStatus("conn", "slot", s)
        assertEquals(s, store.statusFor("conn", "slot"))
    }

    @Test
    fun `setStatus overwrites in place`() {
        val store = SatelliteMotionBackendStatusStore()
        store.setStatus("conn", "slot", SatelliteMotionBackendStatus(true, true))
        store.setStatus("conn", "slot", SatelliteMotionBackendStatus(true, false))
        assertEquals(
            SatelliteMotionBackendStatus(true, false),
            store.statusFor("conn", "slot"),
        )
    }

    @Test
    fun `clear drops only the named entry`() {
        val store = SatelliteMotionBackendStatusStore()
        store.setStatus("conn", "slot1", SatelliteMotionBackendStatus(true, true))
        store.setStatus("conn", "slot2", SatelliteMotionBackendStatus(false, false))
        store.clear("conn", "slot1")
        assertNull(store.statusFor("conn", "slot1"))
        assertEquals(
            SatelliteMotionBackendStatus(false, false),
            store.statusFor("conn", "slot2"),
        )
    }

    @Test
    fun `clear on missing entry is a no-op (no spurious emission)`() {
        val store = SatelliteMotionBackendStatusStore()
        val before = store.state.value
        store.clear("conn", "missing")
        // Same reference == no flow emission. Reference equality is the
        // strict version of "the state didn't change" — important because
        // a no-op clear() that still emits would re-trigger every collector.
        assertTrue(before === store.state.value)
    }

    @Test
    fun `clearConnection drops every slot for that connection only`() {
        val store = SatelliteMotionBackendStatusStore()
        store.setStatus("connA", "slot1", SatelliteMotionBackendStatus(true, true))
        store.setStatus("connA", "slot2", SatelliteMotionBackendStatus(true, false))
        store.setStatus("connB", "slot1", SatelliteMotionBackendStatus(false, true))

        store.clearConnection("connA")

        assertNull(store.statusFor("connA", "slot1"))
        assertNull(store.statusFor("connA", "slot2"))
        // connB's slot must remain untouched — clearing one satellite
        // mustn't bleed into another.
        assertEquals(
            SatelliteMotionBackendStatus(false, true),
            store.statusFor("connB", "slot1"),
        )
    }

    @Test
    fun `slotStatusesFor returns only the bound slots`() {
        val store = SatelliteMotionBackendStatusStore()
        store.setStatus("conn", "slot1", SatelliteMotionBackendStatus(true, true))
        store.setStatus("conn", "slot2", SatelliteMotionBackendStatus(true, false))
        store.setStatus("conn", "slot3", SatelliteMotionBackendStatus(false, true))
        store.setStatus("other", "slot1", SatelliteMotionBackendStatus(false, false))

        // The composer asks "what's the status for the slots actually
        // bound to this connection right now?" — slot3 is in the store
        // but not in the bound list (e.g. it was detached), so it must
        // not appear in the projection.
        val projection = store.slotStatusesFor("conn", listOf("slot1", "slot2"))

        assertEquals(2, projection.size)
        assertEquals(SatelliteMotionBackendStatus(true, true), projection["slot1"])
        assertEquals(SatelliteMotionBackendStatus(true, false), projection["slot2"])
    }

    @Test
    fun `slotStatusesFor skips slots with no status entry`() {
        // A slot that's bound but has no observation yet (e.g. ACK not
        // received — the wire's slowest path) is excluded rather than
        // appearing as a "default" entry. The composer treats absence as
        // null = unknown.
        val store = SatelliteMotionBackendStatusStore()
        store.setStatus("conn", "slot1", SatelliteMotionBackendStatus(true, true))

        val projection = store.slotStatusesFor("conn", listOf("slot1", "slot2"))
        assertEquals(1, projection.size)
        assertNull(projection["slot2"])
    }
}
