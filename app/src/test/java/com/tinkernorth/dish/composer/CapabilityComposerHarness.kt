// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.composer

import com.tinkernorth.dish.core.jni.PhysicalInputNative
import com.tinkernorth.dish.core.model.CatalogDto
import com.tinkernorth.dish.core.model.HostFeatureSet
import com.tinkernorth.dish.hotpath.input.PhysicalGamepadRegistry
import com.tinkernorth.dish.hotpath.input.Transport
import com.tinkernorth.dish.source.audio.PadAudioRoute
import com.tinkernorth.dish.source.audio.PadAudioRoutes
import com.tinkernorth.dish.source.sensor.PhoneMotionAvailability
import com.tinkernorth.dish.source.store.MouseSurfaceStore
import com.tinkernorth.dish.source.store.SatelliteHostFacts
import com.tinkernorth.dish.source.store.SatelliteHostRuntime
import com.tinkernorth.dish.source.store.SatelliteMotionBackendStatus
import com.tinkernorth.dish.source.store.SlotToggleStores
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
    touchpadDeviceId: Int? = null,
) = PhysicalGamepadRegistry.Device(
    id = id,
    name = "Pad-$id",
    hasGyro = hasGyro,
    hasRumble = hasRumble,
    vendorId = vendorId,
    productId = productId,
    isUsbSynthetic = isUsbSynthetic,
    transport = transport,
    touchpadDeviceId = touchpadDeviceId,
)

// The store flows the composer folds. Each defaults to empty so a test sets only the ones it
// is about.
internal data class StoreStates(
    val motionEnabled: MutableStateFlow<Map<String, Boolean>> = MutableStateFlow(emptyMap()),
    val rumbleEnabled: MutableStateFlow<Map<String, Boolean>> = MutableStateFlow(emptyMap()),
    val micEnabled: MutableStateFlow<Map<String, Boolean>> = MutableStateFlow(emptyMap()),
    val speakerEnabled: MutableStateFlow<Map<String, Boolean>> = MutableStateFlow(emptyMap()),
    val padAudioRoutes: MutableStateFlow<Map<Int, PadAudioRoute>> = MutableStateFlow(emptyMap()),
    val mouseSurface: MutableStateFlow<Set<String>> = MutableStateFlow(emptySet()),
    val hostFeaturesState: MutableStateFlow<Map<String, HostFeatureSet>> = MutableStateFlow(emptyMap()),
    val backendStatus: MutableStateFlow<Map<Pair<String, String>, SatelliteMotionBackendStatus>> =
        MutableStateFlow(emptyMap()),
    val hostRuntime: MutableStateFlow<Map<String, SatelliteHostRuntime>> = MutableStateFlow(emptyMap()),
    val satTypes: MutableStateFlow<Map<Pair<String, String>, Int>> = MutableStateFlow(emptyMap()),
    // Default no cached catalog: the type layer falls back to BundledCatalog. Tests that
    // exercise the catalog-driven path pass a cachedCatalog explicitly.
    val cachedCatalog: CatalogDto? = null,
)

// What the native model tables and the framework probe say about every pad in the test.
internal data class ModelFacts(
    val modelHasImu: Boolean = false,
    val modelHasRumble: Boolean = false,
    val modelHasTouchpad: Boolean = false,
    val modelHasLightbar: Boolean = false,
    val modelHasPlayerLeds: Boolean = false,
    val modelHasTriggerEffects: Boolean = false,
    val modelHasTriggerRumble: Boolean = false,
    val knownFastLane: Boolean = false,
    val frameworkCaps: PhysicalGamepadRegistry.FrameworkCaps? = null,
)

internal fun composerFor(
    phoneAvailable: Boolean,
    devices: MutableStateFlow<Map<Int, PhysicalGamepadRegistry.Device>>,
    bindings: MutableStateFlow<Map<String, String>>,
    connections: MutableStateFlow<List<ConnectionSummary>>,
    scope: CoroutineScope,
    stores: StoreStates = StoreStates(),
    model: ModelFacts = ModelFacts(),
): CapabilityComposer {
    val availability: PhoneMotionAvailability = mockk { every { hasGyro } returns phoneAvailable }
    val registry: PhysicalGamepadRegistry =
        mockk {
            every { this@mockk.devices } returns devices
            every { frameworkCapsFor(any(), any()) } returns model.frameworkCaps
        }
    val hub: ConnectionCoordinator =
        mockk {
            every { this@mockk.bindings } returns bindings
            every { this@mockk.connections } returns connections
            every { satTypes } returns stores.satTypes
        }
    val native: PhysicalInputNative =
        mockk {
            every { modelHasImu(any(), any()) } returns model.modelHasImu
            every { modelHasRumble(any(), any()) } returns model.modelHasRumble
            every { modelHasTouchpad(any(), any()) } returns model.modelHasTouchpad
            every { modelHasLightbar(any(), any()) } returns model.modelHasLightbar
            every { modelHasPlayerLeds(any(), any()) } returns model.modelHasPlayerLeds
            every { modelHasTriggerEffects(any(), any()) } returns model.modelHasTriggerEffects
            every { modelHasTriggerRumble(any(), any()) } returns model.modelHasTriggerRumble
            every { isKnownFastLaneModel(any(), any()) } returns model.knownFastLane
        }
    val toggles =
        SlotToggleStores(
            motion = mockk { every { state } returns stores.motionEnabled },
            rumble = mockk { every { state } returns stores.rumbleEnabled },
            mic = mockk { every { state } returns stores.micEnabled },
            speaker = mockk { every { state } returns stores.speakerEnabled },
        )
    val routes: PadAudioRoutes =
        mockk {
            every { state } returns stores.padAudioRoutes
            every { routeFor(any(), any()) } answers {
                stores.padAudioRoutes.value[PadAudioRoutes.key(firstArg(), secondArg())] ?: PadAudioRoute.NONE
            }
        }
    val mouseSurfaceStore: MouseSurfaceStore =
        mockk {
            every { state } returns stores.mouseSurface
            every { isOpen(any()) } answers { firstArg<String>() in stores.mouseSurface.value }
        }
    val hostFacts =
        SatelliteHostFacts(
            features =
                mockk {
                    every { state } returns stores.hostFeaturesState
                    every { featuresFor(any()) } answers { stores.hostFeaturesState.value[firstArg()] }
                },
            runtime = mockk { every { runtimeFor(any()) } answers { stores.hostRuntime.value[firstArg()] } },
            motionBackend = mockk { every { state } returns stores.backendStatus },
            catalog = mockk { every { cached(any()) } returns stores.cachedCatalog },
            capabilities = mockk(),
        )
    return CapabilityComposer(
        availability,
        registry,
        hub,
        native,
        toggles,
        routes,
        mouseSurfaceStore,
        hostFacts,
        scope,
    )
}
