// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.source.store

import com.tinkernorth.dish.core.model.HostFeatureSet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
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

    // ── controller audio: the two fields only the capabilities probe carries ──

    @Test
    fun `noteControllerAudio merges into an entry a catalog read already owns`() {
        // Catalog first, probe second: setIfAbsent would have dropped the verdict.
        store.setFeatures("sat-A", features)
        store.noteControllerAudio("sat-A", mic = true, speaker = true)

        assertEquals(features.copy(controllerMic = true, controllerSpeaker = true), store.featuresFor("sat-A"))
    }

    @Test
    fun `noteControllerAudio lands one direction without the other`() {
        // The host gates the two wires apart, so a speaker-only host has to arrive that way
        // rather than as a single verdict rounded up to both.
        store.setFeatures("sat-A", features)
        store.noteControllerAudio("sat-A", mic = false, speaker = true)

        assertEquals(false, store.featuresFor("sat-A")?.controllerMic)
        assertEquals(true, store.featuresFor("sat-A")?.controllerSpeaker)
    }

    @Test
    fun `a catalog write carries a probed audio verdict forward`() {
        // Probe first, catalog second: the catalog has no `audio` field to win with, so
        // its write must not erase the one document that did — in either direction.
        store.setIfAbsent("sat-A", features.copy(controllerMic = true, controllerSpeaker = false))
        store.setFeatures("sat-A", features.copy(mouseControl = false, controllerSpeaker = true))

        val merged = store.featuresFor("sat-A")
        assertEquals(false, merged?.mouseControl) // the catalog still wins its own fields
        assertEquals(true, merged?.controllerMic)
        // The incoming write's own audio fields lose outright, both of them: a catalog-built
        // set carries nothing but defaults there, so honoring one would mean letting a
        // default overwrite the only document that actually asked the host.
        assertEquals(false, merged?.controllerSpeaker)
    }

    @Test
    fun `noteControllerAudio creates an entry only when it changes something`() {
        // An old satellite reports no audio; the default already says so, so probing it
        // must not conjure a host entry out of nothing.
        val before = store.state.value
        store.noteControllerAudio("sat-A", mic = false, speaker = false)
        assertNull(store.featuresFor("sat-A"))
        assertSame(before, store.state.value)

        store.noteControllerAudio("sat-B", mic = true, speaker = true)
        assertEquals(
            HostFeatureSet.SATELLITE_DEFAULT.copy(controllerMic = true, controllerSpeaker = true),
            store.featuresFor("sat-B"),
        )
    }

    @Test
    fun `a re-probe that moves either direction lands, one at a time`() {
        store.setFeatures("sat-A", features.copy(controllerMic = true, controllerSpeaker = false))

        store.noteControllerAudio("sat-A", mic = true, speaker = true)
        assertEquals(true, store.featuresFor("sat-A")?.controllerSpeaker)

        store.noteControllerAudio("sat-A", mic = false, speaker = true)
        assertEquals(false, store.featuresFor("sat-A")?.controllerMic)
    }

    @Test
    fun `a host that switches audio back off is honored`() {
        store.setFeatures("sat-A", features.copy(controllerMic = true, controllerSpeaker = true))
        store.noteControllerAudio("sat-A", mic = false, speaker = false)
        assertEquals(false, store.featuresFor("sat-A")?.controllerMic)
        assertEquals(false, store.featuresFor("sat-A")?.controllerSpeaker)
    }

    @Test
    fun `the audio verdict is per connection`() {
        store.setFeatures("sat-A", features)
        store.setFeatures("sat-B", features)
        store.noteControllerAudio("sat-A", mic = true, speaker = true)

        assertEquals(true, store.featuresFor("sat-A")?.controllerMic)
        assertEquals(true, store.featuresFor("sat-A")?.controllerSpeaker)
        assertEquals(false, store.featuresFor("sat-B")?.controllerMic)
        assertEquals(false, store.featuresFor("sat-B")?.controllerSpeaker)
    }

    @Test
    fun `clearConnection drops only the targeted connection`() {
        store.setFeatures("sat-A", features)
        store.setFeatures("sat-B", HostFeatureSet.SATELLITE_DEFAULT)
        store.clearConnection("sat-A")
        assertNull(store.featuresFor("sat-A"))
        assertEquals(HostFeatureSet.SATELLITE_DEFAULT, store.featuresFor("sat-B"))
    }

    @Test
    fun `noteProtocolVersion seeds an unknown host from the default`() {
        store.noteProtocolVersion("sat-A", 3)

        assertEquals(HostFeatureSet.SATELLITE_DEFAULT.copy(protocolVersion = 3), store.featuresFor("sat-A"))
    }

    @Test
    fun `noteProtocolVersion updates a known host in place`() {
        store.setFeatures("sat-A", features)

        store.noteProtocolVersion("sat-A", 3)

        assertEquals(features.copy(protocolVersion = 3), store.featuresFor("sat-A"))
    }

    @Test
    fun `noteProtocolVersion ignores an invalid version`() {
        store.noteProtocolVersion("sat-A", 0)
        store.noteProtocolVersion("sat-A", -1)

        assertNull(store.featuresFor("sat-A"))
    }

    // ── the merge reducers: the store's StateFlow drops a map equal to the one it holds, so
    // whether an unchanged read builds a new map at all is only visible here, by reference ──

    private val settledAudio = mapOf("sat-A" to features.copy(controllerMic = true, controllerSpeaker = false))

    @Test
    fun `an unchanged audio verdict hands back the same map`() {
        assertSame(settledAudio, withControllerAudio(settledAudio, "sat-A", mic = true, speaker = false))
    }

    @Test
    fun `an audio verdict that moves only the mic builds a new map`() {
        val next = withControllerAudio(settledAudio, "sat-A", mic = false, speaker = false)

        assertNotSame(settledAudio, next)
        assertEquals(features.copy(controllerMic = false, controllerSpeaker = false), next["sat-A"])
    }

    @Test
    fun `an audio verdict that moves only the speaker builds a new map`() {
        val next = withControllerAudio(settledAudio, "sat-A", mic = true, speaker = true)

        assertNotSame(settledAudio, next)
        assertEquals(features.copy(controllerMic = true, controllerSpeaker = true), next["sat-A"])
    }

    @Test
    fun `a silent verdict for an unknown host hands back the same map`() {
        val empty = emptyMap<String, HostFeatureSet>()

        assertSame(empty, withControllerAudio(empty, "sat-A", mic = false, speaker = false))
    }

    @Test
    fun `an audio verdict for an unknown host is merged into the default`() {
        val next = withControllerAudio(emptyMap(), "sat-A", mic = true, speaker = false)

        assertEquals(mapOf("sat-A" to HostFeatureSet.SATELLITE_DEFAULT.copy(controllerMic = true)), next)
    }

    private val probedAudio = features.copy(controllerMic = true, controllerSpeaker = false)
    private val catalog = features.copy(mouseControl = false, controllerMic = false, controllerSpeaker = true)

    @Test
    fun `a catalog read for an unknown host lands as read`() {
        val next = withCatalogFeatures(emptyMap(), "sat-A", catalog)

        assertEquals(mapOf("sat-A" to catalog), next)
    }

    @Test
    fun `a catalog read for a known host wins its own fields and keeps the probed audio`() {
        val next = withCatalogFeatures(mapOf("sat-A" to probedAudio), "sat-A", catalog)

        assertEquals(catalog.copy(controllerMic = true, controllerSpeaker = false), next["sat-A"])
    }

    @Test
    fun `a catalog read leaves the other hosts alone`() {
        val other = HostFeatureSet.SATELLITE_DEFAULT

        val next = withCatalogFeatures(mapOf("sat-B" to other), "sat-A", catalog)

        assertSame(other, next["sat-B"])
    }

    private val settledVersion = mapOf("sat-A" to features.copy(protocolVersion = 3))

    @Test
    fun `an unchanged protocol version hands back the same map`() {
        assertSame(settledVersion, withProtocolVersion(settledVersion, "sat-A", 3))
    }

    @Test
    fun `a changed protocol version builds a new map`() {
        val next = withProtocolVersion(settledVersion, "sat-A", 4)

        assertNotSame(settledVersion, next)
        assertEquals(features.copy(protocolVersion = 4), next["sat-A"])
    }

    @Test
    fun `a protocol version for an unknown host is merged into the default`() {
        val next = withProtocolVersion(settledVersion, "sat-B", 3)

        assertEquals(HostFeatureSet.SATELLITE_DEFAULT.copy(protocolVersion = 3), next["sat-B"])
        assertEquals(settledVersion["sat-A"], next["sat-A"])
    }
}
