// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.common

import android.app.Activity
import android.content.Intent
import androidx.core.net.toUri
import com.tinkernorth.dish.R
import com.tinkernorth.dish.source.notification.DishNotifications

// The one place a browser intent is built. A device without a browser (a TV
// box, a locked-down profile) throws on startActivity, and ExternalLinkGuardTest
// pins every ACTION_VIEW under ui/ to this helper so that failure is always a
// warning instead of a crash. Screens that are not a BaseGamepadHostActivity
// (the main screen extends the game SDK's activity) call it directly.
fun Activity.openExternalLink(
    url: String,
    notifications: DishNotifications,
) {
    val intent =
        Intent(Intent.ACTION_VIEW, url.toUri())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { startActivity(intent) }
        .onFailure {
            notifications.warn(
                title = getString(R.string.error_open_url),
                body = url,
                key = "external-url-failed",
            )
        }
}
