// SPDX-License-Identifier: LGPL-3.0-or-later

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

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class IoDispatcher

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    // SupervisorJob + handler: one composer's combine throwing must not silently
    // kill its collection or cancel siblings.
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

    // Injected so tests can swap in UnconfinedTestDispatcher.
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

    // Fresh client per session: Android's profile-proxy binding is single-use.
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
