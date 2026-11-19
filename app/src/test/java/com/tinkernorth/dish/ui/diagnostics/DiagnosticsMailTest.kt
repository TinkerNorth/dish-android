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
    @Test
    fun `a phone with a mail app sends to mail apps and one without to the share sheet`() {
        assertEquals(MailLaunch.MailApps, mailLaunchFor(hasAMailApp = true))
        assertEquals(MailLaunch.ShareSheet, mailLaunchFor(hasAMailApp = false))
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
