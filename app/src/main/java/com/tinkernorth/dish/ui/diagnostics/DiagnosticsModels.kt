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
import com.tinkernorth.dish.source.connection.SatelliteConnection
import com.tinkernorth.dish.source.inputrate.SlotInputRates
import com.tinkernorth.dish.source.sensor.BatteryValidator.BatterySample
import com.tinkernorth.dish.ui.main.BatteryUi

enum class ControllerDiagState { CONNECTED, DISCONNECTING, TRANSITIONING, NEEDS_REPLUG }

data class SatelliteTelemetry(
    val vigemAvailable: Boolean,
    val activeControllers: Int,
    val epoch: Int,
    val activeBitmap: Int,
)

data class SatelliteSnapshot(
    val live: Boolean,
    val slots: Map<String, SatelliteConnection.SlotBinding>,
    val telemetry: SatelliteTelemetry?,
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
) {
    val summariesById: Map<String, ConnectionSummary> get() = summaries.associateBy { it.id }
}
