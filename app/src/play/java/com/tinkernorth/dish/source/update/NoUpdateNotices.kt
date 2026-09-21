// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.source.update

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

// Google Play updates a Play-distributed app itself, and its policy forbids
// the app pointing anywhere else for that, so this flavor has no update check:
// the screens see nothing and no update code compiles into the artifact.
object NoUpdateNotices : UpdateNotices {
    override val supported: Boolean = false

    override val status: StateFlow<UpdateNoticeStatus> =
        MutableStateFlow(UpdateNoticeStatus(phase = UpdateNoticePhase.Disabled, checksEnabled = false))

    override fun setChecksEnabled(enabled: Boolean) = Unit

    override fun checkNow() = Unit

    override fun skipAvailableVersion() = Unit
}
