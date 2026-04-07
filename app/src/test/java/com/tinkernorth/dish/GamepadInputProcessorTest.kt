package com.tinkernorth.dish

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [GamepadInputProcessor].
 *
 * These verify:
 *   – axis scaling (sign, clamping, dead-zone formula)
 *   – button map press/release (bitfield behaviour)
 *   – trigger key/axis priority
 *   – zeroAxes reset
 *   – report sender callback plumbing
 */
class GamepadInputProcessorTest {

    private lateinit var proc: GamepadInputProcessor
    private val sent = mutableListOf<List<Int>>()

    @Before
    fun setUp() {
        proc = GamepadInputProcessor()
        sent.clear()
        proc.reportSender = GamepadInputProcessor.ReportSender { wButtons, bLT, bRT, sLX, sLY, sRX, sRY ->
            sent.add(listOf(wButtons, bLT, bRT, sLX, sLY, sRX, sRY))
        }
    }

    // ── scaleAxis ─────────────────────────────────────────────────────────

    @Test
    fun `scaleAxis center returns zero`() {
        assertEquals(0, scaleAxis(0f, 32767f))
    }

    @Test
    fun `scaleAxis full positive returns max`() {
        assertEquals(32767, scaleAxis(1f, 32767f))
    }

    @Test
    fun `scaleAxis full negative returns negative max`() {
        assertEquals(-32767, scaleAxis(-1f, 32767f))
    }

    @Test
    fun `scaleAxis Y inversion — positive raw becomes negative output`() {
        // Y axis uses negative max to invert
        assertEquals(-32767, scaleAxis(1f, -32767f))
    }

    @Test
    fun `scaleAxis Y inversion — negative raw becomes positive output`() {
        assertEquals(32767, scaleAxis(-1f, -32767f))
    }

    @Test
    fun `scaleAxis clamps above max`() {
        assertEquals(32767, scaleAxis(1.5f, 32767f))
    }

    @Test
    fun `scaleAxis clamps below min`() {
        assertEquals(-32768, scaleAxis(-1.5f, 32767f))
    }

    @Test
    fun `scaleAxis half value`() {
        assertEquals(16383, scaleAxis(0.5f, 32767f))
    }

    // ── scaleTrigger ──────────────────────────────────────────────────────

    @Test
    fun `scaleTrigger zero returns zero`() {
        assertEquals(0, scaleTrigger(0f, 255f))
    }

    @Test
    fun `scaleTrigger full returns 255`() {
        assertEquals(255, scaleTrigger(1f, 255f))
    }

    @Test
    fun `scaleTrigger clamps above max`() {
        assertEquals(255, scaleTrigger(1.5f, 255f))
    }

    @Test
    fun `scaleTrigger negative clamps to zero`() {
        assertEquals(0, scaleTrigger(-0.1f, 255f))
    }

    @Test
    fun `scaleTrigger half value`() {
        assertEquals(127, scaleTrigger(0.5f, 255f))
    }

    // ── Button state ──────────────────────────────────────────────────────

    @Test
    fun `handleKeyDown sets A button bit`() {
        val consumed = proc.handleKeyDown(android.view.KeyEvent.KEYCODE_BUTTON_A)
        assertTrue(consumed)
        assertEquals(0x1000, proc.wButtons)
    }

    @Test
    fun `handleKeyUp clears A button bit`() {
        proc.handleKeyDown(android.view.KeyEvent.KEYCODE_BUTTON_A)
        proc.handleKeyUp(android.view.KeyEvent.KEYCODE_BUTTON_A)
        assertEquals(0, proc.wButtons)
    }

    @Test
    fun `multiple buttons accumulate`() {
        proc.handleKeyDown(android.view.KeyEvent.KEYCODE_BUTTON_A)  // 0x1000
        proc.handleKeyDown(android.view.KeyEvent.KEYCODE_BUTTON_B)  // 0x2000
        assertEquals(0x3000, proc.wButtons)
    }

    @Test
    fun `releasing one button keeps others`() {
        proc.handleKeyDown(android.view.KeyEvent.KEYCODE_BUTTON_A)
        proc.handleKeyDown(android.view.KeyEvent.KEYCODE_BUTTON_X)
        proc.handleKeyUp(android.view.KeyEvent.KEYCODE_BUTTON_A)
        assertEquals(0x4000, proc.wButtons) // only X remains
    }

    @Test
    fun `unknown keyCode is not consumed`() {
        assertFalse(proc.handleKeyDown(android.view.KeyEvent.KEYCODE_VOLUME_UP))
    }

    // ── Trigger key overrides ─────────────────────────────────────────────

    @Test
    fun `L2 key sets trigger to 255`() {
        proc.handleKeyDown(android.view.KeyEvent.KEYCODE_BUTTON_L2)
        assertEquals(255, proc.bLT)
    }

    @Test
    fun `L2 key release resets trigger to 0`() {
        proc.handleKeyDown(android.view.KeyEvent.KEYCODE_BUTTON_L2)
        proc.handleKeyUp(android.view.KeyEvent.KEYCODE_BUTTON_L2)
        assertEquals(0, proc.bLT)
    }

    // ── zeroAxes ──────────────────────────────────────────────────────────

    @Test
    fun `zeroAxes resets all state`() {
        proc.handleKeyDown(android.view.KeyEvent.KEYCODE_BUTTON_A)
        proc.handleKeyDown(android.view.KeyEvent.KEYCODE_BUTTON_L2)
        proc.zeroAxes()
        assertEquals(0, proc.wButtons)
        assertEquals(0, proc.bLT)
        assertEquals(0, proc.bRT)
        assertEquals(0, proc.sLX)
        assertEquals(0, proc.sLY)
        assertEquals(0, proc.sRX)
        assertEquals(0, proc.sRY)
    }

    // ── ReportSender callback ─────────────────────────────────────────────

    @Test
    fun `trySend invokes reportSender with current state`() {
        proc.handleKeyDown(android.view.KeyEvent.KEYCODE_BUTTON_A)
        // handleKeyDown already calls trySend, so sent list should have entries
        assertTrue(sent.isNotEmpty())
        val last = sent.last()
        assertEquals(0x1000, last[0]) // wButtons
    }

    @Test
    fun `trySend increments telemetry counters`() {
        val before = proc.telTotalSent
        proc.trySend()
        assertEquals(before + 1, proc.telTotalSent)
    }

    // ── Telemetry drain ───────────────────────────────────────────────────

    @Test
    fun `drainTelemetry returns and resets counters`() {
        proc.telEventCount = 5
        proc.telSampleCount = 10
        proc.telSendCount = 3

        val snap = proc.drainTelemetry()
        assertEquals(5, snap.events)
        assertEquals(10, snap.samples)
        assertEquals(3, snap.sends)

        // Counters should be zeroed after drain
        assertEquals(0, proc.telEventCount)
        assertEquals(0, proc.telSampleCount)
        assertEquals(0, proc.telSendCount)
    }

    // ── BUTTON_MAP completeness ───────────────────────────────────────────

    @Test
    fun `BUTTON_MAP contains all expected buttons`() {
        val map = GamepadInputProcessor.BUTTON_MAP
        assertEquals(14, map.size) // 4 face + 2 bumper + 2 stick + start + select + 4 dpad

        // Verify no bit collisions
        val bits = map.values.toList()
        val combined = bits.fold(0) { acc, bit -> acc or bit }
        // If there are no collisions, combining all bits and counting 1-bits
        // should equal the number of entries that have unique bits
        val uniqueBits = bits.toSet()
        assertEquals(bits.size, uniqueBits.size)
    }
}
