// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class StickHealthTest {
    @Test
    fun `drift of an empty capture is zero`() {
        assertEquals(0f, StickHealth.drift(emptyList()), 1e-6f)
    }

    @Test
    fun `drift averages resting offset magnitudes`() {
        val samples = List(10) { StickSample(0.06f, 0.08f) } // magnitude 0.1
        assertEquals(0.1f, StickHealth.drift(samples), 1e-4f)
    }

    @Test
    fun `suggested deadzone gives headroom over the drift and rounds to 2 decimals`() {
        assertEquals(0.15f, StickHealth.suggestedDeadzone(0.10f), 1e-6f)
    }

    @Test
    fun `suggested deadzone is floored at sensor noise and capped for faulty sticks`() {
        assertEquals(0.04f, StickHealth.suggestedDeadzone(0f), 1e-6f)
        assertEquals(0.30f, StickHealth.suggestedDeadzone(0.9f), 1e-6f)
    }

    @Test
    fun `envelope tracks per-axis extremes`() {
        val samples =
            listOf(
                StickSample(-0.9f, 0.1f),
                StickSample(0.95f, 0.0f),
                StickSample(0.0f, -0.85f),
                StickSample(0.1f, 0.92f),
            )
        val e = StickHealth.envelope(samples)
        assertEquals(-0.9f, e.minX, 1e-6f)
        assertEquals(0.95f, e.maxX, 1e-6f)
        assertEquals(-0.85f, e.minY, 1e-6f)
        assertEquals(0.92f, e.maxY, 1e-6f)
    }

    @Test
    fun `a full clean circle has near-zero circularity error`() {
        val samples = List(360) { i -> circle(i.toDouble(), radius = 0.98f) }
        val error = StickHealth.envelope(samples).circularityError
        assertNotNull(error)
        assertTrue("expected ~0, got $error", error!! < 0.01f)
    }

    @Test
    fun `a flat spot on one side shows up as circularity error`() {
        val samples =
            List(360) { i ->
                // The right quadrant only reaches 70% of the rail: a worn stick.
                val radius = if (i < 45 || i > 315) 0.7f else 1.0f
                circle(i.toDouble(), radius)
            }
        val error = StickHealth.envelope(samples).circularityError
        assertNotNull(error)
        assertTrue("expected a real error, got $error", error!! > 0.15f)
    }

    @Test
    fun `an incomplete sweep refuses a circularity verdict`() {
        // Only the top-right arc: most buckets never visited.
        val samples = List(60) { i -> circle(i.toDouble(), radius = 1f) }
        assertNull(StickHealth.envelope(samples).circularityError)
    }

    @Test
    fun `inner travel does not count toward the rim`() {
        // A full circle at 30% magnitude is user wiggle, not the stick's rim.
        val samples = List(360) { i -> circle(i.toDouble(), radius = 0.3f) }
        assertNull(StickHealth.envelope(samples).circularityError)
    }

    private fun circle(
        degrees: Double,
        radius: Float,
    ): StickSample {
        val rad = Math.toRadians(degrees)
        return StickSample((cos(rad) * radius).toFloat(), (sin(rad) * radius).toFloat())
    }
}
