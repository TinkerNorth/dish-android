// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.diagnostics

import com.tinkernorth.dish.composer.ConnectionKind
import com.tinkernorth.dish.composer.ConnectionSummary
import com.tinkernorth.dish.composer.LinkState
import com.tinkernorth.dish.core.model.Feature
import com.tinkernorth.dish.core.model.HostFeatureSet
import com.tinkernorth.dish.core.model.SlotCapabilities
import com.tinkernorth.dish.hotpath.input.PhysicalGamepadRegistry
import com.tinkernorth.dish.hotpath.input.Transport
import com.tinkernorth.dish.source.audio.MicCapturePlan
import com.tinkernorth.dish.source.audio.SpeakerTarget
import com.tinkernorth.dish.source.bluetooth.BluetoothGamepadRegistry
import com.tinkernorth.dish.source.bluetooth.BluetoothLinkType
import com.tinkernorth.dish.source.connection.SatelliteConnection
import com.tinkernorth.dish.source.connection.moonlight.MoonlightPad
import com.tinkernorth.dish.source.connection.moonlight.MoonlightSessionState
import com.tinkernorth.dish.source.inputrate.FrameworkTimingSummary
import com.tinkernorth.dish.source.inputrate.SlotInputRates
import com.tinkernorth.dish.source.sensor.BatteryValidator.BatterySample
import com.tinkernorth.dish.source.store.FeedbackActivity
import com.tinkernorth.dish.source.store.LinkHistory
import com.tinkernorth.dish.source.store.LinkHistoryState
import com.tinkernorth.dish.source.store.MoonlightHostFacts
import com.tinkernorth.dish.source.store.SatelliteMotionBackendStatus
import com.tinkernorth.dish.source.store.StickTestRecord
import com.tinkernorth.dish.source.system.BluetoothAdapterState
import com.tinkernorth.dish.source.system.BluetoothPermissionState
import com.tinkernorth.dish.source.system.WifiLink
import com.tinkernorth.dish.source.usb.UsbEndpointFacts
import com.tinkernorth.dish.ui.main.BatteryUi

enum class ControllerDiagState { CONNECTED, DISCONNECTING, TRANSITIONING, NEEDS_REPLUG }

data class SatelliteTelemetry(
    val vigemAvailable: Boolean,
    val activeControllers: Int,
    val epoch: Int,
    val activeBitmap: Int,
)

data class SatelliteSessionStats(
    val rttP50Ms: Double?,
    val rttP99Ms: Double?,
    val rttSamples: Int,
    val rttRecentMs: List<Float>,
    val pings: Long,
    val acks: Long,
    val missed: Int,
)

data class SatelliteSnapshot(
    val live: Boolean,
    val slots: Map<String, SatelliteConnection.SlotBinding>,
    val telemetry: SatelliteTelemetry?,
    val stats: SatelliteSessionStats? = null,
    val facts: SatelliteConnection.SessionFacts? = null,
    val packetsSent: Long = 0L,
    val closeReason: Int = -1,
    val slotSends: Map<Int, Long> = emptyMap(),
    val slotMotion: Map<Int, Long> = emptyMap(),
)

data class MoonlightSnapshot(
    val state: MoonlightSessionState,
    val rttMs: Long?,
    val pads: Map<String, MoonlightPad>,
    val reportsByNumber: Map<Int, Long>,
    val facts: MoonlightHostFacts?,
)

data class BtHostSnapshot(
    val state: BluetoothGamepadRegistry.SlotState,
    val reportsSent: Long,
)

data class RadioFacts(
    val wifi: WifiLink?,
    val wifiDrops: Int,
    val lowLatencyLockHeld: Boolean,
    val bluetoothAdapter: BluetoothAdapterState,
    val bluetoothPermission: BluetoothPermissionState,
    val usb: Map<Int, UsbEndpointFacts>,
) {
    companion object {
        val NONE =
            RadioFacts(
                wifi = null,
                wifiDrops = 0,
                lowLatencyLockHeld = false,
                bluetoothAdapter = BluetoothAdapterState.UNSUPPORTED,
                bluetoothPermission = BluetoothPermissionState.SATISFIED,
                usb = emptyMap(),
            )
    }
}

data class DeviceLatency(
    val samples: Int,
    val stage1P50Ms: Double?,
    val stage1P99Ms: Double?,
    val gapP50Ms: Double?,
    val gapP99Ms: Double?,
)

data class DirectDeviceInfo(
    val model: String,
    val parser: String,
    val init: String,
    val reportBytes: Int,
    val endpointOut: Boolean,
    val lastUrbStatus: Int,
)

data class PadFacts(
    val vendorId: Int,
    val productId: Int,
    val endpoint: UsbEndpointFacts?,
    val direct: DirectDeviceInfo?,
    val urbErrors: Long,
    val quirkBits: Int,
    val linkType: BluetoothLinkType,
    val reportCount: Long,
    val lastInputAtMs: Long,
    val directTiming: DeviceLatency?,
    val frameworkTiming: FrameworkTimingSummary?,
    val stickHistory: StickTestRecord?,
)

data class BoundHostDiag(
    val connectionId: String,
    val label: String,
    val kind: ConnectionKind,
    val live: LinkState,
    val typeId: Int?,
    val btProfile: String?,
    val slotIndex: Int? = null,
    val touchpadMode: String? = null,
    val registered: Boolean? = null,
    val streaming: Boolean? = null,
)

