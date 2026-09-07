// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.diagnostics

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

data class HostInspectorUiState(
    val host: HostDiag?,
    val nowMs: Long,
)

@HiltViewModel
class HostInspectorViewModel
    @Inject
    constructor(
        savedState: SavedStateHandle,
        private val sources: DiagnosticsSources,
    ) : ViewModel() {
        val connectionId: String = savedState.get<String>(EXTRA_CONNECTION_ID).orEmpty()

        val ui: StateFlow<HostInspectorUiState> =
            sources.world
                .map { world ->
                    HostInspectorUiState(
                        host = hostDiags(world, sources::touchpadMode).firstOrNull { it.id == connectionId },
                        nowMs = world.nowMs,
                    )
                }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), HostInspectorUiState(host = null, nowMs = 0L))

        companion object {
            const val EXTRA_CONNECTION_ID = "extra_connection_id"
            const val EXTRA_LABEL = "extra_label"
        }
    }
