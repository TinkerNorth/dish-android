// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.source.connection

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// Private IPv6 literals: the manager's own host vetting would let each through to a round trip.
private const val IPV6_HOST = "fd00::5"
private const val LINK_LOCAL_IPV6_HOST = "[fe80::1]"
private const val PIN = "1234"
private const val CLIENT_PIN = "4242"

private const val PAIRED = """{"ok":true,"sharedKey":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}"""

// The satellite is IPv4 end to end (its receiver and discovery bind AF_INET, and so does the dish's
// UDP socket), so an IPv6 address is refused before any round trip and the user is told to use IPv4.
@OptIn(ExperimentalCoroutinesApi::class)
class SatelliteConnectionManagerIpv6Test : SatelliteConnectionManagerFixture() {
    private val ipv6Server by lazy { server.copy(ip = IPV6_HOST) }

    private fun stubEverythingAnswers() {
        stubLiveSession()
        every { store.satelliteSharedKey(any()) } returns "aa".repeat(32)
        coEvery { discoveryRepo.pair(any(), any(), any(), any(), any(), any(), any(), any()) } returns ok(PAIRED)
    }

    private fun assertNothingDialled() {
        coVerify(exactly = 0) { discoveryRepo.pair(any(), any(), any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { discoveryRepo.putSession(any(), any(), any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { discoveryRepo.pairStatus(any(), any(), any(), any()) }
        verify(exactly = 0) { controllerRepo.openSocket(any(), any()) }
    }

    private fun assertToldToUseIpv4(events: List<ConnectionEvent>) =
        assertEquals(listOf(ConnectionEvent.Error(ConnectionError.Ipv6Unsupported)), events)

    private fun userAttemptIsRefused(attempt: (SatelliteConnectionManager) -> Unit) =
        runMgrTest { mgr, events ->
            stubEverythingAnswers()

            attempt(mgr)
            scope.testScheduler.runCurrent()

            assertNothingDialled()
            assertToldToUseIpv4(events)
            val rows = mgr.connections.value.values
            assertTrue("no connection left linking: $rows", rows.all { it.state.value == SatelliteSessionState.Idle })
        }

    @Test
    fun `a user connect to a paired IPv6 satellite opens no session and says to use IPv4`() =
        userAttemptIsRefused { mgr -> mgr.connect(ipv6Server, ConnectIntent.USER_INITIATED) }

    @Test
    fun `a user connect to an unpaired IPv6 satellite sends no pair request and says to use IPv4`() =
        userAttemptIsRefused { mgr ->
            every { store.satelliteSharedKey(any()) } returns null
            mgr.connect(ipv6Server, ConnectIntent.USER_INITIATED)
        }

    @Test
    fun `a bracketed link-local IPv6 address is refused the same way`() =
        userAttemptIsRefused { mgr -> mgr.connect(server.copy(ip = LINK_LOCAL_IPV6_HOST), ConnectIntent.USER_INITIATED) }

    @Test
    fun `a PIN pairing with an IPv6 satellite sends no pair request and says to use IPv4`() =
        userAttemptIsRefused { mgr -> mgr.pairWithPin(ipv6Server, PIN) }

    @Test
    fun `an approval request to an IPv6 satellite sends no pair request and says to use IPv4`() =
        userAttemptIsRefused { mgr -> mgr.requestApproval(ipv6Server, CLIENT_PIN) }

    @Test
    fun `a silent reconnect to an IPv6 satellite dials nothing and raises no banner`() =
        runMgrTest { mgr, events ->
            stubEverythingAnswers()

            mgr.connect(ipv6Server, ConnectIntent.AUTO_RECONNECT)
            scope.testScheduler.runCurrent()

            assertNothingDialled()
            assertTrue("a reconnect the user did not ask for raises no banner: $events", events.isEmpty())
        }

    @Test
    fun `an IPv4 satellite beside it still goes live`() =
        runMgrTest { mgr, events ->
            stubEverythingAnswers()

            mgr.connect(server, ConnectIntent.USER_INITIATED)
            scope.testScheduler.runCurrent()

            assertEquals(SatelliteSessionState.Live, mgr.get(serverId)?.state?.value)
            assertTrue(events.isEmpty())
        }
}
