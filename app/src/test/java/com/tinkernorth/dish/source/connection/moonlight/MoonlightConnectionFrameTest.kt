// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.source.connection.moonlight

import com.tinkernorth.dish.core.net.moonlight.BTN_A
import com.tinkernorth.dish.core.net.moonlight.BTN_TOUCHPAD
import com.tinkernorth.dish.core.net.moonlight.MoonlightControlSession
import com.tinkernorth.dish.core.net.moonlight.MoonlightHost
import com.tinkernorth.dish.core.net.moonlight.PLAYSTATION
import com.tinkernorth.dish.source.connection.TouchpadReport
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.management.ManagementFactory

// One pad frame as the connection hands it to the session.
private data class SentFrame(
    val controllerNumber: Int,
    val activeMask: Int,
    val buttons: Int,
    val leftTrigger: Int,
    val rightTrigger: Int,
    val leftX: Int,
    val leftY: Int,
    val rightX: Int,
    val rightY: Int,
)

// A datagram plumbing that never carries anything: the session over it stays idle.
private class SilentTransport : MoonlightControlSession.Transport {
    override fun send(datagram: ByteArray) = Unit

    override fun receive(timeoutMs: Int): ByteArray? = null

    override fun close() = Unit
}

// An AES-128 key's length; the idle session never seals with it.
private const val IDLE_SESSION_KEY_BYTES = 16

// The connection's per-frame path: what it remembers of each pad's last frame, and that
// remembering it allocates nothing.
class MoonlightConnectionFrameTest {
    private val dispatcher = StandardTestDispatcher()
    private val threads = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
    private val sent = mutableListOf<SentFrame>()

    private fun connection(): MoonlightConnection =
        MoonlightConnection(
            id = "moonlight:uid:abc",
            host = MoonlightHost(name = "PC", address = "10.0.0.5", uniqueId = "abc"),
            scope = TestScope(dispatcher),
            ioDispatcher = dispatcher,
        )

    private fun recordFrame(
        controllerNumber: Int,
        activeMask: Int,
        buttons: Int,
        leftTrigger: Int,
        rightTrigger: Int,
        leftX: Int,
        leftY: Int,
        rightX: Int,
        rightY: Int,
    ) {
        sent += SentFrame(controllerNumber, activeMask, buttons, leftTrigger, rightTrigger, leftX, leftY, rightX, rightY)
    }

    private fun recordingSession(): MoonlightControlSession {
        val session = mockk<MoonlightControlSession>(relaxed = true)
        every { session.state } returns MoonlightControlSession.State.CONNECTED
        every {
            session.sendControllerState(any(), any(), any(), any(), any(), any(), any(), any(), any())
        } answers {
            recordFrame(
                arg(0),
                arg(1),
                arg(2),
                arg(3),
                arg(4),
                arg(5),
                arg(6),
                arg(7),
                arg(8),
            )
        }
        return session
    }

    // A live connection over a recording session holding [pads] pads, numbered from 0.
    private fun liveWithPads(pads: Int): MoonlightConnection {
        val conn = connection()
        repeat(pads) { conn.acquirePad("slot-$it", PLAYSTATION, CAPS, BUTTONS) }
        conn.markLive(recordingSession(), appId = null, appName = null)
        sent.clear()
        return conn
    }

    private fun clickReport(pressed: Boolean) =
        TouchpadReport(
            finger0Active = false,
            finger1Active = false,
            buttonPressed = pressed,
            rightPressed = false,
            middlePressed = false,
            finger0TrackingId = 0,
            finger0X = 0,
            finger0Y = 0,
            finger1TrackingId = 0,
            finger1X = 0,
            finger1Y = 0,
            eventTimeMs = 0L,
            scrollDelta = 0,
        )

