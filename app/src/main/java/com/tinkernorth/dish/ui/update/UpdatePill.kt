// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.update

import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import com.tinkernorth.dish.R
import com.tinkernorth.dish.source.update.UpdateNoticePhase
import com.tinkernorth.dish.source.update.UpdateNoticeStatus
import com.tinkernorth.dish.source.update.UpdateNotices
import com.tinkernorth.dish.ui.common.observeWhileStarted
import com.tinkernorth.dish.ui.common.slidePillIn
import com.tinkernorth.dish.ui.common.slidePillOut

// The update notice on the main screen: the `updatePill` include docked above
// the donate pill. It renders the coordinator's status and nothing else: shown
// while a newer release is on offer, gone the moment the status says
// otherwise, so a skip or a fresh install hides it without a second source of
// truth. Tapping opens the release through the caller's guarded opener; the
// close button skips that version, which a required update does not offer.
fun AppCompatActivity.attachUpdatePill(
    notices: UpdateNotices,
    open: (String) -> Unit,
) {
    if (!notices.supported) return
    val pill = findViewById<View>(R.id.updatePill) ?: return
    val host = UpdatePillHost(this, pill, notices, open)
    observeWhileStarted(notices.status) { host.render(it) }
}

private class UpdatePillHost(
    private val activity: AppCompatActivity,
    private val pill: View,
    private val notices: UpdateNotices,
    private val open: (String) -> Unit,
) {
    private val headline: TextView = pill.findViewById(R.id.updatePillHeadline)
    private val ask: TextView = pill.findViewById(R.id.updatePillAsk)
    private val dismiss: View = pill.findViewById(R.id.updatePillDismiss)
    private var shown = false

    fun render(status: UpdateNoticeStatus) {
        if (status.phase != UpdateNoticePhase.Available) {
            hide()
            return
        }
        if (status.required) {
            headline.text = activity.getString(R.string.update_pill_headline_required)
            ask.text = activity.getString(R.string.update_pill_ask_required, status.availableVersion)
        } else {
            headline.text = activity.getString(R.string.update_pill_headline, status.availableVersion)
            ask.text = activity.getString(R.string.update_pill_ask)
        }
        dismiss.isVisible = !status.required
        dismiss.setOnClickListener { notices.skipAvailableVersion() }
        pill.setOnClickListener { open(status.downloadUrl) }
        show()
    }

    private fun show() {
        if (shown) return
        shown = true
        pill.isVisible = true
        activity.slidePillIn(pill)
    }

    private fun hide() {
        if (!shown) return
        shown = false
        slidePillOut(pill) {
            if (!shown) pill.isVisible = false
        }
    }
}
