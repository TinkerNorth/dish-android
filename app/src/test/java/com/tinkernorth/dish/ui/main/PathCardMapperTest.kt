// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.main

import com.tinkernorth.dish.source.usb.DirectClaimFailure
import com.tinkernorth.dish.source.usb.PathChoice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PathCardMapperTest {
    private val caps = PathCapabilities(rumble = true, motion = false)

    @Suppress("LongParameterList")
    private fun map(
        isClaimedDirect: Boolean = false,
        usbPresent: Boolean = true,
        recognized: Boolean = true,
        restoring: Boolean = false,
        needsReplug: Boolean = false,
        restoreStuck: Boolean = false,
        directFailure: DirectClaimFailure? = null,
    ) = PathCardMapper.map(
        isClaimedDirect = isClaimedDirect,
        usbPresent = usbPresent,
        recognized = recognized,
        restoring = restoring,
        standard = caps,
        direct = caps,
        directPollHz = 1000,
        needsReplug = needsReplug,
        restoreStuck = restoreStuck,
        directFailure = directFailure,
    )

    @Test
    fun `the toggle follows the live mode, not stored or default intent`() {
        // A live synthetic reads Direct; anything else (including a verified model that has not claimed)
        // reads Standard, so the switch can never show Direct while the controller is on Standard.
        assertEquals(PathChoice.Direct, map(isClaimedDirect = true).selected)
        assertEquals(PathChoice.Standard, map(isClaimedDirect = false, recognized = true).selected)
        assertEquals(PathChoice.Standard, map(isClaimedDirect = false, recognized = false).selected)
    }

    @Test
    fun `badge and toggle always agree`() {
        val direct = map(isClaimedDirect = true)
        assertEquals(InputPathMode.Direct, direct.currentMode)
        assertEquals(PathChoice.Direct, direct.selected)
        val standard = map(isClaimedDirect = false)
        assertEquals(InputPathMode.Standard, standard.currentMode)
        assertEquals(PathChoice.Standard, standard.selected)
    }

    @Test
    fun `a held synthetic reads as Standard on both badge and toggle while restoring`() {
        val card = map(isClaimedDirect = true, restoring = true)
        assertEquals(InputPathMode.Standard, card.currentMode)
        assertEquals(PathChoice.Standard, card.selected)
    }

    @Test
    fun `a stuck restore reads as Standard on both badge and toggle`() {
        val card = map(isClaimedDirect = true, restoreStuck = true)
        assertEquals(InputPathMode.Standard, card.currentMode)
        assertEquals(PathChoice.Standard, card.selected)
    }

    @Test
    fun `a verified model whose Direct claim failed reads as Standard`() {
        // No synthetic exists after a failed claim, so both badge and toggle settle on Standard; the
        // failure is still surfaced via the reason field.
        val card = map(recognized = true, directFailure = DirectClaimFailure.InitFailed)
        assertEquals(PathChoice.Standard, card.selected)
        assertEquals(InputPathMode.Standard, card.currentMode)
        assertEquals(DirectClaimFailure.InitFailed, card.failure)
    }

    @Test
    fun `no usb connection forces Standard and disables Direct`() {
        val card = map(usbPresent = false)
        assertEquals(PathChoice.Standard, card.selected)
        assertFalse(card.directAvailable)
        assertEquals(PathRisk.BluetoothUnavailable, card.risk)
    }

    @Test
    fun `an unknown usb model carries the guessed-layout risk`() {
        assertEquals(PathRisk.GuessedLayout, map(usbPresent = true, recognized = false).risk)
    }

    @Test
    fun `a verified usb model has no risk`() {
        assertEquals(PathRisk.None, map(usbPresent = true, recognized = true).risk)
    }

    @Test
    fun `restoring is carried through to gate the toggle`() {
        assertTrue(map(restoring = true).restoring)
        assertFalse(map(restoring = false).restoring)
    }

    @Test
    fun `a recorded claim failure is carried onto the card`() {
        assertEquals(DirectClaimFailure.Busy, map(directFailure = DirectClaimFailure.Busy).failure)
        assertNull(map().failure)
    }

    @Test
    fun `restore-stuck and needs-replug flags are carried through`() {
        assertTrue(map(restoreStuck = true).restoreStuck)
        assertFalse(map().restoreStuck)
        assertTrue(map(needsReplug = true).needsReplug)
    }
}
