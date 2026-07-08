// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.diagnostics

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LatencyPanelTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `a full snapshot parses every field and halves the rtt`() {
        val panel =
            parseLatencyPanel(
                """{"enabled":true,
                    "stage1_hotpath_us":{"n":10,"p50":150.0,"p99":900.0},
                    "urb_gap_us":{"n":10,"p50":4000.0,"p99":8000.0},
                    "rtt_recent_us":[7000,3000],
                    "rtt_us":{"n":32,"p50":6000.0,"p99":9000.0}}""",
                json,
            )!!
        assertEquals(0.15, panel.phonePathP50Ms!!, 1e-6)
        assertEquals(0.9, panel.phonePathP99Ms!!, 1e-6)
        assertEquals(4.0, panel.pollingJitterP50Ms!!, 1e-6)
        assertEquals(3.0, panel.networkOneWayP50Ms!!, 1e-6)
        assertEquals(32, panel.rttSamples)
        assertEquals(listOf(7f, 3f), panel.rttHistoryMs)
    }

    @Test
    fun `an empty metric group parses to nulls, not zeros`() {
        val panel = parseLatencyPanel("""{"enabled":true,"rtt_us":{"n":0}}""", json)!!
        assertNull(panel.networkOneWayP50Ms)
        assertNull(panel.phonePathP50Ms)
        assertEquals(0, panel.rttSamples)
    }

    @Test
    fun `garbage json parses to null`() {
        assertNull(parseLatencyPanel("not json", json))
    }

    @Test
    fun `a single rtt sample hides the sparkline history`() {
        val panel = parseLatencyPanel("""{"rtt_recent_us":[5000]}""", json)!!
        assertTrue(panel.rttHistoryMs.isEmpty())
    }
}
