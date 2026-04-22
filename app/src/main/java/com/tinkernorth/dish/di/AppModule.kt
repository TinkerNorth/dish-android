package com.tinkernorth.dish.di

import android.content.Context
import android.os.Build
import com.tinkernorth.dish.ui.bluetooth.AndroidHidProxyClient
import com.tinkernorth.dish.ui.bluetooth.BluetoothHidSession
import com.tinkernorth.dish.ui.bluetooth.HidProxyClient
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides
    @Singleton
    fun provideApplicationScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

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
     * new instance every time it enters [com.tinkernorth.dish.ui.bluetooth.SessionState.Acquiring].
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

    override fun registerApp(profile: com.tinkernorth.dish.ui.bluetooth.BluetoothGamepad.GamepadProfile) = Unit

    override fun connectToHost(mac: String) = Unit

    override fun disconnectCurrentHost() = Unit

    override fun findOsConnectedHost(mac: String): String? = null

    override fun sendReport(report: ByteArray): Boolean = false

    override fun unregisterAndRelease() = Unit
}
