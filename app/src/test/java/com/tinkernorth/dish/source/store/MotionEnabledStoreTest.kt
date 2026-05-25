// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.source.store

import com.tinkernorth.dish.repository.MotionPreference
import com.tinkernorth.dish.repository.MotionPreferenceRepository
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MotionEnabledStoreTest {
    @Test
    fun `hydrates state from the repository on construction`() {
        val repo =
            mockk<MotionPreferenceRepository> {
                every { all() } returns
                    listOf(
                        MotionPreference("virtual", enabled = true),
                        MotionPreference("9", enabled = false),
                    )
            }
        val store = MotionEnabledStore(repo)

        assertEquals(true, store.state.value["virtual"])
        assertEquals(false, store.state.value["9"])
        assertEquals(2, store.state.value.size)
    }

    @Test
    fun `default is on for an unwritten slot`() {
        val repo = repoWithEmpty()
        val store = MotionEnabledStore(repo)
        assertTrue(store.isEnabled("never-toggled"))
        assertTrue(MotionEnabledStore.DEFAULT_ENABLED)
    }

    @Test
    fun `setEnabled persists to the repository AND republishes state`() {
        val repo = repoWithEmpty()
        val store = MotionEnabledStore(repo)

        store.setEnabled("9", enabled = false)

        verify { repo.put(MotionPreference("9", enabled = false)) }
        assertEquals(false, store.state.value["9"])
        assertFalse(store.isEnabled("9"))
    }

    @Test
    fun `setEnabled then setEnabled with opposite value flips both layers`() {
        val repo = repoWithEmpty()
        val store = MotionEnabledStore(repo)
        store.setEnabled("9", enabled = false)
        store.setEnabled("9", enabled = true)
        store.setEnabled("9", enabled = false)

        verify { repo.put(MotionPreference("9", enabled = false)) }
        verify { repo.put(MotionPreference("9", enabled = true)) }
        assertEquals(false, store.state.value["9"])
        assertFalse(store.isEnabled("9"))
    }

    @Test
    fun `forget removes the entry from state AND from the repository`() {
        val repo =
            mockk<MotionPreferenceRepository> {
                every { all() } returns listOf(MotionPreference("9", enabled = false))
                justRun { remove("9") }
            }
        val store = MotionEnabledStore(repo)
        assertFalse(store.isEnabled("9"))

        store.forget("9")

        verify { repo.remove("9") }
        assertTrue(store.isEnabled("9"))
        assertEquals(null, store.state.value["9"])
    }

    @Test
    fun `setEnabled for slot A leaves slot B unchanged`() {
        val repo = repoWithEmpty()
        val store = MotionEnabledStore(repo)

        store.setEnabled("virtual", enabled = true)
        store.setEnabled("9", enabled = false)
        store.setEnabled("virtual", enabled = false)

        assertEquals(false, store.state.value["virtual"])
        assertEquals(false, store.state.value["9"])
    }

    @Test
    fun `isEnabled for an explicitly disabled slot returns false`() {
        val repo =
            mockk<MotionPreferenceRepository> {
                every { all() } returns listOf(MotionPreference("9", enabled = false))
            }
        val store = MotionEnabledStore(repo)
        assertFalse(store.isEnabled("9"))
    }

    private fun repoWithEmpty(): MotionPreferenceRepository =
        mockk(relaxed = true) {
            every { all() } returns emptyList()
        }
}
