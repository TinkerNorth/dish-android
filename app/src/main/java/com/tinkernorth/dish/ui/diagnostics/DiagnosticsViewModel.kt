// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.diagnostics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tinkernorth.dish.core.jni.PhysicalInputNative
import com.tinkernorth.dish.source.store.LatencyProfilingStore
import com.tinkernorth.dish.source.system.WifiLink
import com.tinkernorth.dish.source.system.WifiLinkSource
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.json.Json
import javax.inject.Inject

@HiltViewModel
class DiagnosticsViewModel
    @Inject
    constructor(
        latencyProfilingStore: LatencyProfilingStore,
        private val physicalInputNative: PhysicalInputNative,
        private val wifiLinkSource: WifiLinkSource,
        private val json: Json,
    ) : ViewModel() {
        sealed interface LatencyUi {
            data object Off : LatencyUi

            data object Waiting : LatencyUi

            data class Stats(
                val panel: LatencyPanel,
            ) : LatencyUi
        }

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
