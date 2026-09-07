// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.source.system

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WifiSubnetTest {
    @Test
    fun `hosts inside the phone's prefix are on the same network`() {
        assertEquals(true, WifiSubnet.sameSubnet("192.168.1.20", 24, "192.168.1.7"))
        assertEquals(false, WifiSubnet.sameSubnet("192.168.1.20", 24, "192.168.2.7"))
        assertEquals(true, WifiSubnet.sameSubnet("10.0.5.9", 8, "10.200.1.1"))
    }

    @Test
    fun `anything that is not a dotted quad is unknown`() {
        assertNull(WifiSubnet.sameSubnet(null, 24, "192.168.1.7"))
        assertNull(WifiSubnet.sameSubnet("192.168.1.20", 24, "my-pc.local"))
        assertNull(WifiSubnet.sameSubnet("192.168.1.20", 0, "192.168.1.7"))
        assertNull(WifiSubnet.sameSubnet("fe80::1", 64, "192.168.1.7"))
    }

    @Test
    fun `wifi generation maps the platform standard constants`() {
        assertEquals(WifiGeneration.WIFI_4, WifiGeneration.fromWifiStandard(4))
        assertEquals(WifiGeneration.WIFI_5, WifiGeneration.fromWifiStandard(5))
        assertEquals(WifiGeneration.WIFI_6, WifiGeneration.fromWifiStandard(6))
        assertEquals(WifiGeneration.WIFI_7, WifiGeneration.fromWifiStandard(8))
        assertEquals(WifiGeneration.LEGACY, WifiGeneration.fromWifiStandard(1))
        assertEquals(WifiGeneration.UNKNOWN, WifiGeneration.fromWifiStandard(0))
    }
}
