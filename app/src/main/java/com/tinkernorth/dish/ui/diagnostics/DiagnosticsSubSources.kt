// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.diagnostics

import com.tinkernorth.dish.composer.MicCaptureComposer
import com.tinkernorth.dish.composer.SpeakerPlayoutComposer
import com.tinkernorth.dish.composer.WakeStateController
import com.tinkernorth.dish.core.jni.PhysicalInputNative
import com.tinkernorth.dish.hotpath.input.PhysicalGamepadRegistry
import com.tinkernorth.dish.hotpath.input.Transport
import com.tinkernorth.dish.source.audio.SpeakerEngine
import com.tinkernorth.dish.source.bluetooth.BluetoothGamepadRegistry
import com.tinkernorth.dish.source.bluetooth.BluetoothLinkType
import com.tinkernorth.dish.source.bluetooth.BluetoothPadLinkReader
import com.tinkernorth.dish.source.connection.moonlight.MoonlightConnection
import com.tinkernorth.dish.source.connection.moonlight.MoonlightConnectionManager
import com.tinkernorth.dish.source.inputrate.FrameworkInputTimingStore
import com.tinkernorth.dish.source.inputrate.FrameworkTimingSummary
import com.tinkernorth.dish.source.store.FeedbackActivityStore
import com.tinkernorth.dish.source.store.LinkHistoryStore
import com.tinkernorth.dish.source.store.MicMuteStore
import com.tinkernorth.dish.source.store.MoonlightHostFactsStore
import com.tinkernorth.dish.source.store.SatelliteMotionBackendStatusStore
import com.tinkernorth.dish.source.store.StickTestHistoryStore
import com.tinkernorth.dish.source.system.BluetoothAdapterStateObserver
import com.tinkernorth.dish.source.system.BluetoothPermissionStateObserver
import com.tinkernorth.dish.source.system.NetworkStateObserver
import com.tinkernorth.dish.source.system.WifiLinkSource
import com.tinkernorth.dish.source.usb.UsbDescriptorStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

class RadioSources
    @Inject
    constructor(
        private val wifi: WifiLinkSource,
        private val network: NetworkStateObserver,
        private val wakeState: WakeStateController,
        private val bluetoothAdapter: BluetoothAdapterStateObserver,
        private val bluetoothPermission: BluetoothPermissionStateObserver,
        private val usbDescriptors: UsbDescriptorStore,
    ) {
        fun flow(ticks: Flow<Unit>): Flow<RadioFacts> =
            combine(
                combine(ticks, network.wifiDrops, wakeState.shouldKeepScreenOn) { _, drops, held -> Triple(wifi.read(), drops, held) },
                bluetoothAdapter.state,
                bluetoothPermission.state,
                usbDescriptors.state,
            ) { (link, drops, held), adapter, permission, usb ->
                RadioFacts(
                    wifi = link,
                    wifiDrops = drops,
                    lowLatencyLockHeld = held,
                    bluetoothAdapter = adapter,
                    bluetoothPermission = permission,
                    usb = usb,
                )
            }
    }

class PadSources
    @Inject
    constructor(
        private val registry: PhysicalGamepadRegistry,
        private val native: PhysicalInputNative,
        private val timing: FrameworkInputTimingStore,
        private val bluetoothLink: BluetoothPadLinkReader,
        private val stickHistory: StickTestHistoryStore,
        private val json: Json,
    ) {
        private val linkTypes = ConcurrentHashMap<Int, BluetoothLinkType>()

        internal fun flow(ticks: Flow<Unit>): Flow<PadWorld> =
            combine(ticks, registry.devices, stickHistory.state) { _, devices, history ->
                val latency = HashMap<Int, DeviceLatency>()
                val info = HashMap<Int, DirectDeviceInfo>()
                val errors = HashMap<Int, Long>()
                val counts = HashMap<Int, Long>()
                val framework = HashMap<Int, FrameworkTimingSummary>()
                val links = HashMap<Int, BluetoothLinkType>()
                for ((id, device) in devices) {
                    if (device.isUsbSynthetic) {
                        parseDeviceLatency(json, native.deviceLatencyJson(id))?.let { latency[id] = it }
                        parseDeviceInfo(json, native.deviceInfoJson(id))?.let { info[id] = it }
                        errors[id] = native.getDeviceUrbErrorCount(id)
                        counts[id] = native.getDeviceUrbCount(id)
                    } else {
                        timing.summary(id)?.let { framework[id] = it }
                        counts[id] = native.getDeviceInputEventCount(id)
                        if (device.transport == Transport.Bluetooth) {
                            links[id] = linkTypes.computeIfAbsent(id) { bluetoothLink.linkType(device.name) }
                        }
                    }
                }
                linkTypes.keys.retainAll(devices.keys)
                PadWorld(latency, info, errors, counts, framework, links, history)
            }
    }

class LinkSources
    @Inject
    constructor(
        private val history: LinkHistoryStore,
        private val feedback: FeedbackActivityStore,
        private val moonlight: MoonlightConnectionManager,
        private val moonlightFacts: MoonlightHostFactsStore,
        private val bluetoothHosts: BluetoothGamepadRegistry,
        private val motionBackend: SatelliteMotionBackendStatusStore,
    ) {
        internal fun flow(ticks: Flow<Unit>): Flow<LinkWorld> =
            combine(
                history.state,
                ticks.map { feedback.snapshot() },
                moonlightSnapshots(ticks),
                combine(bluetoothHosts.states, ticks) { states, _ ->
                    states.mapValues { (id, state) -> BtHostSnapshot(state, bluetoothHosts.reportsSent(id)) }
                },
                motionBackend.state,
                ::LinkWorld,
            )

        @OptIn(ExperimentalCoroutinesApi::class)
        private fun moonlightSnapshots(ticks: Flow<Unit>): Flow<Map<String, MoonlightSnapshot>> =
            moonlight.connections.flatMapLatest { conns ->
                if (conns.isEmpty()) return@flatMapLatest flowOf(emptyMap())
                val perHost = conns.map { (id, conn) -> snapshotOf(id, conn, ticks) }
                combine(perHost) { it.toMap() }
            }

        private fun snapshotOf(
            id: String,
            conn: MoonlightConnection,
            ticks: Flow<Unit>,
        ): Flow<Pair<String, MoonlightSnapshot>> =
            combine(conn.state, conn.pads, moonlightFacts.state, ticks) { state, pads, facts, _ ->
                id to
                    MoonlightSnapshot(
                        state = state,
                        rttMs = conn.controlRoundTripMs(),
                        pads = pads,
                        reportsByNumber = pads.values.associate { it.number to conn.reportsSentFor(it.number) },
                        facts = facts[id],
                    )
            }
    }

class AudioSources
    @Inject
    constructor(
        private val micPlans: MicCaptureComposer,
        private val micMute: MicMuteStore,
        private val speakerPlans: SpeakerPlayoutComposer,
        private val speaker: SpeakerEngine,
    ) {
        internal fun flow(ticks: Flow<Unit>): Flow<AudioWorld> =
            combine(micPlans.state, micMute.state, speakerPlans.state, ticks) { mic, muted, plan, _ ->
                AudioWorld(
                    micPlan = mic,
                    micMuted = muted,
                    speakerVoices = plan.voices,
                    speakerDrops =
                        plan.voices.values.associate { it.slotId to speaker.droppedSamplesFor(it.sessionHandle, it.controllerIndex) },
                )
            }
    }
