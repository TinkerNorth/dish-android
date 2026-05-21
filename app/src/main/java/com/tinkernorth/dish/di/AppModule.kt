// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.di

import android.content.Context
import android.os.Build
import android.util.Log
import com.tinkernorth.dish.source.bluetooth.AndroidHidProxyClient
import com.tinkernorth.dish.source.bluetooth.BluetoothHidSession
import com.tinkernorth.dish.source.bluetooth.HidProxyClient
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json
import javax.inject.Qualifier
import javax.inject.Singleton

/** Marker for the long-lived UDP/HTTP/BT/JNI IO dispatcher (Dispatchers.IO in prod). */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class IoDispatcher

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    /**
     * Process scope used by every @Singleton flow collector. Two
     * non-defaults make this load-bearing:
     *
     *  - [CoroutineExceptionHandler] — without it, an uncaught exception
     *    inside any composer's combine block (e.g. an unchecked-cast
     *    regression in an upstream payload shape) silently kills that
     *    composer's collection for the rest of the process. A crash that
     *    doesn't crash. With it, the failure lands in logcat / crash
     *    reporting where it can be diagnosed.
     *  - [SupervisorJob] — keeps a single failed child from cancelling
     *    siblings; one broken composer must not take down the rest.
     *
     * The default dispatcher stays [Dispatchers.Default] because the
     * majority of work on this scope is composer combine blocks (CPU). IO
     * sites opt into the injected `@IoDispatcher` so tests can swap them.
     */
    @Provides
    @Singleton
    fun provideApplicationScope(): CoroutineScope {
        val handler =
            CoroutineExceptionHandler { ctx, throwable ->
                Log.e(
                    "DishAppScope",
                    "Uncaught exception in application scope (job=${ctx[kotlinx.coroutines.Job]})",
                    throwable,
                )
            }
        return CoroutineScope(SupervisorJob() + Dispatchers.Default + handler)
    }

    /**
     * Injectable IO dispatcher. Production binds to [Dispatchers.IO]; tests
     * override with `UnconfinedTestDispatcher` so the IO branches of
     * `SatelliteConnectionManager.openSession` and friends become reachable
     * from `runTest`-driven unit tests.
     */
    @Provides
    @Singleton
    @IoDispatcher
    fun provideIoDispatcher(): CoroutineDispatcher = Dispatchers.IO

    @Provides
    @Singleton
    fun provideJson(): Json =
        Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
            encodeDefaults = true
        }

    /**
     * A fresh [HidProxyClient] per session start — each profile-proxy binding
     * is single-use from Android's point of view, so the session allocates a
     * new instance every time it enters [com.tinkernorth.dish.source.bluetooth.BluetoothSessionState.Acquiring].
     * On API < 28 the factory returns a no-op stub; the FSM will surface a
     * Failed state when it tries to acquire.
     */
    @Provides
    @Singleton
    fun provideHidProxyFactory(
        @ApplicationContext context: Context,
    ): @JvmSuppressWildcards () -> HidProxyClient =
        {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                AndroidHidProxyClient(context)
            } else {
                UnavailableHidProxyClient
            }
        }

    @Provides
    @Singleton
    fun provideBluetoothHidSession(factory: @JvmSuppressWildcards () -> HidProxyClient): BluetoothHidSession = BluetoothHidSession(factory)
}

private object UnavailableHidProxyClient : HidProxyClient {
    override fun isAdapterEnabled(): Boolean = false

    override fun acquire(events: HidProxyClient.Events) {
        events.onError("Bluetooth HID Device requires Android 9+")
    }

    override fun registerApp(profile: com.tinkernorth.dish.core.input.BluetoothGamepad.GamepadProfile) = Unit

    override fun connectToHost(mac: String) = Unit

    override fun disconnectCurrentHost() = Unit

    override fun findOsConnectedHost(mac: String): String? = null

    override fun sendReport(report: ByteArray): Boolean = false

    override fun unregisterAndRelease() = Unit
}
