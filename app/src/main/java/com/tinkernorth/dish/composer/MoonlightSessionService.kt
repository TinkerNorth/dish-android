// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.composer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.tinkernorth.dish.DishApplication
import com.tinkernorth.dish.R
import com.tinkernorth.dish.source.connection.moonlight.MoonlightConnectionManager
import com.tinkernorth.dish.ui.main.MainActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import javax.inject.Inject

/**
 * Keeps a Moonlight session alive for as long as a binding points at it.
 *
 * The satellite path's [StreamingService] is scoped to the app being in the
 * foreground, because there the phone IS the pad and a dark screen means nobody
 * is playing. A Moonlight session is the opposite: the binding owns a long-lived
 * stream to a PC, the user puts the phone down, and without a foreground service
 * the idle network restrictions cut the socket about forty seconds later and the
 * host times the session out. So this one follows the session, not the app: it
 * holds the partial wake lock the control pump needs to keep ticking with the
 * screen off, and the low-latency Wi-Fi lock the input packets need.
 */
@AndroidEntryPoint
class MoonlightSessionService : Service() {
    @Inject lateinit var moonlight: MoonlightConnectionManager

    @Inject lateinit var hub: ConnectionCoordinator

    private var observerJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        if (!startForegroundInitial()) return
        acquireLocks()
        observerJob =
            moonlight.sessionHostIds
                .onEach(::refresh)
                .launchIn((applicationContext as DishApplication).processScope)
    }

    override fun onDestroy() {
        observerJob?.cancel()
        observerJob = null
        releaseLocks()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        // A repeat startForegroundService against a live service obliges another
        // startForeground call; here it just refreshes the notification.
        if (observerJob != null) startForegroundInitial()
        return START_NOT_STICKY
    }

    private fun startForegroundInitial(): Boolean = startInForeground(build(moonlight.sessionHostIds.value))

    private fun refresh(hostIds: Set<String>) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, build(hostIds))
    }

    private fun build(hostIds: Set<String>): Notification {
        val openIntent =
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java).apply { addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP) },
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        val primary = hostIds.firstOrNull()
        val label = primary?.let { hub.summary(it)?.label }
        val pads = primary?.let { moonlight.get(it)?.padCount } ?: 0
        val body =
            when {
                label == null -> getString(R.string.ml_service_body_idle)
                pads > 0 -> resources.getQuantityString(R.plurals.ml_service_body, pads, pads, label)
                else -> getString(R.string.ml_service_body_starting, label)
            }
        return NotificationCompat
            .Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_dish_connected)
            .setContentTitle(getString(R.string.ml_service_title))
            .setContentText(body)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun startInForeground(notification: Notification): Boolean =
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            true
        } catch (e: IllegalStateException) {
            Log.w(TAG, "foreground start refused: ${e.message}")
            stopSelf()
            false
        }

    // The foreground service exempts the process from the idle network restrictions
    // that were killing the socket; the wake lock is what keeps the control pump and
    // the media pings running once the screen is off.
    private fun acquireLocks() {
        val power = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock =
            power
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
                .apply { acquire(WAKE_LOCK_TIMEOUT_MS) }
        val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wifiLock = wifi.createWifiLock(wifiLockMode(Build.VERSION.SDK_INT), WIFI_LOCK_TAG).apply { acquire() }
    }

    private fun releaseLocks() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        wifiLock?.let { if (it.isHeld) it.release() }
        wifiLock = null
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val channel =
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.ml_service_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.ml_service_channel_description)
                setShowBadge(false)
            }
        nm.createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "dish.moonlight"
        private const val NOTIFICATION_ID = 0x1D16
        private const val TAG = "MoonlightSessionService"
        private const val WAKE_LOCK_TAG = "Dish::MoonlightSession"
        private const val WIFI_LOCK_TAG = "Dish::MoonlightWifi"

        // OS safety-net release; the service lifetime is the actual session keep-alive.
        private const val WAKE_LOCK_TIMEOUT_MS = 6L * 60L * 60L * 1000L
    }
}
