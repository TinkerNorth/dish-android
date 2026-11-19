// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.ui.diagnostics

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.net.toUri
import com.tinkernorth.dish.R
import com.tinkernorth.dish.ui.common.controllerDbStatusText
import com.tinkernorth.dish.ui.common.statusChipTextRes

const val SUPPORT_EMAIL = "support@tinkernorth.com"
const val BUNDLE_MIME_TYPE = "application/json"
private const val MAILTO = "mailto:"

// Where the mail goes: a mail app when the phone has one, the share sheet otherwise.
sealed interface MailLaunch {
    data object MailApps : MailLaunch

    data object ShareSheet : MailLaunch
}

fun mailLaunchFor(hasAMailApp: Boolean): MailLaunch = if (hasAMailApp) MailLaunch.MailApps else MailLaunch.ShareSheet

// The question asked of the package manager: does any app take a mailto: link.
fun mailAppQuery(): Intent = Intent(Intent.ACTION_SENDTO, MAILTO.toUri())

internal fun Context.mailSubject(
    version: String,
    model: String,
): String = getString(R.string.diagnostics_email_subject, version, model)

// The summary the Diagnostics page shows, in the person's language, with hosts as their aliases,
// then the sentence that says what the file holds and never holds.
internal fun Context.mailBody(
    overview: DiagnosticsViewModel.Overview,
    world: DiagnosticsWorld,
    appLine: String,
): String {
    val lines = mutableListOf(appLine, controllerDbStatusText(world.db.status, world.nowMs), "")
    overview.controllers.filter { !it.isVirtual }.forEach { diag -> lines += controllerSummary(diag, world.nowMs) + "" }
    overview.hosts.forEachIndexed { index, host -> lines += hostSummary(index, host) }
    lines += ""
    lines += getString(R.string.diagnostics_email_attachment)
    return lines.joinToString("\n")
}

private fun Context.controllerSummary(
    diag: ControllerDiag,
    nowMs: Long,
): List<String> {
    val facts = diag.facts
    val head = getString(R.string.diagnostics_joined, diag.name, transportLabel(diag))
    val record = facts?.let { recordLines(it) }.orEmpty()
    val write = facts?.let { lastWriteLine(it, nowMs) }
    return listOf(head) + record + listOfNotNull(write)
}

private fun Context.hostSummary(
    index: Int,
    host: HostDiag,
): String {
    val alias = getString(R.string.diagnostics_host_alias, index + 1)
    return getString(R.string.diagnostics_joined, alias, getString(statusChipTextRes(host.live)))
}

// ACTION_SEND with the recipient, subject, body and the file; a mail app only, where the phone has
// one, by a mailto: selector.
fun diagnosticsMailIntent(
    file: Uri,
    subject: String,
    body: String,
    launch: MailLaunch,
): Intent {
    val send =
        Intent(Intent.ACTION_SEND).apply {
            type = BUNDLE_MIME_TYPE
            putExtra(Intent.EXTRA_EMAIL, arrayOf(SUPPORT_EMAIL))
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, body)
            putExtra(Intent.EXTRA_STREAM, file)
            clipData = ClipData.newRawUri(subject, file)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    when (launch) {
        MailLaunch.MailApps -> send.selector = mailAppQuery()
        MailLaunch.ShareSheet -> Unit
    }
    return send
}
