// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.core.net.moonlight

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MoonlightUrlsTest {
    @Test
    fun `serverinfo uses the passed ports, never a hardcoded one`() {
        assertTrue(
            serverInfoHttp("10.0.0.5", 47989, "uid")
                .startsWith("http://10.0.0.5:47989/serverinfo?uniqueid=uid"),
        )
        assertTrue(serverInfoHttps("10.0.0.5", 47984, "uid").startsWith("https://10.0.0.5:47984/serverinfo?"))
    }

    @Test
    fun `launch carries the app id, rikey and rikeyid`() {
        val url = launch("host", 47984, "uid", appId = "881448767", rikeyHex = "00112233", rikeyId = 42, mode = "1280x720x30")
        assertTrue(url.contains("appid=881448767"))
        assertTrue(url.contains("rikey=00112233"))
        assertTrue(url.contains("rikeyid=42"))
        assertTrue(url.contains("mode=1280x720x30"))
        // Stereo as every client spells it: mask 3 << 16 | 2 channels.
        assertTrue(url.contains("surroundAudioInfo=196610"))
    }

    @Test
    fun `pair params are url-encoded`() {
        val url = pairHttp("host", 47989, mapOf("salt" to "ab cd", "clientcert" to "2d/2d"))
        assertTrue(url.contains("salt=ab+cd"))
        assertTrue(url.contains("clientcert=2d%2F2d"))
    }

    @Test
    fun `applist targets the https port and carries the unique id`() {
        val url = appList("10.0.0.5", 47984, "uid")
        assertTrue(url.startsWith("https://10.0.0.5:47984/applist?"))
        assertTrue(url.contains("uniqueid=uid"))
    }

    @Test
    fun `pairHttps targets the https port and encodes its params like pairHttp`() {
        val url = pairHttps("host", 47984, mapOf("salt" to "ab cd", "clientcert" to "2d/2d"))
        assertTrue(url.startsWith("https://host:47984/pair?"))
        assertTrue(url.contains("salt=ab+cd"))
        assertTrue(url.contains("clientcert=2d%2F2d"))
    }

    @Test
    fun `cancel targets the https port and carries only the unique id`() {
        val url = cancel("10.0.0.5", 47984, "uid")
        assertTrue(url.startsWith("https://10.0.0.5:47984/cancel?"))
        assertTrue(url.contains("uniqueid=uid"))
        assertFalse(url.contains("rikey"))
    }

    @Test
    fun `resume carries the rikey pair but no app id`() {
        val url = resume("host", 47984, "uid", rikeyHex = "00112233", rikeyId = 42)
        assertTrue(url.startsWith("https://host:47984/resume?"))
        assertTrue(url.contains("rikey=00112233"))
        assertTrue(url.contains("rikeyid=42"))
        assertFalse(url.contains("appid="))
    }
}
