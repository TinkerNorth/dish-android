// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.source.connection

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Assert.assertEquals
import org.junit.Test

// Five missed ticks: the heartbeat has declared the session dead.
private const val PAST_DEATH_MS = 5100L

// One heartbeat tick: the alive poll has run once.
private const val ONE_TICK_MS = 1100L

// Twice the 60 s backoff cap: any retry still pending has fired by then.
private const val PAST_EVERY_BACKOFF_MS = 120_000L

private const val OTHER_IP = "10.0.0.6"
private const val PIN = "1234"
private const val CLIENT_PIN = "4242"

// A user Disconnect holds that satellite down against every reconnect the app makes on its own (the
// foreground auto reconnect, a reappearing host, a silent retry) until the user connects it again or
// forgets it. A disconnect the manager makes itself holds nothing down.
@OptIn(ExperimentalCoroutinesApi::class)
class SatelliteConnectionManagerUserDisconnectTest : SatelliteConnectionManagerFixture() {
    private fun stateOf(mgr: SatelliteConnectionManager) = mgr.get(serverId)?.state?.value

    private fun assertSessionPuts(
        ip: String,
        count: Int,
    ) = coVerify(exactly = count) { discoveryRepo.putSession(ip, any(), any(), any(), any(), any(), any(), any()) }

    private fun reconnectOnItsOwn(
        mgr: SatelliteConnectionManager,
        intent: ConnectIntent,
    ) {
        mgr.connect(server, intent)
        scope.testScheduler.runCurrent()
    }

    private fun silentConnectAfterAUserDisconnect(intent: ConnectIntent) =
        runMgrTest { mgr, _ ->
            stubLiveSession()
            connectLive(mgr)
            mgr.disconnect(serverId)

            reconnectOnItsOwn(mgr, intent)

            assertEquals(SatelliteSessionState.Idle, stateOf(mgr))
            assertSessionPuts(server.ip, 1)
        }

    @Test
    fun `a user disconnect keeps the foreground auto reconnect from dialling`() =
        silentConnectAfterAUserDisconnect(ConnectIntent.AUTO_RECONNECT)

    @Test
    fun `a user disconnect keeps a silent retry from dialling`() = silentConnectAfterAUserDisconnect(ConnectIntent.RETRY_AFTER_DEATH)

    @Test
    fun `a user disconnect of one satellite leaves another to reconnect on its own`() =
        runMgrTest { mgr, _ ->
            stubLiveSession()
            val other = server.copy(name = "Other", ip = OTHER_IP)
            every { store.satelliteSharedKey(satelliteConnectionIdFor(other)) } returns "aa".repeat(32)
            connectLive(mgr)
            mgr.disconnect(serverId)

            mgr.connect(other, ConnectIntent.AUTO_RECONNECT)
            scope.testScheduler.runCurrent()

            assertEquals(SatelliteSessionState.Live, mgr.get(satelliteConnectionIdFor(other))?.state?.value)
        }

    // Once the user has asked for the satellite again, the app's own reconnects are back on: the user
    // connect here fails, and the foreground reconnect after it dials.
    private fun userAskingAgainLiftsTheHold(askAgain: (SatelliteConnectionManager) -> Unit) =
        runMgrTest { mgr, _ ->
            stubLiveSession()
            connectLive(mgr)
            mgr.disconnect(serverId)
            coEvery {
                discoveryRepo.putSession(any(), any(), any(), any(), any(), any(), any(), any())
            } returns unreachable()
            coEvery { discoveryRepo.pair(any(), any(), any(), any(), any(), any(), any(), any()) } returns unreachable()
            askAgain(mgr)
            scope.testScheduler.runCurrent()
            assertEquals(SatelliteSessionState.Idle, stateOf(mgr))
            coEvery {
                discoveryRepo.putSession(any(), any(), any(), any(), any(), any(), any(), any())
            } returns ok(sessionGrantBody())

            reconnectOnItsOwn(mgr, ConnectIntent.AUTO_RECONNECT)

            assertEquals(SatelliteSessionState.Live, stateOf(mgr))
        }

    @Test
    fun `a user connect after a user disconnect lets the app reconnect on its own again`() =
        userAskingAgainLiftsTheHold { mgr -> mgr.connect(server, ConnectIntent.USER_INITIATED) }

    @Test
    fun `a PIN pairing after a user disconnect lets the app reconnect on its own again`() =
        userAskingAgainLiftsTheHold { mgr -> mgr.pairWithPin(server, PIN) }

    @Test
    fun `an approval request after a user disconnect lets the app reconnect on its own again`() =
        userAskingAgainLiftsTheHold { mgr -> mgr.requestApproval(server, CLIENT_PIN) }

    @Test
    fun `forgetting a satellite the user disconnected lifts the hold`() =
        runMgrTest { mgr, _ ->
            stubLiveSession()
            connectLive(mgr)
            mgr.disconnect(serverId)
            mgr.forget(serverId)

            reconnectOnItsOwn(mgr, ConnectIntent.AUTO_RECONNECT)

            assertEquals(SatelliteSessionState.Live, stateOf(mgr))
            assertSessionPuts(server.ip, 2)
        }

    // The manager's own teardown is no user disconnect: the silent retry brings the session back.
    private fun managerTeardownRetriesOnItsOwn(endSession: () -> Unit) =
        runMgrTest { mgr, _ ->
            stubLiveSession()
            connectLive(mgr)

            endSession()
            scope.testScheduler.advanceTimeBy(PAST_EVERY_BACKOFF_MS)
            scope.testScheduler.runCurrent()

            assertEquals(SatelliteSessionState.Live, stateOf(mgr))
            assertSessionPuts(server.ip, 2)
        }

    @Test
    fun `a heartbeat death holds nothing down and the silent retry reconnects`() =
        managerTeardownRetriesOnItsOwn {
            every { controllerRepo.isConnectionAlive(any()) } returns false
            scope.testScheduler.advanceTimeBy(PAST_DEATH_MS)
            scope.testScheduler.runCurrent()
            every { controllerRepo.isConnectionAlive(any()) } returns true
        }

    @Test
    fun `a satellite kick holds nothing down and the silent retry reconnects`() =
        managerTeardownRetriesOnItsOwn {
            every { controllerRepo.getSessionCloseReason(any()) } returns SatelliteConnection.CLOSE_REASON_KICKED
            scope.testScheduler.advanceTimeBy(ONE_TICK_MS)
            scope.testScheduler.runCurrent()
            every { controllerRepo.getSessionCloseReason(any()) } returns -1
        }
}
