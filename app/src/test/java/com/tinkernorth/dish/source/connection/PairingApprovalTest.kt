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
            val pin = generatePin(Random(it))
            assertEquals(4, pin.length)
            assertTrue(pin.all { c -> c in '0'..'9' })
        }
    }

    @Test
    fun `approved with a full 64-hex key parses to Approved`() {
        val key = "a".repeat(64)
        val st =
            classifyStatus(
                """{"ok":true,"status":"approved","sharedKey":"$key"}""",
            )
        assertTrue(st is Status.Approved)
        assertEquals(key, (st as Status.Approved).sharedKeyHex)
    }

    @Test
    fun `pending parses to Pending`() {
        assertEquals(
            Status.Pending,
            classifyStatus("""{"ok":false,"status":"pending"}"""),
        )
    }

    @Test
    fun `none parses to Declined`() {
        assertEquals(
            Status.Declined,
            classifyStatus("""{"ok":false,"status":"none"}"""),
        )
    }

    @Test
    fun `approved without a full-length key is not trusted`() {
        // A short/garbage key must never be mistaken for a usable session key.
        assertEquals(
            Status.Declined,
            classifyStatus("""{"status":"approved","sharedKey":"abcd"}"""),
        )
    }

    @Test
    fun `an unparseable body is Declined`() {
        assertEquals(Status.Declined, classifyStatus("not json"))
    }

    @Test
    fun `a 64-char non-hex sharedKey is Declined`() {
        // Right length but wrong alphabet: must not be trusted as a session key.
        val notHex = "g".repeat(64)
        assertEquals(
            Status.Declined,
            classifyStatus("""{"status":"approved","sharedKey":"$notHex"}"""),
        )
    }
}
