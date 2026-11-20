// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.ui.diagnostics

import com.tinkernorth.dish.R
import com.tinkernorth.dish.core.controllerdb.VERIFICATION_FLAGGED
import com.tinkernorth.dish.core.controllerdb.VERIFICATION_IMPORTED
import com.tinkernorth.dish.core.controllerdb.VERIFICATION_VERIFIED
import com.tinkernorth.dish.hotpath.input.Transport
import org.junit.Assert.assertEquals
import org.junit.Test

class DiagnosticsMailTest {
    private val gmailCompose = SendHandler("com.google.android.gm", "com.google.android.gm.ComposeActivityGmailExternal")
    private val gmailDraft = SendHandler("com.google.android.gm", "com.google.android.gm.ComposeActivityGmail")
    private val drive = SendHandler("com.google.android.apps.docs", "com.google.android.apps.docs.UploadActivity")
    private val messages = SendHandler("com.google.android.apps.messaging", "com.google.android.apps.messaging.ui.ShareActivity")

    @Test
    fun `a phone with no mail app gets the whole share sheet`() {
        val launch = mailLaunchFor(sendHandlers = listOf(drive, messages), mailPackages = emptySet())
        assertEquals(MailLaunch.ShareSheet, launch)
    }

    @Test
    fun `a phone with a mail app gets the share sheet without every app that takes no mail`() {
        val launch = mailLaunchFor(sendHandlers = listOf(gmailCompose, drive, messages), mailPackages = setOf("com.google.android.gm"))
        assertEquals(MailLaunch.MailApps(excluded = listOf(drive, messages)), launch)
    }

    @Test
    fun `every activity of a mail app stays on the sheet`() {
        val launch = mailLaunchFor(sendHandlers = listOf(gmailCompose, gmailDraft), mailPackages = setOf("com.google.android.gm"))
        assertEquals(MailLaunch.MailApps(excluded = emptyList()), launch)
    }

    @Test
    fun `a mail app the send query did not list excludes nothing it cannot see`() {
        val launch = mailLaunchFor(sendHandlers = emptyList(), mailPackages = setOf("com.google.android.gm"))
        assertEquals(MailLaunch.MailApps(excluded = emptyList()), launch)
    }

    @Test
    fun `an imported record shows the app's layout guessed chip`() {
        assertEquals(R.string.diagnostics_verification_verified, verificationLabel(VERIFICATION_VERIFIED))
        assertEquals(R.string.diagnostics_verification_guessed, verificationLabel(VERIFICATION_IMPORTED))
        assertEquals(R.string.diagnostics_verification_flagged, verificationLabel(VERIFICATION_FLAGGED))
    }

    @Test
    fun `issues are matched on the transport the pad uses`() {
        assertEquals("bluetooth", dbTransportOf(Transport.Bluetooth))
        assertEquals("usb", dbTransportOf(Transport.Usb))
        assertEquals("usb", dbTransportOf(null))
    }

    @Test
    fun `the android major is the release's leading number`() {
        assertEquals(15, androidMajorOf("15"))
        assertEquals(13, androidMajorOf("13.0.1"))
        assertEquals(0, androidMajorOf("Baklava"))
    }
}
