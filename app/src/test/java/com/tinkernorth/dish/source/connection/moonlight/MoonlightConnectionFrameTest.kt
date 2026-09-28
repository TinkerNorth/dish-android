// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.source.connection.moonlight

import com.tinkernorth.dish.architecture.testing.allocatedBytesDuring
import com.tinkernorth.dish.architecture.testing.fewestAllocatedBytesDuring
import com.tinkernorth.dish.architecture.testing.freshAppInstanceOf
import com.tinkernorth.dish.core.net.moonlight.BTN_A
import com.tinkernorth.dish.core.net.moonlight.BTN_TOUCHPAD
import com.tinkernorth.dish.core.net.moonlight.MoonlightControlSession
import com.tinkernorth.dish.core.net.moonlight.MoonlightHost
import com.tinkernorth.dish.core.net.moonlight.PLAYSTATION
import com.tinkernorth.dish.core.net.moonlight.TOUCH_EVENT_MOVE
import com.tinkernorth.dish.core.net.moonlight.XBOX
import com.tinkernorth.dish.source.connection.TouchpadReport
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.function.LongSupplier

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

// The idle session never reads its clock past construction.
private const val IDLE_SESSION_NOW_MS = 0L

// Any pad will do: the frame path never reads what a pad can do.
private const val CYCLE_CAPS = 0xFF
private const val CYCLE_BUTTONS = 0x10FFFF

// Every pad's frame, over a real session that is not connected: it drops an unconnected send
// before building anything, so what is measured is the connection's own path. Reached through
// Runnable (one cycle) and LongSupplier (the frames the last pad has counted) because the
// allocation test makes it in a class loader of its own (freshAppInstanceOf).
internal class MoonlightFrameCycles :
    Runnable,
    LongSupplier {
    private val dispatcher = StandardTestDispatcher()
    private val connection =
        MoonlightConnection(
            id = "moonlight:uid:abc",
            host = MoonlightHost(name = "PC", address = "10.0.0.5", uniqueId = "abc"),
            scope = TestScope(dispatcher),
            ioDispatcher = dispatcher,
        )

    init {
        for (number in 0 until MoonlightConnection.MAX_PADS) connection.acquirePad("slot-$number", PLAYSTATION, CYCLE_CAPS, CYCLE_BUTTONS)
        val idle = MoonlightControlSession(ByteArray(IDLE_SESSION_KEY_BYTES), 0, SilentTransport(), { IDLE_SESSION_NOW_MS })
        connection.markLive(idle, appId = null, appName = null)
    }

    override fun run() {
        for (number in 0 until MoonlightConnection.MAX_PADS) sendFrame(number)
    }

    private fun sendFrame(number: Int) {
        connection.sendControllerState(number, BTN_A or number, number, number, number, -number, number, -number)
    }

    override fun getAsLong(): Long = connection.reportsSentFor(MoonlightConnection.MAX_PADS - 1)
}

// How many lookups one MoonlightSlotLookups cycle makes.
internal const val SLOT_LOOKUPS_PER_CYCLE = 250

// A bridge upcall's slot lookup, for the last pad of a full connection, SLOT_LOOKUPS_PER_CYCLE
// times a run; the lookups that named a slot so far through LongSupplier. Reached through JDK
// interfaces because the allocation test makes it in a class loader of its own.
internal class MoonlightSlotLookups :
    Runnable,
    LongSupplier {
    private val dispatcher = StandardTestDispatcher()
    private val connection =
        MoonlightConnection(
            id = "moonlight:uid:abc",
            host = MoonlightHost(name = "PC", address = "10.0.0.5", uniqueId = "abc"),
            scope = TestScope(dispatcher),
            ioDispatcher = dispatcher,
        )
    private var named = 0L

    init {
        for (number in 0 until MoonlightConnection.MAX_PADS) connection.acquirePad("slot-$number", PLAYSTATION, CYCLE_CAPS, CYCLE_BUTTONS)
    }

    override fun run() {
        repeat(SLOT_LOOKUPS_PER_CYCLE) { lookUpTheLastPad() }
    }

    private fun lookUpTheLastPad() {
        if (connection.slotIdForNumber(MoonlightConnection.MAX_PADS - 1) != null) named++
    }

    override fun getAsLong(): Long = named
}

