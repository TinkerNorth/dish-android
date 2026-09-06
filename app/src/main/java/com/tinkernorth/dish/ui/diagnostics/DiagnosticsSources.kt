// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.diagnostics

import android.content.Context
import com.tinkernorth.dish.R
import com.tinkernorth.dish.composer.CapabilityComposer
import com.tinkernorth.dish.composer.ConnectionCoordinator
import com.tinkernorth.dish.composer.ConnectionKind
import com.tinkernorth.dish.composer.ConnectionSummary
import com.tinkernorth.dish.core.jni.ControllerRepository
import com.tinkernorth.dish.core.model.HostFeatureSet
import com.tinkernorth.dish.core.model.SlotCapabilities
import com.tinkernorth.dish.hotpath.input.PhysicalGamepadRegistry
import com.tinkernorth.dish.repository.SatelliteCatalogRepository
import com.tinkernorth.dish.source.connection.SatelliteConnection
import com.tinkernorth.dish.source.connection.SatelliteConnectionManager
import com.tinkernorth.dish.source.connection.SatelliteSessionState
import com.tinkernorth.dish.source.inputrate.InputRateStore
import com.tinkernorth.dish.source.inputrate.SlotInputRates
import com.tinkernorth.dish.source.sensor.BatteryValidator.BatterySample
import com.tinkernorth.dish.source.store.BatteryStatusStore
import com.tinkernorth.dish.source.store.SatelliteHostFeaturesStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import javax.inject.Inject

private data class LiveInputs(
    val devices: Map<Int, PhysicalGamepadRegistry.Device>,
    val bindings: Map<String, String>,
    val summaries: List<ConnectionSummary>,
    val satellites: Map<String, SatelliteSnapshot>,
)

private data class FactInputs(
    val rates: Map<String, SlotInputRates>,
    val batteries: Map<String, BatterySample>,
    val caps: Map<String, SlotCapabilities>,
    val hostFeatures: Map<String, HostFeatureSet>,
)

@Suppress("LongParameterList")
class DiagnosticsSources
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val registry: PhysicalGamepadRegistry,
        private val hub: ConnectionCoordinator,
        private val satellite: SatelliteConnectionManager,
        private val controllerRepo: ControllerRepository,
        private val capabilities: CapabilityComposer,
        private val inputRates: InputRateStore,
        private val batteries: BatteryStatusStore,
        private val hostFeatures: SatelliteHostFeaturesStore,
        private val catalogRepo: SatelliteCatalogRepository,
    ) {
        fun touchpadMode(slotId: String): String = capabilities.touchpadWireMode(slotId)

        private val telemetryTicks: Flow<Unit> =
            flow {
                while (true) {
                    emit(Unit)
                    delay(TELEMETRY_POLL_MS)
                }
            }

        @OptIn(ExperimentalCoroutinesApi::class)
        val satellites: Flow<Map<String, SatelliteSnapshot>> =
            satellite.connections.flatMapLatest { conns ->
                if (conns.isEmpty()) return@flatMapLatest flowOf(emptyMap())
                val perConnection =
                    conns.map { (id, conn) ->
                        combine(conn.state, conn.slots, telemetryTicks) { state, slots, _ ->
                            id to SatelliteSnapshot(state == SatelliteSessionState.Live, slots, telemetryOf(conn))
                        }
                    }
                combine(perConnection) { it.toMap() }
            }

        internal val world: Flow<DiagnosticsWorld> =
            combine(
                combine(registry.devices, hub.bindings, hub.connections, satellites, ::LiveInputs),
                combine(inputRates.state, batteries.samples, capabilities.state, hostFeatures.state) { rates, bat, caps, hf ->
                    FactInputs(rates.slots, bat, caps, hf)
                },
            ) { live, facts ->
                DiagnosticsWorld(
                    devices = live.devices,
                    virtualName = context.getString(R.string.default_virtual_controller_name),
                    bindings = live.bindings,
                    summaries = live.summaries,
                    satellites = live.satellites,
                    rates = facts.rates,
                    batteries = facts.batteries,
                    caps = facts.caps,
                    hostFeatures = facts.hostFeatures,
                    serverVersions = serverVersions(live.summaries),
                )
            }

        private fun telemetryOf(conn: SatelliteConnection): SatelliteTelemetry? {
            val handle = conn.handle
            if (handle < 0) return null
            return SatelliteTelemetry(
                vigemAvailable = controllerRepo.getVigemAvailable(handle) > 0,
                activeControllers = controllerRepo.getActiveControllerCount(handle),
                epoch = controllerRepo.getServerEpoch(handle),
                activeBitmap = controllerRepo.getActiveBitmap(handle),
            )
        }

        private fun serverVersions(summaries: List<ConnectionSummary>): Map<String, String> =
            summaries
                .filter { it.kind == ConnectionKind.SATELLITE }
                .mapNotNull { summary ->
                    catalogRepo
                        .cached(summary.id)
                        ?.serverVersion
                        ?.takeIf { it.isNotBlank() }
                        ?.let { summary.id to it }
                }.toMap()

        private companion object {
            const val TELEMETRY_POLL_MS = 1000L
        }
    }
