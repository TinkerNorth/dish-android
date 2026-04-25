// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.data.network

/**
 * JNI bridge to satellite_jni.cpp.
 * Controller input is handled via direct JNI calls from Kotlin for lowest latency.
 * Network-blocking functions (discoverServers, pair, httpConnect, httpDisconnect)
 * must be called from a background coroutine (Dispatchers.IO).
 */
object SatelliteNative {
    init {
        System.loadLibrary("satellite")
    }

    // ── UDP Session (handle-based) ──────────────────────────────────────────
    // openSocket returns a positive session handle, or -1 on failure. All other
    // session-scoped calls take that handle so multiple WiFi servers can run
    // side-by-side with independent sockets, tokens, counters and heartbeat
    // threads.

    /** Open a UDP socket aimed at ip:port. Returns handle >= 1 on success, -1 on failure. */
    external fun openSocket(
        ip: String,
        port: Int,
    ): Int

    /** Close the session identified by [handle] and stop its heartbeat. */
    external fun closeSocket(handle: Int)

    // ── Connection params ───────────────────────────────────────────────────

    /** Set the 4-byte token and 32-byte encryption key for [handle]. */
    external fun setConnectionParams(
        handle: Int,
        token: ByteArray,
        key: ByteArray,
    )

    // ── Encrypted gamepad data ──────────────────────────────────────────────

    /**
     * Send an encrypted gamepad report (0x0001) on [handle] for the given
     * controller index. Non-blocking sendto, called from main thread for
     * minimum latency.
     */
    external fun sendReport(
        handle: Int,
        controllerIndex: Int,
        wButtons: Int,
        bLeftTrigger: Int,
        bRightTrigger: Int,
        sThumbLX: Int,
        sThumbLY: Int,
        sThumbRX: Int,
        sThumbRY: Int,
    )

    // ── Controller management ───────────────────────────────────────────────

    /** Send 0x0004 Controller Add message on [handle]. */
    external fun controllerAdd(
        handle: Int,
        controllerIndex: Int,
        capabilities: Int,
    )

    /** Send 0x0005 Controller Remove message on [handle]. */
    external fun controllerRemove(
        handle: Int,
        controllerIndex: Int,
    )

    /** Send 0x0008 Controller Type message on [handle]. */
    external fun sendControllerType(
        handle: Int,
        controllerIndex: Int,
        controllerType: Int,
    )

    // ── Heartbeat ───────────────────────────────────────────────────────────

    /** Start the heartbeat sender thread for [handle] (sends 0x0002 every 2s). */
    external fun startHeartbeat(handle: Int)

    /** Stop the heartbeat sender thread for [handle]. */
    external fun stopHeartbeat(handle: Int)

    /** Check if [handle]'s connection is still alive (no missed ACK timeout). */
    external fun isConnectionAlive(handle: Int): Boolean

    /**
     * Returns the last controller ACK for [handle] as a packed int32:
     *   bits 31-16: requestType (0x0004 or 0x0005)
     *   bits 15-8:  controllerIndex
     *   bits 7-0:   result code (0x00=OK, 0x01=VIGEM_UNAVAIL, etc.)
     * Returns -1 if no ACK has been received yet.
     */
    external fun getLastControllerAck(handle: Int): Int

    /** Reset the controller ACK state for [handle] to -1. */
    external fun resetControllerAck(handle: Int)

    /** ViGEm availability from the latest 0x0007 Server Status on [handle]. */
    external fun getVigemAvailable(handle: Int): Int

    /** Active controller count from the latest 0x0007 Server Status on [handle]. */
    external fun getActiveControllerCount(handle: Int): Int

    /**
     * Receive and process one UDP packet on [handle] (heartbeat/controller ACK
     * or server status). Non-blocking with 500ms timeout. Call from background.
     */
    external fun receiveAck(handle: Int)

    // ── HTTP Connection API ─────────────────────────────────────────────────

    /**
     * POST /api/connections — opens a new connection for this device.
     * Returns the server's JSON response (connectionId, token, maxControllers, vigemAvailable).
     * BLOCKING — call on Dispatchers.IO.
     */
    external fun httpConnect(
        ip: String,
        httpPort: Int,
        deviceId: String,
    ): String

    /**
     * DELETE /api/connections/:id — closes the connection and removes all controllers.
     * Returns the server's JSON response.
     * BLOCKING — call on Dispatchers.IO.
     */
    external fun httpDisconnect(
        ip: String,
        httpPort: Int,
        connectionId: String,
        deviceId: String,
    ): String

    // ── Discovery & Pairing ─────────────────────────────────────────────────

    /**
     * Listen on discPort for timeoutMs milliseconds, collecting Satellite beacon
     * broadcasts. Returns a JSON array of server objects (name, ip, udpPort, pairPort).
     * BLOCKING — call on Dispatchers.IO.
     */
    external fun discoverServers(
        discPort: Int,
        timeoutMs: Int,
    ): String

    /**
     * Perform a TCP pairing handshake with the server.
     * Returns the server's raw JSON response, e.g. {"ok":true,"message":"paired successfully"}.
     * BLOCKING — call on Dispatchers.IO.
     */
    external fun pair(
        ip: String,
        pairPort: Int,
        deviceId: String,
        deviceName: String,
        pin: String,
    ): String
}