    private fun sendFrame(
        conn: MoonlightConnection,
        number: Int,
        seed: Int,
    ) {
        conn.sendControllerState(
            controllerNumber = number,
            buttons = BTN_A or seed,
            leftTrigger = seed + 1,
            rightTrigger = seed + 2,
            leftX = seed + 3,
            leftY = -(seed + 4),
            rightX = seed + 5,
            rightY = -(seed + 6),
        )
    }

    private fun frameOf(
        number: Int,
        mask: Int,
        seed: Int,
        extraButtons: Int = 0,
    ) = SentFrame(
        controllerNumber = number,
        activeMask = mask,
        buttons = BTN_A or seed or extraButtons,
        leftTrigger = seed + 1,
        rightTrigger = seed + 2,
        leftX = seed + 3,
        leftY = -(seed + 4),
        rightX = seed + 5,
        rightY = -(seed + 6),
    )

    private fun neutralFrame(
        number: Int,
        mask: Int,
        buttons: Int,
    ) = SentFrame(number, mask, buttons, 0, 0, 0, 0, 0, 0)

    @Test
    fun `a click edge replays the first pad's last frame with the click merged`() {
        val conn = liveWithPads(ALL_PADS)
        sendFrame(conn, FIRST_NUMBER, FIRST_SEED)
        sendFrame(conn, LAST_NUMBER, LAST_SEED)
        sent.clear()
        conn.sendTouchpad("slot-$FIRST_NUMBER", clickReport(pressed = true))
        assertEquals(listOf(frameOf(FIRST_NUMBER, ALL_PADS_MASK, FIRST_SEED, BTN_TOUCHPAD)), sent)
    }

    @Test
    fun `a click edge replays the last pad's last frame with the click merged`() {
        val conn = liveWithPads(ALL_PADS)
        sendFrame(conn, LAST_NUMBER, LAST_SEED)
        sendFrame(conn, FIRST_NUMBER, FIRST_SEED)
        sent.clear()
        conn.sendTouchpad("slot-$LAST_NUMBER", clickReport(pressed = true))
        assertEquals(listOf(frameOf(LAST_NUMBER, ALL_PADS_MASK, LAST_SEED, BTN_TOUCHPAD)), sent)
    }

    @Test
    fun `a click release replays the pad's last frame without the click`() {
        val conn = liveWithPads(1)
        conn.sendTouchpad("slot-0", clickReport(pressed = true))
        sendFrame(conn, FIRST_NUMBER, FIRST_SEED)
        sent.clear()
        conn.sendTouchpad("slot-0", clickReport(pressed = false))
        assertEquals(listOf(frameOf(FIRST_NUMBER, ONE_PAD_MASK, FIRST_SEED)), sent)
    }

    @Test
    fun `a click edge before any frame replays a pad at rest`() {
        val conn = liveWithPads(1)
        conn.sendTouchpad("slot-0", clickReport(pressed = true))
        assertEquals(listOf(neutralFrame(FIRST_NUMBER, ONE_PAD_MASK, BTN_TOUCHPAD)), sent)
    }

    // The click state starts unknown, not released: the first report says where the click is,
    // whichever way that is, and the host hears it once.
    @Test
    fun `the first report with the click up still replays the frame once`() {
        val conn = liveWithPads(1)
        sendFrame(conn, FIRST_NUMBER, FIRST_SEED)
        sent.clear()
        conn.sendTouchpad("slot-0", clickReport(pressed = false))
        conn.sendTouchpad("slot-0", clickReport(pressed = false))
        assertEquals(listOf(frameOf(FIRST_NUMBER, ONE_PAD_MASK, FIRST_SEED)), sent)
    }

    @Test
    fun `a held click replays nothing more`() {
        val conn = liveWithPads(1)
        conn.sendTouchpad("slot-0", clickReport(pressed = true))
        sent.clear()
        conn.sendTouchpad("slot-0", clickReport(pressed = true))
        assertTrue(sent.isEmpty())
    }

