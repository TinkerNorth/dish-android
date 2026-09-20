// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.composer

import com.tinkernorth.dish.core.jni.PhysicalInputNative
import com.tinkernorth.dish.core.model.CatalogDto
import com.tinkernorth.dish.core.model.HostFeatureSet
import com.tinkernorth.dish.hotpath.input.PhysicalGamepadRegistry
import com.tinkernorth.dish.hotpath.input.Transport
import com.tinkernorth.dish.repository.SatelliteCatalogRepository
import com.tinkernorth.dish.source.audio.PadAudioRoute
import com.tinkernorth.dish.source.audio.PadAudioRoutes
import com.tinkernorth.dish.source.sensor.PhoneMotionAvailability
import com.tinkernorth.dish.source.store.MicEnabledStore
import com.tinkernorth.dish.source.store.MotionEnabledStore
import com.tinkernorth.dish.source.store.MouseSurfaceStore
import com.tinkernorth.dish.source.store.RumbleEnabledStore
import com.tinkernorth.dish.source.store.SatelliteHostFeaturesStore
import com.tinkernorth.dish.source.store.SatelliteHostRuntime
import com.tinkernorth.dish.source.store.SatelliteHostRuntimeStore
import com.tinkernorth.dish.source.store.SatelliteMotionBackendStatus
import com.tinkernorth.dish.source.store.SatelliteMotionBackendStatusStore
import com.tinkernorth.dish.source.store.SpeakerEnabledStore
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow

// The harness CapabilityComposerTest and CapabilityComposerPadAudioTest share: the
// summary/device builders and the fully mocked composer factory. Top level so the
// two suites stay under the class-size gate without duplicating any of it.

internal fun summary(
    id: String,
    kind: ConnectionKind = ConnectionKind.SATELLITE,
    live: LinkState = LinkState.Connected,
    satelliteControllerTypes: Map<String, Int> = emptyMap(),
) = ConnectionSummary(
    id = id,
    kind = kind,
    label = id,
    detail = "",
    live = live,
    boundSlotIds = emptyList(),
    satelliteControllerTypes = satelliteControllerTypes,
)

internal fun device(
    id: Int,
    hasGyro: Boolean = false,
    hasRumble: Boolean = false,
    vendorId: Int = 0,
    productId: Int = 0,
    isUsbSynthetic: Boolean = false,
    transport: Transport = Transport.Usb,
) = PhysicalGamepadRegistry.Device(
    id = id,
    name = "Pad-$id",
    hasGyro = hasGyro,
    hasRumble = hasRumble,
    vendorId = vendorId,
    productId = productId,
    isUsbSynthetic = isUsbSynthetic,
    transport = transport,
)

