// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.core.jni

/**
 * One encrypted UDP session to a satellite: the socket, its keys, the heartbeat that keeps
 * it alive and the acks that come back, plus beacon discovery of satellites on the LAN.
 */
object SessionNative {
    init {
        System.loadLibrary("satellite")
    }

    external fun openSocket(
        ip: String,
        port: Int,
    ): Int

    external fun closeSocket(handle: Int)

    external fun setConnectionParams(
        handle: Int,
        token: ByteArray,
        key: ByteArray,
        protocolVersion: Int,
    )

    external fun startHeartbeat(handle: Int)

    external fun stopHeartbeat(handle: Int)

    external fun isConnectionAlive(handle: Int): Boolean

    // Session epoch from the latest enriched heartbeat ack; -1 until one lands.
    // Compared against the applied epoch from the last PUT/GET: mismatch means
    // the server's topology moved involuntarily and a REST reconcile is due.
    external fun getServerEpoch(handle: Int): Int

    // Active-controller bitmap (bit i = ctrlIdx i live) from the latest ack; -1 until one lands.
    external fun getActiveBitmap(handle: Int): Int

    // CLOSE_REASON_* from an authenticated session-close notify (0x000F); -1 = none.
    // Terminal: the session is gone server-side the moment this is non-negative.
    external fun getSessionCloseReason(handle: Int): Int

    // Send counter for the proactive re-key poll. Clamped at 0xFFFFFFFF past
    // exhaustion so it can never read below the re-PUT threshold again.
    external fun getSendCounter(handle: Int): Long

    external fun getVigemAvailable(handle: Int): Int

    external fun getActiveControllerCount(handle: Int): Int

    // Blocks ≤500ms (socket recv timeout); call from background. Returns
    // 1 = datagram consumed, 0 = timeout/rejected datagram, -1 = session or
    // socket dead (terminal: the caller's drain loop must stop).
    external fun receiveAck(handle: Int): Int

    // One session's own RTT window plus ping/ack/missed tallies; empty for an unknown handle.
    external fun sessionStatsJson(handle: Int): String

    // BLOCKING. Call on Dispatchers.IO. Returns JSON array of beacon objects.
    external fun discoverServers(
        discPort: Int,
        timeoutMs: Int,
    ): String
}