data class ControllerDiag(
    val slotId: String,
    val name: String,
    val isVirtual: Boolean,
    val transport: Transport?,
    val isUsbSynthetic: Boolean,
    val hasGyro: Boolean,
    val pollRateHz: Int,
    val gyroHz: Int,
    val state: ControllerDiagState,
    val battery: BatteryUi?,
    val host: BoundHostDiag?,
    val functions: List<Feature>,
    val facts: PadFacts? = null,
)

data class HostSlotDiag(
    val slotId: String,
    val controllerName: String,
    val typeId: Int?,
    val slotIndex: Int? = null,
    val touchpadMode: String? = null,
    val registered: Boolean? = null,
    val streaming: Boolean? = null,
)

data class HostDiag(
    val id: String,
    val label: String,
    val detail: String,
    val kind: ConnectionKind,
    val live: LinkState,
    val btProfile: String?,
    val telemetry: SatelliteTelemetry?,
    val features: HostFeatureSet?,
    val serverVersion: String?,
    val slots: List<HostSlotDiag>,
    val history: LinkHistory? = null,
    val satellite: SatelliteSnapshot? = null,
    val moonlight: MoonlightSnapshot? = null,
    val bluetooth: BtHostSnapshot? = null,
    val sameSubnet: Boolean? = null,
)

enum class BatterySource { PHONE, PAD, LOWEST_OF_BOTH }

enum class FeedbackTargetKind { PHONE, PAD_DIRECT, PAD_FRAMEWORK, NONE }

enum class MicSlotState { OFF, ARMED_MUTED, CAPTURING }

data class LatencyEstimate(
    val pollHalfMs: Double?,
    val phonePathMs: Double?,
    val networkOneWayMs: Double?,
) {
    val totalMs: Double?
        get() {
            val parts = listOf(pollHalfMs, phonePathMs, networkOneWayMs)
            if (parts.any { it == null }) return null
            return parts.sumOf { it ?: 0.0 }
        }
}

data class BindingDiag(
    val slotId: String,
    val controllerName: String,
    val isVirtual: Boolean,
    val host: BoundHostDiag,
    val boundSinceMs: Long?,
    val declaredCaps: Int?,
    val declaredTouchpadMode: String?,
    val applyResult: String?,
    val motionBackend: SatelliteMotionBackendStatus?,
    val caps: SlotCapabilities?,
    val packetsSent: Long?,
    val motionSent: Long?,
    val touchpadMode: String,
    val batterySource: BatterySource,
    val rumbleTarget: FeedbackTargetKind,
    val feedback: FeedbackActivity?,
    val micState: MicSlotState,
    val speakerPlaying: Boolean,
    val speakerDropped: Long,
    val latency: LatencyEstimate,
)

internal data class PadWorld(
    val deviceLatency: Map<Int, DeviceLatency> = emptyMap(),
    val deviceInfo: Map<Int, DirectDeviceInfo> = emptyMap(),
    val urbErrors: Map<Int, Long> = emptyMap(),
    val reportCounts: Map<Int, Long> = emptyMap(),
    val frameworkTiming: Map<Int, FrameworkTimingSummary> = emptyMap(),
    val btLinkTypes: Map<Int, BluetoothLinkType> = emptyMap(),
    val stickHistory: Map<String, StickTestRecord> = emptyMap(),
)

internal data class LinkWorld(
    val history: LinkHistoryState = LinkHistoryState(),
    val feedback: Map<String, FeedbackActivity> = emptyMap(),
    val moonlight: Map<String, MoonlightSnapshot> = emptyMap(),
    val btHosts: Map<String, BtHostSnapshot> = emptyMap(),
    val motionBackend: Map<Pair<String, String>, SatelliteMotionBackendStatus> = emptyMap(),
)

internal data class AudioWorld(
    val micPlan: MicCapturePlan = MicCapturePlan.IDLE,
    val micMuted: Map<String, Boolean> = emptyMap(),
    val speakerVoices: Map<Long, SpeakerTarget> = emptyMap(),
    val speakerDrops: Map<String, Long> = emptyMap(),
)

internal data class DiagnosticsWorld(
    val devices: Map<Int, PhysicalGamepadRegistry.Device>,
    val virtualName: String,
    val bindings: Map<String, String>,
    val summaries: List<ConnectionSummary>,
    val satellites: Map<String, SatelliteSnapshot>,
    val rates: Map<String, SlotInputRates>,
    val batteries: Map<String, BatterySample>,
    val caps: Map<String, SlotCapabilities>,
    val hostFeatures: Map<String, HostFeatureSet>,
    val serverVersions: Map<String, String>,
    val radios: RadioFacts = RadioFacts.NONE,
    val pads: PadWorld = PadWorld(),
    val links: LinkWorld = LinkWorld(),
    val audio: AudioWorld = AudioWorld(),
    val nowMs: Long = 0L,
) {
    val summariesById: Map<String, ConnectionSummary> get() = summaries.associateBy { it.id }
}

internal data class HostSections(
    val link: List<String>,
    val host: List<String>,
    val network: List<String>,
)

internal data class BindingSections(
    val binding: List<String>,
    val declared: List<String>,
    val capabilities: List<String>,
    val streams: List<String>,
    val feedback: List<String>,
    val latency: List<String>,
)
