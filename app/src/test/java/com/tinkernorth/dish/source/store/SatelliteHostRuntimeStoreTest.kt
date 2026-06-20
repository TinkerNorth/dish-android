// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.source.store

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SatelliteHostRuntimeStoreTest {
    private val store = SatelliteHostRuntimeStore()

    private val runtime = SatelliteHostRuntime(motionBackendOk = true)

    @Test
    fun `runtimeFor is null before any write`() {
        assertNull(store.runtimeFor("sat-A"))
    }

    @Test
    fun `set then get round-trips per host`() {
        store.setRuntime("sat-A", runtime)
        assertEquals(runtime, store.runtimeFor("sat-A"))
        assertNull(store.runtimeFor("sat-B"))
    }

    @Test
    fun `clearConnection drops only the targeted host`() {
        store.setRuntime("sat-A", runtime)
        store.setRuntime("sat-B", SatelliteHostRuntime(motionBackendOk = false))
        store.clearConnection("sat-A")
        assertNull(store.runtimeFor("sat-A"))
        assertEquals(SatelliteHostRuntime(motionBackendOk = false), store.runtimeFor("sat-B"))
    }
}
