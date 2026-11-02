// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.hotpath.input

import com.tinkernorth.dish.source.connection.SatelliteConnection
import com.tinkernorth.dish.source.connection.SatelliteConnectionManager
import com.tinkernorth.dish.source.connection.SatelliteSessionState
import com.tinkernorth.dish.source.lights.LightSource

/**
 * Where one (session handle, controller index) pair's feedback lands: the slot it is bound to,
 * with the slot id and the light-bar source made once rather than per datagram.
 */
class FeedbackRoute(
    val target: RumbleTarget,
    val slotId: String,
    val lightSource: LightSource,
)

/**
 * Every bound (session handle, controller index) pair's route, built from one look at the
 * sessions by the rules [resolveRumble] applies, and the inputs it was built from, so a reader
 * can tell whether any has changed. Immutable: the session receive threads read it per feedback
 * datagram, and a lookup scans two int arrays without allocating.
 */
class FeedbackRoutes internal constructor(
    private val sessions: Map<String, SatelliteConnection>,
    private val connections: Array<SatelliteConnection>,
    private val states: Array<SatelliteSessionState>,
    private val slots: Array<Map<String, SatelliteConnection.SlotBinding>>,
    private val handles: IntArray,
    private val routeHandles: IntArray,
    private val routeControllers: IntArray,
    private val routes: Array<FeedbackRoute>,
) {
    fun routeFor(
        sessionHandle: Int,
        controllerIndex: Int,
    ): FeedbackRoute? {
        for (i in routeHandles.indices) {
            if (routeHandles[i] == sessionHandle && routeControllers[i] == controllerIndex) return routes[i]
        }
        return null
    }

    /** Whether the sessions, their states, slots and handles are all still the ones the routes were built from. */
    fun isCurrentFor(current: Map<String, SatelliteConnection>): Boolean {
        if (current !== sessions) return false
        for (i in connections.indices) {
            val connection = connections[i]
            val unchanged =
                connection.state.value === states[i] &&
                    connection.slots.value === slots[i] &&
                    connection.handle == handles[i]
            if (!unchanged) return false
        }
        return true
    }
}

/** The routes of the sessions as they stand: one route per bound controller of each handle's winning connection. */
fun feedbackRoutesOf(sessions: Map<String, SatelliteConnection>): FeedbackRoutes {
    val connections = sessions.values.toTypedArray()
    val states = Array(connections.size) { connections[it].state.value }
    val slots = Array(connections.size) { connections[it].slots.value }
    val handles = IntArray(connections.size) { connections[it].handle }
    val snapshots =
        connections.indices.map { i ->
            RumbleConnectionSnapshot(
                connectionId = connections[i].id,
                handle = handles[i],
                connected = states[i] == SatelliteSessionState.Live,
                slots = slots[i],
            )
        }
    val routeHandles = ArrayList<Int>()
    val routeControllers = ArrayList<Int>()
    val routes = ArrayList<FeedbackRoute>()
    for (handle in handles.distinct()) {
        val winner = connectionForHandle(snapshots, handle) ?: continue
        for ((slotId, binding) in winner.slots) {
            val taken = routeHandles.indices.any { routeHandles[it] == handle && routeControllers[it] == binding.controllerIndex }
            if (taken) continue
            routeHandles += handle
            routeControllers += binding.controllerIndex
            routes += FeedbackRoute(classifyTarget(slotId), slotId, LightSource(winner.connectionId, binding.controllerIndex))
        }
    }
    return FeedbackRoutes(
        sessions,
        connections,
        states,
        slots,
        handles,
        routeHandles.toIntArray(),
        routeControllers.toIntArray(),
        routes.toTypedArray(),
    )
}

/**
 * The routes as of the last lookup, rebuilt by the first lookup after the sessions change. Two
 * receive threads that both see a change both rebuild, and either result is the same routes.
 */
class FeedbackRouteCache(
    private val satellite: SatelliteConnectionManager,
) {
    @Volatile
    private var routes: FeedbackRoutes = feedbackRoutesOf(emptyMap())

    fun current(): FeedbackRoutes {
        val sessions = satellite.connections.value
        val cached = routes
        if (cached.isCurrentFor(sessions)) return cached
        return feedbackRoutesOf(sessions).also { routes = it }
    }
}
