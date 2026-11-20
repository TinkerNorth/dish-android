// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.ui.diagnostics

import android.view.InputDevice
import com.tinkernorth.dish.composer.BundleBattery
import com.tinkernorth.dish.composer.BundleDevice
import com.tinkernorth.dish.composer.BundleReport
import com.tinkernorth.dish.composer.BundleWifi
import com.tinkernorth.dish.composer.ConnectionKind
import com.tinkernorth.dish.composer.HostSnapshot
import com.tinkernorth.dish.composer.ReportCategory
import com.tinkernorth.dish.composer.SnapshotReply
import com.tinkernorth.dish.composer.capabilitiesSnapshotOf
import com.tinkernorth.dish.composer.isOlderSatellite
import com.tinkernorth.dish.composer.moonlightSnapshotOf
import com.tinkernorth.dish.composer.offlineSatelliteSnapshot
import com.tinkernorth.dish.composer.olderSatelliteSnapshot
import com.tinkernorth.dish.composer.satelliteHostSnapshot
import com.tinkernorth.dish.core.jni.PhysicalInputNative
import com.tinkernorth.dish.source.audio.PlayoutLane
import com.tinkernorth.dish.source.connection.DiagnosticsAnswer
import com.tinkernorth.dish.source.connection.SatelliteConnectionManager
import com.tinkernorth.dish.source.store.FeedbackActivityStore
import com.tinkernorth.dish.source.store.FeedbackKind
import com.tinkernorth.dish.source.store.LaneActivity
import com.tinkernorth.dish.source.store.PadAudioRefusal
import com.tinkernorth.dish.source.store.PadAudioRefusalStore
import com.tinkernorth.dish.source.store.SatelliteHostFacts
import com.tinkernorth.dish.source.system.PhoneFactsReader
import com.tinkernorth.dish.source.system.WifiLink
import com.tinkernorth.dish.source.system.wifiBandForFrequency
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
import javax.inject.Inject
import com.tinkernorth.dish.composer.snapshotReplyOf as httpSnapshotReplyOf

// What the person chose on the report screen.
data class ReportDraft(
    val category: ReportCategory,
    val text: String,
    val slotId: String?,
)

// What the report adds to the world when it is prepared: the person's words, the controller they
// chose (whose capture alone rides along), and the facts read only at that moment.
internal data class BundleExtras(
    val report: BundleReport? = null,
    val reportSlotId: String? = null,
    val deviceStates: Map<Int, InputSnapshot> = emptyMap(),
    val inputSources: Map<Int, Int> = emptyMap(),
    val lanes: Map<String, Map<FeedbackKind, LaneActivity>> = emptyMap(),
    val refusals: Map<Pair<String, PlayoutLane>, PadAudioRefusal> = emptyMap(),
    val typeSlugs: Map<String, Map<Int, String>> = emptyMap(),
    val snapshots: Map<String, HostSnapshot> = emptyMap(),
    // Each satellite's address as the app reaches it, which its snapshot names as its alias.
    val hostAddresses: Map<String, String> = emptyMap(),
)

// How long a report waits for one host to describe its side of the session.
const val HOST_SNAPSHOT_TIMEOUT_MS = 2_000