@Suppress("LongParameterList")
internal fun composerFor(
    phoneAvailable: Boolean,
    devices: MutableStateFlow<Map<Int, PhysicalGamepadRegistry.Device>>,
    bindings: MutableStateFlow<Map<String, String>>,
    connections: MutableStateFlow<List<ConnectionSummary>>,
    scope: CoroutineScope,
    motionEnabled: MutableStateFlow<Map<String, Boolean>> = MutableStateFlow(emptyMap()),
    rumbleEnabled: MutableStateFlow<Map<String, Boolean>> = MutableStateFlow(emptyMap()),
    micEnabled: MutableStateFlow<Map<String, Boolean>> = MutableStateFlow(emptyMap()),
    speakerEnabled: MutableStateFlow<Map<String, Boolean>> = MutableStateFlow(emptyMap()),
    padAudioRoutes: MutableStateFlow<Map<Int, PadAudioRoute>> = MutableStateFlow(emptyMap()),
    mouseSurface: MutableStateFlow<Set<String>> = MutableStateFlow(emptySet()),
    hostFeaturesState: MutableStateFlow<Map<String, HostFeatureSet>> = MutableStateFlow(emptyMap()),
    backendStatus: MutableStateFlow<Map<Pair<String, String>, SatelliteMotionBackendStatus>> =
        MutableStateFlow(emptyMap()),
    hostRuntime: MutableStateFlow<Map<String, SatelliteHostRuntime>> = MutableStateFlow(emptyMap()),
    cachedCatalog: CatalogDto? = null,
    modelHasImu: Boolean = false,
    modelHasRumble: Boolean = false,
    modelHasTouchpad: Boolean = false,
    modelHasLightbar: Boolean = false,
    modelHasPlayerLeds: Boolean = false,
    modelHasTriggerEffects: Boolean = false,
    modelHasTriggerRumble: Boolean = false,
    knownFastLane: Boolean = false,
    frameworkCaps: PhysicalGamepadRegistry.FrameworkCaps? = null,
    satTypes: MutableStateFlow<Map<Pair<String, String>, Int>> = MutableStateFlow(emptyMap()),
): CapabilityComposer {
    val availability: PhoneMotionAvailability = mockk { every { hasGyro } returns phoneAvailable }
    val registry: PhysicalGamepadRegistry =
        mockk {
            every { this@mockk.devices } returns devices
            every { frameworkCapsFor(any(), any()) } returns frameworkCaps
        }
    val hub: ConnectionCoordinator =
        mockk {
            every { this@mockk.bindings } returns bindings
            every { this@mockk.connections } returns connections
            every { this@mockk.satTypes } returns satTypes
        }
    val native: PhysicalInputNative =
        mockk {
            every { modelHasImu(any(), any()) } returns modelHasImu
            every { modelHasRumble(any(), any()) } returns modelHasRumble
            every { modelHasTouchpad(any(), any()) } returns modelHasTouchpad
            every { modelHasLightbar(any(), any()) } returns modelHasLightbar
            every { modelHasPlayerLeds(any(), any()) } returns modelHasPlayerLeds
            every { modelHasTriggerEffects(any(), any()) } returns modelHasTriggerEffects
            every { modelHasTriggerRumble(any(), any()) } returns modelHasTriggerRumble
            every { isKnownFastLaneModel(any(), any()) } returns knownFastLane
        }
    val motionStore: MotionEnabledStore = mockk { every { state } returns motionEnabled }
    val rumbleStore: RumbleEnabledStore = mockk { every { state } returns rumbleEnabled }
    val micStore: MicEnabledStore = mockk { every { state } returns micEnabled }
    val speakerStore: SpeakerEnabledStore = mockk { every { state } returns speakerEnabled }
    val routes: PadAudioRoutes =
        mockk {
            every { state } returns padAudioRoutes
            every { routeFor(any(), any()) } answers {
                padAudioRoutes.value[PadAudioRoutes.key(firstArg(), secondArg())] ?: PadAudioRoute.NONE
            }
        }
    val mouseSurfaceStore: MouseSurfaceStore =
        mockk {
            every { state } returns mouseSurface
            every { isOpen(any()) } answers { firstArg<String>() in mouseSurface.value }
        }
    val hostStore: SatelliteHostFeaturesStore =
        mockk {
            every { state } returns hostFeaturesState
            every { featuresFor(any()) } answers { hostFeaturesState.value[firstArg()] }
        }
    val backendStore: SatelliteMotionBackendStatusStore = mockk { every { state } returns backendStatus }
    val hostRuntimeStore: SatelliteHostRuntimeStore =
        mockk { every { runtimeFor(any()) } answers { hostRuntime.value[firstArg()] } }
    // Default no cached catalog: the type layer falls back to BundledCatalog. Tests that
    // exercise the catalog-driven path pass a cachedCatalog explicitly.
    val catalogRepo: SatelliteCatalogRepository = mockk { every { cached(any()) } returns cachedCatalog }
    return CapabilityComposer(
        availability,
        registry,
        hub,
        native,
        motionStore,
        rumbleStore,
        micStore,
        speakerStore,
        routes,
        mouseSurfaceStore,
        hostStore,
        backendStore,
        hostRuntimeStore,
        catalogRepo,
        scope,
    )
}
