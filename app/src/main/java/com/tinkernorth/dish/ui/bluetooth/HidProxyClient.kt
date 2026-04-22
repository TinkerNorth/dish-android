package com.tinkernorth.dish.ui.bluetooth

/**
 * Narrow seam over [android.bluetooth.BluetoothHidDevice] and
 * [android.bluetooth.BluetoothAdapter] so the HID state machine in
 * [BluetoothHidSession] can be unit tested without the Android framework.
 *
 * Implementations own the profile-proxy binding and the registered HID app.
 * Callers deliver events through [Events] — the session is responsible for
 * all state transitions and for ignoring events delivered by a proxy that
 * has already been released (see [unregisterAndRelease]).
 *
 * All methods are safe to call repeatedly: re-entry from the session is
 * expected whenever the user taps "reconnect" on a stale session.
 */
interface HidProxyClient {

    interface Events {
        /** Profile proxy successfully bound; the session may now register the app. */
        fun onAcquired()

        /** Profile proxy was released by the framework. All subsequent calls are no-ops. */
        fun onReleased()

        /** HID app registration succeeded; the session may attempt a host connect. */
        fun onAppRegistered()

        /** HID app registration was lost (framework teardown, OEM freeze, etc). */
        fun onAppUnregistered()

        fun onHostConnected(mac: String, name: String?)
        fun onHostDisconnected(mac: String)

        /** Any unrecoverable error from the profile binding or registration. */
        fun onError(message: String)
    }

    /** @return true if the Bluetooth adapter is powered on. */
    fun isAdapterEnabled(): Boolean

    /** Bind the HID Device profile proxy. Events will arrive via [events]. */
    fun acquire(events: Events)

    /** Register the HID app descriptors. Valid only after [Events.onAcquired]. */
    fun registerApp(profile: BluetoothGamepad.GamepadProfile)

    /** Actively open an HID link to the remote [mac]. */
    fun connectToHost(mac: String)

    /** Disconnect whichever host is currently plugged in, if any. */
    fun disconnectCurrentHost()

    /**
     * If the framework already has the host at [mac] in connected state,
     * return its display name (or `null` if unknown). Returns `null` when
     * the host is not connected.
     */
    fun findOsConnectedHost(mac: String): String?

    /** @return true if the report was accepted by the framework. */
    fun sendReport(report: ByteArray): Boolean

    /**
     * Unregister the HID app and close the profile proxy. MUST be called on
     * every teardown path, including error recovery and restart, so that
     * the next [acquire] starts from a clean framework state. Safe to call
     * when nothing is bound.
     */
    fun unregisterAndRelease()
}
