// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.source.connection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class PairingApprovalTest {
    @Test
    fun `generated pin is four digits`() {
        repeat(50) {
            val pin = PairingApproval.generatePin(Random(it))
            assertEquals(4, pin.length)
            assertTrue(pin.all { c -> c in '0'..'9' })
        }
    }

    @Test
    fun `approved with a full 64-hex key parses to Approved`() {
        val key = "a".repeat(64)
        val st =
            PairingApproval.classifyStatus(
                """{"ok":true,"status":"approved","sharedKey":"$key"}""",
            )
        assertTrue(st is PairingApproval.Status.Approved)
        assertEquals(key, (st as PairingApproval.Status.Approved).sharedKeyHex)
    }

    @Test
    fun `pending parses to Pending`() {
        assertEquals(
            PairingApproval.Status.Pending,
            PairingApproval.classifyStatus("""{"ok":false,"status":"pending"}"""),
        )
    }

    @Test
    fun `none parses to Declined`() {
        assertEquals(
            PairingApproval.Status.Declined,
            PairingApproval.classifyStatus("""{"ok":false,"status":"none"}"""),
        )
    }

    @Test
    fun `approved without a full-length key is not trusted`() {
        // A short/garbage key must never be mistaken for a usable session key.
        assertEquals(
            PairingApproval.Status.Declined,
            PairingApproval.classifyStatus("""{"status":"approved","sharedKey":"abcd"}"""),
        )
    }

    @Test
    fun `an unparseable body is Declined`() {
        assertEquals(PairingApproval.Status.Declined, PairingApproval.classifyStatus("not json"))
    }
}
