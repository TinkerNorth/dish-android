// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.source.connection

import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import kotlinx.coroutines.suspendCancellableCoroutine
import java.net.Inet4Address
import java.util.concurrent.Executor
import kotlin.coroutines.resume

/**
 * Resolves one service a discovery listener reported to the record that names its host, the
 * step both LAN discoveries (satellite and Moonlight) share. Null is "could not be resolved"
 * in every form: the OS refused the request, the resolve failed, or the caller's timeout
 * cancelled it.
 */
internal object NsdServiceResolver {
    private const val TAG = "NsdServiceResolver"

    suspend fun resolveNsdService(
        nsd: NsdManager,
        info: NsdServiceInfo,
    ): NsdServiceInfo? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            resolveViaCallback(nsd, info)
        } else {
            resolveViaListener(nsd, info)
        }

    /**
     * The address to open a session to. The UDP data path is IPv4-only, so an IPv4 address
     * is preferred wherever the platform offers the whole list; an IPv6-only host cannot
     * carry a session (openSocket refuses non-IPv4 literals rather than streaming to 0.0.0.0).
     */
    fun hostAddress(info: NsdServiceInfo): String? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val addresses = info.hostAddresses
            (addresses.firstOrNull { it is Inet4Address } ?: addresses.firstOrNull())?.hostAddress
        } else {
            legacyHostAddress(info)
        }

    // API 34 resolves through a registered callback: the first update carries the host, and
    // the registration is dropped as soon as it has answered (or the caller gave up).
    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private suspend fun resolveViaCallback(
        nsd: NsdManager,
        info: NsdServiceInfo,
    ): NsdServiceInfo? =
        suspendCancellableCoroutine { cont ->
            val callback =
                object : NsdManager.ServiceInfoCallback {
                    override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {
                        Log.w(TAG, "resolve of ${info.serviceName} could not be registered: $errorCode")
                        if (cont.isActive) cont.resume(null)
                    }

                    override fun onServiceUpdated(serviceInfo: NsdServiceInfo) {
                        if (!cont.isActive) return
                        unregister(nsd, this)
                        cont.resume(serviceInfo)
                    }

                    override fun onServiceLost() {
                        if (!cont.isActive) return
                        unregister(nsd, this)
                        cont.resume(null)
                    }

                    override fun onServiceInfoCallbackUnregistered() = Unit
                }
            try {
                nsd.registerServiceInfoCallback(info, INLINE_EXECUTOR, callback)
            } catch (e: IllegalArgumentException) {
                // The OS already holds a registration for this service (a resolveNsdService racing past
                // the caller's serialisation); this one yields rather than doubling it.
                Log.w(TAG, "resolve of ${info.serviceName} rejected: ${e.message}")
                if (cont.isActive) cont.resume(null)
            }
            cont.invokeOnCancellation { unregister(nsd, callback) }
        }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun unregister(
        nsd: NsdManager,
        callback: NsdManager.ServiceInfoCallback,
    ) {
        try {
            nsd.unregisterServiceInfoCallback(callback)
        } catch (e: IllegalArgumentException) {
            // Already gone (the OS dropped it with the service, or the other completion path
            // won the race); nothing is held either way.
            Log.d(TAG, "resolve callback already unregistered: ${e.message}")
        }
    }

    // Marker: NsdManager.resolveService is the only resolveNsdService API before 34, where
    // registerServiceInfoCallback (used above) replaces it. Deprecated in the SDK the app
    // compiles against, current on every device that reaches this branch. The right fix is
    // a minSdk of 34, which would drop Android 7 to 13 devices.
    @Suppress("DEPRECATION")
    private suspend fun resolveViaListener(
        nsd: NsdManager,
        info: NsdServiceInfo,
    ): NsdServiceInfo? =
        suspendCancellableCoroutine { cont ->
            val listener =
                object : NsdManager.ResolveListener {
                    override fun onResolveFailed(
                        si: NsdServiceInfo,
                        errorCode: Int,
                    ) {
                        if (cont.isActive) cont.resume(null)
                    }

                    override fun onServiceResolved(si: NsdServiceInfo) {
                        if (cont.isActive) cont.resume(si)
                    }
                }
            try {
                nsd.resolveService(info, listener)
            } catch (e: IllegalArgumentException) {
                // Some OEMs race past the caller's serialisation and reject as in-flight.
                Log.w(TAG, "resolve of ${info.serviceName} rejected: ${e.message}")
                if (cont.isActive) cont.resume(null)
            }
        }

    // Marker: NsdServiceInfo.host is the only address a resolved record carries before 34,
    // where hostAddresses (used above) replaces it; same deprecation and same right fix as
    // resolveViaListener.
    @Suppress("DEPRECATION")
    private fun legacyHostAddress(info: NsdServiceInfo): String? = info.host?.hostAddress

    // The callback runs on the binder thread that delivers it, like the resolveNsdService listener did.
    private val INLINE_EXECUTOR = Executor { it.run() }
}
