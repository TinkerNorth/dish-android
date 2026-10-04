// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.repository

import android.util.Log
import com.tinkernorth.dish.composer.CONTROLLER_TYPE_DUALSENSE
import com.tinkernorth.dish.composer.CONTROLLER_TYPE_PLAYSTATION
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

// Beyond the keyed contract: durability across a fresh repository over the same prefs, the wire
// names the desktops share, the per-host read, and the corrupt-decode fallback with its breadcrumb.
class MoonlightBindingRepositoryTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val pc =
        RememberedBinding(descriptor = "usb:054c:0ce6:1", hostId = "moonlight:10.0.0.5", controllerType = CONTROLLER_TYPE_PLAYSTATION)
    private val den =
        RememberedBinding(descriptor = "bt:aa:bb", hostId = "moonlight:10.0.0.9", controllerType = CONTROLLER_TYPE_DUALSENSE)

    @Before
    fun mockLog() {
        mockkStatic(Log::class)
        every { Log.w(any<String>(), any<String>()) } returns 0
    }

    @After
    fun unmockLog() {
        unmockkStatic(Log::class)
    }

    @Test
    fun `a binding survives a fresh repository over the same prefs`() {
        val (ctx, store) = mapBackedPrefs()
        MoonlightBindingRepository(ctx, json).put(pc)

        val again = MoonlightBindingRepository(mapBackedPrefs(store).first, json)

        assertEquals(pc, again.get(pc.descriptor))
    }

    // The desktops keep their list under this key (dish-windows SettingsKeys); the prefs file is
    // the one the host list already lives in.
    @Test
    fun `the list is one JSON array under the key the desktops use, in the connection store prefs`() {
        val (ctx, store) = mapBackedPrefs()

        MoonlightBindingRepository(ctx, json).put(pc)

        val raw = store["moonlight_binding_list"] as String
        assertEquals(listOf(pc), json.decodeFromString(ListSerializer(RememberedBinding.serializer()), raw))
        verify { ctx.getSharedPreferences("connection_store", any()) }
    }

    @Test
    fun `forHost lists every pad remembered for that host and no other`() {
        val repo = MoonlightBindingRepository(mapBackedPrefs().first, json)
        repo.put(pc)
        repo.put(den)
        repo.put(pc.copy(descriptor = "usb:045e:02ea:2"))

        assertEquals(setOf("usb:054c:0ce6:1", "usb:045e:02ea:2"), repo.forHost(pc.hostId).map { it.descriptor }.toSet())
        assertEquals(listOf(den), repo.forHost(den.hostId))
    }

    @Test
    fun `a pad remembered again replaces what was remembered for it`() {
        val repo = MoonlightBindingRepository(mapBackedPrefs().first, json)
        repo.put(pc)

        repo.put(pc.copy(hostId = den.hostId, controllerType = CONTROLLER_TYPE_DUALSENSE))

        assertEquals(1, repo.all().size)
        assertEquals(den.hostId, repo.get(pc.descriptor)?.hostId)
        assertEquals(CONTROLLER_TYPE_DUALSENSE, repo.get(pc.descriptor)?.controllerType)
    }

    @Test
    fun `a corrupt list reads as empty with a breadcrumb rather than failing startup`() {
        val (ctx, store) = mapBackedPrefs()
        store["moonlight_binding_list"] = "{not a list"
        val repo = MoonlightBindingRepository(ctx, json)

        assertEquals(emptyList<RememberedBinding>(), repo.all())
        assertNull(repo.get(pc.descriptor))
        verify { Log.w("MoonlightBindingRepo", match<String> { "binding list" in it }) }
    }
}
