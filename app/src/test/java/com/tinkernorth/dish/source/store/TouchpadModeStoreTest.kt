// SPDX-License-Identifier: LGPL-3.0-or-later

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

class TouchpadModeStoreTest {
    @Test
    fun `hydrates state from the repository on construction`() {
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
        val repo = repoWithEmpty()
        val store = TouchpadModeStore(repo)
        assertNull(store.modeFor("never-picked"))
    }

    @Test
    fun `setMode persists to the repository AND republishes state`() {
        val repo = repoWithEmpty()
        val store = TouchpadModeStore(repo)

        store.setMode("sat-a", TouchpadModeValue.MOUSE)

        verify { repo.put(TouchpadModePreference("sat-a", TouchpadModeValue.MOUSE)) }
        assertEquals(TouchpadModeValue.MOUSE, store.state.value["sat-a"])
        assertEquals(TouchpadModeValue.MOUSE, store.modeFor("sat-a"))
    }

    @Test
    fun `setMode then setMode with different value updates both layers`() {
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
        assertNull(store.modeFor("sat-a"))
        assertNull(store.state.value["sat-a"])
    }

    @Test
    fun `setMode for sat-a leaves sat-b unchanged`() {
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
