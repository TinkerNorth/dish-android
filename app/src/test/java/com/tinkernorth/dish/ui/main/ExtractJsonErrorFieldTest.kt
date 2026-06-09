// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.main

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

// Guards the hand-rolled extractor shared by MainViewModel and ConfigureBindingsViewModel:
// it stands in for a real JSON parser, so its happy path and every bail-out branch are pinned here.
class ExtractJsonErrorFieldTest {
    @Test
    fun `reads the error string from a well-formed reply`() {
        assertEquals("device not paired", extractJsonErrorField("""{"error":"device not paired"}"""))
    }

    @Test
    fun `reads the error even when it is not the first key`() {
        assertEquals("unauthorized", extractJsonErrorField("""{"ok":false,"error":"unauthorized"}"""))
    }

    @Test
    fun `returns null when there is no error field`() {
        assertNull(extractJsonErrorField("""{"ok":true}"""))
    }

    @Test
    fun `returns null on empty input`() {
        assertNull(extractJsonErrorField(""))
    }

    @Test
    fun `returns null when the error value is malformed and never closes its quote`() {
        // Open quote but no closing quote: the extractor must bail rather than read past the end.
        assertNull(extractJsonErrorField("""{"error":"unterminated"""))
    }

    @Test
    fun `returns null when the error value is missing entirely`() {
        assertNull(extractJsonErrorField("""{"error":}"""))
    }

    @Test
    fun `extracts an empty error string as empty - not null`() {
        assertEquals("", extractJsonErrorField("""{"error":""}"""))
    }
}
