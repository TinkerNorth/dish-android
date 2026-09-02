// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.common

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Which ground the mute pill paints. The face is LOCAL state only: [micMutePillFace] does not
 * take the host's lamp at all, and that absence is the fix for the pill that looked dead. Host
 * software that reads the wire's held mute-state bit as a held button toggles its own mute out
 * of phase with ours and then re-drives MSG_MIC_LED; when the lamp owned the face, that repaint
 * landed right after every local toggle and erased it. Now the lamp is a ring on top and the
 * face answers to MicMuteStore alone.
 */
class MicMutePillFaceTest {
    @Test
    fun `an idle live pill paints the plain ground`() {
        assertEquals(MicPillFace.IDLE, micMutePillFace(pressed = false, muted = false))
    }

    @Test
    fun `a muted pill paints the muted ground for as long as the mute holds`() {
        assertEquals(MicPillFace.MUTED, micMutePillFace(pressed = false, muted = true))
    }

    @Test
    fun `a finger on the pill wins the ground, muted or not`() {
        // The pressed flash is sub-second finger feedback and must never be swallowed; the mute
        // is not lost under it because the slashed glyph tracks muted on its own.
        assertEquals(MicPillFace.PRESSED, micMutePillFace(pressed = true, muted = false))
        assertEquals(MicPillFace.PRESSED, micMutePillFace(pressed = true, muted = true))
    }
}
