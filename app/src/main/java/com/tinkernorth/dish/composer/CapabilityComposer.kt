// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.composer

import com.tinkernorth.dish.architecture.abstracts.AbstractComposer
import com.tinkernorth.dish.core.jni.PhysicalInputNative
import com.tinkernorth.dish.core.model.CapabilitySet
import com.tinkernorth.dish.core.model.Feature
import com.tinkernorth.dish.core.model.HostFeatureSet
import com.tinkernorth.dish.core.model.SlotCapabilities
import com.tinkernorth.dish.core.net.ControllerDescriptor
import com.tinkernorth.dish.core.net.moonlight.MoonlightEmulatedType
import com.tinkernorth.dish.hotpath.input.PhysicalGamepadRegistry
import com.tinkernorth.dish.repository.SatelliteCatalogRepository
import com.tinkernorth.dish.repository.TouchpadModeValue
import com.tinkernorth.dish.source.audio.PadAudioRoutes
import com.tinkernorth.dish.source.sensor.PhoneMotionAvailability
import com.tinkernorth.dish.source.store.MicEnabledStore
import com.tinkernorth.dish.source.store.MotionEnabledStore
import com.tinkernorth.dish.source.store.MouseSurfaceStore
import com.tinkernorth.dish.source.store.RumbleEnabledStore
import com.tinkernorth.dish.source.store.SatelliteHostFeaturesStore
import com.tinkernorth.dish.source.store.SatelliteHostRuntimeStore
import com.tinkernorth.dish.source.store.SatelliteMotionBackendStatus
import com.tinkernorth.dish.source.store.SatelliteMotionBackendStatusStore
import com.tinkernorth.dish.source.store.SpeakerEnabledStore
import com.tinkernorth.dish.ui.main.VIRTUAL_SLOT_ID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import javax.inject.Inject
import javax.inject.Singleton

data class InputFunctions(
    val known: Boolean,
    val rumble: Boolean,
    val gyro: Boolean,
    val touchpad: Boolean,
)

