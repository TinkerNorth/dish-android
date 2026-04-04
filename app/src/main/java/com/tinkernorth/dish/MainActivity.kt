package com.tinkernorth.dish

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.input.InputManager
import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.androidgamesdk.GameActivity
import com.tinkernorth.dish.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

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

class MainActivity :
    GameActivity(),
    InputManager.InputDeviceListener {
    private lateinit var binding: ActivityMainBinding
    private lateinit var inputManager: InputManager

    // ── Multi-controller tracking ─────────────────────────────────────────────
    private val controllers = mutableMapOf<Int, ControllerEntry>()
    private var nextControllerIndex = 0

    // ── Server connection (shared across all controllers) ─────────────────────
    private var connectedServer: DiscoveredServer? = null
    private var connectionId: String? = null
    private var serverConnected = false
    private var ackReceiverJob: Job? = null
    private var aliveMonitorJob: Job? = null

    // ── Per-controller input state (main thread only) ─────────────────────────
    // For simplicity, input from the most recently active gamepad is sent.
    // Future: per-device input multiplexing when multiple gamepads are active.
    private var wButtons = 0
    private var bLT = 0
    private var bRT = 0
    private var sLX = 0
    private var sLY = 0
    private var sRX = 0
    private var sRY = 0
    private var ltFromKey = false
    private var rtFromKey = false

    // ── Wake / screen locks (app-level) ───────────────────────────────────────
    private var wakeLock: PowerManager.WakeLock? = null
    private var screenLockActive = false
    private var wakeLockActive = false

    // ── Telemetry ─────────────────────────────────────────────────────────────
    private var telEventCount = 0
    private var telSampleCount = 0
    private var telSendCount = 0
    private var telTotalSent = 0L
    private var telLastEventMs = 0L

    private val telHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val telRunnable =
        object : Runnable {
            override fun run() {
                updateTelemetry()
                telHandler.postDelayed(this, 1000)
            }
        }

    private val deviceId by lazy { getOrCreateDeviceId() }
    private val deviceName by lazy { Build.MODEL }

    companion object {
        private const val MAX_CONTROLLERS = 16
        private const val COUNTDOWN_SECONDS = 10
        private val BUTTON_MAP =
            mapOf(
                KeyEvent.KEYCODE_BUTTON_A to 0x1000,
                KeyEvent.KEYCODE_BUTTON_B to 0x2000,
                KeyEvent.KEYCODE_BUTTON_X to 0x4000,
                KeyEvent.KEYCODE_BUTTON_Y to 0x8000,
                KeyEvent.KEYCODE_BUTTON_L1 to 0x0100,
                KeyEvent.KEYCODE_BUTTON_R1 to 0x0200,
                KeyEvent.KEYCODE_BUTTON_THUMBL to 0x0040,
                KeyEvent.KEYCODE_BUTTON_THUMBR to 0x0080,
                KeyEvent.KEYCODE_BUTTON_START to 0x0010,
                KeyEvent.KEYCODE_BUTTON_SELECT to 0x0020,
                KeyEvent.KEYCODE_DPAD_UP to 0x0001,
                KeyEvent.KEYCODE_DPAD_DOWN to 0x0002,
                KeyEvent.KEYCODE_DPAD_LEFT to 0x0004,
                KeyEvent.KEYCODE_DPAD_RIGHT to 0x0008,
            )
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)

        binding = ActivityMainBinding.inflate(layoutInflater)
        val root = findViewById<ViewGroup>(android.R.id.content)
        root.addView(
            binding.root,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        inputManager = getSystemService(Context.INPUT_SERVICE) as InputManager
        inputManager.registerInputDeviceListener(this, null)
        initServerDot()
        binding.btnDisconnectAll.setOnClickListener { disconnectAll() }
        scanExistingGamepads()
        refreshDashboard()
        telHandler.post(telRunnable)
    }

    override fun onDestroy() {
        super.onDestroy()
        telHandler.removeCallbacks(telRunnable)
        inputManager.unregisterInputDeviceListener(this)
        controllers.values.forEach { it.countdownTimer?.cancel() }
        ackReceiverJob?.cancel()
        aliveMonitorJob?.cancel()
        SatelliteNative.closeSocket()
        releaseLocks()
    }

    // ── Wake / screen lock management (app-level) ─────────────────────────────

    private fun updateLocks() {
        val hasActiveControllers =
            controllers.values.any {
                it.cardState == ControllerCardState.ACTIVE
            }
        val shouldLock = serverConnected && hasActiveControllers
        if (shouldLock) acquireLocks() else releaseLocks()
    }

    private fun acquireLocks() {
        if (!screenLockActive) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            screenLockActive = true
        }
        if (wakeLock == null) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock =
                pm
                    .newWakeLock(
                        PowerManager.PARTIAL_WAKE_LOCK,
                        "Dish::ControllerStream",
                    ).apply { acquire() }
            wakeLockActive = true
        }
        updateLockIndicators()
    }

    private fun releaseLocks() {
        if (screenLockActive) {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            screenLockActive = false
        }
        wakeLock?.let {
            if (it.isHeld) it.release()
            wakeLock = null
            wakeLockActive = false
        }
        updateLockIndicators()
    }

    private fun updateLockIndicators() {
        if (!::binding.isInitialized) return
        binding.tvScreenLock.text = if (screenLockActive) "ON" else "OFF"
        binding.tvScreenLock.setTextColor(
            getColor(if (screenLockActive) R.color.colorSuccess else R.color.colorMuted),
        )
        binding.tvWakeLock.text = if (wakeLockActive) "ON" else "OFF"
        binding.tvWakeLock.setTextColor(
            getColor(if (wakeLockActive) R.color.colorSuccess else R.color.colorMuted),
        )
    }

    // ── Server dot ────────────────────────────────────────────────────────────

    private fun initServerDot() {
        val d = GradientDrawable()
        d.shape = GradientDrawable.OVAL
        d.setColor(getColor(R.color.colorMuted))
        binding.dotServer.background = d
    }

    private fun updateServerStatus() {
        val color = if (serverConnected) R.color.colorSuccess else R.color.colorMuted
        (binding.dotServer.background as? GradientDrawable)?.setColor(getColor(color))
        binding.tvServerStatus.text =
            if (serverConnected) {
                connectedServer?.name ?: "CONNECTED"
            } else {
                "NOT CONNECTED"
            }
        binding.tvServerStatus.setTextColor(getColor(color))
        binding.btnDisconnectAll.visibility =
            if (serverConnected) View.VISIBLE else View.GONE
    }

    // ── Gamepad scanning & InputManager callbacks ───────────────────────────

    private fun scanExistingGamepads() {
        InputDevice.getDeviceIds().forEach { id ->
            val dev = InputDevice.getDevice(id) ?: return@forEach
            if ((dev.sources and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD) {
                addController(id, dev.name)
            }
        }
    }

    override fun onInputDeviceAdded(deviceId: Int) {
        val dev = InputDevice.getDevice(deviceId) ?: return
        if ((dev.sources and InputDevice.SOURCE_GAMEPAD) != InputDevice.SOURCE_GAMEPAD) return

        // Check if this device is in DISCONNECTING countdown — cancel and restore
        val existing = controllers[deviceId]
        if (existing != null && existing.cardState == ControllerCardState.DISCONNECTING) {
            existing.countdownTimer?.cancel()
            existing.countdownTimer = null
            existing.cardState = ControllerCardState.ACTIVE
            rebuildCardContent(existing)
            return
        }

        addController(deviceId, dev.name)
    }

    override fun onInputDeviceRemoved(deviceId: Int) {
        val entry = controllers[deviceId] ?: return
        android.util.Log.i("Dish", "onInputDeviceRemoved: id=$deviceId name=${entry.name}")

        if (entry.cardState == ControllerCardState.ACTIVE && entry.vigemActive) {
            startDisconnectCountdown(entry)
        } else {
            removeController(deviceId)
        }
    }

    override fun onInputDeviceChanged(deviceId: Int) {
        // Check if the device is still a gamepad
        val dev = InputDevice.getDevice(deviceId)
        val stillGamepad =
            dev != null &&
                (dev.sources and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
        val entry = controllers[deviceId] ?: return

        if (!stillGamepad) {
            // Device changed to non-gamepad — treat as removal
            if (entry.cardState == ControllerCardState.ACTIVE && entry.vigemActive) {
                startDisconnectCountdown(entry)
            } else {
                removeController(deviceId)
            }
        }
    }

    // ── Controller lifecycle ──────────────────────────────────────────────────

    private fun addController(
        androidDeviceId: Int,
        name: String,
    ) {
        if (controllers.containsKey(androidDeviceId)) return
        if (controllers.size >= MAX_CONTROLLERS) return

        val index = nextControllerIndex++
        val entry =
            ControllerEntry(
                androidDeviceId = androidDeviceId,
                name = name,
                controllerIndex = index,
            )

        // If we're already connected to a server, auto-add this controller
        if (serverConnected) {
            entry.cardState = ControllerCardState.ADDING
        }

        controllers[androidDeviceId] = entry
        refreshDashboard()

        // If server is connected, send controller-add right away;
        // otherwise auto-start discovery so servers appear immediately
        if (serverConnected) {
            sendControllerAdd(entry)
        } else if (entry.cardState == ControllerCardState.NEED_SERVER) {
            startDiscoveryForController(entry)
        }
    }

    private fun removeController(androidDeviceId: Int) {
        val entry = controllers.remove(androidDeviceId) ?: return
        entry.countdownTimer?.cancel()
        if (entry.vigemActive) {
            SatelliteNative.controllerRemove(entry.controllerIndex)
            entry.vigemActive = false
        }
        refreshDashboard()
        updateLocks()
    }

    private fun startDisconnectCountdown(entry: ControllerEntry) {
        entry.cardState = ControllerCardState.DISCONNECTING
        entry.countdownSeconds = COUNTDOWN_SECONDS
        rebuildCardContent(entry)

        entry.countdownTimer =
            object : CountDownTimer(
                (COUNTDOWN_SECONDS * 1000).toLong(),
                1000,
            ) {
                override fun onTick(millisUntilFinished: Long) {
                    entry.countdownSeconds = (millisUntilFinished / 1000).toInt() + 1
                    // Update the countdown text in the card
                    rebuildCardContent(entry)
                }

                override fun onFinish() {
                    finalizeControllerDisconnect(entry)
                }
            }.start()
    }

    private fun finalizeControllerDisconnect(entry: ControllerEntry) {
        entry.countdownTimer?.cancel()
        entry.countdownTimer = null
        if (entry.vigemActive) {
            SatelliteNative.controllerRemove(entry.controllerIndex)
            entry.vigemActive = false
        }
        controllers.remove(entry.androidDeviceId)
        refreshDashboard()
        updateLocks()

        // If no controllers remain, disconnect from server entirely
        if (controllers.isEmpty() && serverConnected) {
            disconnectAll()
        }
    }

    /** Send controller-add to server and wait for ACK. */
    private fun sendControllerAdd(entry: ControllerEntry) {
        SatelliteNative.resetControllerAck()
        SatelliteNative.controllerAdd(entry.controllerIndex, 0x0003)
        lifecycleScope.launch {
            val ackResult =
                withContext(Dispatchers.IO) {
                    var ack = -1
                    for (i in 0 until 30) {
                        ack = SatelliteNative.getLastControllerAck()
                        if (ack != -1) break
                        Thread.sleep(100)
                    }
                    ack
                }
            if (ackResult != -1 && (ackResult and 0xFF) == 0x00) {
                entry.vigemActive = true
                entry.cardState = ControllerCardState.ACTIVE
                // Send the current controller type to the server
                SatelliteNative.sendControllerType(
                    entry.controllerIndex,
                    entry.controllerType.wireValue,
                )
            } else {
                entry.cardState = ControllerCardState.ACTIVE // show status even on error
            }
            rebuildCardContent(entry)
            updateLocks()
        }
    }

    // ── Gamepad input ─────────────────────────────────────────────────────────
    // All input processing + sendReport() runs directly on the main thread for
    // zero queue overhead. sendto() is non-blocking (~50μs), far cheaper than
    // a HandlerThread context switch (~1-3ms under load).

    // Intercept gamepad keys BEFORE GameActivity's InputEnabledSurfaceView consumes them.
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.source and InputDevice.SOURCE_GAMEPAD != 0 ||
            event.source and InputDevice.SOURCE_JOYSTICK != 0
        ) {
            return if (event.action == KeyEvent.ACTION_DOWN) {
                handleKeyDown(event.keyCode)
            } else {
                handleKeyUp(event.keyCode)
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun handleKeyDown(keyCode: Int): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_BUTTON_L2 -> {
                ltFromKey = true
                bLT = 255
                trySend()
                return true
            }
            KeyEvent.KEYCODE_BUTTON_R2 -> {
                rtFromKey = true
                bRT = 255
                trySend()
                return true
            }
        }
        BUTTON_MAP[keyCode]?.let { bit ->
            wButtons = wButtons or bit
            trySend()
            return true
        }
        return false
    }

    private fun handleKeyUp(keyCode: Int): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_BUTTON_L2 -> {
                ltFromKey = false
                bLT = 0
                trySend()
                return true
            }
            KeyEvent.KEYCODE_BUTTON_R2 -> {
                rtFromKey = false
                bRT = 0
                trySend()
                return true
            }
        }
        BUTTON_MAP[keyCode]?.let { bit ->
            wButtons = wButtons and bit.inv()
            trySend()
            return true
        }
        return false
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.source and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK) {
            window.decorView.requestUnbufferedDispatch(event)
            when (event.action) {
                MotionEvent.ACTION_CANCEL -> {
                    zeroAxes()
                    trySend()
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    telEventCount++
                    telLastEventMs = SystemClock.elapsedRealtime()
                    for (i in 0 until event.historySize) processJoystickInput(event, i)
                    processJoystickInput(event, -1)
                    return true
                }
            }
        }
        return super.dispatchGenericMotionEvent(event)
    }

    private fun processJoystickInput(
        event: MotionEvent,
        histPos: Int,
    ) {
        fun axis(a: Int) =
            if (histPos < 0) {
                event.getAxisValue(a)
            } else {
                event.getHistoricalAxisValue(a, histPos)
            }

        fun flat(a: Int) = event.device?.getMotionRange(a, event.source)?.flat ?: 0f

        fun dead(
            v: Float,
            a: Int,
        ) = if (kotlin.math.abs(v) > flat(a)) v else 0f
        sLX = (dead(axis(MotionEvent.AXIS_X), MotionEvent.AXIS_X) * 32767f).toInt().coerceIn(-32768, 32767)
        sLY = (dead(axis(MotionEvent.AXIS_Y), MotionEvent.AXIS_Y) * -32767f).toInt().coerceIn(-32768, 32767)
        sRX = (dead(axis(MotionEvent.AXIS_Z), MotionEvent.AXIS_Z) * 32767f).toInt().coerceIn(-32768, 32767)
        sRY = (dead(axis(MotionEvent.AXIS_RZ), MotionEvent.AXIS_RZ) * -32767f).toInt().coerceIn(-32768, 32767)
        // Only update triggers from axes if not currently held via key events.
        // Digital-only controllers (e.g. Switch Pro) send triggers as key events
        // but report 0 on the axis — without this guard, axis reads clobber the key state.
        if (!ltFromKey) {
            bLT =
                (maxOf(axis(MotionEvent.AXIS_LTRIGGER), axis(MotionEvent.AXIS_BRAKE)) * 255f).toInt().coerceIn(0, 255)
        }
        if (!rtFromKey) {
            bRT =
                (maxOf(axis(MotionEvent.AXIS_RTRIGGER), axis(MotionEvent.AXIS_GAS)) * 255f).toInt().coerceIn(0, 255)
        }
        val hx = axis(MotionEvent.AXIS_HAT_X)
        val hy = axis(MotionEvent.AXIS_HAT_Y)
        wButtons = wButtons and 0x000F.inv()
        if (hx < -0.5f) wButtons = wButtons or 0x0004
        if (hx > 0.5f) wButtons = wButtons or 0x0008
        if (hy < -0.5f) wButtons = wButtons or 0x0001
        if (hy > 0.5f) wButtons = wButtons or 0x0002
        telSampleCount++
        trySend()
    }

    private fun zeroAxes() {
        sLX = 0
        sLY = 0
        sRX = 0
        sRY = 0
        bLT = 0
        bRT = 0
        wButtons = 0
        ltFromKey = false
        rtFromKey = false
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) {
            zeroAxes()
            trySend()
        }
    }

    private fun trySend() {
        if (!serverConnected) return
        // Send report on behalf of the first active controller
        // (future: per-device input multiplexing)
        val activeEntry =
            controllers.values.firstOrNull {
                it.cardState == ControllerCardState.ACTIVE && it.vigemActive
            } ?: return
        SatelliteNative.sendReport(
            activeEntry.controllerIndex,
            wButtons,
            bLT,
            bRT,
            sLX,
            sLY,
            sRX,
            sRY,
        )
        telSendCount++
        telTotalSent++
    }

    // ── Telemetry ─────────────────────────────────────────────────────────────

    private fun updateTelemetry() {
        val events = telEventCount
        val samples = telSampleCount
        val sends = telSendCount
        telEventCount = 0
        telSampleCount = 0
        telSendCount = 0

        binding.tvTelEventRate.text = "$events / s"
        binding.tvTelSampleRate.text = "$samples / s"
        binding.tvTelSendRate.text = "$sends / s"
        binding.tvTelTotalSent.text = "$telTotalSent"

        binding.tvTelLX.text = "%+6d".format(sLX)
        binding.tvTelLY.text = "%+6d".format(sLY)
        binding.tvTelRX.text = "%+6d".format(sRX)
        binding.tvTelRY.text = "%+6d".format(sRY)
        binding.tvTelLT.text = "%3d".format(bLT)
        binding.tvTelRT.text = "%3d".format(bRT)
        binding.tvTelBtns.text = "0x%04X".format(wButtons)
    }

    // ── Dashboard UI ──────────────────────────────────────────────────────────

    /** Rebuild the entire dashboard: empty state vs controller cards. */
    private fun refreshDashboard() {
        val container = binding.llControllerCards
        container.removeAllViews()

        if (controllers.isEmpty()) {
            // Show empty state
            binding.llEmptyState.visibility = View.VISIBLE
            container.visibility = View.GONE
        } else {
            binding.llEmptyState.visibility = View.GONE
            container.visibility = View.VISIBLE
            controllers.values.forEach { entry ->
                val card = buildControllerCard(entry)
                entry.cardView = card
                container.addView(card)
            }
        }
        updateServerStatus()
        updateLocks()
    }

    /** Build a MaterialCardView for one controller. */
    @Suppress("LongMethod")
    private fun buildControllerCard(entry: ControllerEntry): MaterialCardView {
        val dp = resources.displayMetrics.density
        val card =
            MaterialCardView(this).apply {
                setCardBackgroundColor(getColor(R.color.colorSurface))
                strokeColor = getColor(R.color.colorOutline)
                strokeWidth = (1 * dp).toInt()
                radius = 8 * dp
                val lp =
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    )
                lp.bottomMargin = (12 * dp).toInt()
                layoutParams = lp
            }

        val content =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                val pad = (16 * dp).toInt()
                setPadding(pad, pad, pad, pad)
            }
        entry.contentContainer = content
        card.addView(content)
        populateCardContent(content, entry)
        return card
    }

    /** Refresh the content of an existing card without rebuilding all cards. */
    private fun rebuildCardContent(entry: ControllerEntry) {
        val content = entry.contentContainer ?: return
        content.removeAllViews()
        populateCardContent(content, entry)
    }

    /** Fill card content based on the controller's current state. */
    @Suppress("LongMethod")
    private fun populateCardContent(
        container: LinearLayout,
        entry: ControllerEntry,
    ) {
        val dp = resources.displayMetrics.density

        // Header: controller name + index
        val header =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
        val dot =
            View(this).apply {
                val size = (10 * dp).toInt()
                layoutParams =
                    LinearLayout.LayoutParams(size, size).apply {
                        marginEnd = (8 * dp).toInt()
                    }
                background =
                    GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(getColor(stateColor(entry.cardState)))
                    }
            }
        val tvName =
            TextView(this).apply {
                text = entry.name
                setTextColor(getColor(R.color.colorOnSurface))
                textSize = 15f
                typeface = Typeface.DEFAULT_BOLD
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
        val tvIndex =
            TextView(this).apply {
                text = "#${entry.controllerIndex}"
                setTextColor(getColor(R.color.colorMuted))
                textSize = 12f
                typeface = Typeface.MONOSPACE
            }
        header.addView(dot)
        header.addView(tvName)
        header.addView(tvIndex)
        container.addView(header)

        // State-specific content
        when (entry.cardState) {
            ControllerCardState.NEED_SERVER -> {
                addLabel(container, "No servers found", R.color.colorMuted)
                addControllerTypeSelector(container, entry, dp)
                val btn =
                    MaterialButton(this).apply {
                        text = "Scan for Servers"
                        setOnClickListener { startDiscoveryForController(entry) }
                        val lp =
                            LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.MATCH_PARENT,
                                LinearLayout.LayoutParams.WRAP_CONTENT,
                            )
                        lp.topMargin = (8 * dp).toInt()
                        layoutParams = lp
                    }
                container.addView(btn)
            }
            ControllerCardState.SCANNING -> {
                addLabel(container, "Scanning for servers…", R.color.colorMuted)
                val progress =
                    ProgressBar(this).apply {
                        isIndeterminate = true
                        val lp =
                            LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.WRAP_CONTENT,
                                LinearLayout.LayoutParams.WRAP_CONTENT,
                            )
                        lp.topMargin = (8 * dp).toInt()
                        lp.gravity = Gravity.CENTER_HORIZONTAL
                        layoutParams = lp
                    }
                container.addView(progress)
            }
            ControllerCardState.SERVER_LIST -> {
                addLabel(container, "Select a server:", R.color.colorMuted)
                // Server list will be populated by the discovery callback
            }
            ControllerCardState.ADDING -> {
                addLabel(container, "Adding to server…", R.color.colorMuted)
                val progress =
                    ProgressBar(this).apply {
                        isIndeterminate = true
                        val lp =
                            LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.WRAP_CONTENT,
                                LinearLayout.LayoutParams.WRAP_CONTENT,
                            )
                        lp.topMargin = (8 * dp).toInt()
                        lp.gravity = Gravity.CENTER_HORIZONTAL
                        layoutParams = lp
                    }
                container.addView(progress)
            }
            ControllerCardState.ACTIVE -> {
                val statusText = if (entry.vigemActive) "Streaming" else "Connected (no ViGEm)"
                val statusColor = if (entry.vigemActive) R.color.colorSuccess else R.color.colorWarning
                addLabel(container, statusText, statusColor)
                addControllerTypeSelector(container, entry, dp)
            }
            ControllerCardState.DISCONNECTING -> {
                addLabel(
                    container,
                    "Controller disconnected — removing in ${entry.countdownSeconds}s",
                    R.color.colorWarning,
                )
                val btn =
                    MaterialButton(this).apply {
                        text = "Disconnect Now"
                        setOnClickListener { finalizeControllerDisconnect(entry) }
                        val lp =
                            LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.MATCH_PARENT,
                                LinearLayout.LayoutParams.WRAP_CONTENT,
                            )
                        lp.topMargin = (8 * dp).toInt()
                        layoutParams = lp
                    }
                container.addView(btn)
            }
        }
    }

    private fun addLabel(
        parent: LinearLayout,
        text: String,
        colorRes: Int,
    ) {
        val dp = resources.displayMetrics.density
        val tv =
            TextView(this).apply {
                this.text = text
                setTextColor(getColor(colorRes))
                textSize = 13f
                val lp =
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    )
                lp.topMargin = (4 * dp).toInt()
                layoutParams = lp
            }
        parent.addView(tv)
    }

    @Suppress("LongMethod")
    private fun addControllerTypeSelector(
        container: LinearLayout,
        entry: ControllerEntry,
        dp: Float,
    ) {
        val row =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                val lp =
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    )
                lp.topMargin = (8 * dp).toInt()
                layoutParams = lp
            }

        val icon =
            ImageView(this).apply {
                setImageResource(entry.controllerType.drawableRes)
                val size = (40 * dp).toInt()
                layoutParams =
                    LinearLayout.LayoutParams(size, size).apply {
                        marginEnd = (10 * dp).toInt()
                    }
                scaleType = ImageView.ScaleType.FIT_CENTER
                contentDescription = entry.controllerType.label
            }

        val spinner =
            Spinner(this).apply {
                adapter =
                    ArrayAdapter(
                        this@MainActivity,
                        android.R.layout.simple_spinner_item,
                        ControllerType.labels,
                    ).also {
                        it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                    }
                setSelection(entry.controllerType.ordinal, false)
                onItemSelectedListener =
                    object : android.widget.AdapterView.OnItemSelectedListener {
                        override fun onItemSelected(
                            parent: android.widget.AdapterView<*>?,
                            view: View?,
                            position: Int,
                            id: Long,
                        ) {
                            val newType = ControllerType.fromIndex(position)
                            if (newType != entry.controllerType) {
                                entry.controllerType = newType
                                icon.setImageResource(newType.drawableRes)
                                icon.contentDescription = newType.label
                                // If active, send type change to server
                                if (entry.vigemActive) {
                                    SatelliteNative.sendControllerType(
                                        entry.controllerIndex,
                                        newType.wireValue,
                                    )
                                }
                            }
                        }

                        override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {
                            // Required by interface — no action needed
                        }
                    }
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }

        row.addView(icon)
        row.addView(spinner)
        container.addView(row)
    }

    private fun stateColor(state: ControllerCardState): Int =
        when (state) {
            ControllerCardState.NEED_SERVER -> R.color.colorMuted
            ControllerCardState.SCANNING -> R.color.colorMuted
            ControllerCardState.SERVER_LIST -> R.color.colorMuted
            ControllerCardState.ADDING -> R.color.colorMuted
            ControllerCardState.ACTIVE -> R.color.colorSuccess
            ControllerCardState.DISCONNECTING -> R.color.colorWarning
        }

    // ── Discovery ─────────────────────────────────────────────────────────────

    private fun startDiscoveryForController(entry: ControllerEntry) {
        // If we're already connected to a server, just add this controller
        if (serverConnected) {
            entry.cardState = ControllerCardState.ADDING
            rebuildCardContent(entry)
            sendControllerAdd(entry)
            return
        }

        entry.cardState = ControllerCardState.SCANNING
        // Set all other NEED_SERVER controllers to SCANNING too (shared server)
        controllers.values
            .filter {
                it.cardState == ControllerCardState.NEED_SERVER
            }.forEach {
                it.cardState = ControllerCardState.SCANNING
            }
        refreshDashboard()

        lifecycleScope.launch {
            val json =
                withContext(Dispatchers.IO) {
                    SatelliteNative.discoverServers(9879, 4000)
                }
            val servers = parseServers(json)
            if (servers.isEmpty()) {
                // Reset to NEED_SERVER
                controllers.values
                    .filter {
                        it.cardState == ControllerCardState.SCANNING
                    }.forEach {
                        it.cardState = ControllerCardState.NEED_SERVER
                    }
                refreshDashboard()
                // Show a toast
                android.widget.Toast
                    .makeText(
                        this@MainActivity,
                        "No servers found — check your network",
                        android.widget.Toast.LENGTH_SHORT,
                    ).show()
            } else {
                // Show server list in the first scanning controller's card
                controllers.values
                    .filter {
                        it.cardState == ControllerCardState.SCANNING
                    }.forEach {
                        it.cardState = ControllerCardState.SERVER_LIST
                    }
                refreshDashboard()
                // Populate server list in the first controller's card
                val firstEntry =
                    controllers.values.firstOrNull {
                        it.cardState == ControllerCardState.SERVER_LIST
                    }
                if (firstEntry != null) {
                    populateServerListInCard(firstEntry, servers)
                }
            }
        }
    }

    private fun populateServerListInCard(
        entry: ControllerEntry,
        servers: List<DiscoveredServer>,
    ) {
        val content = entry.contentContainer ?: return
        val dp = resources.displayMetrics.density
        servers.forEach { server ->
            val item =
                LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    val pad = (12 * dp).toInt()
                    setPadding(pad, pad, pad, pad)
                    background =
                        GradientDrawable().apply {
                            setColor(getColor(R.color.colorBackground))
                            cornerRadius = 4 * dp
                            setStroke((1 * dp).toInt(), getColor(R.color.colorOutline))
                        }
                    val lp =
                        LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                        )
                    lp.topMargin = (8 * dp).toInt()
                    layoutParams = lp
                    setOnClickListener { onServerSelected(server) }
                }
            val tvName =
                TextView(this).apply {
                    text = server.name
                    setTextColor(getColor(R.color.colorOnSurface))
                    textSize = 15f
                    typeface = Typeface.DEFAULT_BOLD
                }
            val tvMeta =
                TextView(this).apply {
                    text = "${server.ip}  •  UDP:${server.udpPort}"
                    setTextColor(getColor(R.color.colorMuted))
                    textSize = 12f
                    typeface = Typeface.MONOSPACE
                }
            item.addView(tvName)
            item.addView(tvMeta)
            content.addView(item)
        }

        // Add rescan button
        val btnRescan =
            MaterialButton(this).apply {
                text = "Rescan"
                val lp =
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    )
                lp.topMargin = (8 * dp).toInt()
                layoutParams = lp
                setOnClickListener {
                    controllers.values
                        .filter {
                            it.cardState == ControllerCardState.SERVER_LIST
                        }.forEach {
                            it.cardState = ControllerCardState.NEED_SERVER
                        }
                    startDiscoveryForController(entry)
                }
            }
        content.addView(btnRescan)
    }

    // ── Server selection & pairing ────────────────────────────────────────────

    private fun onServerSelected(server: DiscoveredServer) {
        // Move all SERVER_LIST controllers to SCANNING
        controllers.values
            .filter {
                it.cardState == ControllerCardState.SERVER_LIST
            }.forEach {
                it.cardState = ControllerCardState.SCANNING
            }
        refreshDashboard()

        lifecycleScope.launch {
            val result =
                withContext(Dispatchers.IO) {
                    SatelliteNative.pair(server.ip, server.pairPort, deviceId, deviceName, "")
                }
            if (result.contains("\"ok\":true")) {
                savePairKey(result)
                connectToServer(server)
            } else {
                showPinDialog(server)
            }
        }
    }

    /**
     * Show a PIN-entry dialog. Using an AlertDialog gives the EditText its own
     * window, which bypasses GameActivity's native InputConnection interception
     * that prevents the soft keyboard from delivering text to in-layout EditTexts.
     */
    private fun showPinDialog(server: DiscoveredServer) {
        val dp = resources.displayMetrics.density
        val input =
            EditText(this).apply {
                inputType = android.text.InputType.TYPE_CLASS_NUMBER
                hint = "PIN"
                textSize = 24f
                gravity = Gravity.CENTER
                typeface = Typeface.MONOSPACE
                letterSpacing = 0.2f
                filters = arrayOf(android.text.InputFilter.LengthFilter(8))
                val pad = (16 * dp).toInt()
                setPadding(pad, pad, pad, pad)
            }

        val dialog =
            MaterialAlertDialogBuilder(this)
                .setTitle("Pair with ${server.name}")
                .setMessage("Enter the PIN shown in the server web UI:")
                .setView(input)
                .setPositiveButton("PAIR", null)
                .setNegativeButton("CANCEL") { d, _ -> d.dismiss() }
                .setCancelable(true)
                .create()

        dialog.setOnShowListener {
            input.requestFocus()
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val pin = input.text.toString().trim()
                if (pin.isEmpty()) {
                    input.error = "Enter the PIN shown on the server"
                    return@setOnClickListener
                }
                input.error = null
                input.isEnabled = false
                dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).isEnabled = false
                lifecycleScope.launch {
                    val result =
                        withContext(Dispatchers.IO) {
                            SatelliteNative.pair(
                                server.ip,
                                server.pairPort,
                                deviceId,
                                deviceName,
                                pin,
                            )
                        }
                    if (result.contains("\"ok\":true")) {
                        savePairKey(result)
                        dialog.dismiss()
                        connectToServer(server)
                    } else {
                        input.isEnabled = true
                        dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).isEnabled = true
                        input.error = jsonGet(result, "error") ?: "Pairing failed"
                    }
                }
            }
        }
        dialog.show()
    }

    // ── Connection ────────────────────────────────────────────────────────────

    @Suppress("LongMethod")
    private fun connectToServer(server: DiscoveredServer) {
        lifecycleScope.launch {
            val sharedKeyHex = getStoredPairKey()
            if (sharedKeyHex.length != 64) {
                android.widget.Toast
                    .makeText(
                        this@MainActivity,
                        "No shared key — re-pair needed",
                        android.widget.Toast.LENGTH_SHORT,
                    ).show()
                controllers.values.forEach {
                    it.cardState = ControllerCardState.NEED_SERVER
                }
                refreshDashboard()
                return@launch
            }
            val key = hexToBytes(sharedKeyHex)

            val response =
                withContext(Dispatchers.IO) {
                    SatelliteNative.httpConnect(server.ip, server.httpPort, deviceId)
                }
            val connId = jsonGet(response, "connectionId")
            val tokenHex = jsonGet(response, "token")

            if (connId == null || tokenHex == null) {
                val error = jsonGet(response, "error") ?: "connection failed"
                android.widget.Toast
                    .makeText(
                        this@MainActivity,
                        "Error: $error",
                        android.widget.Toast.LENGTH_SHORT,
                    ).show()
                controllers.values.forEach {
                    it.cardState = ControllerCardState.NEED_SERVER
                }
                refreshDashboard()
                return@launch
            }

            val token = hexToBytes(tokenHex)
            if (token.size != 4 || key.size != 32) {
                controllers.values.forEach {
                    it.cardState = ControllerCardState.NEED_SERVER
                }
                refreshDashboard()
                return@launch
            }

            if (!SatelliteNative.openSocket(server.ip, server.udpPort)) {
                controllers.values.forEach {
                    it.cardState = ControllerCardState.NEED_SERVER
                }
                refreshDashboard()
                return@launch
            }

            SatelliteNative.setConnectionParams(token, key)
            connectionId = connId
            connectedServer = server
            serverConnected = true

            SatelliteNative.resetControllerAck()
            ackReceiverJob =
                lifecycleScope.launch(Dispatchers.IO) {
                    while (isActive) {
                        SatelliteNative.receiveAck()
                    }
                }

            SatelliteNative.startHeartbeat()
            updateServerStatus()

            // Add all current controllers to the server
            controllers.values.forEach { entry ->
                entry.cardState = ControllerCardState.ADDING
                rebuildCardContent(entry)
                sendControllerAdd(entry)
            }

            // Start alive monitor
            aliveMonitorJob =
                lifecycleScope.launch {
                    while (isActive) {
                        delay(1000)
                        if (!SatelliteNative.isConnectionAlive()) {
                            disconnectAll()
                            break
                        }
                    }
                }
        }
    }

    /** Disconnect all controllers and tear down server connection. */
    private fun disconnectAll() {
        aliveMonitorJob?.cancel()
        aliveMonitorJob = null

        // Remove all virtual controllers
        controllers.values.forEach { entry ->
            entry.countdownTimer?.cancel()
            entry.countdownTimer = null
            if (entry.vigemActive) {
                SatelliteNative.controllerRemove(entry.controllerIndex)
                entry.vigemActive = false
            }
            entry.cardState = ControllerCardState.NEED_SERVER
        }

        ackReceiverJob?.cancel()
        ackReceiverJob = null
        SatelliteNative.closeSocket()
        releaseLocks()

        // DELETE /api/connections/:id
        val server = connectedServer
        val connId = connectionId
        if (server != null && connId != null) {
            lifecycleScope.launch(Dispatchers.IO) {
                SatelliteNative.httpDisconnect(
                    server.ip,
                    server.httpPort,
                    connId,
                    deviceId,
                )
            }
        }
        connectionId = null
        connectedServer = null
        serverConnected = false
        refreshDashboard()
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun getOrCreateDeviceId(): String {
        val p = getSharedPreferences("satellite", Context.MODE_PRIVATE)
        return p.getString("deviceId", null) ?: UUID
            .randomUUID()
            .toString()
            .replace("-", "")
            .also { p.edit().putString("deviceId", it).apply() }
    }

    private fun savePairKey(pairResult: String) {
        val key = jsonGet(pairResult, "sharedKey") ?: return
        getSharedPreferences("satellite", Context.MODE_PRIVATE)
            .edit()
            .putString("sharedKey", key)
            .apply()
    }

    private fun getStoredPairKey(): String =
        getSharedPreferences("satellite", Context.MODE_PRIVATE)
            .getString("sharedKey", "") ?: ""

    private fun jsonGet(
        json: String,
        key: String,
    ): String? {
        val needle = "\"$key\":"
        val idx = json.indexOf(needle)
        if (idx < 0) return null
        var pos = idx + needle.length
        while (pos < json.length && json[pos] == ' ') pos++
        if (pos >= json.length) return null
        return if (json[pos] == '"') {
            val end = json.indexOf('"', pos + 1)
            if (end < 0) null else json.substring(pos + 1, end)
        } else {
            val end = json.substring(pos).indexOfFirst { it == ',' || it == '}' }
            if (end < 0) json.substring(pos) else json.substring(pos, pos + end)
        }
    }

    private fun hexToBytes(hex: String): ByteArray {
        val len = hex.length
        val data = ByteArray(len / 2)
        var i = 0
        while (i < len) {
            data[i / 2] =
                (
                    (Character.digit(hex[i], 16) shl 4) +
                        Character.digit(hex[i + 1], 16)
                ).toByte()
            i += 2
        }
        return data
    }

    private fun parseServers(json: String): List<DiscoveredServer> {
        val stripped =
            json
                .trim()
                .removePrefix("[")
                .removeSuffix("]")
                .trim()
        if (stripped.isEmpty()) return emptyList()
        val result = mutableListOf<DiscoveredServer>()
        var depth = 0
        var start = 0
        for (i in stripped.indices) {
            when (stripped[i]) {
                '{' -> {
                    if (depth == 0) start = i
                    depth++
                }
                '}' -> {
                    depth--
                    if (depth == 0) {
                        val obj = stripped.substring(start, i + 1)
                        val name = jsonGet(obj, "name") ?: continue
                        val ip = jsonGet(obj, "ip") ?: continue
                        val udpPort = jsonGet(obj, "udpPort") ?.toIntOrNull() ?: 9876
                        val pairPort = jsonGet(obj, "pairPort") ?.toIntOrNull() ?: 9878
                        val httpPort = jsonGet(obj, "httpPort") ?.toIntOrNull() ?: 9877
                        result.add(DiscoveredServer(name, ip, udpPort, pairPort, httpPort))
                    }
                }
            }
        }
        return result
    }
}
