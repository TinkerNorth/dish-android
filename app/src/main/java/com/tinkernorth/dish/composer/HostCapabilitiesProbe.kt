// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.composer

import com.tinkernorth.dish.repository.SatelliteCapabilitiesRepository
import com.tinkernorth.dish.source.connection.SatelliteConnection
import com.tinkernorth.dish.source.connection.SatelliteConnectionManager
import com.tinkernorth.dish.source.connection.SatelliteSessionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads GET /api/server/capabilities once a satellite's link goes Live, so the host's live state
 * reaches the capability model without waiting for someone to open the binding screen.
 *
 * The controller-audio verdict is why this exists. It rides that one document — the host-level
 * `controllerAudio` block, or the per-backend `audio` flag on a satellite predating it — and
 * nothing else carries it: not the catalog (cached on server version and locale, so a switch the
 * user flips on the PC must not move it) and not the session PUT. Its only reader used to be the
 * configure screen, which meant a session restored by startup auto-reconnect streamed with the
 * verdict unknown, and unknown is opt-OUT by design: the microphone and the controller speaker
 * stayed off until the user happened to open that screen once.
 *
 * Probed once per SESSION rather than once per process like the catalog, because it IS live state:
 * `controllerAudio` is a switch on the host's own dashboard, and a reconnect is exactly when it may
 * have moved. The session is identified by its HANDLE and not by a Live transition, because a
 * transition is the wrong question twice over: a heartbeat blip (Live to Faltering and back) is one
 * session recovering and would read as a fresh Live, while a drop and reconnect the collector only
 * ever sees as a blip is a genuinely new session and would read as none. The handle is what
 * distinguishes them, and it is republished by every session PUT.
 *
 * Observes the raw connection states, NOT the composed connections flow, for the same reason
 * [CatalogPrewarmer] does: re-collecting the composer's own output into its scope perturbs its
 * flatMapLatest.
 */
@Singleton
class HostCapabilitiesProbe
    @Inject
    constructor(
        private val satellite: SatelliteConnectionManager,
        private val capabilitiesRepo: SatelliteCapabilitiesRepository,
        private val scope: CoroutineScope,
    ) {
        // One watcher per known satellite, cancelled when its connection goes, so a forgotten host
        // leaves no collector behind and a re-added one is watched afresh.
        private val watchers = ConcurrentHashMap<String, Job>()

        fun start() {
            satellite.connections
                .onEach { conns ->
                    for (id in watchers.keys.toList()) {
                        if (id !in conns) watchers.remove(id)?.cancel()
                    }
                    for ((id, conn) in conns) {
                        if (watchers.containsKey(id)) continue
                        watchers[id] = scope.launch { watch(id, conn) }
                    }
                }.launchIn(scope)
        }

        private suspend fun watch(
            id: String,
            conn: SatelliteConnection,
        ) {
            var probedHandle: Int? = null
            conn.state.collect { state ->
                if (state != SatelliteSessionState.Live) return@collect
                // The session's own handle, which markConnected publishes BEFORE flipping the
                // state, so a Live reading always has one. A negative handle is a session already
                // gone; leaving it unrecorded lets the next emission try again.
                val handle = conn.handle
                if (handle < 0 || handle == probedHandle) return@collect
                probedHandle = handle
                capabilitiesRepo.refresh(conn.server.value, id)
            }
        }
    }
