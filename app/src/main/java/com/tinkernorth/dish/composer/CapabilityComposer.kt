// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.composer

import com.tinkernorth.dish.architecture.abstracts.AbstractComposer
import com.tinkernorth.dish.core.jni.PhysicalInputNative
import com.tinkernorth.dish.core.model.CapabilitySet
import com.tinkernorth.dish.core.model.Feature
import com.tinkernorth.dish.core.model.HostFeatureSet
import com.tinkernorth.dish.core.model.SlotCapabilities
import com.tinkernorth.dish.core.net.moonlight.fromStored
import com.tinkernorth.dish.core.net.moonlight.resolveMoonlightEmulatedType
import com.tinkernorth.dish.hotpath.input.PhysicalGamepadRegistry
import com.tinkernorth.dish.hotpath.input.Transport
import com.tinkernorth.dish.repository.TOUCHPAD_MODE_OFF
import com.tinkernorth.dish.source.audio.PadAudioRoutes
import com.tinkernorth.dish.source.sensor.PhoneMotionAvailability
import com.tinkernorth.dish.source.store.MicEnabledStore
import com.tinkernorth.dish.source.store.MotionEnabledStore
import com.tinkernorth.dish.source.store.MouseSurfaceStore
import com.tinkernorth.dish.source.store.RumbleEnabledStore
import com.tinkernorth.dish.source.store.SatelliteHostFacts
import com.tinkernorth.dish.source.store.SatelliteMotionBackendStatus
import com.tinkernorth.dish.source.store.SlotToggleStores
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

// The per-slot user toggles as one upstream value. The pad route table rides the same fold
// without being carried: the controller layer reads it through PadAudioRoutes the way it reads
// the native model tables, and it is here so a pad's endpoints appearing or vanishing
// re-publishes.
private data class SlotToggles(
    val motion: Map<String, Boolean>,
    val rumble: Map<String, Boolean>,
    val mic: Map<String, Boolean>,
    val speaker: Map<String, Boolean>,
)

// What the bound satellite hosts report: their feature sets and the per-controller motion
// backend status.
private data class HostInputs(
    val features: Map<String, HostFeatureSet>,
    val motionBackend: Map<Pair<String, String>, SatelliteMotionBackendStatus>,
)

// The wire-facing projection of one slot's capabilities: the caps word the descriptor carries
// and its touchpadMode. Only these two move the descriptor, so consumers converging the wire
// key on this rather than on every capability emission.
data class WireProjection(
    val caps: Int,
    val touchpadMode: String,
)

