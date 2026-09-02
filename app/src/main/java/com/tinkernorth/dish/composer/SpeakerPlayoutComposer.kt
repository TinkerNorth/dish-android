// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.composer

import com.tinkernorth.dish.architecture.abstracts.AbstractComposer
import com.tinkernorth.dish.core.model.Feature
import com.tinkernorth.dish.core.model.SlotCapabilities
import com.tinkernorth.dish.source.audio.SlotAudioRoutes
import com.tinkernorth.dish.source.audio.SpeakerPlayoutPlan
import com.tinkernorth.dish.source.audio.SpeakerPlayoutPolicy
import com.tinkernorth.dish.source.audio.SpeakerSlotInput
import com.tinkernorth.dish.source.connection.SatelliteConnectionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The moving facts behind controller sound, folded into one plan: what is bound and streaming,
 * what the capability model says a slot's emulated pad carries and the user left on, which
 * controller index the session gave it, and which audio endpoint (if any) belongs to its pad.
 *
 * They are combined here rather than watched separately by the playback engine because they are
 * one decision; [SpeakerPlayoutPolicy] holds the rule itself, with nothing reactive in it. The
 * mirror image of [MicCaptureComposer], with the addressing turned round: capture is published
 * per slot because that is what a frame is sent TO, while playback is published per (session,
 * controller index) because that is what a frame arrives FROM.
 */
@Singleton
class SpeakerPlayoutComposer
    @Inject
    constructor(
        private val hub: ConnectionCoordinator,
        private val capabilities: CapabilityComposer,
        private val satellite: SatelliteConnectionManager,
        private val routing: SlotAudioRoutes,
        scope: CoroutineScope,
    ) : AbstractComposer<SpeakerPlayoutPlan>(scope, SpeakerPlayoutPlan.IDLE) {
        @OptIn(ExperimentalCoroutinesApi::class)
        override fun upstream(): Flow<SpeakerPlayoutPlan> =
            satellite.connections
                .flatMapLatest { conns ->
                    // The outer map only re-emits on session add/remove; a slot's controller index
                    // and its registered flag live on the connection, so those flows have to ride
                    // the combine too (same shape as PhysicalSlotBindingObserver).
                    val slotFlows = conns.values.map { it.slots }
                    val slotsTrigger: Flow<Unit> =
                        if (slotFlows.isEmpty()) flowOf(Unit) else combine(slotFlows) { }
                    combine(
                        hub.bindings,
                        hub.connections,
                        capabilities.state,
                        routing.changes,
                        slotsTrigger,
                    ) { bindings, summaries, caps, _, _ ->
                        val summariesById = summaries.associateBy { it.id }
                        val slots =
                            bindings.map { (slotId, connId) ->
                                val conn = conns[connId]
                                val binding = conn?.slots?.value?.get(slotId)
                                SpeakerSlotInput(
                                    slotId = slotId,
                                    sessionHandle = conn?.handle ?: NO_HANDLE,
                                    // Unregistered means the descriptor has not applied, so the
                                    // emulated pad does not exist yet and no host can be sending
                                    // it audio; holding a track open for it would be waste.
                                    controllerIndex =
                                        binding?.takeIf { it.registered }?.controllerIndex ?: NO_INDEX,
                                    streaming = streaming(summariesById[connId]),
                                    speakerEnabled = Feature.SPEAKER in (caps[slotId] ?: SlotCapabilities.NONE).live,
                                    playbackDeviceId = routing.forSlot(slotId).playbackDeviceId,
                                )
                            }
                        SpeakerPlayoutPolicy.plan(slots)
                    }
                }.distinctUntilChanged()

        // Only a satellite carries controller audio at all: the Moonlight control protocol has no
        // speaker channel and a Bluetooth HID gamepad has no audio endpoints to be. Unstable counts
        // as streaming for the same reason the gamepad reports keep flowing over it: the link is up,
        // it is just noisy, and tearing down an audio track on a blip would cost more than the
        // frames the blip drops.
        private fun streaming(summary: ConnectionSummary?): Boolean =
            summary != null &&
                summary.kind == ConnectionKind.SATELLITE &&
                (summary.live == LinkState.Connected || summary.live == LinkState.Unstable)

        private companion object {
            const val NO_HANDLE = -1
            const val NO_INDEX = -1
        }
    }
