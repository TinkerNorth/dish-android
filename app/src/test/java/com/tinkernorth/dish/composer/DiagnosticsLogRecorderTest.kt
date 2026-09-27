// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.composer

import com.tinkernorth.dish.hotpath.input.PhysicalGamepadRegistry
import com.tinkernorth.dish.source.store.DiagnosticsLogStore
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import org.junit.Assert.assertEquals
import org.junit.Test

// The flight recorder is fed by one collector per flow: a second install must not double every line.
@OptIn(ExperimentalCoroutinesApi::class)
class DiagnosticsLogRecorderTest {
    private val scope = TestScope(StandardTestDispatcher())
    private val connections = MutableStateFlow<List<ConnectionSummary>>(emptyList())
    private val devices = MutableStateFlow<Map<Int, PhysicalGamepadRegistry.Device>>(emptyMap())
    private val hub =
        mockk<ConnectionCoordinator> {
            every { this@mockk.connections } returns this@DiagnosticsLogRecorderTest.connections
        }
    private val registry =
        mockk<PhysicalGamepadRegistry> {
            every { this@mockk.devices } returns this@DiagnosticsLogRecorderTest.devices
        }
    private val log = DiagnosticsLogStore()
    private val recorder = DiagnosticsLogRecorder(hub, registry, log, scope)

    private fun summary(live: LinkState) =
        ConnectionSummary(
            id = LINK,
            kind = ConnectionKind.SATELLITE,
            label = LINK,
            detail = "",
            live = live,
            boundSlotIds = emptyList(),
        )

    private fun linesTagged(tag: String): List<String> =
        log.state.value
            .filter { it.tag == tag }
            .map { it.message }

    @Test
    fun `the first emissions are recorded as the starting inventory`() {
        connections.value = listOf(summary(LinkState.Connected))
        devices.value = mapOf(PAD_ID to PhysicalGamepadRegistry.Device(PAD_ID, PAD_NAME))

        recorder.install()
        scope.testScheduler.runCurrent()

        assertEquals(listOf("$LINK: appeared (SATELLITE, Connected)"), linesTagged(TAG_LINK))
        assertEquals(1, linesTagged(TAG_PAD).size)
    }

    @Test
    fun `install twice records each event once`() {
        connections.value = listOf(summary(LinkState.Connected))
        devices.value = mapOf(PAD_ID to PhysicalGamepadRegistry.Device(PAD_ID, PAD_NAME))

        recorder.install()
        recorder.install()
        scope.testScheduler.runCurrent()

        assertEquals(1, linesTagged(TAG_LINK).size)
        assertEquals(1, linesTagged(TAG_PAD).size)
    }

    @Test
    fun `a link transition after install lands as one line`() {
        connections.value = listOf(summary(LinkState.Connecting))
        recorder.install()
        scope.testScheduler.runCurrent()

        connections.value = listOf(summary(LinkState.Connected))
        scope.testScheduler.runCurrent()

        assertEquals(
            listOf("$LINK: appeared (SATELLITE, Connecting)", "$LINK: Connecting -> Connected"),
            linesTagged(TAG_LINK),
        )
    }

    private companion object {
        const val LINK = "satellite:desk"
        const val PAD_ID = 9
        const val PAD_NAME = "Pad"
        const val TAG_LINK = "link"
        const val TAG_PAD = "pad"
    }
}
