// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.source.connection

import org.junit.Assert.assertEquals
import org.junit.Test

class ConnectionErrorTest {
    @Test
    fun `a pairing the satellite gave a reason for carries that reason`() {
        assertEquals(ConnectionError.PairingRefused("invalid or expired PIN"), pairingRefusal("invalid or expired PIN"))
    }

    @Test
    fun `a pairing refused without a reason is a plain pairing failure`() {
        assertEquals(ConnectionError.PairingFailed, pairingRefusal(null))
    }

    @Test
    fun `a pairing refused with a blank reason is a plain pairing failure`() {
        assertEquals(ConnectionError.PairingFailed, pairingRefusal(" "))
    }

    @Test
    fun `a session the satellite gave a reason for carries that reason`() {
        assertEquals(ConnectionError.SessionRefused("no free pad"), sessionRefusal("no free pad"))
    }

    @Test
    fun `a session refused without a reason is a plain session refusal`() {
        assertEquals(ConnectionError.SessionFailed, sessionRefusal(null))
    }

    @Test
    fun `a session refused with a blank reason is a plain session refusal`() {
        assertEquals(ConnectionError.SessionFailed, sessionRefusal(""))
    }
}
