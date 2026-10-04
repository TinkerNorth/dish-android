// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.source.connection.moonlight

import android.content.Context
import android.content.SharedPreferences
import com.tinkernorth.dish.composer.CONTROLLER_TYPE_PLAYSTATION
import com.tinkernorth.dish.composer.CONTROLLER_TYPE_XBOX
import com.tinkernorth.dish.core.net.moonlight.MoonlightIdentity
import com.tinkernorth.dish.repository.MoonlightBindingRepository
import com.tinkernorth.dish.repository.RememberedBinding
import com.tinkernorth.dish.repository.mapBackedPrefs
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

// The binding memory lives with the manager, as the host records do.
@OptIn(ExperimentalCoroutinesApi::class)
class MoonlightBindingMemoryTest {
    private val dispatcher = StandardTestDispatcher()
    private val memory = MoonlightBindingRepository(mapBackedPrefs().first, Json { ignoreUnknownKeys = true })
    private lateinit var manager: MoonlightConnectionManager

    private val pc = "moonlight:10.0.0.5"
    private val den = "moonlight:10.0.0.9"

    @Before
    fun setUp() {
        val prefs = mockk<SharedPreferences>(relaxed = true)
        val context = mockk<Context>(relaxed = true)
        every { context.getSharedPreferences(any(), any()) } returns prefs
        manager =
            MoonlightConnectionManager(
                context = context,
                scope = TestScope(dispatcher),
                ioDispatcher = dispatcher,
                discovery = mockk(relaxed = true),
                gateway = mockk(relaxed = true),
                identity = mockk<MoonlightIdentity>(relaxed = true),
                store = mockk(relaxed = true),
                bindings = memory,
            )
    }

    @Test
    fun `a binding remembered for a pad is read back with its host and type`() {
        manager.rememberBinding("usb:054c:0ce6:1", pc, CONTROLLER_TYPE_PLAYSTATION)

        assertEquals(listOf(RememberedBinding("usb:054c:0ce6:1", pc, CONTROLLER_TYPE_PLAYSTATION)), manager.rememberedBindings)
    }

    @Test
    fun `a binding forgotten for a pad is gone and the others stay`() {
        manager.rememberBinding("usb:054c:0ce6:1", pc, CONTROLLER_TYPE_XBOX)
        manager.rememberBinding("bt:aa:bb", den, CONTROLLER_TYPE_XBOX)

        manager.forgetBinding("usb:054c:0ce6:1")

        assertEquals(listOf("bt:aa:bb"), manager.rememberedBindings.map { it.descriptor })
    }

    @Test
    fun `forgetting a host takes every binding remembered for it, present or not, and no other host's`() =
        runTest(dispatcher) {
            manager.rememberBinding("usb:054c:0ce6:1", pc, CONTROLLER_TYPE_XBOX)
            manager.rememberBinding("bt:aa:bb", pc, CONTROLLER_TYPE_XBOX)
            manager.rememberBinding("usb:045e:02ea:2", den, CONTROLLER_TYPE_XBOX)

            manager.forget(pc)
            dispatcher.scheduler.advanceUntilIdle()

            assertEquals(listOf("usb:045e:02ea:2"), manager.rememberedBindings.map { it.descriptor })
        }
}
