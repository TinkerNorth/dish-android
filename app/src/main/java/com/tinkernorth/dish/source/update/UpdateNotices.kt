// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.source.update

import androidx.lifecycle.DefaultLifecycleObserver
import kotlinx.coroutines.flow.StateFlow

// What the screens see of the update feature. The github flavor binds a
// coordinator that asks GitHub Releases for the newest build; the play flavor
// binds NoUpdateNotices, because Google Play reserves updating a
// Play-distributed app to Play itself, so no update code compiles into that
// artifact. Registered on the process lifecycle, so checks run only while the
// app is on screen.
interface UpdateNotices : DefaultLifecycleObserver {
    // False on a build whose distribution channel updates the app itself; the
    // screens then show nothing update-related at all.
    val supported: Boolean

    val status: StateFlow<UpdateNoticeStatus>

    // The Settings switch. Off means no update-related network request of any
    // kind, including from the Settings button.
    fun setChecksEnabled(enabled: Boolean)

    // The Settings button. Rate-limited by the implementation.
    fun checkNow()

    // Mutes the version on offer until a newer one appears. Ignored while the
    // running build is below the release's supported minimum.
    fun skipAvailableVersion()
}

enum class UpdateNoticePhase {
    Disabled,
    Idle,
    Checking,
    UpToDate,
    Available,
    Failed,
}

// The slice a screen renders. `availableVersion` and `downloadUrl` are set only
// in Available; `required` means the running build is below the release's
// supported minimum, so the notice cannot be skipped.
data class UpdateNoticeStatus(
    val phase: UpdateNoticePhase = UpdateNoticePhase.Idle,
    val checksEnabled: Boolean = true,
    val availableVersion: String = "",
    val downloadUrl: String = "",
    val required: Boolean = false,
)
