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
            touchpadModes = setOf("absolute"),
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

    @Test
    fun `clearConnection drops only the targeted connection`() {
        store.setFeatures("sat-A", features)
        store.setFeatures("sat-B", HostFeatureSet.SATELLITE_DEFAULT)
        store.clearConnection("sat-A")
        assertNull(store.featuresFor("sat-A"))
        assertEquals(HostFeatureSet.SATELLITE_DEFAULT, store.featuresFor("sat-B"))
    }
}
