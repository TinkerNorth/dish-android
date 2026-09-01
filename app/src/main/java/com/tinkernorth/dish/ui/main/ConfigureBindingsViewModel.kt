// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.main

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tinkernorth.dish.R
import com.tinkernorth.dish.composer.CONTROLLER_TYPE_DUALSENSE
import com.tinkernorth.dish.composer.CONTROLLER_TYPE_PLAYSTATION
import com.tinkernorth.dish.composer.CONTROLLER_TYPE_SWITCHPRO
import com.tinkernorth.dish.composer.CONTROLLER_TYPE_XBOX
import com.tinkernorth.dish.composer.CapabilityComposer
import com.tinkernorth.dish.composer.ConnectionCoordinator
import com.tinkernorth.dish.composer.ConnectionKind
import com.tinkernorth.dish.composer.ConnectionSummary
import com.tinkernorth.dish.composer.InputFunctions
import com.tinkernorth.dish.composer.LinkState
import com.tinkernorth.dish.composer.LinkTiers
import com.tinkernorth.dish.core.jni.PhysicalInputNative
import com.tinkernorth.dish.core.model.CapabilitySet
import com.tinkernorth.dish.core.model.CatalogTypeDto
import com.tinkernorth.dish.core.model.Feature
import com.tinkernorth.dish.core.model.SlotCapabilities
import com.tinkernorth.dish.core.net.DishProtocol
import com.tinkernorth.dish.core.net.moonlight.MoonlightEmulatedType
import com.tinkernorth.dish.hotpath.input.PhysicalGamepadRegistry
import com.tinkernorth.dish.hotpath.input.Transport
import com.tinkernorth.dish.repository.SatelliteCapabilitiesRepository
import com.tinkernorth.dish.repository.SatelliteCatalogRepository
import com.tinkernorth.dish.source.connection.SatelliteConnectionManager
import com.tinkernorth.dish.source.connection.moonlight.MoonlightConnectionEvent
import com.tinkernorth.dish.source.connection.moonlight.MoonlightConnectionManager
import com.tinkernorth.dish.source.connection.moonlight.MoonlightProbe
import com.tinkernorth.dish.source.connection.moonlight.MoonlightSessionState
import com.tinkernorth.dish.source.connection.moonlight.MoonlightTrustState
import com.tinkernorth.dish.source.store.MicEnabledStore
import com.tinkernorth.dish.source.store.MotionEnabledStore
import com.tinkernorth.dish.source.store.RumbleEnabledStore
import com.tinkernorth.dish.source.store.SatelliteHostFeaturesStore
import com.tinkernorth.dish.source.store.SpeakerEnabledStore
import com.tinkernorth.dish.source.system.MicPermissionGate
import com.tinkernorth.dish.source.usb.PathChoice
import com.tinkernorth.dish.source.usb.UsbGamepadManager
import com.tinkernorth.dish.source.usb.UsbPhase
import com.tinkernorth.dish.ui.common.bundledControllerTypeLabelRes
import com.tinkernorth.dish.ui.common.moonlightTypeLabelRes
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

enum class BindingLink { USB, BLUETOOTH, ONSCREEN }

// Controller glyph for an input link; shared by the dashboard configure screen and
// the setup flow's review step so both render the same icon.
fun BindingLink.iconRes(): Int =
    when (this) {
        BindingLink.BLUETOOTH -> R.drawable.ic_bluetooth
        BindingLink.ONSCREEN -> R.drawable.ic_gamepad_virtual
        BindingLink.USB -> R.drawable.ic_gamepad
    }

data class BindingHost(
    val id: String,
    val label: String,
    val kind: ConnectionKind,
)

internal fun List<BindingHost>.orderedForPicker(): List<BindingHost> = sortedWith(LinkTiers.byTier(BindingHost::kind))

// The host side of each candidate resolution (transport ∩ type ∩ host), unioned across
// the candidate types. The input's controller layer deliberately stays out: the card
// describes the destination, not the pad currently in hand.
internal fun destinationPotential(candidates: List<SlotCapabilities>): CapabilitySet =
    candidates.fold(CapabilitySet.EMPTY) { acc, c ->
        CapabilitySet(acc.features + (c.transport intersect c.type intersect c.host).features)
    }

data class BindingSnapshot(
    val slotId: String,
    val name: String,
    val link: BindingLink,
    val directCapable: Boolean,
    val directVerified: Boolean,
    val bound: Boolean,
    val directPollHz: Int,
    // Used to re-resolve the slot after a USB path switch replaces the
    // framework device with a synthetic twin (or vice versa).
    val vendorId: Int = 0,
    val productId: Int = 0,
)

// One "Emulate as" choice. Rendered from the satellite's catalog; `id` is the
// wire enum value the descriptor carries.
data class TypeOption(
    val id: Int,
    val label: String,
)

data class BindingDraft(
    val hostId: String?,
    // null until the host's catalog resolves the type (or a remembered/manual pick sets it); never a
    // guessed default, so the configure screen shows a loader instead of a wrong type for a satellite.
    val type: Int?,
    val directOn: Boolean,
    val motionOn: Boolean,
    val rumbleOn: Boolean = true,
    val micOn: Boolean = false,
    val speakerOn: Boolean = true,
)

sealed interface BindingBlocker {
    data object InputLost : BindingBlocker

    data class HostLost(
        val hostLabel: String,
        val reconnecting: Boolean,
    ) : BindingBlocker

    data object HostUnsteady : BindingBlocker
}