// The facts a report reads only at the moment it is prepared: the phone's conditions, each pad's
// last state and declared sources, the feedback lanes, and what every host says about itself.
class ReportSources
    @Inject
    constructor(
        private val physicalInput: PhysicalInputNative,
        private val phone: PhoneFactsReader,
        private val feedback: FeedbackActivityStore,
        private val refusals: PadAudioRefusalStore,
        private val hostFacts: SatelliteHostFacts,
        private val satellite: SatelliteConnectionManager,
        private val json: Json,
    ) {
        internal fun device(wifi: WifiLink?): BundleDevice {
            val facts = phone.read()
            return deviceFacts().copy(
                battery = facts.batteryPercent?.let { BundleBattery(it, facts.charging) },
                thermal = facts.thermal,
                powerSave = facts.powerSave,
                wifi = wifi?.let(::bundleWifiOf),
                usbHost = facts.usbHost,
            )
        }

        internal suspend fun extras(
            overview: DiagnosticsViewModel.Overview,
            draft: ReportDraft?,
        ): BundleExtras {
            val deviceIds = overview.controllers.mapNotNull { it.slotId.toIntOrNull() }
            return BundleExtras(
                report = draft?.let { reportOf(it, overview.controllers) },
                reportSlotId = draft?.slotId,
                deviceStates = deviceStatesOf(deviceIds),
                inputSources = deviceIds.mapNotNull { id -> InputDevice.getDevice(id)?.let { id to it.sources } }.toMap(),
                lanes = feedback.lanes(),
                refusals = refusals.state.value,
                typeSlugs = overview.hosts.associate { it.id to typeSlugsOf(it.id) },
                snapshots = snapshotsOf(overview.hosts),
                hostAddresses =
                    overview.hosts
                        .mapNotNull { host ->
                            satellite
                                .get(host.id)
                                ?.server
                                ?.value
                                ?.ip
                                ?.let { host.id to it }
                        }.toMap(),
            )
        }

        private fun deviceStatesOf(deviceIds: List<Int>): Map<Int, InputSnapshot> {
            physicalInput.refreshDeviceStates()
            return deviceIds.mapNotNull { id -> parseInputSnapshot(json, physicalInput.deviceStateJson(id))?.let { id to it } }.toMap()
        }

        private fun typeSlugsOf(hostId: String): Map<Int, String> =
            hostFacts.catalog
                .cached(hostId)
                ?.controllerTypes
                .orEmpty()
                .filter { it.slug.isNotBlank() }
                .associate { it.id to it.slug }

        private suspend fun snapshotsOf(hosts: List<HostDiag>): Map<String, HostSnapshot> =
            coroutineScope {
                hosts.map { host -> async { host.id to snapshotOf(host) } }.awaitAll().toMap()
            }

        private suspend fun snapshotOf(host: HostDiag): HostSnapshot =
            when (host.kind) {
                ConnectionKind.SATELLITE -> satelliteSnapshotOf(host)
                ConnectionKind.MOONLIGHT -> HostSnapshot(moonlightSnapshotOf(host.moonlight?.facts), error = null)
                ConnectionKind.BLUETOOTH -> HostSnapshot.NONE
            }

        private suspend fun satelliteSnapshotOf(host: HostDiag): HostSnapshot {
            val caps = hostFacts.capabilities.lastFetched(host.id)
            val catalog = hostFacts.catalog.cached(host.id)
            val fallback = capabilitiesSnapshotOf(caps, catalog)
            val serverVersion = caps?.serverVersion?.takeIf { it.isNotBlank() } ?: host.serverVersion
            if (isOlderSatellite(serverVersion)) return olderSatelliteSnapshot(fallback)
            val isLive = host.satellite?.live == true
            if (!isLive) return offlineSatelliteSnapshot(fallback)
            val answer = satellite.connectionDiagnostics(host.id, HOST_SNAPSHOT_TIMEOUT_MS)
            return satelliteHostSnapshot(snapshotReplyOf(answer), fallback, json)
        }
    }

internal fun bundleWifiOf(link: WifiLink): BundleWifi =
    BundleWifi(band = wifiBandWire(wifiBandForFrequency(link.frequencyMhz)), rssiDbm = link.rssiDbm, linkSpeedMbps = link.linkSpeedMbps)

// The person's words as they go into the mail and the file: trimmed, nothing else changed.
internal fun reportOf(
    draft: ReportDraft,
    controllers: List<ControllerDiag>,
): BundleReport =
    BundleReport(
        category = draft.category,
        text = draft.text.trim(),
        controller = controllers.firstOrNull { it.slotId == draft.slotId }?.let(::reportControllerOf),
    )

// A satellite never asked is unreachable from here; one we hold no key for cannot be proven to.
internal fun snapshotReplyOf(answer: DiagnosticsAnswer): SnapshotReply =
    when (answer) {
        DiagnosticsAnswer.NotLive -> SnapshotReply.Unreachable
        DiagnosticsAnswer.NoKey -> SnapshotReply.NoCredentials
        is DiagnosticsAnswer.Asked -> httpSnapshotReplyOf(answer.reply)
    }
