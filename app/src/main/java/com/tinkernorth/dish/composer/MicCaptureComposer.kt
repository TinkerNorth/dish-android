// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.composer

import com.tinkernorth.dish.architecture.abstracts.AbstractComposer
import com.tinkernorth.dish.core.model.Feature
import com.tinkernorth.dish.core.model.SlotCapabilities
import com.tinkernorth.dish.source.audio.MicCapturePlan
import com.tinkernorth.dish.source.audio.MicCapturePolicy
import com.tinkernorth.dish.source.audio.MicSlotInput
import com.tinkernorth.dish.source.store.MicMuteStore
import com.tinkernorth.dish.source.system.MicPermissionGate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The four moving facts behind the microphone, folded into one plan: what is bound and streaming,
 * what the capability model says a slot's emulated pad carries and the user switched on, whether
 * RECORD_AUDIO is granted, and what is muted.
 *
 * They are combined here rather than watched separately by the capture engine because they are one
 * decision. Any of the four going false has to stop the microphone, and four independent
 * collectors would each have a window where they disagreed. [MicCapturePolicy] holds the rule
 * itself, with nothing reactive in it.
 *
 * Read by both consumers of that decision: the capture engine ([MicCapturePlan.delivering]) and
 * the foreground service's type selection ([MicCapturePlan.armed]).
 */
@Singleton
class MicCaptureComposer
    @Inject
    constructor(
        private val hub: ConnectionCoordinator,
        private val capabilities: CapabilityComposer,
        private val micPermission: MicPermissionGate,
        private val micMute: MicMuteStore,
        scope: CoroutineScope,
    ) : AbstractComposer<MicCapturePlan>(scope, MicCapturePlan.IDLE) {
        override fun upstream(): Flow<MicCapturePlan> =
            combine(
                hub.bindings,
                hub.connections,
                capabilities.state,
                micPermission.state,
                micMute.state,
            ) { bindings, summaries, caps, granted, muted ->
                val summariesById = summaries.associateBy { it.id }
                val slots =
                    bindings.map { (slotId, connId) ->
                        MicSlotInput(
                            slotId = slotId,
                            connectionId = connId,
                            streaming = streaming(summariesById[connId]),
                            micEnabled = Feature.MIC in (caps[slotId] ?: SlotCapabilities.NONE).live,
                            muted = muted[slotId] ?: MicMuteStore.DEFAULT_MUTED,
                        )
                    }
                MicCapturePolicy.plan(slots, permissionGranted = granted)
            }.distinctUntilChanged()

        // Only a satellite carries controller audio at all: the Moonlight control protocol has no
        // microphone channel and a Bluetooth HID gamepad has no audio endpoints to be. Unstable
        // counts as streaming for the same reason the gamepad reports keep flowing over it: the
        // link is up, it is just noisy, and stopping the microphone on a blip would be worse than
        // the packets that blip costs.
        private fun streaming(summary: ConnectionSummary?): Boolean =
            summary != null &&
                summary.kind == ConnectionKind.SATELLITE &&
                (summary.live == LinkState.Connected || summary.live == LinkState.Unstable)
    }
