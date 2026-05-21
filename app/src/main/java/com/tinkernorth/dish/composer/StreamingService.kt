// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.composer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.tinkernorth.dish.DishApplication
import com.tinkernorth.dish.R
import com.tinkernorth.dish.source.bluetooth.BluetoothGamepadRegistry
import com.tinkernorth.dish.source.connection.SatelliteConnectionManager
import com.tinkernorth.dish.ui.main.MainActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import javax.inject.Inject

/**
 * Foreground service that keeps Dish alive while a controller is streaming.
 *
 * Without a foreground service the kernel + framework reserve the right to Doze the
 * UDP socket, kill the wake-lock, and pause background work once the user navigates
 * away from a Dish activity. With it, the OS honours our "actively serving the
 * user" contract and the streaming session survives the user looking up a strategy
 * guide on a sibling app.
 *
 * **This file holds only the Android [Service] (framework-bound — its lifecycle is
 * the OS's, not ours).** The controller that *starts and stops* the service in
 * response to [WakeStateController.streamingSlotCount] lives in
 * [StreamingServiceController] in its own file because it is a
 * [com.tinkernorth.dish.architecture.abstracts.AbstractStateSource] and
 * belongs alongside the other process-lifecycle pieces.
 *
 * Notification copy is dynamic (current count + bound-server name) and the Stop
 * action calls [SatelliteConnectionManager.disconnect] on the bound satellite,
 * then stops the service.
 */
@AndroidEntryPoint
class StreamingService : Service() {
    @Inject lateinit var wakeState: WakeStateController

    @Inject lateinit var hub: ConnectionHub

    @Inject lateinit var satellite: SatelliteConnectionManager

    @Inject lateinit var btRegistry: BluetoothGamepadRegistry

    private var observerJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        startForegroundInitial()
        // Re-paint the notification whenever the binding set or connection state
        // changes — body text reads "N controllers streaming to <host>" and that
        // should track the truth, not be stamped once.
        observerJob =
            combine(wakeState.streamingSlotCount, hub.connections) { count, conns -> count to conns }
                .onEach { (count, conns) -> refresh(count, conns) }
                .launchIn(wakeStateScope())
    }

    override fun onDestroy() {
        observerJob?.cancel()
        observerJob = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        if (intent?.action == ACTION_STOP_ALL) {
            stopAllSessions()
            stopSelf()
        }
        return START_STICKY
    }

    /**
     * Cancel every live session so the wake-state count drops to zero and
     * [StreamingServiceController] removes the service. Routed through the
     * managers so the underlying sockets / HID app get torn down cleanly.
     */
    private fun stopAllSessions() {
        hub.connections.value
            .filter { it.kind == ConnectionKind.SATELLITE && it.live == LinkState.Connected }
            .forEach { satellite.disconnect(it.id) }
        hub.connections.value
            .filter { it.kind == ConnectionKind.BLUETOOTH && it.live == LinkState.Connected }
            .forEach { btRegistry.stop(it.id) }
    }

    private fun startForegroundInitial() {
        val notification = build(count = wakeState.streamingSlotCount.value, primaryLabel = null)
        startInForeground(notification)
    }

    private fun refresh(
        count: Int,
        conns: List<ConnectionSummary>,
    ) {
        if (count <= 0) return
        val primary =
            conns
                .firstOrNull { it.live == LinkState.Connected }
                ?.label
        val notification = build(count = count, primaryLabel = primary)
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, notification)
    }

    private fun build(
        count: Int,
        primaryLabel: String?,
    ): Notification {
        val openIntent =
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                },
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        val stopIntent =
            PendingIntent.getService(
                this,
                1,
                Intent(this, StreamingService::class.java).apply { action = ACTION_STOP_ALL },
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        val body =
            if (primaryLabel != null) {
                getString(R.string.streaming_notification_body, count, primaryLabel)
            } else {
                getString(R.string.streaming_notification_body_plural, count)
            }
        return NotificationCompat
            .Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_dish_connected)
            .setContentTitle(getString(R.string.streaming_notification_title))
            .setContentText(body)
            .setContentIntent(openIntent)
            .addAction(
                NotificationCompat.Action
                    .Builder(
                        R.drawable.ic_close,
                        getString(R.string.streaming_notification_stop),
                        stopIntent,
                    ).build(),
            ).setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    /**
     * `startForeground` on API 34+ requires the explicit `foregroundServiceType` we
     * declared in the manifest. Older API levels take the no-type overload.
     */
    private fun startInForeground(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    /**
     * Create the streaming notification channel on API 26+. Below that the channel
     * concept doesn't exist and [NotificationCompat] handles the downgrade
     * automatically, so this is a true no-op for API 24–25.
     */
    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val channel =
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.streaming_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.streaming_channel_description)
                setShowBadge(false)
            }
        nm.createNotificationChannel(channel)
    }

    /**
     * Use the `@ApplicationContext`-owned process scope so the notification
     * collector outlives onCreate's local scope. Pulled from the application via
     * [DishApplication] so we don't have to inject it on the service.
     */
    private fun wakeStateScope(): CoroutineScope {
        val app = applicationContext as DishApplication
        return app.processScope
    }

    companion object {
        const val ACTION_STOP_ALL = "com.tinkernorth.dish.action.STOP_ALL"
        private const val CHANNEL_ID = "dish.streaming"
        private const val NOTIFICATION_ID = 0x1D15
    }
}
