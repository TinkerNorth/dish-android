// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.composer

import com.tinkernorth.dish.architecture.testing.composerTest
import com.tinkernorth.dish.architecture.testing.probe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Demonstrates the [com.tinkernorth.dish.architecture.abstracts.AbstractComposer] test pattern: the upstream
 * flows are fed as [MutableStateFlow]s; the [com.tinkernorth.dish.architecture.abstracts.AbstractComposerProbe]
 * records every derived value so assertions can target either the latest or the
 * full sequence.
 *
 * No `LifecycleOwner`, no scheduler advance, no JNI mock — composers are pure and
 * test like pure functions.
 */
class WakeStateComposerTest {
    private fun connectionSummary(
        id: String,
        live: LinkState,
    ): ConnectionSummary =
        ConnectionSummary(
            id = id,
            kind = ConnectionKind.SATELLITE,
            label = id,
            detail = "",
            live = live,
            boundSlotIds = emptyList(),
        )

    private fun composerFor(
        bindings: MutableStateFlow<Map<String, String>>,
        connections: MutableStateFlow<List<ConnectionSummary>>,
        scope: kotlinx.coroutines.CoroutineScope,
    ): WakeStateComposer {
        val hub: ConnectionHub =
            mockk {
                every { this@mockk.bindings } returns bindings
                every { this@mockk.connections } returns connections
            }
        return WakeStateComposer(hub, scope)
    }

    @Test
    fun `idle when no bindings`() =
        composerTest {
            val bindings = MutableStateFlow<Map<String, String>>(emptyMap())
            val conns = MutableStateFlow<List<ConnectionSummary>>(emptyList())
            val probe = composerFor(bindings, conns, backgroundScope).probe(this)
            testScheduler.runCurrent()

            probe.assertLatest(WakeState.Idle)
        }

    @Test
    fun `streamingSlotCount counts slots whose connection is Connected`() =
        composerTest {
            val bindings = MutableStateFlow(mapOf("slot-1" to "sat-A", "slot-2" to "sat-B"))
            val conns =
                MutableStateFlow(
                    listOf(
                        connectionSummary("sat-A", LinkState.Connected),
                        connectionSummary("sat-B", LinkState.Connecting),
                    ),
                )
            val probe = composerFor(bindings, conns, backgroundScope).probe(this)
            testScheduler.runCurrent()

            // sat-A is Connected → slot-1 counts; sat-B is Connecting → slot-2 does not.
            probe.assertLatest(WakeState(streamingSlotCount = 1, shouldKeepScreenOn = true))
        }

    @Test
    fun `shouldKeepScreenOn flips when last Connected slot disconnects`() =
        composerTest {
            val bindings = MutableStateFlow(mapOf("slot-1" to "sat-A"))
            val conns = MutableStateFlow(listOf(connectionSummary("sat-A", LinkState.Connected)))
            val probe = composerFor(bindings, conns, backgroundScope).probe(this)
            testScheduler.runCurrent()

            assertEquals(true, probe.latest.shouldKeepScreenOn)

            // Disconnect.
            conns.value = listOf(connectionSummary("sat-A", LinkState.Saved))
            testScheduler.runCurrent()

            assertEquals(false, probe.latest.shouldKeepScreenOn)
            assertEquals(0, probe.latest.streamingSlotCount)
        }
}
