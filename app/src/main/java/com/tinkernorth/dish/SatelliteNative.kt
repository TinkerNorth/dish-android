package com.tinkernorth.dish

/**
 * JNI bridge to satellite_jni.cpp.
 * Controller input is now handled entirely in the native GameActivity loop.
 * Network-blocking functions (discoverServers, pair) must be called
 * from a background coroutine (Dispatchers.IO).
 */
object SatelliteNative {

    init {
        System.loadLibrary("satellite")
    }

    /** Open a UDP socket aimed at ip:port for streaming. Returns true on success. */
    external fun openSocket(ip: String, port: Int): Boolean

    /** Close the streaming UDP socket. */
    external fun closeSocket()

    /**
     * Send a 12-byte XUSB_REPORT packet immediately (non-blocking sendto).
     * Called from the main thread input handlers for minimum latency.
     */
    external fun sendReport(
        wButtons: Int, bLeftTrigger: Int, bRightTrigger: Int,
        sThumbLX: Int, sThumbLY: Int, sThumbRX: Int, sThumbRY: Int
    )

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

