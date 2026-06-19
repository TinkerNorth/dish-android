// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.composer

import com.tinkernorth.dish.architecture.abstracts.AbstractComposer
import com.tinkernorth.dish.core.jni.PhysicalInputNative
import com.tinkernorth.dish.core.model.CapabilitySet
import com.tinkernorth.dish.core.model.Feature
import com.tinkernorth.dish.core.model.HostFeatureSet
import com.tinkernorth.dish.core.model.SlotCapabilities
import com.tinkernorth.dish.hotpath.input.PhysicalGamepadRegistry
import com.tinkernorth.dish.repository.SatelliteCatalogRepository
import com.tinkernorth.dish.repository.TouchpadModeValue
import com.tinkernorth.dish.source.sensor.PhoneMotionAvailability
import com.tinkernorth.dish.source.store.MotionEnabledStore
import com.tinkernorth.dish.source.store.RumbleEnabledStore
import com.tinkernorth.dish.source.store.SatelliteHostFeaturesStore
import com.tinkernorth.dish.source.store.SatelliteHostRuntimeStore
import com.tinkernorth.dish.source.store.SatelliteMotionBackendStatus
import com.tinkernorth.dish.source.store.SatelliteMotionBackendStatusStore
import com.tinkernorth.dish.source.store.TouchpadModeStore
import com.tinkernorth.dish.ui.main.VIRTUAL_SLOT_ID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import javax.inject.Inject
import javax.inject.Singleton

@Suppress("UNCHECKED_CAST", "LongParameterList")
private inline fun <T1, T2, T3, T4, T5, T6, T7, T8, R> combine8(
    f1: Flow<T1>,
    f2: Flow<T2>,
    f3: Flow<T3>,
    f4: Flow<T4>,
    f5: Flow<T5>,
    f6: Flow<T6>,
    f7: Flow<T7>,
    f8: Flow<T8>,
    crossinline transform: suspend (T1, T2, T3, T4, T5, T6, T7, T8) -> R,
): Flow<R> =
    combine(f1, f2, f3, f4, f5, f6, f7, f8) { args ->
        transform(
            args[0] as T1,
            args[1] as T2,
            args[2] as T3,
            args[3] as T4,
            args[4] as T5,
            args[5] as T6,
            args[6] as T7,
            args[7] as T8,
        )
    }

