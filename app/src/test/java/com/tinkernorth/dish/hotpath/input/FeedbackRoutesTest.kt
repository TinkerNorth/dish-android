// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.hotpath.input

import com.tinkernorth.dish.source.connection.SatelliteConnection
import com.tinkernorth.dish.source.connection.SatelliteConnectionManager
import com.tinkernorth.dish.source.connection.SatelliteSessionState
import com.tinkernorth.dish.source.lights.LightSource
import com.tinkernorth.dish.ui.main.VIRTUAL_SLOT_ID
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class FeedbackRoutesTest {
    private class Live(
        val id: String,
        initialHandle: Int,
        initialState: SatelliteSessionState,
        initialSlots: Map<String, SatelliteConnection.SlotBinding>,
    ) {
        var handle = initialHandle
        val state = MutableStateFlow(initialState)
        val slots = MutableStateFlow(initialSlots)
        val connection: SatelliteConnection = mockk()

        init {
            every { connection.id } returns id
            every { connection.handle } answers { handle }
            every { connection.state } returns state
            every { connection.slots } returns slots
        }
    }

    private fun slot(index: Int) = SatelliteConnection.SlotBinding(controllerIndex = index, controllerType = 0, registered = true)

    private fun sessionsOf(vararg live: Live): Map<String, SatelliteConnection> = live.associate { it.id to it.connection }

    private fun snapshotsOf(vararg live: Live): List<RumbleConnectionSnapshot> =
        live.map {
            RumbleConnectionSnapshot(it.id, it.handle, it.state.value == SatelliteSessionState.Live, it.slots.value)
        }

    private val everyKind =
        Live(
            "a",
            7,
            SatelliteSessionState.Live,
            mapOf(VIRTUAL_SLOT_ID to slot(0), "1234" to slot(1), "-1000" to slot(2), "not-an-int" to slot(3)),
        )

    @Test
    fun `every target kind routes as resolveRumble decides it`() {
        val stale = Live("b", 7, SatelliteSessionState.Idle, mapOf("55" to slot(0)))
        val other = Live("c", 9, SatelliteSessionState.Live, mapOf("66" to slot(4)))
        val routes = feedbackRoutesOf(sessionsOf(stale, everyKind, other))
        val snapshots = snapshotsOf(stale, everyKind, other)
        for (handle in listOf(-1, 7, 9, 11)) {
            for (controller in 0..5) {
                assertEquals(
                    "handle $handle controller $controller",
                    resolveRumble(snapshots, handle, controller),
                    routes.routeFor(handle, controller)?.target ?: RumbleTarget.None,
                )
            }
        }
        assertEquals(RumbleTarget.Phone, routes.routeFor(7, 0)?.target)
        assertEquals(RumbleTarget.Framework(1234), routes.routeFor(7, 1)?.target)
        assertEquals(RumbleTarget.DirectUsb(-1000), routes.routeFor(7, 2)?.target)
        assertEquals(RumbleTarget.None, routes.routeFor(7, 3)?.target)
    }

    @Test
    fun `a route carries its slot id and its light source made once`() {
        val routes = feedbackRoutesOf(sessionsOf(everyKind))
        val route = routes.routeFor(7, 1)
        assertEquals("1234", route?.slotId)
        assertEquals(LightSource("a", 1), route?.lightSource)
        assertSame(route, routes.routeFor(7, 1))
    }

    @Test
    fun `a negative handle and an unbound controller have no route`() {
        val unopened = Live("x", -1, SatelliteSessionState.Linking, mapOf("1" to slot(0)))
        val routes = feedbackRoutesOf(sessionsOf(unopened, everyKind))
        assertNull(routes.routeFor(-1, 0))
        assertNull(routes.routeFor(7, 9))
    }

    @Test
    fun `the first slot bound to a controller index wins it, as resolveSlotId has it`() {
        val doubled = Live("d", 3, SatelliteSessionState.Live, linkedMapOf("10" to slot(0), "11" to slot(0)))
        assertEquals(RumbleTarget.Framework(10), feedbackRoutesOf(sessionsOf(doubled)).routeFor(3, 0)?.target)
    }

    @Test
    fun `the cache keeps its routes until a slot map, a state or a handle changes`() {
        val manager = mockk<SatelliteConnectionManager>()
        every { manager.connections } returns MutableStateFlow(sessionsOf(everyKind))
        val cache = FeedbackRouteCache(manager)
        val first = cache.current()
        assertSame(first, cache.current())

        everyKind.slots.value = everyKind.slots.value.toMap()
        assertSame("an equal slot map is the same slot map", first, cache.current())

        everyKind.slots.value = mapOf("42" to slot(0))
        val rebound = cache.current()
        assertNotSame(first, rebound)
        assertEquals(RumbleTarget.Framework(42), rebound.routeFor(7, 0)?.target)

        everyKind.state.value = SatelliteSessionState.Faltering
        val faltering = cache.current()
        assertNotSame(rebound, faltering)

        everyKind.handle = 8
        val reopened = cache.current()
        assertNotSame(faltering, reopened)
        assertNull(reopened.routeFor(7, 0))
        assertEquals(RumbleTarget.Framework(42), reopened.routeFor(8, 0)?.target)
    }
}
