// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.core.net.moonlight

import org.junit.Assert.assertTrue
import org.junit.Test

class MoonlightUrlsTest {
    @Test
    fun `serverinfo uses the passed ports, never a hardcoded one`() {
        assertTrue(
            MoonlightUrls
                .serverInfoHttp("10.0.0.5", 47989, "uid")
                .startsWith("http://10.0.0.5:47989/serverinfo?uniqueid=uid"),
        )
        assertTrue(MoonlightUrls.serverInfoHttps("10.0.0.5", 47984, "uid").startsWith("https://10.0.0.5:47984/serverinfo?"))
    }

    @Test
    fun `launch carries the app id, rikey and rikeyid`() {
        val url = MoonlightUrls.launch("host", 47984, "uid", appId = "881448767", rikeyHex = "00112233", rikeyId = 42, mode = "1280x720x30")
        assertTrue(url.contains("appid=881448767"))
        assertTrue(url.contains("rikey=00112233"))
        assertTrue(url.contains("rikeyid=42"))
        assertTrue(url.contains("mode=1280x720x30"))
    }

    @Test
    fun `pair params are url-encoded`() {
        val url = MoonlightUrls.pairHttp("host", 47989, mapOf("salt" to "ab cd", "clientcert" to "2d/2d"))
        assertTrue(url.contains("salt=ab+cd"))
        assertTrue(url.contains("clientcert=2d%2F2d"))
    }
}
