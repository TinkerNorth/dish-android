// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.source.system

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WifiSubnetTest {
    @Test
    fun `hosts inside the phone's prefix are on the same network`() {
        assertEquals(true, sameSubnet("192.168.1.20", 24, "192.168.1.7"))
        assertEquals(false, sameSubnet("192.168.1.20", 24, "192.168.2.7"))
        assertEquals(true, sameSubnet("10.0.5.9", 8, "10.200.1.1"))
    }

    @Test
    fun `anything that is not a dotted quad is unknown`() {
        assertNull(sameSubnet(null, 24, "192.168.1.7"))
        assertNull(sameSubnet("192.168.1.20", 24, "my-pc.local"))
        assertNull(sameSubnet("192.168.1.20", 0, "192.168.1.7"))
        assertNull(sameSubnet("fe80::1", 64, "192.168.1.7"))
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

    @Test
    fun `a prefix past 32 bits is unknown`() {
        assertNull(sameSubnet("192.168.1.20", 33, "192.168.1.20"))
    }

    @Test
    fun `a slash 32 matches only the same address`() {
        assertEquals(true, sameSubnet("192.168.1.20", 32, "192.168.1.20"))
        assertEquals(false, sameSubnet("192.168.1.20", 32, "192.168.1.21"))
    }

    @Test
    fun `wifi band maps a frequency to its marketing band`() {
        assertEquals(WifiBand.GHZ_2_4, WifiBand.fromFrequencyMhz(2412))
        assertEquals(WifiBand.GHZ_5, WifiBand.fromFrequencyMhz(5180))
        assertEquals(WifiBand.GHZ_6, WifiBand.fromFrequencyMhz(5955))
        assertEquals(WifiBand.UNKNOWN, WifiBand.fromFrequencyMhz(5900))
        assertEquals(WifiBand.UNKNOWN, WifiBand.fromFrequencyMhz(0))
    }
}
