// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.main

import com.tinkernorth.dish.composer.CONTROLLER_TYPE_XBOX
import com.tinkernorth.dish.composer.ConnectionKind
import com.tinkernorth.dish.composer.ConnectionSummary
import com.tinkernorth.dish.composer.LinkState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigUiStateBlockerTest {
    private fun summary(
        id: String,
        live: LinkState,
        label: String = id,
    ) = ConnectionSummary(
        id = id,
        kind = ConnectionKind.SATELLITE,
        label = label,
        detail = "",
        live = live,
        boundSlotIds = emptyList(),
    )

    private fun state(
        hostId: String? = "s:1",
        connections: List<ConnectionSummary> = emptyList(),
        controllerPresent: Boolean = true,
        knownHostLabels: Map<String, String> = emptyMap(),
        dismissed: Set<String> = emptySet(),
        loaded: Boolean = true,
    ) = ConfigUiState(
        loaded = loaded,
        draft =
            BindingDraft(
                hostId = hostId,
                type = CONTROLLER_TYPE_XBOX,
                directOn = false,
                motionOn = false,
                touchpadMode = "off",
            ),
        connections = connections,
        knownHostLabels = knownHostLabels,
        controllerPresent = controllerPresent,
        dismissedUnsteadyHostIds = dismissed,
    )

    @Test
    fun `no blocker before load completes`() {
        assertNull(state(loaded = false, controllerPresent = false).blocker)
    }

    @Test
    fun `controller loss blocks regardless of host state`() {
        val s = state(controllerPresent = false, connections = listOf(summary("s:1", LinkState.Connected)))
        assertEquals(BindingBlocker.InputLost, s.blocker)
    }

    @Test
    fun `no host selected means no host blocker`() {
        assertNull(state(hostId = null).blocker)
    }

    @Test
    fun `connected host is not blocked`() {
        assertNull(state(connections = listOf(summary("s:1", LinkState.Connected))).blocker)
    }

    @Test
    fun `offline host blocks as lost`() {
        val s = state(connections = listOf(summary("s:1", LinkState.Saved, label = "Den PC")))
        assertEquals(BindingBlocker.HostLost("Den PC", reconnecting = false), s.blocker)
    }

    @Test
    fun `connecting host blocks as lost with reconnect in flight`() {
        val s = state(connections = listOf(summary("s:1", LinkState.Connecting, label = "Den PC")))
        assertEquals(BindingBlocker.HostLost("Den PC", reconnecting = true), s.blocker)
    }

    @Test
    fun `forgotten host falls back to the last known label`() {
        val s = state(knownHostLabels = mapOf("s:1" to "Den PC"))
        assertEquals(BindingBlocker.HostLost("Den PC", reconnecting = false), s.blocker)
    }

    @Test
    fun `never seen host blocks with an empty label`() {
        assertEquals(BindingBlocker.HostLost("", reconnecting = false), state().blocker)
    }

    @Test
    fun `unstable host blocks as unsteady`() {
        val s = state(connections = listOf(summary("s:1", LinkState.Unstable)))
        assertEquals(BindingBlocker.HostUnsteady, s.blocker)
    }

    @Test
    fun `dismissed unsteady warning stays dismissed`() {
        val s = state(connections = listOf(summary("s:1", LinkState.Unstable)), dismissed = setOf("s:1"))
        assertNull(s.blocker)
    }

    @Test
    fun `dismissing unsteady does not suppress a real loss`() {
        val s = state(connections = listOf(summary("s:1", LinkState.Saved)), dismissed = setOf("s:1"))
        assertEquals(BindingBlocker.HostLost("s:1", reconnecting = false), s.blocker)
    }

    @Test
    fun `unrelated dismissal does not hide the unsteady warning`() {
        val s = state(connections = listOf(summary("s:1", LinkState.Unstable)), dismissed = setOf("s:other"))
        assertEquals(BindingBlocker.HostUnsteady, s.blocker)
    }

    @Test
    fun `every link state resolves to a deterministic blocker`() {
        for (link in LinkState.entries) {
            val s = state(connections = listOf(summary("s:1", link)))
            when (link) {
                LinkState.Connected -> assertNull(s.blocker)
                LinkState.Unstable -> assertEquals(BindingBlocker.HostUnsteady, s.blocker)
                else -> assertTrue(s.blocker is BindingBlocker.HostLost)
            }
        }
    }
}
