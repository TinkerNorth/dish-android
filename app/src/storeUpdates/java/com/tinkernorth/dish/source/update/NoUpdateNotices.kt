// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.source.update

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

// Google Play and the Amazon Appstore update the apps they distribute themselves, and Play's policy
// forbids the app pointing anywhere else for that, so the store flavors have no update check: the
// screens see nothing and no update code compiles into either artifact.
class NoUpdateNotices
    @Inject
    constructor() : UpdateNotices {
        override val supported: Boolean = false

        override val status: StateFlow<UpdateNoticeStatus> =
            MutableStateFlow(UpdateNoticeStatus(phase = UpdateNoticePhase.Disabled, checksEnabled = false))

        override fun setChecksEnabled(enabled: Boolean) = Unit

        override fun checkNow() = Unit

        override fun skipAvailableVersion() = Unit
    }
