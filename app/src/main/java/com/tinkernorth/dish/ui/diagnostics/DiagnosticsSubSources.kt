// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.diagnostics

import com.tinkernorth.dish.composer.MicCaptureComposer
import com.tinkernorth.dish.composer.SpeakerPlayoutComposer
import com.tinkernorth.dish.composer.WakeStateController
import com.tinkernorth.dish.core.jni.PhysicalInputNative
import com.tinkernorth.dish.hotpath.input.PhysicalGamepadRegistry
import com.tinkernorth.dish.hotpath.input.Transport
import com.tinkernorth.dish.source.audio.PadAudioFacts
import com.tinkernorth.dish.source.audio.PadAudioFactsStore
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
import com.tinkernorth.dish.source.store.StickTestRecord
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
        private val audioFacts: PadAudioFactsStore,
        private val json: Json,
    ) {
        private val linkTypes = ConcurrentHashMap<Int, BluetoothLinkType>()

        private data class DirectPadFacts(
            val latency: Map<Int, DeviceLatency>,
            val info: Map<Int, DirectDeviceInfo>,
            val urbErrors: Map<Int, Long>,
            val reportCounts: Map<Int, Long>,
        )

        private data class FrameworkPadFacts(
            val timing: Map<Int, FrameworkTimingSummary>,
            val eventCounts: Map<Int, Long>,
            val linkTypes: Map<Int, BluetoothLinkType>,
        )

        internal fun flow(ticks: Flow<Unit>): Flow<PadWorld> =
            combine(ticks, registry.devices, stickHistory.state, audioFacts.state) { _, devices, history, audio ->
                padWorldFor(devices, history, audio)
            }

        private fun padWorldFor(
            devices: Map<Int, PhysicalGamepadRegistry.Device>,
            history: Map<String, StickTestRecord>,
            audio: Map<Int, PadAudioFacts>,
        ): PadWorld {
            val direct = directPadFacts(devices.filterValues { it.isUsbSynthetic }.keys)
            val framework = frameworkPadFacts(devices.filterValues { !it.isUsbSynthetic })
            linkTypes.keys.retainAll(devices.keys)
            return PadWorld(
                deviceLatency = direct.latency,
                deviceInfo = direct.info,
                urbErrors = direct.urbErrors,
                reportCounts = direct.reportCounts + framework.eventCounts,
                frameworkTiming = framework.timing,
                btLinkTypes = framework.linkTypes,
                stickHistory = history,
                audio = audio,
            )
        }

        // A Direct pad is read straight from the native poller: its latency and URB counters.
        private fun directPadFacts(ids: Set<Int>): DirectPadFacts =
            DirectPadFacts(
                latency = ids.mapNotNull { id -> parseDeviceLatency(json, native.deviceLatencyJson(id))?.let { id to it } }.toMap(),
                info = ids.mapNotNull { id -> parseDeviceInfo(json, native.deviceInfoJson(id))?.let { id to it } }.toMap(),
                urbErrors = ids.associateWith { native.getDeviceUrbErrorCount(it) },
                reportCounts = ids.associateWith { native.getDeviceUrbCount(it) },
            )

        // A framework pad is read from the event timing store, and a Bluetooth one also carries
        // its link type, looked up once per device and forgotten with it.
        private fun frameworkPadFacts(devices: Map<Int, PhysicalGamepadRegistry.Device>): FrameworkPadFacts =
            FrameworkPadFacts(
                timing = devices.keys.mapNotNull { id -> timing.summary(id)?.let { id to it } }.toMap(),
                eventCounts = devices.keys.associateWith { native.getDeviceInputEventCount(it) },
                linkTypes =
                    devices
                        .filterValues { it.transport == Transport.Bluetooth }
                        .mapValues { (id, device) -> linkTypes.computeIfAbsent(id) { bluetoothLink.linkType(device.name) } },
            )
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
