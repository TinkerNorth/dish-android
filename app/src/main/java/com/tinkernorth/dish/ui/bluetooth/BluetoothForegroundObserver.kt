package com.tinkernorth.dish.ui.bluetooth

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.tinkernorth.dish.data.network.ConnectionHub
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Process-scoped observer that re-establishes the Bluetooth HID link when the
 * app returns to the foreground.
 *
 * The app's original reconnect bug was caused by holding a stale HID
 * registration across backgrounding: the OS-level Bluetooth link was still
 * live, but the app's proxy binding had been severed, so reports silently
 * went nowhere. Binding this observer to [androidx.lifecycle.ProcessLifecycleOwner]
 * guarantees we always kick [ConnectionHub.autoReconnectAll] on every return
 * to foreground, which in turn tears down and rebuilds the HID session if
 * the remembered host isn't currently live. Safe to no-op when everything
 * is already healthy — the hub's reconnect paths are idempotent.
 */
@Singleton
class BluetoothForegroundObserver @Inject constructor(
    private val hub: ConnectionHub,
) : DefaultLifecycleObserver {

    override fun onStart(owner: LifecycleOwner) {
        hub.autoReconnectAll()
    }
}