    @Test
    fun `a frame for a number past the last pad is sent as given and remembered for none`() {
        val conn = liveWithPads(ALL_PADS)
        sendFrame(conn, FIRST_NUMBER, FIRST_SEED)
        sendFrame(conn, PAST_LAST_NUMBER, LAST_SEED)
        conn.sendTouchpad("slot-$FIRST_NUMBER", clickReport(pressed = true))
        val expected =
            listOf(
                frameOf(FIRST_NUMBER, ALL_PADS_MASK, FIRST_SEED),
                frameOf(PAST_LAST_NUMBER, ALL_PADS_MASK, LAST_SEED),
                frameOf(FIRST_NUMBER, ALL_PADS_MASK, FIRST_SEED, BTN_TOUCHPAD),
            )
        assertEquals(expected, sent)
        assertEquals(0L, conn.reportsSentFor(PAST_LAST_NUMBER))
    }

    @Test
    fun `a frame for a number before the first pad is sent as given and remembered for none`() {
        val conn = liveWithPads(ALL_PADS)
        sendFrame(conn, LAST_NUMBER, LAST_SEED)
        sendFrame(conn, BEFORE_FIRST_NUMBER, FIRST_SEED)
        conn.sendTouchpad("slot-$LAST_NUMBER", clickReport(pressed = true))
        val expected =
            listOf(
                frameOf(LAST_NUMBER, ALL_PADS_MASK, LAST_SEED),
                frameOf(BEFORE_FIRST_NUMBER, ALL_PADS_MASK, FIRST_SEED),
                frameOf(LAST_NUMBER, ALL_PADS_MASK, LAST_SEED, BTN_TOUCHPAD),
            )
        assertEquals(expected, sent)
        assertEquals(0L, conn.reportsSentFor(BEFORE_FIRST_NUMBER))
    }

    // Anything a caller sets past XUSB's low 16 and the wire's touchpad flag is not a button.
    @Test
    fun `a frame's buttons are masked to what the wire carries`() {
        val conn = liveWithPads(1)
        conn.sendControllerState(FIRST_NUMBER, NOT_A_BUTTON or BTN_A or BTN_TOUCHPAD, 0, 0, 0, 0, 0, 0)
        assertEquals(listOf(neutralFrame(FIRST_NUMBER, ONE_PAD_MASK, BTN_A or BTN_TOUCHPAD)), sent)
    }

    @Test
    fun `a released pad's frame is not replayed for the pad that takes its number`() {
        val conn = liveWithPads(1)
        sendFrame(conn, FIRST_NUMBER, FIRST_SEED)
        conn.releasePad("slot-0")
        conn.acquirePad("slot-next", PLAYSTATION, CAPS, BUTTONS)
        sent.clear()
        conn.sendTouchpad("slot-next", clickReport(pressed = false))
        assertEquals(listOf(neutralFrame(FIRST_NUMBER, ONE_PAD_MASK, 0)), sent)
    }

    @Test
    fun `a session that goes down forgets every pad's frame and click`() {
        val conn = liveWithPads(1)
        sendFrame(conn, FIRST_NUMBER, FIRST_SEED)
        conn.sendTouchpad("slot-0", clickReport(pressed = true))
        conn.markDisconnected()
        conn.markLive(recordingSession(), appId = null, appName = null)
        sent.clear()
        conn.sendTouchpad("slot-0", clickReport(pressed = true))
        assertEquals(listOf(neutralFrame(FIRST_NUMBER, ONE_PAD_MASK, BTN_TOUCHPAD)), sent)
    }

    @Test
    fun `the active mask follows pads as they come and go`() {
        val conn = liveWithPads(ALL_PADS)
        conn.releasePad("slot-1")
        sendFrame(conn, FIRST_NUMBER, FIRST_SEED)
        conn.acquirePad("slot-again", PLAYSTATION, CAPS, BUTTONS)
        sendFrame(conn, FIRST_NUMBER, FIRST_SEED)
        val framesForFirst = sent.filter { it.controllerNumber == FIRST_NUMBER }
        assertEquals(listOf(ALL_PADS_MASK and SECOND_PAD_BIT.inv(), ALL_PADS_MASK), framesForFirst.map { it.activeMask })
    }

