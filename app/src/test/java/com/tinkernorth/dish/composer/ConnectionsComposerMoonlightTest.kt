// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.composer

import com.tinkernorth.dish.core.net.moonlight.MoonlightHost
import com.tinkernorth.dish.core.net.moonlight.RememberedMoonlight
import com.tinkernorth.dish.core.net.moonlight.moonlightHostIdFor
import com.tinkernorth.dish.repository.ConnectionStore
import com.tinkernorth.dish.source.bluetooth.BluetoothGamepadRegistry
import com.tinkernorth.dish.source.connection.SatelliteConnectionManager
import com.tinkernorth.dish.source.connection.moonlight.MoonlightConnection
import com.tinkernorth.dish.source.connection.moonlight.MoonlightConnectionManager
import com.tinkernorth.dish.source.connection.moonlight.MoonlightSessionState
import com.tinkernorth.dish.source.store.ControllerTypeStore
import com.tinkernorth.dish.source.store.SlotBindingStore
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import org.junit.Assert.assertEquals
import org.junit.Test

// How the three Moonlight host sources (remembered, discovered, live session) fold into one row.
@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionsComposerMoonlightTest {
    private val scope = TestScope(StandardTestDispatcher())
    private val sessions = MutableStateFlow<Map<String, MoonlightConnection>>(emptyMap())
    private val discoveredHosts = MutableStateFlow<List<MoonlightHost>>(emptyList())
    private val rememberedHosts = MutableStateFlow<List<RememberedMoonlight>>(emptyList())
    private val bindingStore = SlotBindingStore()
    private val typeStore = ControllerTypeStore()

    private val satellite =
        mockk<SatelliteConnectionManager> {
            every { connections } returns MutableStateFlow(emptyMap())
            every { discoveredServers } returns MutableStateFlow(emptyList())
            every { staleSatelliteIds } returns MutableStateFlow(emptySet())
        }

    private val bt =
        mockk<BluetoothGamepadRegistry> {
            every { states } returns MutableStateFlow(emptyMap())
            every { staleBtIds } returns MutableStateFlow(emptyMap())
        }

    private val store =
        mockk<ConnectionStore> {
            every { rememberedSatellitesFlow } returns MutableStateFlow(emptyList())
            every { rememberedBtFlow } returns MutableStateFlow(emptyList())
        }

    private val moonlight =
        mockk<MoonlightConnectionManager> {
            every { connections } returns sessions
            every { discovered } returns discoveredHosts
            every { remembered } returns rememberedHosts
        }

    private val composer =
        ConnectionsComposer(
            context = mockk(relaxed = true),
            satellite = satellite,
            bt = bt,
            moonlight = moonlight,
            store = store,
            bindingStore = bindingStore,
            typeStore = typeStore,
            scope = scope,
        )

    /** Touching the state starts the eager collection, so the touch has to come before the scheduler runs. */
    private fun moonlightRow(): ConnectionSummary {
        composer.state
        scope.testScheduler.runCurrent()
        return composer.state.value.single { it.kind == ConnectionKind.MOONLIGHT }
    }

    private fun session(
        host: MoonlightHost,
        state: MoonlightSessionState,
    ): MoonlightConnection =
        mockk {
            every { this@mockk.host } returns MutableStateFlow(host)
            every { this@mockk.state } returns MutableStateFlow(state)
        }

    @Test
    fun `a moonlight row carries the host id`() {
        rememberedHosts.value = listOf(REMEMBERED)
        assertEquals(ID, moonlightRow().id)
    }

    @Test
    fun `a remembered moonlight host reads Saved`() {
        rememberedHosts.value = listOf(REMEMBERED)
        assertEquals(LinkState.Saved, moonlightRow().live)
    }

    @Test
    fun `a remembered host is labelled by its remembered name`() {
        rememberedHosts.value = listOf(REMEMBERED)
        assertEquals(NAME, moonlightRow().label)
    }

    @Test
    fun `a discovered moonlight host reads Ready`() {
        discoveredHosts.value = listOf(DISCOVERED)
        assertEquals(LinkState.Ready, moonlightRow().live)
    }

    @Test
    fun `a nameless discovered host is labelled by its address`() {
        discoveredHosts.value = listOf(MoonlightHost(name = "", address = ADDRESS, uniqueId = UID))
        assertEquals(ADDRESS, moonlightRow().label)
    }

    @Test
    fun `a remembered host that discovery sees again reads Ready`() {
        rememberedHosts.value = listOf(REMEMBERED)
        discoveredHosts.value = listOf(DISCOVERED)
        assertEquals(LinkState.Ready, moonlightRow().live)
    }

    @Test
    fun `a remembered host that discovery sees again keeps its remembered name`() {
        rememberedHosts.value = listOf(REMEMBERED)
        discoveredHosts.value = listOf(DISCOVERED)
        assertEquals(NAME, moonlightRow().label)
    }

    @Test
    fun `a live moonlight session's host wins over the remembered row`() {
        rememberedHosts.value = listOf(REMEMBERED)
        sessions.value = mapOf(ID to session(RENAMED, MoonlightSessionState.Live))
        assertEquals(RENAMED.name, moonlightRow().label)
    }

    @Test
    fun `a live moonlight session reads Connected`() {
        rememberedHosts.value = listOf(REMEMBERED)
        sessions.value = mapOf(ID to session(RENAMED, MoonlightSessionState.Live))
        assertEquals(LinkState.Connected, moonlightRow().live)
    }

    @Test
    fun `a launching session with no remembered or discovered row is still listed`() {
        sessions.value = mapOf(ID to session(RENAMED, MoonlightSessionState.Launching))
        val row = moonlightRow()
        assertEquals(ID to LinkState.Connecting, row.id to row.live)
    }

    @Test
    fun `bound slots attach to the moonlight row`() {
        rememberedHosts.value = listOf(REMEMBERED)
        bindingStore.bind(SLOT, ID)
        assertEquals(listOf(SLOT), moonlightRow().boundSlotIds)
    }

    @Test
    fun `a bound slot's stored type rides the row`() {
        rememberedHosts.value = listOf(REMEMBERED)
        bindingStore.bind(SLOT, ID)
        typeStore.setType(ID, SLOT, CONTROLLER_TYPE_PLAYSTATION)
        assertEquals(mapOf(SLOT to CONTROLLER_TYPE_PLAYSTATION), moonlightRow().satelliteControllerTypes)
    }

    @Test
    fun `a bound slot without a stored type attaches with no type`() {
        rememberedHosts.value = listOf(REMEMBERED)
        bindingStore.bind(SLOT, ID)
        assertEquals(emptyMap<String, Int>(), moonlightRow().satelliteControllerTypes)
    }

    private companion object {
        const val UID = "abc"
        const val ADDRESS = "10.0.0.5"
        const val NAME = "Desk PC"
        const val SLOT = "slot-A"
        val ID = moonlightHostIdFor(ADDRESS, UID)
        val REMEMBERED = RememberedMoonlight(id = ID, name = NAME, address = ADDRESS, uniqueId = UID)
        val DISCOVERED = MoonlightHost(name = "desk-pc.local", address = ADDRESS, uniqueId = UID)
        val RENAMED = MoonlightHost(name = "Renamed PC", address = ADDRESS, uniqueId = UID)
    }
}
