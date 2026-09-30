// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.composer

import com.tinkernorth.dish.source.store.LinkHistoryStore
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LinkHistoryRecorderTest {
    private val scope = TestScope(StandardTestDispatcher())
    private val connections = MutableStateFlow<List<ConnectionSummary>>(emptyList())
    private val bindings = MutableStateFlow<Map<String, String>>(emptyMap())
    private val hub =
        mockk<ConnectionCoordinator> {
            every { this@mockk.connections } returns this@LinkHistoryRecorderTest.connections
            every { this@mockk.bindings } returns this@LinkHistoryRecorderTest.bindings
        }

    private fun summary(live: LinkState) =
        ConnectionSummary(
            id = LINK,
            kind = ConnectionKind.SATELLITE,
            label = LINK,
            detail = "",
            live = live,
            boundSlotIds = emptyList(),
        )

    @Test
    fun `a link that comes up counts one connect`() {
        val store = LinkHistoryStore()
        connections.value = listOf(summary(LinkState.Connected))

        LinkHistoryRecorder(hub, store, scope).install()
        scope.testScheduler.runCurrent()

        assertEquals(
            1,
            store.state.value.connections
                .getValue(LINK)
                .connects,
        )
    }

    @Test
    fun `a binding is stamped with the connection it points at`() {
        val store = LinkHistoryStore()
        bindings.value = mapOf(SLOT to LINK)

        LinkHistoryRecorder(hub, store, scope).install()
        scope.testScheduler.runCurrent()

        assertEquals(LINK, store.state.value.boundTo[SLOT])
    }

    @Test
    fun `install twice keeps one collector per flow`() {
        val store = mockk<LinkHistoryStore>(relaxed = true)
        val recorder = LinkHistoryRecorder(hub, store, scope)

        recorder.install()
        recorder.install()
        scope.testScheduler.runCurrent()

        verify(exactly = FLOWS_RECORDED) { store.update(any()) }
    }

    private companion object {
        const val LINK = "satellite:desk"
        const val SLOT = "slot-A"
        const val FLOWS_RECORDED = 2
    }
}