// A touch contact's id and its two positions, as MoonlightTouchCycles sends them.
private const val CYCLE_TOUCH_ID = 9
private const val CYCLE_TOUCH_X: Short = -1000
private const val CYCLE_MOVED_TOUCH_X: Short = 1000
private const val CYCLE_TOUCH_EVENTS = 3

// The idle session one touch cycle is measured over.
private fun idleSession() = MoonlightControlSession(ByteArray(IDLE_SESSION_KEY_BYTES), 0, SilentTransport(), { IDLE_SESSION_NOW_MS })

private fun cycleTouchReport(
    active: Boolean,
    x: Short,
) = TouchpadReport(active, false, false, false, false, CYCLE_TOUCH_ID, x, 0, 0, 0, 0, 0L, 0)

// One pad's contact landing, moving and lifting through the connection: three events, over a real
// session that is not connected. Reached through Runnable because the allocation test makes it in
// a class loader of its own.
internal class MoonlightTouchCycles : Runnable {
    private val dispatcher = StandardTestDispatcher()
    private val connection =
        MoonlightConnection(
            id = "moonlight:uid:abc",
            host = MoonlightHost(name = "PC", address = "10.0.0.5", uniqueId = "abc"),
            scope = TestScope(dispatcher),
            ioDispatcher = dispatcher,
        )
    private val landing = cycleTouchReport(active = true, x = CYCLE_TOUCH_X)
    private val moving = cycleTouchReport(active = true, x = CYCLE_MOVED_TOUCH_X)
    private val lifting = cycleTouchReport(active = false, x = CYCLE_MOVED_TOUCH_X)

    init {
        connection.acquirePad("slot-0", PLAYSTATION, CYCLE_CAPS, CYCLE_BUTTONS)
        connection.markLive(idleSession(), appId = null, appName = null)
    }

    override fun run() {
        connection.sendTouchpad("slot-0", landing)
        connection.sendTouchpad("slot-0", moving)
        connection.sendTouchpad("slot-0", lifting)
    }
}

// The same three events sent on an idle session directly: the session's own cost.
internal class MoonlightSessionTouchCycles : Runnable {
    private val session = idleSession()

    override fun run() {
        repeat(CYCLE_TOUCH_EVENTS) { session.sendControllerTouch(0, TOUCH_EVENT_MOVE, CYCLE_TOUCH_ID, 0f, 0f, 1f) }
    }
}

// The connection's per-frame path: what it remembers of each pad's last frame, and that
// remembering it allocates nothing.
class MoonlightConnectionFrameTest {
    private val dispatcher = StandardTestDispatcher()
    private val sent = mutableListOf<SentFrame>()

