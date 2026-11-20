// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.diagnostics

import android.content.Context
import android.os.Build
import com.tinkernorth.dish.R
import com.tinkernorth.dish.composer.CapabilityComposer
import com.tinkernorth.dish.composer.ConnectionCoordinator
import com.tinkernorth.dish.composer.ConnectionKind
import com.tinkernorth.dish.composer.ConnectionSummary
import com.tinkernorth.dish.core.controllerdb.ActiveControllerDb
import com.tinkernorth.dish.core.controllerdb.stringsLocaleForList
import com.tinkernorth.dish.core.jni.ControllerRepository
import com.tinkernorth.dish.core.model.HostFeatureSet
import com.tinkernorth.dish.core.model.SlotCapabilities
import com.tinkernorth.dish.hotpath.input.PhysicalGamepadRegistry
import com.tinkernorth.dish.source.connection.SatelliteConnection
import com.tinkernorth.dish.source.connection.SatelliteConnectionManager
import com.tinkernorth.dish.source.connection.SatelliteSessionState
import com.tinkernorth.dish.source.controllerdb.ControllerDbStatus
import com.tinkernorth.dish.source.controllerdb.ControllerDbUpdater
import com.tinkernorth.dish.source.inputrate.FrameworkInputTimingStore
import com.tinkernorth.dish.source.inputrate.InputRateStore
import com.tinkernorth.dish.source.inputrate.SlotInputRates
import com.tinkernorth.dish.source.sensor.BatteryValidator.BatterySample
import com.tinkernorth.dish.source.store.BatteryStatusStore
import com.tinkernorth.dish.source.store.SatelliteHostFacts
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
    val ignored: Map<Int, PhysicalGamepadRegistry.IgnoredInputDevice>,
)

internal data class FactInputs(
    val rates: Map<String, SlotInputRates>,
    val batteries: Map<String, BatterySample>,
    val caps: Map<String, SlotCapabilities>,
    val hostFeatures: Map<String, HostFeatureSet>,
)

internal data class ExtraInputs(
    val radios: RadioFacts,
    val pads: PadWorld,
    val links: LinkWorld,
    val audio: AudioWorld,
    val db: DbWorld,
)

// The per-slot facts the report overlays on the live topology.
class FactSources
    @Inject
    constructor(
        private val inputRates: InputRateStore,
        private val batteries: BatteryStatusStore,
        private val capabilities: CapabilityComposer,
        private val hostFacts: SatelliteHostFacts,
    ) {
        internal val flow: Flow<FactInputs> =
            combine(inputRates.state, batteries.samples, capabilities.state, hostFacts.features.state) { rates, bat, caps, hf ->
                FactInputs(rates.slots, bat, caps, hf)
            }
    }

// The side worlds (radios, pads, links, audio, the controller database), each polled on the
// shared telemetry tick or following its own source.
class ExtraSources
    @Inject
    constructor(
        private val radioSources: RadioSources,
        private val padSources: PadSources,
        private val linkSources: LinkSources,
        private val audioSources: AudioSources,
        private val dbSources: DbSources,
    ) {
        internal fun flow(ticks: Flow<Unit>): Flow<ExtraInputs> =
            combine(
                radioSources.flow(ticks),
                padSources.flow(ticks),
                linkSources.flow(ticks),
                audioSources.flow(ticks),
                dbSources.flow,
                ::ExtraInputs,
            )
    }

// The controller database as the report reads it, with the phone facts its issues match against.
class DbSources
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val controllerDb: ControllerDbUpdater,
    ) {
        internal val flow: Flow<DbWorld> get() = combine(controllerDb.status, controllerDb.active, ::dbWorldOf)

        private fun dbWorldOf(
            status: ControllerDbStatus,
            active: ActiveControllerDb?,
        ): DbWorld {
            val locales = context.resources.configuration.locales
            return DbWorld(
                status = status,
                active = active,
                stringsLocale = stringsLocaleForList((0 until locales.size()).map { locales[it].let { l -> l.language to l.country } }),
                deviceModel = Build.MODEL.orEmpty(),
                androidMajor = androidMajorOf(Build.VERSION.RELEASE.orEmpty()),
            )
        }
    }

class DiagnosticsSources
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val registry: PhysicalGamepadRegistry,
        private val hub: ConnectionCoordinator,
        private val satelliteSnapshots: SatelliteSnapshotSources,
        private val capabilities: CapabilityComposer,
        private val facts: FactSources,
        private val hostFacts: SatelliteHostFacts,
        private val timing: FrameworkInputTimingStore,
        private val extras: ExtraSources,
    ) {
        fun touchpadMode(slotId: String): String = capabilities.touchpadWireMode(slotId)

        private val telemetryTicks: Flow<Unit> =
            flow {
                while (true) {
                    emit(Unit)
                    delay(TELEMETRY_POLL_MS)
                }
            }

        val satellites: Flow<Map<String, SatelliteSnapshot>> = satelliteSnapshots.flow(telemetryTicks)

        internal val world: Flow<DiagnosticsWorld> =
            combine(
                combine(registry.devices, hub.bindings, hub.connections, satellites, registry.ignoredInputDevices, ::LiveInputs),
                facts.flow,
                extras.flow(telemetryTicks),
            ) { live, facts, extra ->
                DiagnosticsWorld(
                    devices = live.devices,
                    ignoredInputs = live.ignored.values.toList(),
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
                    db = extra.db,
                    nowMs = System.currentTimeMillis(),
                )
            }.onStart { timing.arm() }
                .onCompletion { timing.disarm() }

        private fun serverVersions(summaries: List<ConnectionSummary>): Map<String, String> =
            summaries
                .filter { it.kind == ConnectionKind.SATELLITE }
                .mapNotNull { summary ->
                    hostFacts.catalog
                        .cached(summary.id)
                        ?.serverVersion
                        ?.takeIf { it.isNotBlank() }
                        ?.let { summary.id to it }
                }.toMap()

        private companion object {
            const val TELEMETRY_POLL_MS = 1000L
        }
    }

// One snapshot per live satellite session: its FSM state, slot table, session facts and, while
// a socket is open, the native counters behind it, re-read on every telemetry tick.
class SatelliteSnapshotSources
    @Inject
    constructor(
        private val satellite: SatelliteConnectionManager,
        private val controllerRepo: ControllerRepository,
        private val json: Json,
    ) {
        @OptIn(ExperimentalCoroutinesApi::class)
        fun flow(ticks: Flow<Unit>): Flow<Map<String, SatelliteSnapshot>> =
            satellite.connections.flatMapLatest { conns ->
                if (conns.isEmpty()) return@flatMapLatest flowOf(emptyMap())
                val perConnection =
                    conns.map { (id, conn) ->
                        combine(conn.state, conn.slots, conn.sessionFacts, ticks) { state, slots, facts, _ ->
                            id to snapshotOf(conn, state, slots, facts)
                        }
                    }
                combine(perConnection) { it.toMap() }
            }

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
                messages = parseMessageCounts(json, controllerRepo.sessionMessageCountsJson(handle)),
            )
        }
    }

// "15" of Android 15, "13" of "13.0.1"; 0 for a release name that starts with no number.
internal fun androidMajorOf(release: String): Int = release.takeWhile { it.isDigit() }.toIntOrNull() ?: 0
