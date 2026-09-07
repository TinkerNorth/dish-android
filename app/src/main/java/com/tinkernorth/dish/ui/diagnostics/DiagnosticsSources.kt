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
import com.tinkernorth.dish.source.inputrate.FrameworkInputTimingStore
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
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.serialization.json.Json
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

private data class ExtraInputs(
    val radios: RadioFacts,
    val pads: PadWorld,
    val links: LinkWorld,
    val audio: AudioWorld,
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
        private val timing: FrameworkInputTimingStore,
        private val json: Json,
        radioSources: RadioSources,
        padSources: PadSources,
        linkSources: LinkSources,
        audioSources: AudioSources,
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
                        combine(conn.state, conn.slots, conn.sessionFacts, telemetryTicks) { state, slots, facts, _ ->
                            id to snapshotOf(conn, state, slots, facts)
                        }
                    }
                combine(perConnection) { it.toMap() }
            }

        private val extras: Flow<ExtraInputs> =
            combine(
                radioSources.flow(telemetryTicks),
                padSources.flow(telemetryTicks),
                linkSources.flow(telemetryTicks),
                audioSources.flow(telemetryTicks),
                ::ExtraInputs,
            )

        internal val world: Flow<DiagnosticsWorld> =
            combine(
                combine(registry.devices, hub.bindings, hub.connections, satellites, ::LiveInputs),
                combine(inputRates.state, batteries.samples, capabilities.state, hostFeatures.state) { rates, bat, caps, hf ->
                    FactInputs(rates.slots, bat, caps, hf)
                },
                extras,
            ) { live, facts, extra ->
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
                    radios = extra.radios,
                    pads = extra.pads,
                    links = extra.links,
                    audio = extra.audio,
                    nowMs = System.currentTimeMillis(),
                )
            }.onStart { timing.arm() }
                .onCompletion { timing.disarm() }

        private fun snapshotOf(
            conn: SatelliteConnection,
            state: SatelliteSessionState,
            slots: Map<String, SatelliteConnection.SlotBinding>,
            facts: SatelliteConnection.SessionFacts?,
        ): SatelliteSnapshot {
            val handle = conn.handle
            if (handle < 0) {
                return SatelliteSnapshot(live = state == SatelliteSessionState.Live, slots = slots, telemetry = null, facts = facts)
            }
            val telemetry =
                SatelliteTelemetry(
                    vigemAvailable = controllerRepo.getVigemAvailable(handle) > 0,
                    activeControllers = controllerRepo.getActiveControllerCount(handle),
                    epoch = controllerRepo.getServerEpoch(handle),
                    activeBitmap = controllerRepo.getActiveBitmap(handle),
                )
            val indices = slots.values.map { it.controllerIndex }.distinct()
            return SatelliteSnapshot(
                live = state == SatelliteSessionState.Live,
                slots = slots,
                telemetry = telemetry,
                stats = parseSessionStats(json, controllerRepo.sessionStatsJson(handle)),
                facts = facts,
                packetsSent = controllerRepo.getSendCounter(handle),
                closeReason = controllerRepo.getSessionCloseReason(handle),
                slotSends = indices.associateWith { controllerRepo.getSlotSendCount(handle, it) },
                slotMotion = indices.associateWith { controllerRepo.getSlotMotionCount(handle, it) },
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
