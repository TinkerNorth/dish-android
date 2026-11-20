// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.ui.diagnostics

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tinkernorth.dish.composer.ReportCategory
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

// One controller the report may concern, by the name the person sees.
data class ReportControllerChoice(
    val slotId: String,
    val name: String,
)

// "Report a problem" and "Ask a question": the same screen, started in a different category.
@HiltViewModel
class SupportReportViewModel
    @Inject
    constructor(
        savedState: SavedStateHandle,
        private val sources: DiagnosticsSources,
        private val support: DiagnosticsSupport,
    ) : ViewModel() {
        data class UiState(
            val startCategory: ReportCategory?,
            val controllers: List<ReportControllerChoice>,
            val defaultSlotId: String?,
            val preparing: Boolean,
        )

        sealed interface Event {
            data class Preview(
                val text: String,
            ) : Event

            data class MailReady(
                val file: File,
                val report: PreparedReport,
            ) : Event

            data object Failed : Event
        }

        private val isQuestion: Boolean = savedState.get<Boolean>(EXTRA_QUESTION) == true

        private val world: Flow<DiagnosticsWorld> =
            sources.world.shareIn(viewModelScope, SharingStarted.WhileSubscribed(), replay = 1)

        private val preparing = MutableStateFlow(false)

        val uiState: StateFlow<UiState> =
            combine(world, preparing) { w, busy -> uiStateOf(diagnosticsOverviewOf(w, sources::touchpadMode), busy) }
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), UiState(startCategoryOf(isQuestion), emptyList(), null, false))

        private val _events = MutableSharedFlow<Event>(extraBufferCapacity = 1)
        val events: SharedFlow<Event> = _events.asSharedFlow()

        fun preview(draft: ReportDraft) {
            launchPrepared(draft) { prepared -> Event.Preview(prepared.text) }
        }

        fun send(draft: ReportDraft) {
            launchPrepared(draft, ::mailEventOf)
        }

        private suspend fun mailEventOf(prepared: PreparedReport): Event {
            val file = support.write(prepared) ?: return Event.Failed
            return Event.MailReady(file, prepared)
        }

        private fun launchPrepared(
            draft: ReportDraft,
            then: suspend (PreparedReport) -> Event,
        ) {
            if (preparing.value) return
            preparing.value = true
            viewModelScope.launch {
                try {
                    val snapshot = world.first()
                    val overview = diagnosticsOverviewOf(snapshot, sources::touchpadMode)
                    _events.emit(then(support.prepare(snapshot, overview, draft)))
                } finally {
                    preparing.value = false
                }
            }
        }

        private fun uiStateOf(
            overview: DiagnosticsViewModel.Overview,
            busy: Boolean,
        ): UiState =
            UiState(
                startCategory = startCategoryOf(isQuestion),
                controllers = reportChoicesOf(overview.controllers),
                defaultSlotId = defaultReportSlotOf(overview.controllers),
                preparing = busy,
            )

        companion object {
            const val EXTRA_QUESTION = "extra_question"
        }
    }

// "Ask a question" starts on the question; "Report a problem" leaves the category to the person.
internal fun startCategoryOf(isQuestion: Boolean): ReportCategory? = if (isQuestion) ReportCategory.QUESTION else null

// A category the person left unpicked is "something else".
internal fun reportCategoryOrOther(picked: ReportCategory?): ReportCategory = picked ?: ReportCategory.OTHER

// The physical pads the report may concern; the on-screen pad is the phone.
internal fun reportChoicesOf(controllers: List<ControllerDiag>): List<ReportControllerChoice> =
    controllers.filter { !it.isVirtual }.map { ReportControllerChoice(it.slotId, it.name) }
