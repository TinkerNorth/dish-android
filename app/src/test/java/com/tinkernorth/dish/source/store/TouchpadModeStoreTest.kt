// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.source.store

import com.tinkernorth.dish.repository.TouchpadModePreference
import com.tinkernorth.dish.repository.TouchpadModeRepository
import com.tinkernorth.dish.repository.TouchpadModeValue
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for [TouchpadModeStore] — the reactive mirror over the
 * durable [TouchpadModeRepository].
 *
 * Mirrors the [MotionEnabledStoreTest] surface because the two stores
 * share the exact same shape: hydrate-on-construct + write-through +
 * forget-restores-default. If you grow another per-satellite preference
 * store, copy this file.
 *
 * Headline invariants pinned here:
 *
 *  - **Hydration on construction:** the store seeds its `state` from
 *    `repo.all()` so a fresh process restart sees yesterday's picks
 *    without an extra round trip.
 *  - **Absence stays absent:** [TouchpadModeStore.modeFor] returns null
 *    for an unwritten satellite. The resolver
 *    ([com.tinkernorth.dish.composer.TouchpadModeComposer]) is the one
 *    that collapses absence onto a pair-time default — the store does
 *    not invent a value.
 *  - **Write-through:** [TouchpadModeStore.setMode] always persists AND
 *    republishes — neither side is allowed to silently win. This is the
 *    reactive contract the dashboard combine relies on.
 *  - **Forget restores absence:** removing a satellite's entry returns
 *    [TouchpadModeStore.modeFor] to null.
 */
class TouchpadModeStoreTest {
    @Test
    fun `hydrates state from the repository on construction`() {
        // Process restart scenario: the durable repository already holds
        // yesterday's picks. The store must surface them immediately — no
        // race window where state reads emptyMap and the dashboard chip
        // selection shows the default for a satellite the user explicitly
        // configured.
        val repo =
            mockk<TouchpadModeRepository> {
                every { all() } returns
                    listOf(
                        TouchpadModePreference("sat-a", TouchpadModeValue.MOUSE),
                        TouchpadModePreference("sat-b", TouchpadModeValue.DS4),
                    )
            }
        val store = TouchpadModeStore(repo)

        assertEquals(TouchpadModeValue.MOUSE, store.state.value["sat-a"])
        assertEquals(TouchpadModeValue.DS4, store.state.value["sat-b"])
        assertEquals(2, store.state.value.size)
    }

    @Test
    fun `modeFor returns null for an unwritten satellite - no invented default`() {
        // The store deliberately exposes absence as null. The pair-time
        // default lives in TouchpadModeComposer.resolve; baking it in
        // here would collapse "never picked" and "explicitly off" into
        // the same observable state.
        val repo = repoWithEmpty()
        val store = TouchpadModeStore(repo)
        assertNull(store.modeFor("never-picked"))
    }

    @Test
    fun `setMode persists to the repository AND republishes state`() {
        // The store must be both reactive (state flow) AND durable (repo
        // write). A regression that only updated the in-memory cache
        // would lose the pick on app restart; one that only wrote the
        // repo would leave the dashboard chip stuck on the old selection
        // until the next combine emission — the exact bug class that put
        // the previous "Repository owns a StateFlow" design on the wrong
        // side of the architecture audit.
        val repo = repoWithEmpty()
        val store = TouchpadModeStore(repo)

        store.setMode("sat-a", TouchpadModeValue.MOUSE)

        verify { repo.put(TouchpadModePreference("sat-a", TouchpadModeValue.MOUSE)) }
        assertEquals(TouchpadModeValue.MOUSE, store.state.value["sat-a"])
        assertEquals(TouchpadModeValue.MOUSE, store.modeFor("sat-a"))
    }

    @Test
    fun `setMode then setMode with different value updates both layers`() {
        // Picking Mouse then Pad then Mouse again must leave the store
        // in the final state across both the durable and reactive layers
        // — no half-updated state where the chip selection lags the repo.
        val repo = repoWithEmpty()
        val store = TouchpadModeStore(repo)
        store.setMode("sat-a", TouchpadModeValue.MOUSE)
        store.setMode("sat-a", TouchpadModeValue.DS4)
        store.setMode("sat-a", TouchpadModeValue.MOUSE)

        verify { repo.put(TouchpadModePreference("sat-a", TouchpadModeValue.MOUSE)) }
        verify { repo.put(TouchpadModePreference("sat-a", TouchpadModeValue.DS4)) }
        assertEquals(TouchpadModeValue.MOUSE, store.state.value["sat-a"])
        assertEquals(TouchpadModeValue.MOUSE, store.modeFor("sat-a"))
    }

    @Test
    fun `forget removes the entry from state AND from the repository`() {
        // Per-satellite forget is used when a paired satellite is removed
        // — keeping a stale entry forever would silently override the
        // pair-time default for a future satellite that happened to be
        // assigned the same paired-device id.
        val repo =
            mockk<TouchpadModeRepository> {
                every { all() } returns
                    listOf(TouchpadModePreference("sat-a", TouchpadModeValue.MOUSE))
                justRun { remove("sat-a") }
            }
        val store = TouchpadModeStore(repo)
        assertEquals(TouchpadModeValue.MOUSE, store.modeFor("sat-a"))

        store.forget("sat-a")

        verify { repo.remove("sat-a") }
        // After forget, the slot returns to null (the resolver picks the
        // pair-time default), NOT the prior explicit value — that's the
        // whole point.
        assertNull(store.modeFor("sat-a"))
        assertNull(store.state.value["sat-a"])
    }

    @Test
    fun `setMode for sat-a leaves sat-b unchanged`() {
        // The state map is a single MutableStateFlow; a sloppy reducer
        // could overwrite the whole map and drop other satellites. Pin
        // isolation across satellites.
        val repo = repoWithEmpty()
        val store = TouchpadModeStore(repo)

        store.setMode("sat-a", TouchpadModeValue.MOUSE)
        store.setMode("sat-b", TouchpadModeValue.DS4)
        store.setMode("sat-a", TouchpadModeValue.OFF)

        assertEquals(TouchpadModeValue.OFF, store.state.value["sat-a"])
        assertEquals(TouchpadModeValue.DS4, store.state.value["sat-b"])
    }

    private fun repoWithEmpty(): TouchpadModeRepository =
        mockk(relaxed = true) {
            every { all() } returns emptyList()
        }
}
