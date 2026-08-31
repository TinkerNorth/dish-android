// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.composer

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.lifecycle.LifecycleOwner
import com.tinkernorth.dish.architecture.abstracts.AbstractController
import com.tinkernorth.dish.core.model.Feature
import com.tinkernorth.dish.core.net.moonlight.MoonlightEmulatedType
import com.tinkernorth.dish.core.net.moonlight.MoonlightEvent
import com.tinkernorth.dish.hotpath.input.FeedbackRouter
import com.tinkernorth.dish.hotpath.input.RumbleRouter
import com.tinkernorth.dish.source.connection.moonlight.MoonlightConnection
import com.tinkernorth.dish.source.connection.moonlight.MoonlightConnectionManager
import com.tinkernorth.dish.source.connection.moonlight.MoonlightPadRequest
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

// The pads every Moonlight host is being asked to carry, keyed by host id. A host with an
// entry has at least one binding pointing at it and therefore wants a session; a host with
// none wants its session gone.
typealias MoonlightDesiredPads = Map<String, List<MoonlightPadRequest>>

/**
 * Turns bindings into Moonlight sessions. A host's session is reference counted by the
 * bindings pointing at it: the first one starts (or joins) it and settles the app, later
 * ones only announce their own pad, and the last one leaving is what cancels it.
 *
 * Deliberately NOT stopped when the app leaves the foreground. The session belongs to the
 * binding, not to the screen, and [MoonlightSessionService] keeps the process able to hold
 * it up while the phone is face down.
 */
@Singleton
class MoonlightSessionController
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val hub: ConnectionCoordinator,
        private val moonlight: MoonlightConnectionManager,
        private val capabilities: CapabilityComposer,
        private val rumble: RumbleRouter,
        private val feedback: FeedbackRouter,
        scope: CoroutineScope,
    ) : AbstractController<MoonlightDesiredPads>(scope) {
        private var serviceRunning = false

        init {
            // What comes back from the host. A session drives up to four pads and
            // names the one it means by controller number; the connection is what
            // knows which slot holds that number. Every connection the manager
            // makes gets its sink here, before anything can be live on it.
            scope.launch {
                moonlight.connections.collect { conns ->
                    conns.values.forEach { conn -> conn.onFeedback = { event -> onHostFeedback(conn, event) } }
                }
            }
        }

        override fun upstream(): Flow<MoonlightDesiredPads> =
            combine(hub.bindings, hub.connections, hub.satTypes) { bindings, conns, types ->
                desiredPads(bindings, conns, types)
            }.distinctUntilChanged()

        // The service goes up before the sockets do and comes down after the last
        // /cancel, so the process is never holding a live stream unprotected.
        override fun apply(value: MoonlightDesiredPads) {
            val wanted = value.values.any { it.isNotEmpty() }
            if (wanted && !serviceRunning) startService()
            moonlight.applyDesired(value)
            if (!wanted && serviceRunning) stopService()
        }

        // The service may have been stopped while collection was down, so re-derive
        // from the post-start emission rather than from what was recorded before it.
        override fun onStarting() {
            serviceRunning = false
        }

        private fun onHostFeedback(
            conn: MoonlightConnection,
            event: MoonlightEvent,
        ) {
            val controllerNumber =
                when (event) {
                    is MoonlightEvent.Rumble -> event.controllerNumber
                    is MoonlightEvent.RumbleTriggers -> event.controllerNumber
                    is MoonlightEvent.RgbLed -> event.controllerNumber
                    else -> return
                }
            val slotId =
                conn.pads.value.values
                    .firstOrNull { it.number == controllerNumber }
                    ?.slotId ?: return
            when (event) {
                // Low frequency is the large motor, which the routers call strong. A
                // Moonlight rumble has no duration: it holds until the host sends the
                // next one, so each is delivered for as long as the router allows
                // and a session that drops mid-buzz stops buzzing on its own.
                is MoonlightEvent.Rumble ->
                    rumble.dispatchToSlot(slotId, event.lowFrequency, event.highFrequency, RUMBLE_HOLD_MS)
                is MoonlightEvent.RumbleTriggers ->
                    feedback.dispatchTriggerRumbleToSlot(slotId, event.left, event.right)
                is MoonlightEvent.RgbLed ->
                    feedback.dispatchLightbarToSlot(slotId, event.red, event.green, event.blue)
                else -> Unit
            }
        }

        override fun onStop(owner: LifecycleOwner) = Unit

        private fun desiredPads(
            bindings: Map<String, String>,
            conns: List<ConnectionSummary>,
            types: Map<Pair<String, String>, Int>,
        ): MoonlightDesiredPads {
            val moonlightIds = conns.filter { it.kind == ConnectionKind.MOONLIGHT }.mapTo(mutableSetOf()) { it.id }
            if (moonlightIds.isEmpty()) return emptyMap()
            val out = mutableMapOf<String, MutableList<MoonlightPadRequest>>()
            for ((slotId, hostId) in bindings) {
                if (hostId !in moonlightIds) continue
                out.getOrPut(hostId) { mutableListOf() } += padRequest(slotId, hostId, types[hostId to slotId])
            }
            return out
        }

        private fun padRequest(
            slotId: String,
            hostId: String,
            storedType: Int?,
        ): MoonlightPadRequest {
            val resolved = resolvedTypeFor(slotId, hostId, storedType)
            val caps =
                capabilities.capabilityForCandidate(
                    slotId = slotId,
                    candidateType = resolved,
                    candidateHostKind = ConnectionKind.MOONLIGHT,
                    candidateHostId = hostId,
                )
            val bits = MoonlightCatalog.capabilityBits(resolved, caps.available)
            return MoonlightPadRequest(
                slotId = slotId,
                emulatedType = resolved,
                capabilities = bits,
                supportedButtons = MoonlightEmulatedType.supportedButtons(bits),
            )
        }

        // Auto resolves here, on the client, before the wire: a source with motion asks for a
        // PlayStation pad because that is the only one the host gives a gyro to.
        private fun resolvedTypeFor(
            slotId: String,
            hostId: String,
            storedType: Int?,
        ): Int {
            val picked = MoonlightEmulatedType.fromStored(storedType ?: MoonlightEmulatedType.AUTO)
            if (picked != MoonlightEmulatedType.AUTO) return picked
            val source =
                capabilities.capabilityForCandidate(
                    slotId = slotId,
                    candidateType = MoonlightEmulatedType.XBOX,
                    candidateHostKind = ConnectionKind.MOONLIGHT,
                    candidateHostId = hostId,
                )
            return MoonlightEmulatedType.resolve(picked, source.inputOk(Feature.MOTION))
        }

        private fun startService() {
            val intent = Intent(context, MoonlightSessionService::class.java)
            serviceRunning =
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(intent)
                    } else {
                        context.startService(intent)
                    }
                    true
                } catch (e: IllegalStateException) {
                    Log.w(TAG, "foreground service start refused: ${e.message}")
                    false
                }
        }

        private fun stopService() {
            context.stopService(Intent(context, MoonlightSessionService::class.java))
            serviceRunning = false
        }

        private companion object {
            const val TAG = "MoonlightSessionCtl"
            const val RUMBLE_HOLD_MS = 1500
        }
    }
