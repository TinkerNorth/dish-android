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
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.core.app.NotificationCompat
import com.tinkernorth.dish.DishApplication
import com.tinkernorth.dish.R
import com.tinkernorth.dish.source.audio.MicIndicatorPolicy
import com.tinkernorth.dish.source.audio.MicIndicatorState
import com.tinkernorth.dish.source.bluetooth.BluetoothGamepadRegistry
import com.tinkernorth.dish.source.connection.SatelliteConnectionManager
import com.tinkernorth.dish.source.usb.UsbGamepadManager
import com.tinkernorth.dish.source.usb.directClaimCount
import com.tinkernorth.dish.ui.main.MainActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import javax.inject.Inject

@AndroidEntryPoint
class StreamingService : Service() {
    @Inject lateinit var wakeState: WakeStateController

    @Inject lateinit var hub: ConnectionCoordinator

    @Inject lateinit var satellite: SatelliteConnectionManager

    @Inject lateinit var btRegistry: BluetoothGamepadRegistry

    @Inject lateinit var usbGamepadManager: UsbGamepadManager

    @Inject lateinit var micCapture: MicCaptureComposer

    @Inject lateinit var micIndicator: MicIndicatorCoordinator

    private var observerJob: Job? = null

    // The microphone type bit we last actually asserted. The service type only changes through
    // another startForeground call, so this is what tells us when one is due.
    private var micTypeHeld = false

    private data class ServiceSnapshot(
        val streamingSlots: Int,
        val connections: List<ConnectionSummary>,
        val directClaims: Int,
        val micArmed: Boolean,
        val micState: MicIndicatorState,
    )

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        // Refused foreground start: the service is already stopping, so don't wire observers that would
        // notify for a service that never entered the foreground.
        if (!startForegroundInitial()) return
        // Held Direct claims keep the service up on their own: WakeState zeroes the slot count when
        // the app leaves the foreground, but a claimed pad still needs this process alive for its
        // eventual device-side restore. Collected in the process scope so a background unplug or
        // release still reaches the stopSelf below.
        observerJob =
            combine(
                wakeState.streamingSlotCount,
                hub.connections,
                usbGamepadManager.controllers,
                micCapture.state,
            ) { count, conns, controllers, plan ->
                ServiceSnapshot(count, conns, controllers.directClaimCount(), plan.arming, MicIndicatorPolicy.of(plan))
            }.onEach(::refresh)
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
        when (streamingCommandFor(intent?.action)) {
            StreamingCommand.STOP_ALL -> {
                stopAllSessions()
                // Stop means all of it: release held Direct claims too, so each pad gets its
                // device-side restore instead of staying captured by a process about to idle out.
                usbGamepadManager.releaseAllDirect()
                stopSelf()
            }
            // The notification's mute action, so the shade works outside the app: the same
            // all-armed-slots toggle the in-app chip lands. The plan change flows back through
            // the observer, which repaints this notification (and every other mic surface).
            StreamingCommand.TOGGLE_MIC -> micIndicator.toggleAll()
            StreamingCommand.REASSERT ->
                if (observerJob != null) {
                    // A repeat startForegroundService (the controller re-asserting after a foreground
                    // return) obliges another startForeground call; against a live service it just
                    // refreshes the notification.
                    startForegroundInitial()
                }
        }
        // START_NOT_STICKY: tightly coupled to process state; an OS-respawned bare service helps nobody.
        return START_NOT_STICKY
    }

    private fun stopAllSessions() {
        hub.connections.value
            .filter { it.kind == ConnectionKind.SATELLITE && it.live == LinkState.Connected }
            .forEach { satellite.disconnect(it.id) }
        hub.connections.value
            .filter { it.kind == ConnectionKind.BLUETOOTH && it.live == LinkState.Connected }
            .forEach { btRegistry.stop(it.id) }
    }

    private fun startForegroundInitial(): Boolean {
        val plan = micCapture.state.value
        val notification =
            build(
                count = wakeState.streamingSlotCount.value,
                primaryLabel = null,
                micState = MicIndicatorPolicy.of(plan),
            )
        return startInForeground(notification, plan.arming)
    }

    private fun refresh(snapshot: ServiceSnapshot) {
        if (snapshot.streamingSlots <= 0 && snapshot.directClaims <= 0) {
            // Belt-and-braces against out-of-order emissions so notification never reads "0 streaming".
            stopSelf()
            return
        }
        val primary =
            snapshot.connections
                .firstOrNull { it.live == LinkState.Connected }
                ?.label
        val notification = build(count = snapshot.streamingSlots, primaryLabel = primary, micState = snapshot.micState)
        if (snapshot.micArmed != micTypeHeld) {
            // The microphone type is only ever held while a mic-enabled binding is streaming, so
            // arming and disarming both mean re-declaring the service. Nothing else here does.
            startInForeground(notification, snapshot.micArmed)
            return
        }
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, notification)
    }

    // With no active stream the service is alive only for held Direct claims, so a zero count
    // reads as the claim-hold body rather than "0 streaming". While a microphone is armed the
    // notification also says what it is doing and carries the mute/unmute action, because the
    // shade is the one mic surface that works outside the app.
    private fun build(
        count: Int,
        primaryLabel: String?,
        micState: MicIndicatorState,
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
        val hostLabel = primaryLabel ?: getString(R.string.satellite_fallback_name)
        val body =
            if (count > 0) {
                resources.getQuantityString(R.plurals.streaming_notification_body, count, count, hostLabel)
            } else {
                getString(R.string.streaming_notification_body_usb_hold)
            }
        val builder =
            NotificationCompat
                .Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_dish_connected)
                .setContentTitle(getString(R.string.streaming_notification_title))
                .setContentText(body)
                .setContentIntent(openIntent)
        val micUi = micNotificationUiFor(micState)
        if (micUi != null) {
            // Mute action first, client control before session control: reaching for it is the
            // whole reason the mic rides this notification at all.
            val micIntent =
                PendingIntent.getService(
                    this,
                    2,
                    Intent(this, StreamingService::class.java).apply { action = ACTION_TOGGLE_MIC },
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
            builder
                .setSubText(getString(micUi.stateRes))
                .addAction(
                    NotificationCompat.Action
                        .Builder(micUi.actionIconRes, getString(micUi.actionRes), micIntent)
                        .build(),
                )
        }
        return builder
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
     * Declare (or re-declare) the foreground state with the types this service is entitled to hold
     * right now.
     *
     * The microphone type is while-in-use: Android 12+ only lets it start while the app is in the
     * foreground, and Android 14+ enforces that with an exception. Every path that arms a
     * microphone here runs from the UI (the binding toggle, its permission prompt, the overlay's
     * mute button), which is what satisfies the rule. A refusal is still reachable when a held
     * Direct claim keeps the service alive in the background and a satellite reconnects there, so
     * a refused microphone type falls back to the type this service can always hold rather than
     * taking the session down with it.
     */
    private fun startInForeground(
        notification: Notification,
        micArmed: Boolean,
    ): Boolean =
        try {
            if (!declareForeground(notification, micArmed) && micArmed) {
                declareForeground(notification, micArmed = false)
            }
            true
        } catch (e: IllegalStateException) {
            // A background-initiated FGS start can be refused on Android 12+; stop instead of crashing.
            Log.w(TAG, "foreground start refused: ${e.message}")
            stopSelf()
            false
        }

    /** Returns false when the requested type set was denied; throws only for a refused start. */
    private fun declareForeground(
        notification: Notification,
        micArmed: Boolean,
    ): Boolean =
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, foregroundServiceTypes(micArmed))
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            micTypeHeld = micArmed
            true
        } catch (e: SecurityException) {
            Log.w(TAG, "foreground type (mic=$micArmed) denied: ${e.message}")
            false
        }

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

    private fun wakeStateScope(): CoroutineScope {
        val app = applicationContext as DishApplication
        return app.processScope
    }

    companion object {
        const val ACTION_STOP_ALL = "com.tinkernorth.dish.action.STOP_ALL"
        const val ACTION_TOGGLE_MIC = "com.tinkernorth.dish.action.TOGGLE_MIC"
        private const val CHANNEL_ID = "dish.streaming"
        private const val NOTIFICATION_ID = 0x1D15
        private const val TAG = "StreamingService"
    }
}

