// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.core.net

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HttpReplyTest {
    @Test
    fun `status zero reads as unreachable`() {
        assertTrue(HttpReply(0, """{"error":"request failed: timeout"}""", null).unreachable)
    }

    @Test
    fun `a blank body reads as unreachable`() {
        assertTrue(HttpReply(200, "", null).unreachable)
    }

    @Test
    fun `an http error with a body is reachable`() {
        assertFalse(HttpReply(401, """{"error":"unauthorized"}""", null).unreachable)
        assertFalse(HttpReply(409, """{"error":"protocol version unsupported"}""", null).unreachable)
        assertFalse(HttpReply(500, """{"error":"boom"}""", null).unreachable)
    }

    @Test
    fun `a successful reply is reachable`() {
        assertFalse(HttpReply(200, """{"ok":true}""", null).unreachable)
    }

    @Test
    fun `notModified is 304 only`() {
        assertTrue(HttpReply(304, "", null).notModified)
        assertFalse(HttpReply(200, "", null).notModified)
    }

    @Test
    fun `pinMismatch defaults to false`() {
        assertFalse(HttpReply(200, "{}", null).pinMismatch)
        assertTrue(HttpReply(0, "{}", null, pinMismatch = true).pinMismatch)
    }
}
