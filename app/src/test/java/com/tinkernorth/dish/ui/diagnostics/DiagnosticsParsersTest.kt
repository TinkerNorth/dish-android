// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.diagnostics

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsParsersTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `session stats carry the window, the recent tail and the tallies`() {
        val raw =
            """{"rtt_recent_us":[4000,6000,5000],""" +
                """"rtt_us":{"n":3,"min":4000.0,"p50":5000.0,"p90":6000.0,"p99":6000.0,"max":6000.0,"mean":5000.0},""" +
                """"pings":12,"acks":11,"missed":1}"""
        val stats = parseSessionStats(json, raw) ?: error("no stats")
        assertEquals(5.0, stats.rttP50Ms ?: 0.0, 1e-9)
        assertEquals(6.0, stats.rttP99Ms ?: 0.0, 1e-9)
        assertEquals(3, stats.rttSamples)
        assertEquals(listOf(4f, 6f, 5f), stats.rttRecentMs)
        assertEquals(12L, stats.pings)
        assertEquals(11L, stats.acks)
        assertEquals(1, stats.missed)
    }

    @Test
    fun `an empty window reads as no samples and no sparkline`() {
        val raw = """{"rtt_recent_us":[],"rtt_us":{"n":0},"pings":0,"acks":0,"missed":0}"""
        val stats = parseSessionStats(json, raw) ?: error("no stats")
        assertNull(stats.rttP50Ms)
        assertEquals(0, stats.rttSamples)
        assertTrue(stats.rttRecentMs.isEmpty())
        assertNull(parseSessionStats(json, ""))
    }

    @Test
    fun `device latency and device info decode the native fields`() {
        val latencyRaw =
            """{"stage1_hotpath_us":{"n":9,"p50":120.0,"p99":400.0},""" +
                """"urb_gap_us":{"n":9,"p50":1000.0,"p99":1100.0}}"""
        val latency = parseDeviceLatency(json, latencyRaw)
        assertEquals(9, latency?.samples)
        assertEquals(0.12, latency?.stage1P50Ms ?: 0.0, 1e-9)
        assertEquals(1.1, latency?.gapP99Ms ?: 0.0, 1e-9)

        val infoRaw =
            """{"model":"DualSense","parser":"DualSense protocol","init":"",""" +
                """"reportBytes":64,"endpointOut":true,"lastUrbStatus":-32}"""
        val info = parseDeviceInfo(json, infoRaw)
        assertEquals("DualSense", info?.model)
        assertEquals(64, info?.reportBytes)
        assertEquals(true, info?.endpointOut)
        assertEquals(-32, info?.lastUrbStatus)
        assertNull(parseDeviceInfo(json, ""))
    }
}