@Singleton
class CapabilityComposer
    @Inject
    @Suppress("LongParameterList")
    constructor(
        phoneAvailability: PhoneMotionAvailability,
        private val registry: PhysicalGamepadRegistry,
        private val hub: ConnectionCoordinator,
        private val native: PhysicalInputNative,
        private val motionEnabled: MotionEnabledStore,
        private val rumbleEnabled: RumbleEnabledStore,
        private val touchpadMode: TouchpadModeStore,
        private val hostFeatures: SatelliteHostFeaturesStore,
        private val motionBackend: SatelliteMotionBackendStatusStore,
        private val hostRuntime: SatelliteHostRuntimeStore,
        private val catalogRepo: SatelliteCatalogRepository,
        scope: CoroutineScope,
    ) : AbstractComposer<Map<String, SlotCapabilities>>(scope, emptyMap()) {
        // Fixed hardware fact, captured at construction so the combine arity stays at the eight live flows.
        private val phoneHasGyro: Boolean = phoneAvailability.hasGyro

        override fun upstream(): Flow<Map<String, SlotCapabilities>> =
            combine8(
                registry.devices,
                hub.bindings,
                hub.connections,
                motionEnabled.state,
                rumbleEnabled.state,
                touchpadMode.state,
                hostFeatures.state,
                motionBackend.state,
            ) { devices, bindings, summaries, motionMap, rumbleMap, touchpadMap, hostMap, backendMap ->
                val summariesById = summaries.associateBy { it.id }
                val out = HashMap<String, SlotCapabilities>(devices.size + 1)

                out[VIRTUAL_SLOT_ID] =
                    slotFor(
                        slotId = VIRTUAL_SLOT_ID,
                        controller = virtualControllerLayer(),
                        bindings = bindings,
                        summariesById = summariesById,
                        motionMap = motionMap,
                        rumbleMap = rumbleMap,
                        touchpadMap = touchpadMap,
                        hostMap = hostMap,
                        backendMap = backendMap,
                    )

                for ((deviceId, device) in devices) {
                    val slotId = deviceId.toString()
                    out[slotId] =
                        slotFor(
                            slotId = slotId,
                            controller = deviceControllerLayer(device),
                            bindings = bindings,
                            summariesById = summariesById,
                            motionMap = motionMap,
                            rumbleMap = rumbleMap,
                            touchpadMap = touchpadMap,
                            hostMap = hostMap,
                            backendMap = backendMap,
                        )
                }
                out
            }.distinctUntilChanged()

        // The live per-slot map is the reactive read-surface for consumers that show a
        // BOUND slot's capabilities (dashboard cards, overlay), migrated onto it
        // incrementally. Draft-editing screens that preview an unsaved type/host use
        // capabilityForCandidate, since the bound state does not reflect the draft.
        fun capabilityFor(slotId: String): SlotCapabilities = state.value[slotId] ?: SlotCapabilities.NONE

        /**
         * Inherent availability for [slotId] against a hypothetical host, ignoring the current binding.
         * The report table reads available/inputOk/destinationOk/typeOk, so userEnabled is forced full:
         * the table shows what the path could carry, not what the user has toggled on.
         */
        fun capabilityForCandidate(
            slotId: String,
            candidateType: Int,
            candidateHostKind: ConnectionKind,
            candidateHostId: String?,
        ): SlotCapabilities =
            CapabilityResolver.resolve(
                controller = liveControllerLayer(slotId),
                transport = TransportProfiles.forKind(candidateHostKind),
                type = typeCapabilitiesFor(candidateType, candidateHostId),
                host = candidateHostLayer(candidateHostKind, candidateHostId),
                userEnabled = ALL,
                // Pre-bind runtime probe: lets the report show a feature present-but-down
                // (e.g. motion backend missing) before the user commits.
                runtimeDown = candidateRuntimeDownLayer(candidateHostKind, candidateHostId),
            )

        @Suppress("LongParameterList")
        private fun slotFor(
            slotId: String,
            controller: CapabilitySet,
            bindings: Map<String, String>,
            summariesById: Map<String, ConnectionSummary>,
            motionMap: Map<String, Boolean>,
            rumbleMap: Map<String, Boolean>,
            touchpadMap: Map<String, String>,
            hostMap: Map<String, HostFeatureSet>,
            backendMap: Map<Pair<String, String>, SatelliteMotionBackendStatus>,
        ): SlotCapabilities {
            val connId = bindings[slotId]
            val summary = connId?.let { summariesById[it] }
            val motionOn = motionMap[slotId] ?: MotionEnabledStore.DEFAULT_ENABLED
            val rumbleOn = rumbleMap[slotId] ?: RumbleEnabledStore.DEFAULT_ENABLED
            val touchpad = connId?.let { touchpadMap[it] } ?: TouchpadModeValue.OFF
            return CapabilityResolver.resolve(
                controller = controller,
                transport = transportLayer(summary),
                type = typeLayer(slotId, summary),
                host = hostLayer(connId, summary, hostMap),
                userEnabled = CapabilityResolver.userEnabledCapabilities(motionOn, rumbleOn, touchpad),
                runtimeDown = runtimeDownLayer(connId, slotId, backendMap),
            )
        }

        private fun virtualControllerLayer(): CapabilitySet {
            // The phone IS the input: its screen sources the touchpad and mouse and its
            // vibrator actuates rumble, so all three ride regardless of any pad. Motion
            // rides only if the phone has a gyro.
            val out =
                mutableSetOf(
                    Feature.GAMEPAD,
                    Feature.ANALOG_TRIGGERS,
                    Feature.TOUCHPAD,
                    Feature.MOUSE,
                    Feature.RUMBLE,
                )
            if (phoneHasGyro) out += Feature.MOTION
            return CapabilitySet(out)
        }

        private fun deviceControllerLayer(device: PhysicalGamepadRegistry.Device): CapabilitySet {
            // The pad supplies the gamepad axes; the phone screen still sources touchpad
            // and mouse alongside it. Rumble needs the pad's OWN motor: routing never
            // falls back to the phone for a physical controller, so a motorless pad has
            // no rumble.
            val out = mutableSetOf(Feature.GAMEPAD, Feature.ANALOG_TRIGGERS, Feature.TOUCHPAD, Feature.MOUSE)
            if (device.hasGyro || native.modelHasImu(device.vendorId, device.productId)) out += Feature.MOTION
            if (device.hasRumble || native.modelHasRumble(device.vendorId, device.productId)) out += Feature.RUMBLE
            return CapabilitySet(out)
        }

        // The candidate path reuses the same controller layer the live map already derived for the slot.
        private fun liveControllerLayer(slotId: String): CapabilitySet {
            if (slotId == VIRTUAL_SLOT_ID) return virtualControllerLayer()
            val device = slotId.toIntOrNull()?.let { registry.devices.value[it] } ?: return CapabilitySet.EMPTY
            return deviceControllerLayer(device)
        }

        // Unbound slots get a permissive transport so candidate/report queries see inherent availability.
        private fun transportLayer(summary: ConnectionSummary?): CapabilitySet = summary?.let { TransportProfiles.forKind(it.kind) } ?: ALL

        private fun typeLayer(
            slotId: String,
            summary: ConnectionSummary?,
        ): CapabilitySet {
            if (summary == null) return ALL
            if (summary.kind != ConnectionKind.SATELLITE) return ALL
            val typeId = summary.satelliteControllerTypes[slotId] ?: return ALL
            return typeCapabilitiesFor(typeId, summary.id)
        }

        // The satellite's own per-type features from its cached catalog are the source
        // of truth; the bundled set covers an unfetched catalog or the slugs we ship.
        private fun typeCapabilitiesFor(
            typeId: Int,
            connId: String?,
        ): CapabilitySet {
            val catalogType =
                connId
                    ?.let { catalogRepo.cached(it) }
                    ?.controllerTypes
                    ?.firstOrNull { it.id == typeId }
            return catalogType?.let { CapabilityResolver.typeCapabilities(it) }
                ?: BundledCatalog.typeCapabilitiesById(typeId)
        }

        // BLUETOOTH limits via transport, so its host layer is permissive; an unbound slot is too.
        private fun hostLayer(
            connId: String?,
            summary: ConnectionSummary?,
            hostMap: Map<String, HostFeatureSet>,
        ): CapabilitySet {
            if (summary == null || connId == null) return ALL
            if (summary.kind != ConnectionKind.SATELLITE) return ALL
            return (hostMap[connId] ?: HostFeatureSet.SATELLITE_DEFAULT).toCapabilitySet()
        }

        private fun candidateHostLayer(
            kind: ConnectionKind,
            hostId: String?,
        ): CapabilitySet {
            if (kind != ConnectionKind.SATELLITE) return ALL
            val features = hostId?.let { hostFeatures.featuresFor(it) } ?: HostFeatureSet.SATELLITE_DEFAULT
            return features.toCapabilitySet()
        }

        private fun runtimeDownLayer(
            connId: String?,
            slotId: String,
            backendMap: Map<Pair<String, String>, SatelliteMotionBackendStatus>,
        ): CapabilitySet {
            connId ?: return CapabilitySet.EMPTY
            val status = backendMap[connId to slotId] ?: return CapabilitySet.EMPTY
            return if (!status.backendOk) CapabilitySet.of(Feature.MOTION) else CapabilitySet.EMPTY
        }

        // Pre-bind sibling of runtimeDownLayer: the post-bind per-controller backend status
        // does not exist yet, so the candidate report reads the host runtime probe instead.
        // Same MOTION-down semantics so pre-bind and post-bind agree.
        private fun candidateRuntimeDownLayer(
            kind: ConnectionKind,
            hostId: String?,
        ): CapabilitySet {
            if (kind != ConnectionKind.SATELLITE) return CapabilitySet.EMPTY
            val runtime = hostId?.let { hostRuntime.runtimeFor(it) } ?: return CapabilitySet.EMPTY
            return if (!runtime.motionBackendOk) CapabilitySet.of(Feature.MOTION) else CapabilitySet.EMPTY
        }

        private companion object {
            val ALL = CapabilitySet(Feature.entries.toSet())
        }
    }