    // Each touch event the recording session was handed: its controller number and pointer id.
    private val touches = mutableListOf<Pair<Int, Int>>()

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
        every { session.sendControllerTouch(any(), any(), any(), any(), any(), any()) } answers {
            touches += arg<Int>(0) to arg<Int>(2)
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

    @Test
    fun `a controller frame allocates nothing, even once MockK has rewritten the connection class`() {
        mockk<MoonlightConnection>(relaxed = true).activeMask()
        val frames = freshAppInstanceOf(MoonlightFrameCycles::class.java)
        val cycle = frames as Runnable
        repeat(WARMUP_CYCLES) { cycle.run() }
        val allocatedBytes = allocatedBytesDuring { repeat(MEASURED_CYCLES) { cycle.run() } }
        assertEquals((WARMUP_CYCLES + MEASURED_CYCLES).toLong(), (frames as LongSupplier).asLong)
        assertTrue("$allocatedBytes bytes over $MEASURED_CYCLES cycles", allocatedBytes < MEASURED_CYCLES * BYTES_PER_CYCLE_BOUND)
    }

    // ---- a touch frame's contacts ----

    private fun touchReport(
        active: Boolean,
        x: Short,
    ) = clickReport(pressed = false).apply {
        finger0Active = active
        finger0TrackingId = TOUCH_ID
        finger0X = x
    }

    @Test
    fun `a two-finger frame lands both contacts on the pad's number, first finger first`() {
        val conn = liveWithPads(ALL_PADS)
        val twoFingers =
            touchReport(active = true, x = FIRST_TOUCH_X).apply {
                finger1Active = true
                finger1TrackingId = SECOND_TOUCH_ID
            }
        conn.sendTouchpad("slot-$LAST_NUMBER", twoFingers)
        assertEquals(listOf(LAST_NUMBER to TOUCH_ID, LAST_NUMBER to SECOND_TOUCH_ID), touches)
    }

    // The session's own touch send builds its packet, connected or not; that is the session's
    // cost, measured on its own copy and taken off, and what is left is the connection's.
    @Test
    fun `a touch frame allocates nothing past the session's own send, even once MockK has rewritten both classes`() {
        mockk<MoonlightConnection>(relaxed = true).activeMask()
        mockk<MoonlightControlSession>(relaxed = true).sendControllerTouch(0, 0, 0, 0f, 0f, 0f)
        val touches = freshAppInstanceOf(MoonlightTouchCycles::class.java) as Runnable
        val sessionTouches = freshAppInstanceOf(MoonlightSessionTouchCycles::class.java) as Runnable
        repeat(WARMUP_CYCLES) { touches.run() }
        repeat(WARMUP_CYCLES) { sessionTouches.run() }
        val sessionBytes = fewestAllocatedBytesDuring(MEASURED_RUNS) { repeat(MEASURED_CYCLES) { sessionTouches.run() } }
        val connectionBytes = fewestAllocatedBytesDuring(MEASURED_RUNS) { repeat(MEASURED_CYCLES) { touches.run() } }
        val addedBytes = connectionBytes - sessionBytes
        assertTrue("$addedBytes bytes over $MEASURED_CYCLES cycles", addedBytes < MEASURED_CYCLES * BYTES_PER_CYCLE_BOUND)
    }

    // ---- the slot a bridge upcall's controller number names ----

    @Test
    fun `each held pad's number names its slot`() {
        val conn = connection()
        repeat(ALL_PADS) { conn.acquirePad("slot-$it", PLAYSTATION, CAPS, BUTTONS) }
        for (number in 0 until ALL_PADS) assertEquals("slot-$number", conn.slotIdForNumber(number))
    }

    @Test
    fun `a number no pad holds names no slot`() {
        val conn = connection()
        conn.acquirePad("slot-0", PLAYSTATION, CAPS, BUTTONS)
        assertEquals(null, conn.slotIdForNumber(1))
        assertEquals(null, conn.slotIdForNumber(PAST_LAST_NUMBER))
        assertEquals(null, conn.slotIdForNumber(BEFORE_FIRST_NUMBER))
    }

    @Test
    fun `a released pad's number names the slot that takes it next`() {
        val conn = connection()
        conn.acquirePad("slot-0", PLAYSTATION, CAPS, BUTTONS)
        conn.releasePad("slot-0")
        assertEquals(null, conn.slotIdForNumber(FIRST_NUMBER))
        conn.acquirePad("slot-next", PLAYSTATION, CAPS, BUTTONS)
        assertEquals("slot-next", conn.slotIdForNumber(FIRST_NUMBER))
    }

    @Test
    fun `a pad announced again as another type keeps naming its slot`() {
        val conn = connection()
        conn.acquirePad("slot-0", PLAYSTATION, CAPS, BUTTONS)
        conn.reannouncePad("slot-0", XBOX, CAPS, BUTTONS)
        assertEquals("slot-0", conn.slotIdForNumber(FIRST_NUMBER))
    }

    @Test
    fun `looking a slot up by number allocates nothing, even once MockK has rewritten the connection class`() {
        mockk<MoonlightConnection>(relaxed = true).slotIdForNumber(FIRST_NUMBER)
        val lookups = freshAppInstanceOf(MoonlightSlotLookups::class.java)
        val cycle = lookups as Runnable
        cycle.run()
        val allocatedBytes = fewestAllocatedBytesDuring(MEASURED_RUNS, cycle::run)
        assertEquals(((1 + MEASURED_RUNS) * SLOT_LOOKUPS_PER_CYCLE).toLong(), (lookups as LongSupplier).asLong)
        assertTrue("$allocatedBytes bytes over $SLOT_LOOKUPS_PER_CYCLE lookups", allocatedBytes < SLOT_LOOKUPS_PER_CYCLE * BYTES_PER_CYCLE_BOUND)
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

        const val MEASURED_CYCLES = 250

        // Half the smallest object: one allocation in any frame costs 16 bytes or more every
        // cycle, while the JIT's one-off warm-up allocations stay flat as the cycles grow.
        const val BYTES_PER_CYCLE_BOUND = 8
        const val MEASURED_RUNS = 3
        const val TOUCH_ID = 9
        const val SECOND_TOUCH_ID = 10
        const val FIRST_TOUCH_X: Short = -1000
    }
}