// The "Emulate as" type is host-owned: a satellite host resolves it from the catalog, so the
// configure screen shows a loader until it arrives (Error → tap-to-retry) rather than a guessed
// default. A Bluetooth host is always Ready (its type is profile-driven).
sealed interface TypeLoad {
    data object Loading : TypeLoad

    data object Ready : TypeLoad

    data object Error : TypeLoad
}

data class ConfigUiState(
    val loaded: Boolean = false,
    val snapshot: BindingSnapshot? = null,
    val hosts: List<BindingHost> = emptyList(),
    val draft: BindingDraft? = null,
    val typeOptions: List<TypeOption> = emptyList(),
    val connections: List<ConnectionSummary> = emptyList(),
    val knownHostLabels: Map<String, String> = emptyMap(),
    val controllerPresent: Boolean = true,
    val dismissedUnsteadyHostIds: Set<String> = emptySet(),
    val capabilities: SlotCapabilities = SlotCapabilities.NONE,
    val inputFuncs: InputFunctions = InputFunctions(known = true, rumble = false, gyro = false, touchpad = false),
    // Set only when a satellite catalog fetch failed with nothing cached, so Loading (fetch in
    // flight) and Error (fetch failed) are distinguishable — neither is derivable from the draft alone.
    val typeFetchFailed: Boolean = false,
    // What the chosen Moonlight host last told us. Null for every other kind of destination.
    val moonlight: MoonlightSessionInput? = null,
    // Per-connection protocol verdict (satellite hosts only), for the update chips.
    val hostCompat: Map<String, DishProtocol.Compat> = emptyMap(),
    // RECORD_AUDIO, re-read on every resume: the OS says nothing when a grant is revoked.
    val micPermissionGranted: Boolean = false,
) {
    val selectedHost: BindingHost? get() = hosts.firstOrNull { it.id == draft?.hostId }
    val noHosts: Boolean get() = hosts.isEmpty()
    val hostChosen: Boolean get() = selectedHost != null

    // A Bluetooth host is profile-driven (always Ready); a resolved type (remembered/manual/catalog)
    // is Ready; a failed fetch with nothing cached is Error; otherwise the catalog is still loading.
    // A Moonlight host has no catalog to fetch: its four types are known here, so it is Ready as soon
    // as the draft carries one, which is immediately (Auto is the seeded default).
    val typeLoad: TypeLoad
        get() =
            when {
                isBluetoothHost -> TypeLoad.Ready
                draft?.type != null -> TypeLoad.Ready
                typeFetchFailed -> TypeLoad.Error
                else -> TypeLoad.Loading
            }

    // A binding is a durable intent and pairing is trust verified lazily, so no Moonlight host
    // state blocks Apply: the session is attempted when the controller is used, not when the
    // binding is saved. The one exception is a host already carrying its four controllers, which
    // is a hard protocol limit and says so.
    val canApply: Boolean get() = hostChosen && (isBluetoothHost || draft?.type != null) && !moonlightBlocked

    // Rendered by the Moonlight session section; every state it can be in is in MoonlightSessionUi.
    val moonlightSession: MoonlightSessionUi?
        get() = if (isMoonlightHost) moonlightSessionUi(moonlight ?: MoonlightSessionInput()) else null

    private val moonlightBlocked: Boolean get() = moonlightSession?.blocksApply == true

    // A Moonlight host is never "lost": there is no live link to lose, only remembered trust
    // that the session section reports honestly. Blocking the screen on it would also block
    // the very actions that recover it.
    val blocker: BindingBlocker?
        get() {
            if (!loaded) return null
            if (!controllerPresent) return BindingBlocker.InputLost
            if (isMoonlightHost) return null
            val hostId = draft?.hostId ?: return null
            val summary = connections.firstOrNull { it.id == hostId }
            return when {
                summary == null || !summary.live.isLiveLink() ->
                    BindingBlocker.HostLost(
                        hostLabel = summary?.label ?: knownHostLabels[hostId].orEmpty(),
                        reconnecting = summary?.live == LinkState.Connecting,
                    )
                summary.live == LinkState.Unstable && hostId !in dismissedUnsteadyHostIds -> BindingBlocker.HostUnsteady
                else -> null
            }
        }

    // The capability layers decide what carries: motion needs an input gyro, a motion-bearing
    // type (PlayStation), and a satellite destination. Touch and mouse have no toggle at all:
    // whatever the path can carry is simply on, so the screen only reads their availability.
    val motionAvailable: Boolean
        get() = capabilities.isAvailable(Feature.MOTION)

    // The two audio rows appear only where the whole path carries the endpoint: an
    // audio-capable emulated type, on a host with controller audio switched on, behind an
    // input that can capture or play.
    val micAvailable: Boolean get() = capabilities.isAvailable(Feature.MIC)

    val speakerAvailable: Boolean get() = capabilities.isAvailable(Feature.SPEAKER)

    // A mic switched on with no grant behind it is a switch that would silently do
    // nothing, so the row says so instead and offers the ask.
    val micNeedsPermission: Boolean get() = micAvailable && draft?.micOn == true && !micPermissionGranted

    val isBluetoothHost: Boolean get() = selectedHost?.kind == ConnectionKind.BLUETOOTH

    val isMoonlightHost: Boolean get() = selectedHost?.kind == ConnectionKind.MOONLIGHT

    // The USB path the draft would apply (null off USB), so previews track the toggle, not the current path.
    val candidateDirect: Boolean? get() = if (snapshot?.link == BindingLink.USB) draft?.directOn == true else null

    val inputUnknown: Boolean get() = !inputFuncs.known
}

data class ApplyStep(
    val key: String,
    val label: String,
)

sealed interface ApplyState {
    data object Idle : ApplyState