@Singleton
class CapabilityComposer
    @Inject
    constructor(
        phoneAvailability: PhoneMotionAvailability,
        private val registry: PhysicalGamepadRegistry,
        private val hub: ConnectionCoordinator,
        private val native: PhysicalInputNative,
        private val toggles: SlotToggleStores,
        private val padAudioRoutes: PadAudioRoutes,
        private val mouseSurface: MouseSurfaceStore,
        private val hostFacts: SatelliteHostFacts,
        scope: CoroutineScope,
    ) : AbstractComposer<Map<String, SlotCapabilities>>(scope, emptyMap()) {
        // Fixed hardware fact, captured at construction so it needs no flow of its own.
        private val phoneHasGyro: Boolean = phoneAvailability.hasGyro

        private val slotToggles: Flow<SlotToggles> =
            combine(
                toggles.motion.state,
                toggles.rumble.state,
                toggles.mic.state,
                toggles.speaker.state,
                padAudioRoutes.state,
            ) { motion, rumble, mic, speaker, _ ->
                SlotToggles(motion, rumble, mic, speaker)
            }

        private val hostInputs: Flow<HostInputs> =
            combine(hostFacts.features.state, hostFacts.motionBackend.state, ::HostInputs)

        override fun upstream(): Flow<Map<String, SlotCapabilities>> =
            combine(
                registry.devices,
                hub.bindings,
                hub.connections,
                slotToggles,
                hostInputs,
            ) { devices, bindings, summaries, userToggles, hosts ->
                val summariesById = summaries.associateBy { it.id }
                val out = HashMap<String, SlotCapabilities>(devices.size + 1)

                out[VIRTUAL_SLOT_ID] =
                    slotFor(
                        slotId = VIRTUAL_SLOT_ID,
                        controller = virtualControllerLayer(),
                        bindings = bindings,
                        summariesById = summariesById,
                        userToggles = userToggles,
                        hosts = hosts,
                    )

                for ((deviceId, device) in devices) {
                    val slotId = deviceId.toString()
                    out[slotId] =
                        slotFor(
                            slotId = slotId,
                            controller = deviceControllerLayer(device),
                            bindings = bindings,
                            summariesById = summariesById,
                            userToggles = userToggles,
                            hosts = hosts,
                        )
                }
                out
            }.distinctUntilChanged()

        /**
         * The per-slot wire projection ([wireCapsFor] plus [touchpadWireMode]), re-derived on
         * every capability emission and on every mouse-surface flip: opening the mouse overlay
         * changes a slot's derived mode without moving any capability, and the descriptor must
         * still converge. Distinct, so unrelated composer emissions (host, type or runtime
         * changes that do not move the descriptor) never fire a no-op wire update.
         */
        val wireProjection: Flow<Map<String, WireProjection>> =
            combine(state, mouseSurface.state) { caps, _ ->
                caps.mapValues { (slotId, slot) ->
                    WireProjection(CapabilityResolver.wireCaps(slot), touchpadWireMode(slotId))
                }
            }.distinctUntilChanged()

        // The live per-slot map is the reactive read-surface for consumers that show a
        // BOUND slot's capabilities (dashboard cards, overlay), migrated onto it
        // incrementally. Draft-editing screens that preview an unsaved type/host use
        // capabilityForCandidate, since the bound state does not reflect the draft.
        fun capabilityFor(slotId: String): SlotCapabilities = state.value[slotId] ?: SlotCapabilities.NONE

        /**
         * The whole caps word the satellite descriptor carries for [slotId]: the base a pad always
         * has, motion gated on the input gyro and the user toggle, the feedback caps gated on what
         * the bound input can actuate, and the audio caps on their toggles too
         * ([CapabilityResolver.wireCaps] holds the rules). Deliberately NOT gated on
         * link-liveness: a reconnect must recover the pad's capabilities without a re-handshake,
         * so this is a different projection from the `available`/`live` views.
         *
         * This is the per-connection lambda SatelliteConnection builds every descriptor from.
         */
        fun wireCapsFor(slotId: String): Int = CapabilityResolver.wireCaps(capabilityFor(slotId))

        /**
         * The descriptor's touchpadMode for [slotId], pulled at descriptor-build time like
         * [wireCapsFor]. Reads the source stores directly instead of the composed [state]:
         * the pick is keyed by the bound connection, and during a bind the store writes land
         * synchronously before declareSlot while the composed map recomputes asynchronously,
         * so going through [state] would declare a stale "off" and need a second PUT to heal.
         */
        fun touchpadWireMode(slotId: String): String {
            val connId = hub.bindings.value[slotId] ?: return TOUCHPAD_MODE_OFF
            return wireMode(
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
                host = (hostFacts.features.featuresFor(connId) ?: HostFeatureSet.SATELLITE_DEFAULT).toCapabilitySet(),
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
                transport = transportProfileFor(candidateHostKind),
                type = typeCapabilitiesFor(candidateType, candidateHostId, candidateHostKind),
                host = candidateHostLayer(candidateHostKind, candidateHostId),
                userEnabled = ALL,
                // Pre-bind runtime probe: lets the report show a feature present-but-down
                // (e.g. motion backend missing) before the user commits.
                runtimeDown = candidateRuntimeDownLayer(candidateHostKind, candidateHostId),
            )

        private fun slotFor(
            slotId: String,
            controller: CapabilitySet,
            bindings: Map<String, String>,
            summariesById: Map<String, ConnectionSummary>,
            userToggles: SlotToggles,
            hosts: HostInputs,
        ): SlotCapabilities {
            val connId = bindings[slotId]
            val summary = connId?.let { summariesById[it] }
            val motionOn = userToggles.motion[slotId] ?: MotionEnabledStore.DEFAULT_ENABLED
            val rumbleOn = userToggles.rumble[slotId] ?: RumbleEnabledStore.DEFAULT_ENABLED
            val micOn = userToggles.mic[slotId] ?: MicEnabledStore.DEFAULT_ENABLED
            val speakerOn = userToggles.speaker[slotId] ?: SpeakerEnabledStore.DEFAULT_ENABLED
            return CapabilityResolver.resolve(
                controller = controller,
                transport = transportLayer(summary),
                type = typeLayer(slotId, summary),
                host = hostLayer(connId, summary, hosts.features),
                userEnabled = CapabilityResolver.userEnabledCapabilities(motionOn, rumbleOn, micOn, speakerOn),
                runtimeDown = runtimeDownLayer(connId, slotId, hosts.motionBackend),
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
                // trigger and player-LED surfaces the framework path never reaches, comes from the
                // native tables. (The light bar is the one framework pads also drive, over Bluetooth.)
                if (native.modelHasImu(vid, pid)) out += Feature.MOTION
                if (native.modelHasRumble(vid, pid)) out += Feature.RUMBLE
                if (native.modelHasLightbar(vid, pid)) out += Feature.LIGHTBAR
                if (native.modelHasTriggerEffects(vid, pid)) out += Feature.TRIGGER_EFFECTS
                if (native.modelHasPlayerLeds(vid, pid)) out += Feature.PLAYER_LEDS
                if (native.modelHasTriggerRumble(vid, pid)) out += Feature.TRIGGER_RUMBLE
            } else {
                val framework = frameworkFactsFor(device)
                if (framework?.hasGyro == true) out += Feature.MOTION
                if (framework?.hasRumble == true) out += Feature.RUMBLE
                // The light bar rides the Android lights API, which reaches a uhid pad's LEDs but
                // not a USB one's (the input service cannot write generic-sysfs LED nodes), so it is
                // advertised on the Bluetooth transport only. A USB pad's bar comes from Direct.
                if (device.transport == Transport.Bluetooth && framework?.hasLightbar == true) {
                    out += Feature.LIGHTBAR
                }
            }
            // The pad's own audio endpoints are Android's to route, not ours: we claim only
            // the HID interface (or, on the framework path, nothing at all), so its USB-audio
            // function stays with the OS on either path. That makes the model tables the wrong
            // source here, and the OS route table the right one: a pad whose audio function
            // the OS never enumerated can't be captured from or played to, whatever its model
            // says it has. A Bluetooth pad has no such function and resolves to nothing.
            if (device.transport == Transport.Usb) {
                val audio = padAudioRoutes.routeFor(vid, pid)
                if (audio.microphone) out += Feature.MIC
                if (audio.speaker) out += Feature.SPEAKER
                if (audio.haptics) out += Feature.HAPTIC_AUDIO
            }
            return CapabilitySet(out)
        }

        private fun frameworkFactsFor(device: PhysicalGamepadRegistry.Device): PhysicalGamepadRegistry.FrameworkCaps? =
            if (device.isUsbSynthetic) {
                registry.frameworkCapsFor(device.vendorId, device.productId)
            } else {
                PhysicalGamepadRegistry.FrameworkCaps(
                    hasGyro = device.hasGyro,
                    hasRumble = device.hasRumble,
                    hasLightbar = device.hasLightbar,
                    hasTouchpad = device.touchpadDeviceId != null,
                )
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
                // The surface the framework exposed, read through pointer capture; only a
                // model with a trackpad is routed through it (deviceTouchpadSource).
                touchpad = framework?.hasTouchpad == true && native.modelHasTouchpad(vid, pid),
            )
        }

        // The app reads the pad's surface itself on Direct (the raw report) and, on a framework
        // path, through pointer capture of the surface Android exposed (Device.touchpadDeviceId).
        private fun deviceTouchpadSource(
            device: PhysicalGamepadRegistry.Device,
            direct: Boolean = device.isUsbSynthetic,
        ): TouchpadSource =
            sourceFor(
                isVirtual = false,
                padHasTouchpad = native.modelHasTouchpad(device.vendorId, device.productId),
                padCaptured = direct || (!device.isUsbSynthetic && device.touchpadDeviceId != null),
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
        private fun transportLayer(summary: ConnectionSummary?): CapabilitySet = summary?.let { transportProfileFor(it.kind) } ?: ALL

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
                    resolveMoonlightEmulatedType(
                        fromStored(typeId),
                        sourceHasMotion = false,
                    ),
                )
            }
            val catalogType =
                connId
                    ?.let { hostFacts.catalog.cached(it) }
                    ?.controllerTypes
                    ?.firstOrNull { it.id == typeId }
            return catalogType?.let { CapabilityResolver.typeCapabilities(it) }
                ?: typeCapabilitiesById(typeId)
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
            val features = hostId?.let { hostFacts.features.featuresFor(it) } ?: HostFeatureSet.SATELLITE_DEFAULT
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
            val runtime = hostId?.let { hostFacts.runtime.runtimeFor(it) } ?: return CapabilitySet.EMPTY
            return if (!runtime.motionBackendOk) CapabilitySet.of(Feature.MOTION) else CapabilitySet.EMPTY
        }

        private companion object {
            val ALL = CapabilitySet(Feature.entries.toSet())
        }
    }