/** What one start command asks of the service; every unknown action is the re-assert no-op. */
internal enum class StreamingCommand {
    STOP_ALL,
    TOGGLE_MIC,
    REASSERT,
}

internal fun streamingCommandFor(action: String?): StreamingCommand =
    when (action) {
        StreamingService.ACTION_STOP_ALL -> StreamingCommand.STOP_ALL
        StreamingService.ACTION_TOGGLE_MIC -> StreamingCommand.TOGGLE_MIC
        else -> StreamingCommand.REASSERT
    }

/**
 * The notification's mic line and action for one indicator state, or null for none at all: a
 * session with no armed microphone must not mention one, since a mute control with nothing to
 * mute is exactly the unaccountable-microphone impression the service types work to avoid. The
 * action names what the tap DOES, the subtext what the mic IS, and the icon shows the state the
 * tap leads to.
 */
internal data class MicNotificationUi(
    @param:StringRes val stateRes: Int,
    @param:StringRes val actionRes: Int,
    @param:DrawableRes val actionIconRes: Int,
)

internal fun micNotificationUiFor(state: MicIndicatorState): MicNotificationUi? =
    when (state) {
        MicIndicatorState.HIDDEN -> null
        MicIndicatorState.LIVE ->
            MicNotificationUi(
                stateRes = R.string.mic_state_live,
                actionRes = R.string.mic_action_mute,
                actionIconRes = R.drawable.ic_mic_off,
            )
        MicIndicatorState.MUTED ->
            MicNotificationUi(
                stateRes = R.string.mic_state_muted,
                actionRes = R.string.mic_action_unmute,
                actionIconRes = R.drawable.ic_mic,
            )
    }

/**
 * The service types this session is entitled to hold. CONNECTED_DEVICE is what the session IS and
 * is always present; MICROPHONE appears exactly while a mic-enabled binding is streaming, because
 * a foreground service claiming a microphone type it is not using is a microphone the user cannot
 * account for.
 *
 * Deliberately keyed on ARMED and not on delivering: a mute is a moment-to-moment control, and
 * dropping the type on every toggle would risk not getting it back, since a while-in-use type can
 * only start while the app is in the foreground. Muted still means zero packets, enforced where it
 * belongs, in the capture engine.
 */
internal fun foregroundServiceTypes(micArmed: Boolean): Int =
    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
        if (micArmed) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
