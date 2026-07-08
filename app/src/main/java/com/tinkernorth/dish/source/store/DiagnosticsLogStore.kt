// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.source.store

import com.tinkernorth.dish.architecture.abstracts.AbstractStateSource
import javax.inject.Inject
import javax.inject.Singleton

data class DiagnosticsLogEntry(
    val atMs: Long,
    val tag: String,
    val message: String,
)

/**
 * Bounded in-memory flight recorder for connection and controller lifecycle events, so
 * "it disconnected sometime last night" arrives as a timestamped sequence instead of a
 * memory. Entries are deliberately English: they exist to be copied into bug reports.
 * Never fed from the input hot path; only rare lifecycle transitions land here.
 */
@Singleton
class DiagnosticsLogStore
    @Inject
    constructor() : AbstractStateSource<List<DiagnosticsLogEntry>>(emptyList()) {
        fun log(
            tag: String,
            message: String,
            atMs: Long = System.currentTimeMillis(),
        ) {
            setState { entries -> (entries + DiagnosticsLogEntry(atMs, tag, message)).takeLast(MAX_ENTRIES) }
        }

        companion object {
            const val MAX_ENTRIES = 200
        }
    }
