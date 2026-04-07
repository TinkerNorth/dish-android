package com.tinkernorth.dish

import android.os.CountDownTimer
import android.widget.LinearLayout
import com.google.android.material.card.MaterialCardView

data class DiscoveredServer(
    val name: String,
    val ip: String,
    val udpPort: Int,
    val pairPort: Int,
    val httpPort: Int,
)

/** Per-controller state tracked by the dashboard. */
enum class ControllerCardState {
    NEED_SERVER, // gamepad detected, no server connection
    SCANNING, // scanning for servers
    SERVER_LIST, // showing discovered servers
    ADDING, // adding to already-connected server
    ACTIVE, // streaming, vigem active
    DISCONNECTING, // physical gamepad unplugged, countdown running
}

/** Supported controller visual types (must match satellite types.h values). */
enum class ControllerType(
    val wireValue: Int,
    val label: String,
    val drawableRes: Int,
) {
    XBOX(0, "Xbox", R.drawable.ctrl_xbox),
    PLAYSTATION(1, "PlayStation", R.drawable.ctrl_playstation),
    ;

    companion object {
        val labels = entries.map { it.label }

        fun fromIndex(index: Int) = entries.getOrElse(index) { XBOX }
    }
}

/** Mutable state for one physical controller. */
data class ControllerEntry(
    val androidDeviceId: Int,
    val name: String,
    var controllerIndex: Int = -1,
    var cardState: ControllerCardState = ControllerCardState.NEED_SERVER,
    var vigemActive: Boolean = false,
    var controllerType: ControllerType = ControllerType.XBOX,
    var countdownTimer: CountDownTimer? = null,
    var countdownSeconds: Int = 10,
    // UI references (set when card is built)
    var cardView: MaterialCardView? = null,
    var contentContainer: LinearLayout? = null,
)