    data class Running(
        val steps: List<ApplyStep>,
        val doneCount: Int,
    ) : ApplyState

    data class Finished(
        val errorMessage: String?,
        val warningMessage: String?,
        val hostName: String,
        val controllerName: String,
    ) : ApplyState
}

@HiltViewModel
class ConfigureBindingsViewModel
    @Inject
    @Suppress("LongParameterList")
    constructor(
        @ApplicationContext private val context: Context,
        private val hub: ConnectionCoordinator,
        private val gamepadRegistry: PhysicalGamepadRegistry,
        private val motionEnabledStore: MotionEnabledStore,
        private val rumbleEnabledStore: RumbleEnabledStore,
        private val micEnabledStore: MicEnabledStore,
        private val speakerEnabledStore: SpeakerEnabledStore,
        private val micPermission: MicPermissionGate,
        private val capabilityComposer: CapabilityComposer,
        private val satellite: SatelliteConnectionManager,
        private val moonlight: MoonlightConnectionManager,
        private val usbGamepadManager: UsbGamepadManager,
        private val catalogRepo: SatelliteCatalogRepository,
        private val capabilitiesRepo: SatelliteCapabilitiesRepository,
        private val native: PhysicalInputNative,
        private val hostFeaturesStore: SatelliteHostFeaturesStore,
    ) : ViewModel() {
        private val _ui = MutableStateFlow(ConfigUiState())
        val ui: StateFlow<ConfigUiState> = _ui.asStateFlow()

        private val _applyState = MutableStateFlow<ApplyState>(ApplyState.Idle)
        val applyState: StateFlow<ApplyState> = _applyState.asStateFlow()

        // One-shot ask for RECORD_AUDIO, emitted when the mic toggle goes on without the
        // grant. The screen owns the system prompt (only an Activity can launch one), so
        // this is the seam it listens on. Deliberately not replayed: a prompt must follow
        // a tap, never a rotation, and the row keeps offering the ask meanwhile.
        private val _micPermissionRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        val micPermissionRequests: SharedFlow<Unit> = _micPermissionRequests.asSharedFlow()

        private var loadedSlotId: String? = null

        private var moonlightPairing: MoonlightPairingUi? = null
        private var moonlightFailure: MoonlightFailure? = null
        private var pairingJob: kotlinx.coroutines.Job? = null

        fun load(slotId: String) {
            if (loadedSlotId == slotId) return
            loadedSlotId = slotId
            val snapshot = buildSnapshot(slotId)
            val hosts = buildHosts()
            val draft = buildSeedDraft(slotId)
            val conns = hub.connections.value
            _ui.value =
                ConfigUiState(
                    loaded = true,
                    snapshot = snapshot,
                    hosts = hosts,
                    draft = draft,
                    typeOptions = bundledTypeOptions(),
                    connections = conns,
                    knownHostLabels = conns.associate { it.id to it.label },
                    controllerPresent = controllerPresent(snapshot),
                    micPermissionGranted = micPermission.granted,
                ).withCapabilities()
            draft.hostId?.let { refreshTypeOptions(it) }
            // Refresh the host list as connections come and go, without disturbing the in-progress draft.
            hub.connections
                .onEach { latest ->
                    _ui.update { state ->
                        state
                            .copy(
                                hosts = buildHosts(),
                                connections = latest,
                                knownHostLabels = state.knownHostLabels + latest.associate { it.id to it.label },
                            ).withCapabilities()
                    }
                }.launchIn(viewModelScope)
            gamepadRegistry.devices
                .onEach { _ui.update { state -> state.copy(controllerPresent = controllerPresent(state.snapshot)).withCapabilities() } }
                .launchIn(viewModelScope)
            hostFeaturesStore.state
                .onEach { features ->
                    _ui.update { it.copy(hostCompat = features.mapValues { (_, f) -> f.compat }) }
                }.launchIn(viewModelScope)
            observeMoonlightEvents()
        }

        fun setHost(hostId: String) {
            _ui.update { it.copy(draft = it.draft?.copy(hostId = hostId), moonlight = null).withCapabilities() }
            refreshTypeOptions(hostId)
        }

        fun setType(type: Int) = _ui.update { it.copy(draft = it.draft?.copy(type = type)).withCapabilities() }

        // Re-runs the catalog fetch for the current host after a load error (the type-row retry affordance).
        fun retryTypeLoad() {
            val hostId = _ui.value.draft?.hostId ?: return
            refreshTypeOptions(hostId)
        }

        /**
         * What the destination card advertises: every flow this host could carry at its
         * best emulated type, independent of the current input device and type pick. The
         * picker compares HOSTS; the type picker and the review narrow to actual choices.
         */
        fun destinationPotential(
            slotId: String,
            hostKind: ConnectionKind,
            hostId: String?,
        ): CapabilitySet {
            val candidateTypes =
                if (hostKind == ConnectionKind.MOONLIGHT) {
                    listOf(MoonlightEmulatedType.XBOX, MoonlightEmulatedType.PLAYSTATION, MoonlightEmulatedType.NINTENDO)
                } else {
                    listOf(
                        CONTROLLER_TYPE_XBOX,
                        CONTROLLER_TYPE_PLAYSTATION,
                        CONTROLLER_TYPE_DUALSENSE,
                        CONTROLLER_TYPE_SWITCHPRO,
                    )
                }
            return destinationPotential(
                candidateTypes.map { capabilityComposer.capabilityForCandidate(slotId, it, hostKind, hostId) },
            )
        }

        /**
         * Auto is resolved on the client, before the wire: an input with motion asks the host
         * for a PlayStation pad, which is the only type its emulator gives a gyro to. The Auto
         * card renders the resolved type's rows for exactly this reason.
         */
        fun moonlightResolvedType(type: Int): Int {
            val picked = MoonlightEmulatedType.fromStored(type)
            if (picked != MoonlightEmulatedType.AUTO) return picked
            val slotId = loadedSlotId ?: return MoonlightEmulatedType.XBOX
            val caps =
                capabilityComposer.capabilityForCandidate(
                    slotId = slotId,
                    candidateType = MoonlightEmulatedType.XBOX,
                    candidateHostKind = ConnectionKind.MOONLIGHT,
                    candidateHostId = _ui.value.draft?.hostId,
                    candidateDirect = _ui.value.candidateDirect,
                )
            return MoonlightEmulatedType.resolve(picked, caps.inputOk(Feature.MOTION))
        }

        /** Re-verify the chosen Moonlight host: on entering the screen, and before a session. */
        fun refreshMoonlight() {
            val hostId = _ui.value.draft?.hostId ?: return
            if (!_ui.value.isMoonlightHost) return
            val host = moonlight.rememberedHost(hostId)
            if (host == null) {
                // A destination that resolves to nothing leaves the section stuck on its
                // spinner forever, which is the shape of every silent failure on this
                // path. Unreachable is the honest word and it carries a Retry.
                Log.w(TAG, "no Moonlight host behind $hostId; rendering it unreachable")
                _ui.update { it.copy(moonlight = MoonlightSessionInput(trust = MoonlightTrustState.UNREACHABLE)) }
                return
            }
            _ui.update { it.copy(moonlight = MoonlightSessionInput()) }
            viewModelScope.launch {
                val probe = moonlight.probe(host)
                _ui.update { state -> state.copy(moonlight = moonlightInputFrom(probe, hostId)) }
            }
        }

        private fun moonlightInputFrom(
            probe: MoonlightProbe,
            hostId: String,
        ): MoonlightSessionInput {
            val conn = moonlight.get(hostId)
            val slotId = loadedSlotId
            val pad = slotId?.let { conn?.padFor(it) }
            val appName = conn?.sessionAppName?.takeIf { it.isNotBlank() } ?: moonlight.rememberedAppName(hostId)
            val phase =
                when {
                    conn?.state?.value == MoonlightSessionState.Live && pad != null ->
                        MoonlightPhase.Live(pad.number + 1, appName)
                    conn?.state?.value == MoonlightSessionState.Live ->
                        MoonlightPhase.Joining(conn.padCount + 1, appName)
                    conn?.state?.value == MoonlightSessionState.Dropped -> MoonlightPhase.Dropped
                    conn?.state?.value == MoonlightSessionState.Ended -> MoonlightPhase.Ended
                    probe.ownSession -> MoonlightPhase.Joining((conn?.padCount ?: 0) + 1, appName)
                    else -> MoonlightPhase.Idle
                }
            val full = (conn?.padCount ?: 0) >= MOONLIGHT_MAX_PADS && pad == null
            return MoonlightSessionInput(
                trust = probe.trust,
                pairing = moonlightPairing,
                apps = appsUiFrom(probe),
                phase = phase,
                failure = if (full) MoonlightFailure.HostFull else moonlightFailure,
                selectedAppId = moonlight.rememberedAppId(hostId).takeIf { it.isNotEmpty() },
            )
        }

        private fun appsUiFrom(probe: MoonlightProbe): MoonlightApps =
            when {
                probe.trust != MoonlightTrustState.PAIRED -> MoonlightApps.Loading
                probe.appsFailed -> MoonlightApps.Failed
                !probe.appsFetched -> MoonlightApps.Loading
                probe.apps.isEmpty() -> MoonlightApps.Empty
                else -> MoonlightApps.Ready(probe.apps.map { MoonlightAppUi(it.id, it.title.ifEmpty { it.id }) })
            }

        fun moonlightAddress(hostId: String): String = moonlight.rememberedHost(hostId)?.address.orEmpty()

        /** Persist the app this session will start; the next binding on the host inherits it. */
        fun selectMoonlightApp(app: MoonlightAppUi) {
            val hostId = _ui.value.draft?.hostId ?: return
            moonlight.rememberApp(hostId, app.id, app.title)
            _ui.update { state ->
                state.copy(moonlight = state.moonlight?.copy(selectedAppId = app.id))
            }
        }

        fun onMoonlightAction(action: MoonlightAction) {
            val hostId = _ui.value.draft?.hostId
            if (hostId == null) {
                Log.w(TAG, "Moonlight action $action with no destination chosen")
                return
            }
            val host = moonlight.rememberedHost(hostId)
            if (host == null) {
                Log.w(TAG, "Moonlight action $action for unknown host $hostId")
                refreshMoonlight()
                return
            }
            Log.i(TAG, "Moonlight action $action on ${host.address}")
            when (action) {
                MoonlightAction.PAIR, MoonlightAction.PAIR_AGAIN, MoonlightAction.TRY_AGAIN,
                MoonlightAction.NEW_CODE,
                -> startMoonlightPairing(host)
                MoonlightAction.CANCEL -> {
                    cancelMoonlightPairing()
                    moonlightPairing = null
                    refreshMoonlight()
                }
                MoonlightAction.QUIT_APP -> {
                    moonlight.quitHostApp(host)
                    moonlightFailure = null
                    refreshMoonlight()
                }
                MoonlightAction.RETRY, MoonlightAction.RECONNECT, MoonlightAction.START_SESSION -> {
                    moonlightFailure = null
                    moonlight.disconnect(hostId)
                    moonlight.retrySessions()
                    refreshMoonlight()
                }
                MoonlightAction.SEE_BINDINGS -> Unit
            }
        }

        // A live job is REPLACED, not a reason to do nothing. New code is only ever offered
        // while a pairing is in flight, so the old guard made the one button that state
        // exists to offer unreachable by construction.
        private fun startMoonlightPairing(host: com.tinkernorth.dish.core.net.moonlight.MoonlightHost) {
            cancelMoonlightPairing()
            pairingJob =
                viewModelScope.launch {
                    moonlight.pairHost(host)
                    refreshMoonlight()
                }
        }

        private fun cancelMoonlightPairing() {
            pairingJob?.cancel()
            pairingJob = null
        }

        private fun observeMoonlightEvents() {
            moonlight.events
                .onEach { event -> onMoonlightEvent(event) }
                .launchIn(viewModelScope)
        }

        private fun onMoonlightEvent(event: MoonlightConnectionEvent) {
            when (event) {
                is MoonlightConnectionEvent.PairingPinReady -> moonlightPairing = MoonlightPairingUi.Pin(event.pin)
                is MoonlightConnectionEvent.PairingFailed -> {
                    Log.w(TAG, "pairing with ${event.host.address} failed: ${event.reason}")
                    moonlightPairing = MoonlightPairingUi.Failed
                }
                is MoonlightConnectionEvent.Paired -> moonlightPairing = null
                is MoonlightConnectionEvent.AppAlreadyRunning ->
                    if (!event.resumable) moonlightFailure = MoonlightFailure.BusyOther
                is MoonlightConnectionEvent.RejoinRefused -> moonlightFailure = MoonlightFailure.ResumeFailed
                is MoonlightConnectionEvent.LaunchRefused -> moonlightFailure = MoonlightFailure.Refused(event.message)
                is MoonlightConnectionEvent.SetupFailed -> moonlightFailure = MoonlightFailure.SetupFailed
                is MoonlightConnectionEvent.HostFull -> moonlightFailure = MoonlightFailure.HostFull
                is MoonlightConnectionEvent.HostReplaced, is MoonlightConnectionEvent.EndedByHost -> Unit
                is MoonlightConnectionEvent.Error, is MoonlightConnectionEvent.Notice -> Unit
            }
            _ui.update { state ->
                if (!state.isMoonlightHost) {
                    state
                } else {
                    state.copy(
                        moonlight =
                            state.moonlight?.copy(pairing = moonlightPairing, failure = moonlightFailure)
                                ?: MoonlightSessionInput(pairing = moonlightPairing, failure = moonlightFailure),
                    )
                }
            }
        }

        fun setDirect(on: Boolean) = _ui.update { it.copy(draft = it.draft?.copy(directOn = on)).withCapabilities() }

        fun setMotion(on: Boolean) = _ui.update { it.copy(draft = it.draft?.copy(motionOn = on)).withCapabilities() }

        fun setRumble(on: Boolean) = _ui.update { it.copy(draft = it.draft?.copy(rumbleOn = on)).withCapabilities() }

        /**
         * Turning the microphone on is also the moment to ask for it: the grant is what
         * makes the toggle mean anything, and asking here is what lets the foreground
         * service claim its microphone type later. Turning it off never asks.
         */
        fun setMic(on: Boolean) {
            _ui.update { it.copy(draft = it.draft?.copy(micOn = on)).withCapabilities() }
            if (on && !micPermission.granted) _micPermissionRequests.tryEmit(Unit)
        }

        fun setSpeaker(on: Boolean) = _ui.update { it.copy(draft = it.draft?.copy(speakerOn = on)).withCapabilities() }

        /** Re-read RECORD_AUDIO: on resume, and when a request the screen launched resolves. */
        fun refreshMicPermission() {
            micPermission.refresh()
            _ui.update { it.copy(micPermissionGranted = micPermission.granted) }
        }

        /** The row's "needs permission" affordance: ask again, from the same seam. */
        fun requestMicPermission() {
            refreshMicPermission()
            if (!micPermission.granted) _micPermissionRequests.tryEmit(Unit)
        }

        // Inherent path capability for the current draft, used by the screen's gates and the
        // setup type cards. Keyed by the loaded slot so a USB path switch is reflected on reload.
        fun capabilityForCandidate(
            slotId: String,
            candidateType: Int,
            candidateHostKind: ConnectionKind,
            candidateHostId: String?,
        ): SlotCapabilities =
            capabilityComposer.capabilityForCandidate(
                slotId = slotId,
                candidateType = candidateType,
                candidateHostKind = candidateHostKind,
                candidateHostId = candidateHostId,
                candidateDirect = _ui.value.candidateDirect,
            )

        // Re-resolves the path capabilities from the current draft/host so the gates stay in sync.
        // userEnabled is forced full inside the composer, so these are the inherent "available" layers.
        private fun ConfigUiState.withCapabilities(): ConfigUiState {
            val slotId = loadedSlotId ?: return copy(capabilities = SlotCapabilities.NONE)
            val d = draft ?: return copy(capabilities = SlotCapabilities.NONE)
            // An unresolved type has no known capabilities yet: the caps rows stay hidden behind the loader.
            val kind = selectedHost?.kind ?: ConnectionKind.SATELLITE
            val caps =
                d.type?.let {
                    capabilityComposer.capabilityForCandidate(
                        slotId = slotId,
                        candidateType = if (kind == ConnectionKind.MOONLIGHT) moonlightResolvedType(it) else it,
                        candidateHostKind = kind,
                        candidateHostId = d.hostId,
                        candidateDirect = candidateDirect,
                    )
                } ?: SlotCapabilities.NONE
            return copy(capabilities = caps, inputFuncs = capabilityComposer.inputFunctionsFor(slotId, candidateDirect))
        }

        // The label for a controller type from the live catalog, falling back to the
        // bundled names; shared by the dashboard configure screen and the setup flow.
        fun typeLabel(type: Int): String =
            _ui.value.typeOptions
                .firstOrNull { it.id == type }
                ?.label
                ?: context.getString(bundledControllerTypeLabelRes(type))

        fun dismissApplyResult() {
            _applyState.value = ApplyState.Idle
        }

        fun unbind() {
            loadedSlotId?.let { hub.unbind(it) }
        }

        fun reconnectHosts() {
            hub.autoReconnectAll()
        }

        fun dismissUnsteady() =
            _ui.update { state ->
                val hostId = state.draft?.hostId ?: return@update state
                state.copy(dismissedUnsteadyHostIds = state.dismissedUnsteadyHostIds + hostId)
            }

        private fun controllerPresent(snapshot: BindingSnapshot?): Boolean {
            if (snapshot == null || snapshot.link == BindingLink.ONSCREEN) return true
            val id = snapshot.slotId.toIntOrNull() ?: return true
            val twins =
                gamepadRegistry.devices.value.values.filter { device ->
                    device.id == id ||
                        (snapshot.vendorId != 0 && device.vendorId == snapshot.vendorId && device.productId == snapshot.productId)
                }
            return twins.any { !it.isDisconnecting }
        }

        /**
         * Nothing the user edits commits until here. The whole binding is ONE
         * declarative call to the satellite: the descriptor (type, caps,
         * touchpad routing) travels with the bind, so the overlay shows one
         * spinner per real async action: the USB-direct switch (which can wait
         * on a system permission prompt) and the single REST round-trip.
         */
        fun apply() {
            val state = _ui.value
            val snapshot = state.snapshot
            val draft = state.draft
            // Apply is gated on canApply (a resolved type); guard defensively so an unresolved
            // type never ships. Every one of these used to return without a word, so a Bind
            // button that could not act was indistinguishable from one that had not been pressed.
            val type = draft?.type
            val host = state.hosts.firstOrNull { it.id == draft?.hostId }
            if (snapshot == null || draft == null || type == null || host == null) {
                Log.w(
                    TAG,
                    "apply refused: snapshot=${snapshot != null} draft=${draft != null} " +
                        "type=$type host=${draft?.hostId}",
                )
                return
            }
            val hostId = host.id
            if (_applyState.value is ApplyState.Running) return

            val steps = buildSteps(state)
            viewModelScope.launch {
                var done = 0
                _applyState.value = ApplyState.Running(steps, done)

                var directFellBack = false
                if (snapshot.link == BindingLink.USB && snapshot.directCapable) {
                    val achieved = applyUsbPath(snapshot.slotId, draft.directOn)
                    directFellBack = draft.directOn && !achieved
                    done++
                    _applyState.value = ApplyState.Running(steps, done)
                }

                val slotId = resolveCurrentSlotId(snapshot)

                if (state.motionAvailable) {
                    // Local gate; its capability bit rides the same descriptor.
                    motionEnabledStore.setEnabled(slotId, draft.motionOn)
                }
                // Rumble is a local delivery gate (the phone vibrates as a fallback),
                // so it applies regardless of the controller's own motor.
                rumbleEnabledStore.setEnabled(slotId, draft.rumbleOn)
                // Audio persists like motion, gated on the path carrying it: writing a
                // mic "on" for a slot that has no microphone endpoint would advertise one
                // the moment the user later moved that slot to a host that does.
                if (state.micAvailable) micEnabledStore.setEnabled(slotId, draft.micOn)
                if (state.speakerAvailable) speakerEnabledStore.setEnabled(slotId, draft.speakerOn)
                val bound = hub.bind(slotId, hostId, type)
                if (!bound) {
                    _applyState.value =
                        ApplyState.Finished(
                            errorMessage = context.getString(R.string.binding_apply_error_slot_gone, snapshot.name),
                            warningMessage = null,
                            hostName = host.label,
                            controllerName = snapshot.name,
                        )
                    return@launch
                }
                val applied = awaitApplied(host, slotId)
                done++
                _applyState.value = ApplyState.Running(steps, done)
                if (!applied) {
                    _applyState.value =
                        ApplyState.Finished(
                            errorMessage = context.getString(R.string.binding_apply_error_no_connect, host.label),
                            warningMessage = null,
                            hostName = host.label,
                            controllerName = snapshot.name,
                        )
                    return@launch
                }

                val warningMessage =
                    if (directFellBack) {
                        context.getString(R.string.binding_apply_warn_detail, snapshot.name, host.label)
                    } else {
                        null
                    }
                _applyState.value =
                    ApplyState.Finished(
                        errorMessage = null,
                        warningMessage = warningMessage,
                        hostName = host.label,
                        controllerName = snapshot.name,
                    )
            }
        }

        private fun buildSteps(state: ConfigUiState): List<ApplyStep> {
            val out = mutableListOf<ApplyStep>()
            val snapshot = state.snapshot
            if (snapshot?.link == BindingLink.USB && snapshot.directCapable) {
                out += ApplyStep(STEP_DIRECT, context.getString(R.string.binding_label_connection))
            }
            out += ApplyStep(STEP_APPLY, context.getString(R.string.binding_label_destination))
            return out
        }

        private suspend fun applyUsbPath(
            slotId: String,
            wantDirect: Boolean,
        ): Boolean {
            val deviceId = slotId.toIntOrNull() ?: return !wantDirect
            val device = gamepadRegistry.devices.value[deviceId] ?: return !wantDirect
            usbGamepadManager.setPathChoice(
                device.vendorId,
                device.productId,
                if (wantDirect) PathChoice.Direct else PathChoice.Standard,
            )
            if (!wantDirect) return true
            val key = vpKey(device)
            // Direct shows a system permission prompt; wait out the FSM (Routed while still wanting Direct = prompt open).
            val settled =
                withTimeoutOrNull(DIRECT_TIMEOUT_MS) {
                    usbGamepadManager.controllers.first { map ->
                        val c = map[key]
                        when (c?.phase) {
                            UsbPhase.Direct, UsbPhase.NeedsReplug, UsbPhase.RestoreStuck -> true
                            UsbPhase.Routed -> c.desired != PathChoice.Direct
                            UsbPhase.Claiming, UsbPhase.AwaitingFramework, null -> false
                        }
                    }
                }
            return settled?.get(key)?.phase == UsbPhase.Direct
        }

        // A USB path switch can retire the slot id the screen was opened with:
        // Direct replaces the framework id with a synthetic twin; Standard
        // re-enumerates the framework id. Prefer whichever exists right now.
        private fun resolveCurrentSlotId(snapshot: BindingSnapshot): String {
            val original = snapshot.slotId.toIntOrNull() ?: return snapshot.slotId
            if (gamepadRegistry.devices.value.containsKey(original)) return snapshot.slotId
            if (snapshot.vendorId == 0 && snapshot.productId == 0) return snapshot.slotId
            val twin =
                gamepadRegistry.devices.value.values.firstOrNull {
                    it.vendorId == snapshot.vendorId && it.productId == snapshot.productId
                }
            return twin?.id?.toString() ?: snapshot.slotId
        }

        /**
         * The bind's REST round-trip, observed: a satellite host is applied
         * once the slot's descriptor is confirmed (`registered`); a Bluetooth
         * host once the link is live. Times out into the error overlay rather
         * than spinning forever.
         */
        private suspend fun awaitApplied(
            host: BindingHost,
            slotId: String,
        ): Boolean {
            // A Moonlight binding is a durable intent, not a handshake: the session is started
            // by the binding itself and is allowed to take its time, so applying never waits
            // on a link that does not exist yet.
            if (host.kind == ConnectionKind.MOONLIGHT) return true
            val hostUp =
                withTimeoutOrNull(CONNECT_TIMEOUT_MS) {
                    hub.connections.first { conns ->
                        val live = conns.firstOrNull { it.id == host.id }?.live
                        live == LinkState.Connected || live == LinkState.Unstable
                    }
                    true
                } ?: false
            if (!hostUp) return false
            if (host.kind != ConnectionKind.SATELLITE) return true
            val conn = satellite.get(host.id) ?: return false
            return withTimeoutOrNull(APPLY_TIMEOUT_MS) {
                conn.slots.first { it[slotId]?.registered == true }
                true
            } ?: false
        }

        // Bundled fallback when no catalog has ever been fetched (offline
        // first-run): the two types this app ships art for.
        private fun bundledTypeOptions(): List<TypeOption> =
            listOf(
                TypeOption(CONTROLLER_TYPE_PLAYSTATION, context.getString(R.string.picker_type_playstation)),
                TypeOption(CONTROLLER_TYPE_XBOX, context.getString(R.string.picker_type_xbox)),
                TypeOption(CONTROLLER_TYPE_DUALSENSE, context.getString(R.string.picker_type_dualsense)),
                TypeOption(CONTROLLER_TYPE_SWITCHPRO, context.getString(R.string.picker_type_switchpro)),
            )

        // The four types a Moonlight host can be asked to plug in. Hard-coded because no host
        // reports them: the type byte travels client to host in CONTROLLER_ARRIVAL and nothing
        // comes back the other way.
        private fun moonlightTypeOptions(): List<TypeOption> =
            MoonlightEmulatedType.ORDER.map { TypeOption(it, context.getString(moonlightTypeLabelRes(it))) }

        // A Moonlight host owns its own four types and has no catalog to fetch, so it must be
        // answered before the satellite lookup: satellite.get() is null for a Moonlight id, which
        // used to leave the type unresolved forever and Apply disabled with it.
        private fun refreshTypeOptions(hostId: String) {
            if (hub.summary(hostId)?.kind == ConnectionKind.MOONLIGHT) {
                val stored = loadedSlotId?.let { hub.satTypes.value[hostId to it] }
                val seeded = MoonlightEmulatedType.fromStored(stored ?: moonlight.rememberedEmulatedType(hostId))
                _ui.update { state ->
                    state
                        .copy(
                            typeOptions = moonlightTypeOptions(),
                            typeFetchFailed = false,
                            draft = state.draft?.copy(type = state.draft.type ?: seeded),
                        ).withCapabilities()
                }
                refreshMoonlight()
                return
            }
            val conn = satellite.get(hostId)
            if (conn == null) {
                _ui.update { it.copy(typeOptions = bundledTypeOptions()) }
                return
            }
            // Fresh resolve for this satellite host: clear any prior error, seed from cache if present.
            _ui.update { state ->
                val cleared = state.copy(typeFetchFailed = false)
                catalogRepo.cached(hostId)?.let { cached ->
                    cleared
                        .copy(typeOptions = typeOptionsFrom(cached.controllerTypes))
                        .withCatalogDefault(cached.controllerTypes)
                } ?: cleared
            }
            viewModelScope.launch {
                // Probe live host state first: it seeds the host layer + pre-bind runtime
                // (motion backend up/down) before the catalog round-trip, so the candidate
                // report reflects the real receiver even if the catalog is slow/unreachable.
                capabilitiesRepo.refresh(conn.server.value, hostId)
                _ui.update { state -> state.withCapabilities() }
                val catalog = catalogRepo.catalogFor(conn.server.value, hostId)
                if (catalog == null) {
                    // Fetch failed: surface Error only when nothing is cached (a cache still resolves the type).
                    _ui.update { state -> if (catalogRepo.cached(hostId) == null) state.copy(typeFetchFailed = true) else state }
                    return@launch
                }
                // Recompute the gates too: the fetched catalog's per-type features now back
                // the type layer, not just the picker labels.
                _ui.update { state ->
                    state
                        .copy(typeOptions = typeOptionsFrom(catalog.controllerTypes), typeFetchFailed = false)
                        .withCatalogDefault(catalog.controllerTypes)
                        .withCapabilities()
                }
            }
        }

        private fun typeOptionsFrom(types: List<CatalogTypeDto>): List<TypeOption> {
            if (types.isEmpty()) return bundledTypeOptions()
            return types.map { t ->
                val bundled =
                    when (t.slug) {
                        SLUG_XBOX360 -> context.getString(R.string.picker_type_xbox)
                        SLUG_DS4 -> context.getString(R.string.picker_type_playstation)
                        SLUG_DUALSENSE -> context.getString(R.string.picker_type_dualsense)
                        SLUG_SWITCHPRO -> context.getString(R.string.picker_type_switchpro)
                        else -> null
                    }
                TypeOption(t.id, bundled ?: t.name.ifBlank { t.slug })
            }
        }

        // Once the host's catalog is known, an unresolved type (no remembered binding, no manual pick)
        // snaps to the first offered type. A resolved (non-null) type is never overwritten.
        private fun ConfigUiState.withCatalogDefault(types: List<CatalogTypeDto>): ConfigUiState {
            val d = draft ?: return this
            if (d.type != null) return this
            val firstId = types.firstOrNull()?.id ?: return this
            return copy(draft = d.copy(type = firstId))
        }

        private fun buildSnapshot(slotId: String): BindingSnapshot {
            val bound = hub.bindings.value[slotId] != null
            if (slotId == VIRTUAL_SLOT_ID) {
                return BindingSnapshot(
                    slotId = slotId,
                    name = context.getString(R.string.default_virtual_controller_name),
                    link = BindingLink.ONSCREEN,
                    directCapable = false,
                    directVerified = false,
                    bound = bound,
                    directPollHz = 0,
                )
            }
            val device = slotId.toIntOrNull()?.let { gamepadRegistry.devices.value[it] }
            val isUsb = device?.transport != Transport.Bluetooth
            val vid = device?.vendorId ?: 0
            val pid = device?.productId ?: 0
            return BindingSnapshot(
                slotId = slotId,
                name = device?.name ?: "",
                link = if (isUsb) BindingLink.USB else BindingLink.BLUETOOTH,
                directCapable = isUsb,
                directVerified = native.isKnownFastLaneModel(vid, pid),
                bound = bound,
                directPollHz = device?.pollRateHz ?: 0,
                vendorId = vid,
                productId = pid,
            )
        }

        private fun buildHosts(): List<BindingHost> =
            connectionsVisibleInPicker(hub.connections.value, loadedSlotId?.let { hub.bindings.value[it] })
                .map { BindingHost(it.id, it.label, it.kind) }
                .orderedForPicker()

        private fun buildSeedDraft(slotId: String): BindingDraft {
            val hostId = hub.bindings.value[slotId]
            val remembered = hostId?.let { hub.satTypes.value[it to slotId] }
            val device = slotId.toIntOrNull()?.let { gamepadRegistry.devices.value[it] }
            return BindingDraft(
                hostId = hostId,
                // No guessed default: unresolved (null) until the catalog (or a remembered pick) sets it.
                type = remembered,
                directOn = seedDirectOn(device, desiredUsbPathFor(device)),
                motionOn = motionEnabledStore.isEnabled(slotId),
                rumbleOn = rumbleEnabledStore.isEnabled(slotId),
                micOn = micEnabledStore.isEnabled(slotId),
                speakerOn = speakerEnabledStore.isEnabled(slotId),
            )
        }

        private fun desiredUsbPathFor(device: PhysicalGamepadRegistry.Device?): PathChoice? =
            device?.let { usbGamepadManager.controllers.value[vpKey(it)]?.desired }

        private fun vpKey(device: PhysicalGamepadRegistry.Device): Int = (device.vendorId shl 16) or device.productId

        private companion object {
            const val TAG = "ConfigureBindingsVM"
            const val DIRECT_TIMEOUT_MS = 20_000L
            const val CONNECT_TIMEOUT_MS = 8_000L
            const val APPLY_TIMEOUT_MS = 8_000L
            const val STEP_DIRECT = "direct"
            const val STEP_APPLY = "apply"
            const val SLUG_XBOX360 = "xbox360"
            const val SLUG_DS4 = "ds4"
            const val SLUG_DUALSENSE = "dualsense"
            const val SLUG_SWITCHPRO = "switchpro"
        }
    }

internal fun seedDirectOn(
    device: PhysicalGamepadRegistry.Device?,
    desired: PathChoice?,
): Boolean =
    when {
        device == null -> false
        device.isUsbSynthetic -> true
        device.transport == Transport.Bluetooth -> false
        else -> desired == PathChoice.Direct
    }
