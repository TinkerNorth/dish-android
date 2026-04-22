package com.tinkernorth.dish.ui.bluetooth

/**
 * In-memory test double for [HidProxyClient]. Records every call the session
 * makes to the proxy and exposes helpers to inject framework events back into
 * the session. Threading is irrelevant here — tests drive the session on one
 * thread and all synchronization lives in the session itself.
 */
internal class FakeHidProxyClient(
    var adapterEnabled: Boolean = true,
    /** MAC → name for hosts the OS already considers connected. */
    var osConnectedHosts: MutableMap<String, String?> = mutableMapOf(),
    var sendReportReturns: Boolean = true,
) : HidProxyClient {

    sealed interface Call {
        data class Acquire(val events: HidProxyClient.Events) : Call
        data class RegisterApp(val profile: BluetoothGamepad.GamepadProfile) : Call
        data class ConnectToHost(val mac: String) : Call
        data object DisconnectCurrentHost : Call
        data object UnregisterAndRelease : Call
        data class SendReport(val report: ByteArray) : Call
    }

    val calls: MutableList<Call> = mutableListOf()
    private var events: HidProxyClient.Events? = null

    override fun isAdapterEnabled(): Boolean = adapterEnabled

    override fun acquire(events: HidProxyClient.Events) {
        this.events = events
        calls += Call.Acquire(events)
    }

    override fun registerApp(profile: BluetoothGamepad.GamepadProfile) {
        calls += Call.RegisterApp(profile)
    }

    override fun connectToHost(mac: String) {
        calls += Call.ConnectToHost(mac)
    }

    override fun disconnectCurrentHost() {
        calls += Call.DisconnectCurrentHost
    }

    override fun findOsConnectedHost(mac: String): String? =
        if (osConnectedHosts.containsKey(mac)) osConnectedHosts[mac] else null

    override fun sendReport(report: ByteArray): Boolean {
        calls += Call.SendReport(report)
        return sendReportReturns
    }

    override fun unregisterAndRelease() {
        calls += Call.UnregisterAndRelease
        events = null
    }

    // ── Event injection ─────────────────────────────────────────────────

    fun fireAcquired() = events?.onAcquired() ?: Unit
    fun fireReleased() = events?.onReleased() ?: Unit
    fun fireAppRegistered() = events?.onAppRegistered() ?: Unit
    fun fireAppUnregistered() = events?.onAppUnregistered() ?: Unit
    fun fireHostConnected(mac: String, name: String? = null) = events?.onHostConnected(mac, name) ?: Unit
    fun fireHostDisconnected(mac: String) = events?.onHostDisconnected(mac) ?: Unit
    fun fireError(message: String) = events?.onError(message) ?: Unit

    fun hasLiveEvents(): Boolean = events != null
}
