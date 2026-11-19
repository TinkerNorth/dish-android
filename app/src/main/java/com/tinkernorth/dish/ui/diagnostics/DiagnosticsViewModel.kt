// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.diagnostics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tinkernorth.dish.core.jni.PhysicalInputNative
import com.tinkernorth.dish.source.controllerdb.ControllerDbStatus
import com.tinkernorth.dish.source.store.DiagnosticsLogEntry
import com.tinkernorth.dish.source.store.DiagnosticsLogStore
import com.tinkernorth.dish.source.store.LatencyProfilingStore
import com.tinkernorth.dish.source.system.WifiLink
import com.tinkernorth.dish.source.system.WifiLinkSource
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Inject

@HiltViewModel
class DiagnosticsViewModel
    @Inject
    constructor(
        latencyProfilingStore: LatencyProfilingStore,
        private val physicalInputNative: PhysicalInputNative,
        private val wifiLinkSource: WifiLinkSource,
        private val json: Json,
        private val sources: DiagnosticsSources,
        diagnosticsLog: DiagnosticsLogStore,
        private val support: DiagnosticsSupport,
    ) : ViewModel() {
        sealed interface LatencyUi {
            data object Off : LatencyUi

            data object Waiting : LatencyUi

            data class Stats(
                val panel: LatencyPanel,
            ) : LatencyUi
        }

        data class Overview(
            val controllers: List<ControllerDiag>,
            val hosts: List<HostDiag>,
            val radios: RadioFacts,
            val latencyRows: LatencyRows,
            val ignoredInputs: List<IgnoredInputDiag> = emptyList(),
        ) {
            companion object {
                val EMPTY = Overview(emptyList(), emptyList(), RadioFacts.NONE, LatencyRows(emptyList(), emptyList()))
            }
        }

        // The latency card reads the probe's state and the overview's rows together, so the screen
        // renders one emission instead of caching two.
        data class LatencyPanelUi(
            val ui: LatencyUi,
            val rows: LatencyRows,
        )

        // WhileSubscribed ties the probe to the screen: the collector lives inside
        // repeatOnLifecycle(STARTED), so leaving the screen stops the fast pings.
        @OptIn(ExperimentalCoroutinesApi::class)
        val latency: StateFlow<LatencyUi> =
            latencyProfilingStore.state
                .flatMapLatest { enabled -> if (enabled) probeTicks() else flowOf(LatencyUi.Off) }
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), LatencyUi.Off)

        val wifi: StateFlow<WifiLink?> =
            flow<WifiLink?> {
                while (true) {
                    emit(wifiLinkSource.read())
                    delay(WIFI_POLL_MS)
                }
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), null)

        private val world: Flow<DiagnosticsWorld> =
            sources.world.shareIn(viewModelScope, SharingStarted.WhileSubscribed(), replay = 1)

        val overview: StateFlow<Overview> =
            world
                .map(::overviewOf)
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), Overview.EMPTY)

        val latencyPanel: StateFlow<LatencyPanelUi> =
            combine(latency, overview) { ui, overview -> LatencyPanelUi(ui, overview.latencyRows) }
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), LatencyPanelUi(LatencyUi.Off, Overview.EMPTY.latencyRows))

        val events: StateFlow<List<DiagnosticsLogEntry>> = diagnosticsLog.state

        val controllerDb: StateFlow<ControllerDbStatus> get() = support.controllerDb.status

        // A bundle written and ready to attach, with the report it was made from for the body.
        class PreparedMail internal constructor(
            val file: File,
            val overview: Overview,
            internal val world: DiagnosticsWorld,
        )

        sealed interface SupportEvent {
            data class MailReady(
                val mail: PreparedMail,
            ) : SupportEvent

            data object MailFailed : SupportEvent

            data class Captured(
                val name: String,
                val ok: Boolean,
            ) : SupportEvent
        }

        private val _supportEvents = MutableSharedFlow<SupportEvent>(extraBufferCapacity = 1)
        val supportEvents: SharedFlow<SupportEvent> = _supportEvents.asSharedFlow()

        fun checkControllerDbNow() = support.controllerDb.checkNow()

        fun emailDiagnostics() {
            viewModelScope.launch {
                val snapshot = world.first()
                val overview = overviewOf(snapshot)
                val file = support.writeBundle(snapshot, overview)
                val event = file?.let { SupportEvent.MailReady(PreparedMail(it, overview, snapshot)) } ?: SupportEvent.MailFailed
                _supportEvents.emit(event)
            }
        }

        fun capture(diag: ControllerDiag) {
            val deviceId = diag.slotId.toIntOrNull() ?: return
            viewModelScope.launch {
                val ok = support.capture(deviceId, diag.slotId)
                _supportEvents.emit(SupportEvent.Captured(diag.name, ok))
            }
        }

        private fun overviewOf(w: DiagnosticsWorld): Overview {
            val controllers = controllerDiags(w, sources::touchpadMode)
            val hosts = hostDiags(w, sources::touchpadMode)
            return Overview(controllers, hosts, w.radios, latencyRows(controllers, hosts), ignoredInputDiags(w))
        }

        private fun probeTicks(): Flow<LatencyUi> =
            flow {
                physicalInputNative.setLatencyProbe(true)
                try {
                    while (true) {
                        val panel = parseLatencyPanel(physicalInputNative.hotPathBenchJson(false), json)
                        emit(panel?.let { LatencyUi.Stats(it) } ?: LatencyUi.Waiting)
                        delay(LATENCY_POLL_MS)
                    }
                } finally {
                    physicalInputNative.setLatencyProbe(false)
                }
            }

        private companion object {
            const val LATENCY_POLL_MS = 1000L
            const val WIFI_POLL_MS = 2000L
        }
    }
