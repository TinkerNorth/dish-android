// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.source.store

import com.tinkernorth.dish.core.model.Feature
import com.tinkernorth.dish.core.model.capabilitySetOf
import com.tinkernorth.dish.source.audio.PlayoutLane
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A refused output takes its lane's feature down for as long as the slot still points at the
 * endpoint and width it was refused at; anything else about the endpoint changing is a new attempt.
 */
class PadAudioRefusalStoreTest {
    private val store = PadAudioRefusalStore()

    @Test
    fun `a refusal at the slot's current endpoint takes its lane's feature down`() {
        store.record(PadAudioRefusal(SLOT, PlayoutLane.HAPTICS, PAD_ENDPOINT, QUAD))
        assertEquals(capabilitySetOf(Feature.HAPTIC_AUDIO), refusedAudioFeatures(store.state.value, SLOT, PAD_ENDPOINT, QUAD))
    }

    @Test
    fun `a refusal at another device id or another width is spent`() {
        store.record(PadAudioRefusal(SLOT, PlayoutLane.HAPTICS, PAD_ENDPOINT, QUAD))
        assertTrue(refusedAudioFeatures(store.state.value, SLOT, PAD_ENDPOINT + 1, QUAD).features.isEmpty())
        assertTrue(refusedAudioFeatures(store.state.value, SLOT, PAD_ENDPOINT, STEREO).features.isEmpty())
    }

    @Test
    fun `a refusal is the slot's own`() {
        store.record(PadAudioRefusal(SLOT, PlayoutLane.SPEAKER, PAD_ENDPOINT, STEREO))
        assertTrue(refusedAudioFeatures(store.state.value, OTHER_SLOT, PAD_ENDPOINT, STEREO).features.isEmpty())
    }

    @Test
    fun `each lane takes its own feature down`() {
        store.record(PadAudioRefusal(SLOT, PlayoutLane.SPEAKER, PAD_ENDPOINT, QUAD))
        assertEquals(capabilitySetOf(Feature.SPEAKER), refusedAudioFeatures(store.state.value, SLOT, PAD_ENDPOINT, QUAD))
        store.record(PadAudioRefusal(SLOT, PlayoutLane.HAPTICS, PAD_ENDPOINT, QUAD))
        assertEquals(
            capabilitySetOf(Feature.SPEAKER, Feature.HAPTIC_AUDIO),
            refusedAudioFeatures(store.state.value, SLOT, PAD_ENDPOINT, QUAD),
        )
    }

    @Test
    fun `clearing a lane lifts that lane, and a fresh start lifts every lane`() {
        store.record(PadAudioRefusal(SLOT, PlayoutLane.SPEAKER, PAD_ENDPOINT, QUAD))
        store.record(PadAudioRefusal(SLOT, PlayoutLane.HAPTICS, PAD_ENDPOINT, QUAD))
        store.clear(SLOT, PlayoutLane.HAPTICS)
        assertEquals(capabilitySetOf(Feature.SPEAKER), refusedAudioFeatures(store.state.value, SLOT, PAD_ENDPOINT, QUAD))
        store.clearAll()
        assertTrue(store.state.value.isEmpty())
    }
}

private const val SLOT = "9"
private const val OTHER_SLOT = "10"
private const val PAD_ENDPOINT = 11
private const val QUAD = PlayoutLane.QUAD_CHANNELS
private const val STEREO = PlayoutLane.STEREO_CHANNELS
