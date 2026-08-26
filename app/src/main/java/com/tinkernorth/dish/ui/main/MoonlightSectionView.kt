// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.main

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import androidx.annotation.StringRes
import com.google.android.material.button.MaterialButton
import com.tinkernorth.dish.R
import com.tinkernorth.dish.databinding.BindingSectionMoonlightBinding
import com.tinkernorth.dish.databinding.SetupChoiceRowBinding

// Renders the Moonlight session section so the binding screen and the setup tutorial
// draw the same states from the same code, the way bindCapabilityRows is shared.
// Exactly one state renders at a time; nothing here is ever modal, because a flapping
// session would raise a dialog the user cannot outrun.
fun BindingSectionMoonlightBinding.bindMoonlightSession(
    session: MoonlightSessionUi,
    hostLabel: String,
    onPickApp: (MoonlightAppUi) -> Unit,
    onAction: (MoonlightAction) -> Unit,
) {
    val ctx = root.context
    sessionSpinner.visibility = if (session.showsSpinner) View.VISIBLE else View.GONE

    val titleRes = session.titleRes()
    tvSessionTitle.visibility = if (titleRes == 0) View.GONE else View.VISIBLE
    if (titleRes != 0) {
        tvSessionTitle.text = ctx.formatted(titleRes, session.titleArgs(hostLabel))
        tvSessionTitle.setTextColor(ctx.getColor(session.tone().colorRes()))
    }
    tvSessionBody.text = ctx.formatted(session.bodyRes(), session.bodyArgs(hostLabel))

    val noteRes = session.noteRes()
    tvSessionNote.visibility = if (noteRes == 0) View.GONE else View.VISIBLE
    if (noteRes != 0) tvSessionNote.text = ctx.getString(noteRes, hostLabel)

    bindApps(session, onPickApp)
    bindActions(session, hostLabel, onAction)
}

// A pick-one list, one row per app, and only where the choice actually exists: a binding
// that joins a session already running gets no list rather than a disabled one implying
// a choice it does not have.
private fun BindingSectionMoonlightBinding.bindApps(
    session: MoonlightSessionUi,
    onPickApp: (MoonlightAppUi) -> Unit,
) {
    val inflater = LayoutInflater.from(root.context)
    sessionAppList.removeAllViews()
    val newSession = session as? MoonlightSessionUi.NewSession
    sessionAppList.visibility = if (newSession == null) View.GONE else View.VISIBLE
    newSession?.apps?.forEach { app ->
        val row = SetupChoiceRowBinding.inflate(inflater, sessionAppList, false)
        row.choiceIcon.setImageResource(R.drawable.ic_pc_monitor)
        row.choiceTitle.text = app.title
        row.choiceBody.visibility = View.GONE
        row.choiceBadge.visibility = View.GONE
        row.choiceChevron.visibility = View.GONE
        row.choiceCard.isCheckable = true
        row.choiceCard.isChecked = app.id == newSession.selectedAppId
        row.choiceCard.setOnClickListener { onPickApp(app) }
        sessionAppList.addView(row.root)
    }
}

// The state chooses its own format arguments, so the view can fill a string without
// knowing which state it is drawing; the spread is the price of that indirection.
@Suppress("SpreadOperator")
private fun Context.formatted(
    @StringRes res: Int,
    args: List<Any>,
): String = getString(res, *args.toTypedArray())

// Rebuilt from scratch on every render rather than toggled, because the number of buttons
// changes with the state. The first action gets the filled layout and the rest the outlined
// one, so the ordering in MoonlightSessionUi.actions is what decides which of them reads as
// the recommendation.
private fun BindingSectionMoonlightBinding.bindActions(
    session: MoonlightSessionUi,
    hostLabel: String,
    onAction: (MoonlightAction) -> Unit,
) {
    val ctx = root.context
    val inflater = LayoutInflater.from(ctx)
    sessionActions.removeAllViews()
    val actions = session.actions()
    sessionActions.visibility = if (actions.isEmpty()) View.GONE else View.VISIBLE
    actions.forEachIndexed { index, action ->
        val layout = if (index == 0) R.layout.binding_action_button else R.layout.binding_action_button_outlined
        val button = inflater.inflate(layout, sessionActions, false) as MaterialButton
        button.text = ctx.formatted(action.labelRes(), action.labelArgs(hostLabel))
        button.setOnClickListener { onAction(action) }
        sessionActions.addView(button)
    }
}