// The per-slot audio toggles, folded into one upstream so the composer's combine keeps
// its arity. The pad route table rides the same fold without being carried: the
// controller layer reads it through PadAudioRoutes the way it reads the native model
// tables, and it is here so a pad's endpoints appearing or vanishing re-publishes.
private data class AudioToggles(
    val mic: Map<String, Boolean>,
    val speaker: Map<String, Boolean>,
)

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
        private val micEnabled: MicEnabledStore,
        private val speakerEnabled: SpeakerEnabledStore,
        private val padAudioRoutes: PadAudioRoutes,
        private val mouseSurface: MouseSurfaceStore,
        private val hostFeatures: SatelliteHostFeaturesStore,
        private val motionBackend: SatelliteMotionBackendStatusStore,
        private val hostRuntime: SatelliteHostRuntimeStore,
        private val catalogRepo: SatelliteCatalogRepository,
        scope: CoroutineScope,
    ) : AbstractComposer<Map<String, SlotCapabilities>>(scope, emptyMap()) {
        // Fixed hardware fact, captured at construction so the combine arity stays at the eight live flows.
        private val phoneHasGyro: Boolean = phoneAvailability.hasGyro

        private val audioToggles: Flow<AudioToggles> =
            combine(micEnabled.state, speakerEnabled.state, padAudioRoutes.state) { mic, speaker, _ ->
                AudioToggles(mic, speaker)
            }

        override fun upstream(): Flow<Map<String, SlotCapabilities>> =
            combine8(
                registry.devices,
                hub.bindings,
                hub.connections,
                motionEnabled.state,
                rumbleEnabled.state,
                hostFeatures.state,
                motionBackend.state,
                audioToggles,
            ) { devices, bindings, summaries, motionMap, rumbleMap, hostMap, backendMap, audioMap ->
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
                        hostMap = hostMap,
                        backendMap = backendMap,
                        audioMap = audioMap,
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
                            hostMap = hostMap,
                            backendMap = backendMap,
                            audioMap = audioMap,
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
         * The CAP_MOTION bit the satellite descriptor carries for [slotId]: motion gated on the
         * input gyro and the user toggle, NOT on link-liveness (a reconnect must recover motion
         * without a re-handshake). This is the per-connection lambda value SatelliteConnection ORs
         * onto BASE_CAPABILITIES; it is a different projection from the `available`/`live` views.
         */
        fun motionWireBit(slotId: String): Int =
            if (capabilityFor(slotId).let { Feature.MOTION in it.controller && Feature.MOTION in it.userEnabled }) {
                ControllerDescriptor.CAP_MOTION
            } else {
                0
            }

        /**
         * The descriptor's touchpadMode for [slotId], pulled at descriptor-build time like
         * [motionWireBit]. Reads the source stores directly instead of the composed [state]:
         * the pick is keyed by the bound connection, and during a bind the store writes land
         * synchronously before declareSlot while the composed map recomputes asynchronously,
         * so going through [state] would declare a stale "off" and need a second PUT to heal.
         */
        fun touchpadWireMode(slotId: String): String {
            val connId = hub.bindings.value[slotId] ?: return TouchpadModeValue.OFF
            return TouchpadRouting.wireMode(
                mouseSurfaceOpen = mouseSurface.isOpen(slotId),
                controller = liveControllerLayer(slotId),
                type =
                    typeCapabilitiesFor(
                        hub.satTypes.value[connId to slotId] ?: CONTROLLER_TYPE_XBOX,
                        connId,
                        hub.connections.value
                            .firstOrNull { it.id == connId }
                            ?.kind ?: ConnectionKind.SATELLITE,
                    ),
                host = (hostFeatures.featuresFor(connId) ?: HostFeatureSet.SATELLITE_DEFAULT).toCapabilitySet(),
            )
        }

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
            candidateDirect: Boolean? = null,
        ): SlotCapabilities =
            CapabilityResolver.resolve(
                controller = candidateControllerLayer(slotId, candidateDirect),
                transport = TransportProfiles.forKind(candidateHostKind),
                type = typeCapabilitiesFor(candidateType, candidateHostId, candidateHostKind),
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
            hostMap: Map<String, HostFeatureSet>,
            backendMap: Map<Pair<String, String>, SatelliteMotionBackendStatus>,
            audioMap: AudioToggles,
        ): SlotCapabilities {
            val connId = bindings[slotId]
            val summary = connId?.let { summariesById[it] }
            val motionOn = motionMap[slotId] ?: MotionEnabledStore.DEFAULT_ENABLED
            val rumbleOn = rumbleMap[slotId] ?: RumbleEnabledStore.DEFAULT_ENABLED
            val micOn = audioMap.mic[slotId] ?: MicEnabledStore.DEFAULT_ENABLED
            val speakerOn = audioMap.speaker[slotId] ?: SpeakerEnabledStore.DEFAULT_ENABLED
            return CapabilityResolver.resolve(
                controller = controller,
                transport = transportLayer(summary),
                type = typeLayer(slotId, summary),
                host = hostLayer(connId, summary, hostMap),
                userEnabled = CapabilityResolver.userEnabledCapabilities(motionOn, rumbleOn, micOn, speakerOn),
                runtimeDown = runtimeDownLayer(connId, slotId, backendMap),
            )
        }

        private fun virtualControllerLayer(): CapabilitySet {
            // The phone IS the input AND the actuator: its screen sources the touchpad
            // and mouse, its vibrator actuates rumble (trigger rumble folds into it),
            // its own battery reports, and the skin renders the light surfaces the
            // hardware lacks — lightbar, player LEDs and an active adaptive-trigger
            // effect all draw on the on-screen pad (VirtualPadFeedbackStore). Motion
            // rides only if the phone has a gyro. Audio needs no probe at all: every
            // phone has a microphone and a speaker, which is exactly what the emulated
            // pad's two endpoints need, so both ride unconditionally. The type layer
            // still gates which of these a given emulated pad actually carries.
            val out =
                mutableSetOf(
                    Feature.GAMEPAD,
                    Feature.ANALOG_TRIGGERS,
                    Feature.TOUCHPAD,
                    Feature.MOUSE,
                    Feature.RUMBLE,
                    Feature.TRIGGER_RUMBLE,
                    Feature.BATTERY,
                    Feature.LIGHTBAR,
                    Feature.TRIGGER_EFFECTS,
                    Feature.PLAYER_LEDS,
                    Feature.MIC,
                    Feature.SPEAKER,
                )
            if (phoneHasGyro) out += Feature.MOTION
            return CapabilitySet(out)
        }

        private fun deviceControllerLayer(
            device: PhysicalGamepadRegistry.Device,
            direct: Boolean = device.isUsbSynthetic,
        ): CapabilitySet {
            // The pad supplies the gamepad axes. Touch comes from the pad's OWN trackpad where
            // the path can read it (USB Direct); the phone screen substitutes only for a pad
            // that has no trackpad at all. Rumble needs the pad's OWN motor: routing never
            // falls back to the phone for a physical controller, so a motorless pad has no
            // rumble.
            val vid = device.vendorId
            val pid = device.productId
            val out = mutableSetOf(Feature.GAMEPAD, Feature.ANALOG_TRIGGERS, Feature.BATTERY)
            if (deviceTouchpadSource(device, direct) != TouchpadSource.NONE) {
                out += Feature.TOUCHPAD
                out += Feature.MOUSE
            }
            if (direct) {
                // A Direct pad has no framework InputDevice to probe; everything, including the
                // LED / trigger surfaces the framework can never reach, comes from the native tables.
                if (native.modelHasImu(vid, pid)) out += Feature.MOTION
                if (native.modelHasRumble(vid, pid)) out += Feature.RUMBLE
                if (native.modelHasLightbar(vid, pid)) out += Feature.LIGHTBAR
                if (native.modelHasTriggerEffects(vid, pid)) out += Feature.TRIGGER_EFFECTS
                if (native.modelHasPlayerLeds(vid, pid)) out += Feature.PLAYER_LEDS
                if (native.modelHasTriggerRumble(vid, pid)) out += Feature.TRIGGER_RUMBLE
                // The pad's own audio endpoints are Android's to route, not ours: we claim
                // only the HID interface, so its USB-audio function stays with the OS. That
                // makes the model tables the wrong source here, and the OS route table the
                // right one: a pad whose audio function the OS never enumerated can't be
                // captured from or played to, whatever its model says it has.
                val audio = padAudioRoutes.routeFor(vid, pid)
                if (audio.microphone) out += Feature.MIC
                if (audio.speaker) out += Feature.SPEAKER
            } else {
                val framework = frameworkFactsFor(device)
                if (framework?.hasGyro == true) out += Feature.MOTION
                if (framework?.hasRumble == true) out += Feature.RUMBLE
            }
            return CapabilitySet(out)
        }

        private fun frameworkFactsFor(device: PhysicalGamepadRegistry.Device): PhysicalGamepadRegistry.FrameworkCaps? =
            if (device.isUsbSynthetic) {
                registry.frameworkCapsFor(device.vendorId, device.productId)
            } else {
                PhysicalGamepadRegistry.FrameworkCaps(hasGyro = device.hasGyro, hasRumble = device.hasRumble)
            }

        fun inputFunctionsFor(
            slotId: String,
            direct: Boolean?,
        ): InputFunctions {
            if (slotId == VIRTUAL_SLOT_ID) {
                return InputFunctions(known = true, rumble = false, gyro = phoneHasGyro, touchpad = true)
            }
            val device =
                slotId.toIntOrNull()?.let { registry.devices.value[it] }
                    ?: return InputFunctions(known = true, rumble = false, gyro = false, touchpad = false)
            val vid = device.vendorId
            val pid = device.productId
            val onDirect = direct ?: device.isUsbSynthetic
            if (onDirect) {
                val known = native.isKnownFastLaneModel(vid, pid)
                return InputFunctions(
                    known = known,
                    rumble = known && native.modelHasRumble(vid, pid),
                    gyro = known && native.modelHasImu(vid, pid),
                    touchpad = known && native.modelHasTouchpad(vid, pid),
                )
            }
            val framework = frameworkFactsFor(device)
            return InputFunctions(
                known = framework != null,
                rumble = framework?.hasRumble == true,
                gyro = framework?.hasGyro == true,
                touchpad = false,
            )
        }

        private fun deviceTouchpadSource(
            device: PhysicalGamepadRegistry.Device,
            direct: Boolean = device.isUsbSynthetic,
        ): TouchpadSource =
            TouchpadRouting.sourceFor(
                isVirtual = false,
                padHasTouchpad = native.modelHasTouchpad(device.vendorId, device.productId),
                padCaptured = direct,
            )

        /** Who produces touch for [slotId] right now: the pad, the phone screen, or nobody. */
        fun touchpadSource(slotId: String): TouchpadSource {
            if (slotId == VIRTUAL_SLOT_ID) return TouchpadSource.PHONE
            val device = slotId.toIntOrNull()?.let { registry.devices.value[it] } ?: return TouchpadSource.NONE
            return deviceTouchpadSource(device)
        }

        // The candidate path reuses the same controller layer the live map already derived for the slot.
        private fun liveControllerLayer(slotId: String): CapabilitySet {
            if (slotId == VIRTUAL_SLOT_ID) return virtualControllerLayer()
            val device = slotId.toIntOrNull()?.let { registry.devices.value[it] } ?: return CapabilitySet.EMPTY
            return deviceControllerLayer(device)
        }

        private fun candidateControllerLayer(
            slotId: String,
            direct: Boolean?,
        ): CapabilitySet {
            if (direct == null || slotId == VIRTUAL_SLOT_ID) return liveControllerLayer(slotId)
            val device = slotId.toIntOrNull()?.let { registry.devices.value[it] } ?: return CapabilitySet.EMPTY
            return deviceControllerLayer(device, direct)
        }

        // Unbound slots get a permissive transport so candidate/report queries see inherent availability.
        private fun transportLayer(summary: ConnectionSummary?): CapabilitySet = summary?.let { TransportProfiles.forKind(it.kind) } ?: ALL

        private fun typeLayer(
            slotId: String,
            summary: ConnectionSummary?,
        ): CapabilitySet {
            if (summary == null) return ALL
            if (summary.kind == ConnectionKind.BLUETOOTH) return ALL
            val typeId = summary.satelliteControllerTypes[slotId] ?: return ALL
            return typeCapabilitiesFor(typeId, summary.id, summary.kind)
        }

        // The satellite's own per-type features from its cached catalog are the source
        // of truth; the bundled set covers an unfetched catalog or the slugs we ship.
        // A Moonlight host has no catalog at all and its own table of types, so it never
        // reads either: the two type systems share names and nothing else.
        private fun typeCapabilitiesFor(
            typeId: Int,
            connId: String?,
            kind: ConnectionKind,
        ): CapabilitySet {
            if (kind == ConnectionKind.MOONLIGHT) {
                return MoonlightCatalog.typeCapabilities(
                    MoonlightEmulatedType.resolve(
                        MoonlightEmulatedType.fromStored(typeId),
                        sourceHasMotion = false,
                    ),
                )
            }
            val catalogType =
                connId
                    ?.let { catalogRepo.cached(it) }
                    ?.controllerTypes
                    ?.firstOrNull { it.id == typeId }
            return catalogType?.let { CapabilityResolver.typeCapabilities(it) }
                ?: BundledCatalog.typeCapabilitiesById(typeId)
        }

        // BLUETOOTH limits via transport, so its host layer is permissive; an unbound slot is
        // too. A Moonlight host exposes no capability API, so nothing about it can be crossed out.
        private fun hostLayer(
            connId: String?,
            summary: ConnectionSummary?,
            hostMap: Map<String, HostFeatureSet>,
        ): CapabilitySet {
            if (summary == null || connId == null) return ALL
            if (summary.kind == ConnectionKind.MOONLIGHT) return MoonlightCatalog.HOST_LAYER
            if (summary.kind != ConnectionKind.SATELLITE) return ALL
            return (hostMap[connId] ?: HostFeatureSet.SATELLITE_DEFAULT).toCapabilitySet()
        }

        private fun candidateHostLayer(
            kind: ConnectionKind,
            hostId: String?,
        ): CapabilitySet {
            if (kind == ConnectionKind.MOONLIGHT) return MoonlightCatalog.HOST_LAYER
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