    // Every pad's frame, over a real session that is not connected: it drops an unconnected send
    // before building anything.
    private fun runFrameCycle(conn: MoonlightConnection) {
        for (number in 0 until ALL_PADS) sendFrame(conn, number, number)
    }

    // The same sends made on the session directly. A test that mocks the session class makes
    // MockK instrument it for the rest of the JVM, and that instrumentation allocates on every
    // call, so the connection's cost is what it adds on top of this.
    private fun runSessionCycle(session: MoonlightControlSession) {
        for (number in 0 until ALL_PADS) sendSessionFrame(session, number)
    }

    private fun sendSessionFrame(
        session: MoonlightControlSession,
        number: Int,
    ) {
        session.sendControllerState(number, ALL_PADS_MASK, number, number, number, number, number, number, number)
    }

    private inline fun bytesAllocatedBy(cycle: () -> Unit): Long {
        val measureOnlyStart = threads.currentThreadAllocatedBytes
        val measureOnlyEnd = threads.currentThreadAllocatedBytes
        val measurementCost = measureOnlyEnd - measureOnlyStart
        val start = threads.currentThreadAllocatedBytes
        repeat(MEASURED_CYCLES) { cycle() }
        val end = threads.currentThreadAllocatedBytes
        return end - start - measurementCost
    }

    @Test
    fun `a controller frame allocates nothing`() {
        val conn = connection()
        val session = MoonlightControlSession(ByteArray(IDLE_SESSION_KEY_BYTES), 0, SilentTransport(), { 0L })
        repeat(ALL_PADS) { conn.acquirePad("slot-$it", PLAYSTATION, CAPS, BUTTONS) }
        conn.markLive(session, appId = null, appName = null)
        repeat(WARMUP_CYCLES) { runFrameCycle(conn) }
        repeat(WARMUP_CYCLES) { runSessionCycle(session) }
        val sentBefore = conn.reportsSentFor(LAST_NUMBER)
        val sessionBytes = bytesAllocatedBy { runSessionCycle(session) }
        val connectionBytes = bytesAllocatedBy { runFrameCycle(conn) }
        val addedBytes = connectionBytes - sessionBytes
        assertEquals(MEASURED_CYCLES.toLong(), conn.reportsSentFor(LAST_NUMBER) - sentBefore)
        assertTrue("$addedBytes bytes over $MEASURED_CYCLES cycles", addedBytes < MEASURED_CYCLES * BYTES_PER_CYCLE_BOUND)
    }

    private companion object {
        const val CAPS = 0xFF
        const val BUTTONS = 0x10FFFF
        const val ALL_PADS = MoonlightConnection.MAX_PADS
        const val ALL_PADS_MASK = 0b1111
        const val ONE_PAD_MASK = 0b0001
        const val SECOND_PAD_BIT = 0b0010
        const val FIRST_NUMBER = 0
        const val LAST_NUMBER = ALL_PADS - 1
        const val PAST_LAST_NUMBER = ALL_PADS
        const val BEFORE_FIRST_NUMBER = -1
        const val FIRST_SEED = 10
        const val LAST_SEED = 40
        const val NOT_A_BUTTON = 0x200000
        const val WARMUP_CYCLES = 50

        // Few enough calls that C2 never compiles the send path: its escape analysis would hide an
        // allocation that ART, which has none, still makes.
        const val MEASURED_CYCLES = 250

        // Half the smallest object: one allocation in any frame costs 16 bytes or more every
        // cycle, while the JIT's one-off warm-up allocations stay flat as the cycles grow.
        const val BYTES_PER_CYCLE_BOUND = 8
    }
}
