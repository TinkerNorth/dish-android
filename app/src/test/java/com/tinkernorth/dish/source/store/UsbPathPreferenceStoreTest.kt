// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.source.store

import com.tinkernorth.dish.repository.mapBackedPrefs
import com.tinkernorth.dish.source.usb.PathChoice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

// First coverage for the persisted USB path override (the store had no test). Exercises the
// per-(vid,pid) round-trip, durability across re-construction, the unchanged-write short-circuit,
// and the corrupt/forward-incompatible blob fallbacks in readInitial. Uses the shared map-backed
// SharedPreferences double so one backing map models the on-disk user_preferences file.
class UsbPathPreferenceStoreTest {
    @Test
    fun `setChoice then choiceFor round-trips per vid and pid`() {
        val (ctx, _) = mapBackedPrefs()
        val store = UsbPathPreferenceStore(ctx)

        store.setChoice(0x045E, 0x028E, PathChoice.Direct)

        assertEquals(PathChoice.Direct, store.choiceFor(0x045E, 0x028E))
        assertNull(store.choiceFor(0x054C, 0x05C4)) // an unrelated pad is unaffected
    }

    @Test
    fun `choices survive into a fresh store over the same prefs`() {
        val (_, backing) = mapBackedPrefs()
        UsbPathPreferenceStore(mapBackedPrefs(backing).first)
            .setChoice(0x045E, 0x028E, PathChoice.Standard)

        val reopened = UsbPathPreferenceStore(mapBackedPrefs(backing).first)

        assertEquals(PathChoice.Standard, reopened.choiceFor(0x045E, 0x028E))
    }

    @Test
    fun `clear removes only the targeted pad`() {
        val (ctx, _) = mapBackedPrefs()
        val store = UsbPathPreferenceStore(ctx)
        store.setChoice(0x045E, 0x028E, PathChoice.Direct)
        store.setChoice(0x054C, 0x05C4, PathChoice.Standard)

        store.clear(0x045E, 0x028E)

        assertNull(store.choiceFor(0x045E, 0x028E))
        assertEquals(PathChoice.Standard, store.choiceFor(0x054C, 0x05C4))
    }

    @Test
    fun `setChoice to the current value does not churn the state map`() {
        val (ctx, _) = mapBackedPrefs()
        val store = UsbPathPreferenceStore(ctx)
        store.setChoice(0x045E, 0x028E, PathChoice.Direct)
        val before = store.state.value

        store.setChoice(0x045E, 0x028E, PathChoice.Direct)

        // The store short-circuits an unchanged write, so the same map instance is retained (no emission).
        assertSame(before, store.state.value)
    }

    @Test
    fun `a corrupt choices blob falls back to an empty map without crashing`() {
        val (ctx, backing) = mapBackedPrefs()
        backing[UsbPathPreferenceStore.KEY_CHOICES] = "{not valid json"

        val store = UsbPathPreferenceStore(ctx)

        assertNull(store.choiceFor(0x045E, 0x028E))
    }

    @Test
    fun `an unknown stored path value is dropped on read for forward-compat`() {
        val (ctx, backing) = mapBackedPrefs()
        // A value written by a newer build (an enum constant this build does not know) is ignored.
        backing[UsbPathPreferenceStore.KEY_CHOICES] = """{"045e:028e":"teleport"}"""

        val store = UsbPathPreferenceStore(ctx)

        assertNull(store.choiceFor(0x045E, 0x028E))
    }
}
