// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.diagnostics

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tinkernorth.dish.ui.main.VIRTUAL_SLOT_ID
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

data class BindingInspectorUiState(
    val binding: BindingDiag?,
    val loaded: Boolean,
    val nowMs: Long,
)

@HiltViewModel
class BindingInspectorViewModel
    @Inject
    constructor(
        savedState: SavedStateHandle,
        private val sources: DiagnosticsSources,
    ) : ViewModel() {
        val slotId: String = savedState.get<String>(EXTRA_SLOT_ID) ?: VIRTUAL_SLOT_ID

        val ui: StateFlow<BindingInspectorUiState> =
            sources.world
                .map { world ->
                    BindingInspectorUiState(
                        binding = bindingDiag(slotId, world, sources::touchpadMode),
                        loaded = true,
                        nowMs = world.nowMs,
                    )
                }.stateIn(
                    viewModelScope,
                    SharingStarted.WhileSubscribed(),
                    BindingInspectorUiState(binding = null, loaded = false, nowMs = 0L),
                )

        companion object {
            const val EXTRA_SLOT_ID = "extra_slot_id"
            const val EXTRA_LABEL = "extra_label"
        }
    }
