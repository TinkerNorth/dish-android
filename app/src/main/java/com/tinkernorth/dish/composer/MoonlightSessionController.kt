// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.composer

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.annotation.ChecksSdkIntAtLeast
import androidx.lifecycle.LifecycleOwner
import com.tinkernorth.dish.architecture.abstracts.AbstractController
import com.tinkernorth.dish.core.model.Feature
import com.tinkernorth.dish.core.model.SlotCapabilities
import com.tinkernorth.dish.core.net.moonlight.AUTO
import com.tinkernorth.dish.core.net.moonlight.MoonlightEvent
import com.tinkernorth.dish.core.net.moonlight.XBOX
import com.tinkernorth.dish.core.net.moonlight.fromStored
import com.tinkernorth.dish.core.net.moonlight.resolveMoonlightEmulatedType
import com.tinkernorth.dish.core.net.moonlight.supportedButtons
import com.tinkernorth.dish.hotpath.input.FeedbackRouter
import com.tinkernorth.dish.hotpath.input.PhysicalGamepadRegistry
import com.tinkernorth.dish.hotpath.input.RumbleRouter
import com.tinkernorth.dish.source.connection.moonlight.MoonlightConnection
import com.tinkernorth.dish.source.connection.moonlight.MoonlightConnectionManager
import com.tinkernorth.dish.source.connection.moonlight.MoonlightPadRequest
import com.tinkernorth.dish.source.lights.LightSource
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.runningFold
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

// The pads every Moonlight host is being asked to carry, keyed by host id. A host with an
// entry has at least one bound pad to carry and therefore wants a session; a host with none
// wants its session gone.
typealias MoonlightDesiredPads = Map<String, List<MoonlightPadRequest>>

// What the pads are derived from, one value per upstream emission. The registry's devices ride
// along unread: the composer reads each pad's live controller layer itself.
private data class DesiredInputs(
    val bindings: Map<String, String>,
    val conns: List<ConnectionSummary>,
    val types: Map<Pair<String, String>, Int>,
)

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
        private val registry: PhysicalGamepadRegistry,
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

        // The devices are read only to re-resolve: Auto and the capability bits come from each
        // pad's live controller layer, and Android can enumerate a pad's gyro after the pad, so
        // a device change must ask again or a pad acquired later carries what was resolved
        // before it. A change that resolves the same pads stops at distinctUntilChanged.
        //
        // Each derivation starts from the one before it, for a pad whose device has gone: the
        // binding observer unbinds a departed pad on its own collector, so the device leaves
        // here first, and that pad's request stays what it was until its unbind removes it.
        // The memory lives in this flow, so a restarted collection derives from the world alone.
        override fun upstream(): Flow<MoonlightDesiredPads> =
            combine(hub.bindings, hub.connections, hub.satTypes, registry.devices) { bindings, conns, types, _ ->
                DesiredInputs(bindings, conns, types)
            }.runningFold(NOTHING_DESIRED, ::desiredPads)
                .drop(1)
                .distinctUntilChanged()

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
                    feedback.dispatchLightbarToSlot(
                        slotId,
                        LightSource(conn.id, controllerNumber),
                        event.red,
                        event.green,
                        event.blue,
                    )
            }
        }

        override fun onStop(owner: LifecycleOwner) = Unit

        // A bound pad whose device is present is derived from it; one whose device has gone keeps
        // the request it had on that host, and one never derived there asks for no pad. A request
        // derived from an absent device would be the caps-0 Xbox pad, and a held pad replugged
        // as that, or a dropped session reopened for it, is what its unbind then undoes.
        private fun desiredPads(
            before: MoonlightDesiredPads,
            inputs: DesiredInputs,
        ): MoonlightDesiredPads {
            val moonlightIds = inputs.conns.filter { it.kind == ConnectionKind.MOONLIGHT }.mapTo(mutableSetOf()) { it.id }
            if (moonlightIds.isEmpty()) return NOTHING_DESIRED
            val out = mutableMapOf<String, MutableList<MoonlightPadRequest>>()
            for ((slotId, hostId) in inputs.bindings) {
                if (hostId !in moonlightIds) continue
                val request =
                    padRequest(slotId, hostId, inputs.types[hostId to slotId])
                        ?: before[hostId]?.firstOrNull { it.slotId == slotId }
                        ?: continue
                out.getOrPut(hostId) { mutableListOf() } += request
            }
            return out
        }

        // Null when the slot has no input behind it, which is what the composer answers for a
        // device the registry no longer has; both reads must see it, as it can leave between them.
        // Auto resolves here, on the client, before the wire: a source with motion asks for a
        // PlayStation pad because that is the only one the host gives a gyro to.
        private fun padRequest(
            slotId: String,
            hostId: String,
            storedType: Int?,
        ): MoonlightPadRequest? {
            val source = candidateFor(slotId, hostId, XBOX)
            if (!source.inputOk(Feature.GAMEPAD)) return null
            val resolved = resolveMoonlightEmulatedType(fromStored(storedType ?: AUTO), source.inputOk(Feature.MOTION))
            val caps = candidateFor(slotId, hostId, resolved)
            if (!caps.inputOk(Feature.GAMEPAD)) return null
            val bits = capabilityBits(resolved, caps.available)
            return MoonlightPadRequest(
                slotId = slotId,
                emulatedType = resolved,
                capabilities = bits,
                supportedButtons = supportedButtons(bits),
            )
        }

        private fun candidateFor(
            slotId: String,
            hostId: String,
            type: Int,
        ): SlotCapabilities =
            capabilities.capabilityForCandidate(
                slotId = slotId,
                candidateType = type,
                candidateHostKind = ConnectionKind.MOONLIGHT,
                candidateHostId = hostId,
            )

        private fun startService() {
            val intent = Intent(context, MoonlightSessionService::class.java)
            serviceRunning =
                try {
                    startSessionService(context, intent, Build.VERSION.SDK_INT)
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
            val NOTHING_DESIRED: MoonlightDesiredPads = emptyMap()
        }
    }

// From API 26 a start from the background must promise the foreground notification or the
// system refuses it; below 26 that call does not exist and a plain start is allowed from anywhere.
internal fun startSessionService(
    context: Context,
    intent: Intent,
    sdkInt: Int,
) {
    if (hasForegroundServiceStart(sdkInt)) {
        context.startForegroundService(intent)
    } else {
        context.startService(intent)
    }
}

// The annotation is what lets lint read a caller-supplied API level as the gate the 26+ call
// above needs; a bare `sdkInt >= api` comparison it cannot see through.
@ChecksSdkIntAtLeast(api = Build.VERSION_CODES.O)
private fun hasForegroundServiceStart(sdkInt: Int): Boolean = sdkInt >= Build.VERSION_CODES.O
