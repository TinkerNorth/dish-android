// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.source.store

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SatelliteMotionBackendStatusStoreTest {
    @Test
    fun `fromFlags decodes both bits clear`() {
        val s = SatelliteMotionBackendStatus.fromFlags(0)
        assertFalse(s.sinkSupportedForType)
        assertFalse(s.backendOk)
        assertFalse(s.effective)
    }

    @Test
    fun `fromFlags decodes only SINK_SUPPORTED_FOR_TYPE`() {
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
        val s = SatelliteMotionBackendStatus.fromFlags(0xFF)
        assertTrue(s.sinkSupportedForType)
        assertTrue(s.backendOk)
    }

    @Test
    fun `statusFor is null on a fresh store`() {
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
        // Reference equality pins no flow emission — a no-op clear() that still emits re-triggers every collector.
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

        val projection = store.slotStatusesFor("conn", listOf("slot1", "slot2"))

        assertEquals(2, projection.size)
        assertEquals(SatelliteMotionBackendStatus(true, true), projection["slot1"])
        assertEquals(SatelliteMotionBackendStatus(true, false), projection["slot2"])
    }

    @Test
    fun `slotStatusesFor skips slots with no status entry`() {
        val store = SatelliteMotionBackendStatusStore()
        store.setStatus("conn", "slot1", SatelliteMotionBackendStatus(true, true))

        val projection = store.slotStatusesFor("conn", listOf("slot1", "slot2"))
        assertEquals(1, projection.size)
        assertNull(projection["slot2"])
    }
}
