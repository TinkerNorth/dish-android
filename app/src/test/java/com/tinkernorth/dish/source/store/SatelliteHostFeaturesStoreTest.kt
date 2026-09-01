// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.source.store

import com.tinkernorth.dish.core.model.HostFeatureSet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SatelliteHostFeaturesStoreTest {
    private val store = SatelliteHostFeaturesStore()

    private val features =
        HostFeatureSet(
            hasCatalog = true,
            mouseControl = true,
            keyboardControl = false,
            rumbleReturn = true,
        )

    @Test
    fun `featuresFor is null before any write`() {
        assertNull(store.featuresFor("sat-A"))
    }

    @Test
    fun `set then get round-trips per connection`() {
        store.setFeatures("sat-A", features)
        assertEquals(features, store.featuresFor("sat-A"))
        assertNull(store.featuresFor("sat-B"))
    }

    @Test
    fun `setIfAbsent inserts into an empty store`() {
        store.setIfAbsent("sat-A", features)
        assertEquals(features, store.featuresFor("sat-A"))
    }

    @Test
    fun `setIfAbsent leaves an already-present connection untouched`() {
        store.setFeatures("sat-A", features)
        store.setIfAbsent("sat-A", HostFeatureSet.SATELLITE_DEFAULT)
        assertEquals(features, store.featuresFor("sat-A"))
    }

    // ── controller audio: the one field only the capabilities probe carries ──

    @Test
    fun `noteControllerAudio merges into an entry a catalog read already owns`() {
        // Catalog first, probe second: setIfAbsent would have dropped the verdict.
        store.setFeatures("sat-A", features)
        store.noteControllerAudio("sat-A", controllerAudio = true)

        assertEquals(features.copy(controllerAudio = true), store.featuresFor("sat-A"))
    }

    @Test
    fun `a catalog write carries a probed audio verdict forward`() {
        // Probe first, catalog second: the catalog has no `audio` field to win with, so
        // its write must not erase the one document that did.
        store.setIfAbsent("sat-A", features.copy(controllerAudio = true))
        store.setFeatures("sat-A", features.copy(mouseControl = false))

        val merged = store.featuresFor("sat-A")
        assertEquals(false, merged?.mouseControl) // the catalog still wins its own fields
        assertEquals(true, merged?.controllerAudio)
    }

    @Test
    fun `noteControllerAudio creates an entry only when it changes something`() {
        // An old satellite reports no audio; the default already says so, so probing it
        // must not conjure a host entry out of nothing.
        store.noteControllerAudio("sat-A", controllerAudio = false)
        assertNull(store.featuresFor("sat-A"))

        store.noteControllerAudio("sat-B", controllerAudio = true)
        assertEquals(
            HostFeatureSet.SATELLITE_DEFAULT.copy(controllerAudio = true),
            store.featuresFor("sat-B"),
        )
    }

    @Test
    fun `a host that switches audio back off is honored`() {
        store.setFeatures("sat-A", features.copy(controllerAudio = true))
        store.noteControllerAudio("sat-A", controllerAudio = false)
        assertEquals(false, store.featuresFor("sat-A")?.controllerAudio)
    }

    @Test
    fun `the audio verdict is per connection`() {
        store.setFeatures("sat-A", features)
        store.setFeatures("sat-B", features)
        store.noteControllerAudio("sat-A", controllerAudio = true)

        assertEquals(true, store.featuresFor("sat-A")?.controllerAudio)
        assertEquals(false, store.featuresFor("sat-B")?.controllerAudio)
    }

    @Test
    fun `clearConnection drops only the targeted connection`() {
        store.setFeatures("sat-A", features)
        store.setFeatures("sat-B", HostFeatureSet.SATELLITE_DEFAULT)
        store.clearConnection("sat-A")
        assertNull(store.featuresFor("sat-A"))
        assertEquals(HostFeatureSet.SATELLITE_DEFAULT, store.featuresFor("sat-B"))
    }
}
