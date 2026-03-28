package com.tinkernorth.dish

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

    // ── UDP Socket ──────────────────────────────────────────────────────────

    /** Open a UDP socket aimed at ip:port for streaming. Returns true on success. */
    external fun openSocket(ip: String, port: Int): Boolean

    /** Close the streaming UDP socket and stop heartbeat. */
    external fun closeSocket()

    // ── Connection params ───────────────────────────────────────────────────

    /** Set the 4-byte token and 32-byte encryption key for this connection. */
    external fun setConnectionParams(token: ByteArray, key: ByteArray)

    // ── Encrypted gamepad data ──────────────────────────────────────────────

    /**
     * Send an encrypted gamepad report (0x0001) for the given controller index.
     * Non-blocking sendto, called from main thread for minimum latency.
     */
    external fun sendReport(
        controllerIndex: Int,
        wButtons: Int, bLeftTrigger: Int, bRightTrigger: Int,
        sThumbLX: Int, sThumbLY: Int, sThumbRX: Int, sThumbRY: Int
    )

    // ── Controller management ───────────────────────────────────────────────

    /** Send 0x0004 Controller Add message. */
    external fun controllerAdd(controllerIndex: Int, capabilities: Int)

    /** Send 0x0005 Controller Remove message. */
    external fun controllerRemove(controllerIndex: Int)

    // ── Heartbeat ───────────────────────────────────────────────────────────

    /** Start the heartbeat sender thread (sends 0x0002 every 2s). */
    external fun startHeartbeat()

    /** Stop the heartbeat sender thread. */
    external fun stopHeartbeat()

    /** Check if the connection is still alive (no missed ACK timeout). */
    external fun isConnectionAlive(): Boolean

    /**
     * Returns the last controller ACK as a packed int32:
     *   bits 31-16: requestType (0x0004 or 0x0005)
     *   bits 15-8:  controllerIndex
     *   bits 7-0:   result code (0x00=OK, 0x01=VIGEM_UNAVAIL, etc.)
     * Returns -1 if no ACK has been received yet.
     */
    external fun getLastControllerAck(): Int

    /** Reset the controller ACK state to -1 (no ACK pending). */
    external fun resetControllerAck()

    /**
     * Returns ViGEm availability from the latest 0x0007 Server Status message.
     * -1 = unknown (no status received yet), 0 = idle/unavailable, 1 = bus open
     */
    external fun getVigemAvailable(): Int

    /**
     * Returns the global active controller count from the latest 0x0007 Server Status.
     * -1 = unknown (no status received yet), 0+ = count across all connections
     */
    external fun getActiveControllerCount(): Int

    /**
     * Try to receive and process one UDP packet (heartbeat ACK / controller ACK / server status).
     * Non-blocking with 500ms timeout. Call from a background thread.
     */
    external fun receiveAck()

    // ── HTTP Connection API ─────────────────────────────────────────────────

    /**
     * POST /api/connections — opens a new connection for this device.
     * Returns the server's JSON response (connectionId, token, maxControllers, vigemAvailable).
     * BLOCKING — call on Dispatchers.IO.
     */
    external fun httpConnect(ip: String, httpPort: Int, deviceId: String): String

    /**
     * DELETE /api/connections/:id — closes the connection and removes all controllers.
     * Returns the server's JSON response.
     * BLOCKING — call on Dispatchers.IO.
     */
    external fun httpDisconnect(ip: String, httpPort: Int, connectionId: String, deviceId: String): String

    // ── Discovery & Pairing ─────────────────────────────────────────────────

    /**
     * Listen on discPort for timeoutMs milliseconds, collecting Satellite beacon
     * broadcasts. Returns a JSON array of server objects (name, ip, udpPort, pairPort).
     * BLOCKING — call on Dispatchers.IO.
     */
    external fun discoverServers(discPort: Int, timeoutMs: Int): String

    /**
     * Perform a TCP pairing handshake with the server.
     * Returns the server's raw JSON response, e.g. {"ok":true,"message":"paired successfully"}.
     * BLOCKING — call on Dispatchers.IO.
     */
    external fun pair(
        ip: String, pairPort: Int,
        deviceId: String, deviceName: String, pin: String
    ): String
}

