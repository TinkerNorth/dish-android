package com.tinkernorth.dish

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.hardware.input.InputManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
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

class MainActivity :
    GameActivity(),
    InputManager.InputDeviceListener {
    private enum class State { SCANNING, SERVER_LIST, PAIRING, CONNECTED }

    private lateinit var binding: ActivityMainBinding
    private lateinit var inputManager: InputManager

    // Controller axes / buttons (main thread only)
    private var wButtons = 0
    private var bLT = 0
    private var bRT = 0
    private var sLX = 0
    private var sLY = 0
    private var sRX = 0
    private var sRY = 0

    // Track whether triggers are currently held via key events (digital).
    // When true, axis reads (which report 0 on digital-only controllers) won't clobber.
    private var ltFromKey = false
    private var rtFromKey = false
    private var controllerConnected = false
    private var vigemActive = false // true while we have an active virtual controller on the server

    // App state
    private var appState = State.SCANNING
    private var selServer: DiscoveredServer? = null

    // Connection state (from POST /api/connections)
    private var connectionId: String? = null
    private var ackReceiverJob: Job? = null
    private var aliveMonitorJob: Job? = null

    // Wake / screen locks — acquired when connected + controller present
    private var wakeLock: PowerManager.WakeLock? = null
    private var screenLockActive = false
    private var wakeLockActive = false

    // Telemetry — per-second window counters (main thread only)
    private var telEventCount = 0
    private var telSampleCount = 0
    private var telHistTotal = 0
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

        // Elevate main thread to near-real-time priority.
        // Input dispatch + sendReport() both run here for zero queue overhead.
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)

        // GameActivity.onCreate already calls setContentView with its own FrameLayout.
        // Add our UI layout on top so the SurfaceView stays in the hierarchy.
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
        initDotDrawable()
        checkGamepadConnected()
        setupButtons()
        startDiscovery()
        telHandler.post(telRunnable)
    }

    override fun onDestroy() {
        super.onDestroy()
        telHandler.removeCallbacks(telRunnable)
        inputManager.unregisterInputDeviceListener(this)
        ackReceiverJob?.cancel()
        aliveMonitorJob?.cancel()
        SatelliteNative.closeSocket()
        releaseLocks()
    }

    // ── Wake / screen lock management ─────────────────────────────────────────
    // Acquired when both conditions are met: connected to a server AND controller present.
    // Keeps the CPU awake (PARTIAL_WAKE_LOCK) and the screen on (FLAG_KEEP_SCREEN_ON)
    // so the connection stays alive even during idle periods with no input.

    private fun updateLocks() {
        val shouldLock = appState == State.CONNECTED && controllerConnected
        if (shouldLock) acquireLocks() else releaseLocks()
    }

    private fun acquireLocks() {
        // Screen on
        if (!screenLockActive) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            screenLockActive = true
        }
        // CPU wake lock
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

    // ── Controller dot shape ──────────────────────────────────────────────────

    private fun initDotDrawable() {
        val d = GradientDrawable()
        d.shape = GradientDrawable.OVAL
        d.setColor(getColor(R.color.colorMuted))
        binding.dotController.background = d
    }

    private fun updateControllerDot() {
        val color =
            if (controllerConnected) {
                getColor(R.color.colorSuccess)
            } else {
                getColor(R.color.colorMuted)
            }
        (binding.dotController.background as? GradientDrawable)?.setColor(color)
        binding.tvControllerStatus.text = if (controllerConnected) "CONNECTED" else "NONE"
        updateLocks()
    }

    // ── InputManager callbacks ────────────────────────────────────────────────

    private fun checkGamepadConnected() {
        controllerConnected =
            InputDevice.getDeviceIds().any { id ->
                val dev = InputDevice.getDevice(id) ?: return@any false
                (dev.sources and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
            }
        updateControllerDot()
    }

    override fun onInputDeviceAdded(deviceId: Int) {
        val dev = InputDevice.getDevice(deviceId) ?: return
        if ((dev.sources and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD) {
            controllerConnected = true
            updateControllerDot()
            // Dynamically add virtual controller if connected to server
            if (appState == State.CONNECTED && !vigemActive) {
                sendControllerAdd()
            }
        }
    }

    override fun onInputDeviceRemoved(deviceId: Int) {
        val wasConnected = controllerConnected
        checkGamepadConnected()
        android.util.Log.i(
            "Dish",
            "onInputDeviceRemoved: id=$deviceId wasConnected=$wasConnected " +
                "nowConnected=$controllerConnected state=$appState vigemActive=$vigemActive",
        )
        // Dynamically remove virtual controller if no gamepads remain
        if (wasConnected && !controllerConnected && appState == State.CONNECTED && vigemActive) {
            sendControllerRemove()
        }
    }

    override fun onInputDeviceChanged(deviceId: Int) {
        val wasConnected = controllerConnected
        checkGamepadConnected()
        android.util.Log.i(
            "Dish",
            "onInputDeviceChanged: id=$deviceId wasConnected=$wasConnected " +
                "nowConnected=$controllerConnected state=$appState vigemActive=$vigemActive",
        )
        // Handle controller disconnect reported as a "change" rather than "remove"
        if (wasConnected && !controllerConnected && appState == State.CONNECTED && vigemActive) {
            sendControllerRemove()
        }
    }

    /** Send controller-add + wait for ACK, update UI accordingly. */
    private fun sendControllerAdd() {
        SatelliteNative.resetControllerAck()
        SatelliteNative.controllerAdd(0, 0x0003)
        binding.tvControllerAckStatus.text = "PENDING"
        binding.tvControllerAckStatus.setTextColor(getColor(R.color.colorMuted))
        lifecycleScope.launch {
            val ackResult =
                withContext(Dispatchers.IO) {
                    var ack = -1
                    for (i in 0 until 30) { // 30 × 100ms = 3s
                        ack = SatelliteNative.getLastControllerAck()
                        if (ack != -1) break
                        Thread.sleep(100)
                    }
                    ack
                }
            if (ackResult != -1 && (ackResult and 0xFF) == 0x00) {
                vigemActive = true
            }
            android.util.Log.i(
                "Dish",
                "sendControllerAdd ACK: packed=$ackResult " +
                    "result=${if (ackResult != -1) ackResult and 0xFF else -1} " +
                    "vigemActive=$vigemActive",
            )
            updateControllerAckUI(ackResult)
        }
    }

    /** Send controller-remove, update UI. */
    private fun sendControllerRemove() {
        SatelliteNative.controllerRemove(0)
        vigemActive = false
        binding.tvControllerAckStatus.text = "—"
        binding.tvControllerAckStatus.setTextColor(getColor(R.color.colorMuted))
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
                    telHistTotal += event.historySize
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
        if (appState == State.CONNECTED) {
            SatelliteNative.sendReport(0, wButtons, bLT, bRT, sLX, sLY, sRX, sRY)
            telSendCount++
            telTotalSent++
        }
    }

    // ── Telemetry ─────────────────────────────────────────────────────────────

    private fun updateTelemetry() {
        val events = telEventCount
        val samples = telSampleCount
        val sends = telSendCount
        val hist = telHistTotal
        telEventCount = 0
        telSampleCount = 0
        telSendCount = 0
        telHistTotal = 0

        val histAvg = if (events > 0) hist.toFloat() / events else 0f
        val ageSec =
            if (telLastEventMs == 0L) {
                "—"
            } else {
                "${SystemClock.elapsedRealtime() - telLastEventMs} ms ago"
            }

        binding.tvTelEventRate.text = "$events / s"
        binding.tvTelSampleRate.text = "$samples / s"
        binding.tvTelHistAvg.text = "%.1f".format(histAvg)
        binding.tvTelSendRate.text = "$sends / s"
        binding.tvTelTotalSent.text = "$telTotalSent"
        binding.tvTelLastEvent.text = ageSec

        binding.tvTelLX.text = "%+6d".format(sLX)
        binding.tvTelLY.text = "%+6d".format(sLY)
        binding.tvTelRX.text = "%+6d".format(sRX)
        binding.tvTelRY.text = "%+6d".format(sRY)
        binding.tvTelLT.text = "%3d".format(bLT)
        binding.tvTelRT.text = "%3d".format(bRT)
        binding.tvTelBtns.text = "0x%04X".format(wButtons)
    }

    // ── UI button wiring ──────────────────────────────────────────────────────

    private fun setupButtons() {
        binding.btnRescan.setOnClickListener { startDiscovery() }
        binding.btnCancel.setOnClickListener {
            selServer = null
            startDiscovery()
        }
        binding.btnPair.setOnClickListener { attemptPairing() }
        binding.btnDisconnect.setOnClickListener { disconnect() }
    }

    // ── State machine ─────────────────────────────────────────────────────────

    private fun transitionTo(state: State) {
        appState = state
        binding.llScanning.visibility = if (state == State.SCANNING) View.VISIBLE else View.GONE
        binding.llServerList.visibility = if (state == State.SERVER_LIST) View.VISIBLE else View.GONE
        binding.llPairing.visibility = if (state == State.PAIRING) View.VISIBLE else View.GONE
        binding.llConnected.visibility = if (state == State.CONNECTED) View.VISIBLE else View.GONE
    }

    // ── Discovery ─────────────────────────────────────────────────────────────

    private fun startDiscovery() {
        transitionTo(State.SCANNING)
        binding.tvScanningLabel.text = "Scanning for servers on LAN…"
        lifecycleScope.launch {
            val json = withContext(Dispatchers.IO) { SatelliteNative.discoverServers(9879, 4000) }
            val servers = parseServers(json)
            transitionTo(State.SERVER_LIST)
            if (servers.isEmpty()) {
                binding.tvNoServers.visibility = View.VISIBLE
                binding.llServers.visibility = View.GONE
            } else {
                binding.tvNoServers.visibility = View.GONE
                binding.llServers.visibility = View.VISIBLE
                populateServerList(servers)
            }
        }
    }

    private fun populateServerList(servers: List<DiscoveredServer>) {
        binding.llServers.removeAllViews()
        val dp = resources.displayMetrics.density
        servers.forEach { server ->
            val item =
                LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    val pad = (12 * dp).toInt()
                    setPadding(pad, pad, pad, pad)
                    val bg =
                        GradientDrawable().apply {
                            setColor(getColor(R.color.colorBackground))
                            cornerRadius = 4 * dp
                            setStroke((1 * dp).toInt(), getColor(R.color.colorOutline))
                        }
                    background = bg
                    val lp =
                        LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                        )
                    lp.bottomMargin = (8 * dp).toInt()
                    layoutParams = lp
                    setOnClickListener { onServerSelected(server) }
                }
            val tvName =
                TextView(this).apply {
                    text = server.name
                    setTextColor(getColor(R.color.colorOnSurface))
                    textSize = 15f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                }
            val tvMeta =
                TextView(this).apply {
                    text = "${server.ip}  •  UDP:${server.udpPort}"
                    setTextColor(getColor(R.color.colorMuted))
                    textSize = 12f
                    typeface = android.graphics.Typeface.MONOSPACE
                }
            item.addView(tvName)
            item.addView(tvMeta)
            binding.llServers.addView(item)
        }
    }

    // ── Server selection & pairing ────────────────────────────────────────────

    private fun onServerSelected(server: DiscoveredServer) {
        selServer = server
        transitionTo(State.SCANNING)
        binding.tvScanningLabel.text = "Connecting to ${server.name}…"
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
        transitionTo(State.SERVER_LIST)

        val dp = resources.displayMetrics.density
        val input =
            EditText(this).apply {
                inputType = android.text.InputType.TYPE_CLASS_NUMBER
                hint = "PIN"
                textSize = 24f
                gravity = android.view.Gravity.CENTER
                typeface = android.graphics.Typeface.MONOSPACE
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
                .setPositiveButton("PAIR", null) // set listener below to prevent auto-dismiss
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
                            SatelliteNative.pair(server.ip, server.pairPort, deviceId, deviceName, pin)
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

    private fun attemptPairing() {
        // Kept for btnPair click wired in setupButtons; now a no-op since
        // pairing is handled entirely through showPinDialog.
    }

    private fun connectToServer(server: DiscoveredServer) {
        transitionTo(State.SCANNING)
        binding.tvScanningLabel.text = "Opening connection…"
        lifecycleScope.launch {
            // 0. Get stored shared key from pairing (hex-encoded, 64 chars → 32 bytes)
            val sharedKeyHex = getStoredPairKey()
            if (sharedKeyHex.length != 64) {
                binding.tvScanningLabel.text = "Error: no shared key — re-pair needed"
                transitionTo(State.SERVER_LIST)
                return@launch
            }
            val key = hexToBytes(sharedKeyHex)

            // 1. POST /api/connections to get connectionId and token
            val response =
                withContext(Dispatchers.IO) {
                    SatelliteNative.httpConnect(server.ip, server.httpPort, deviceId)
                }
            val connId = jsonGet(response, "connectionId")
            val tokenHex = jsonGet(response, "token")

            if (connId == null || tokenHex == null) {
                val error = jsonGet(response, "error") ?: "connection failed"
                binding.tvScanningLabel.text = "Error: $error"
                transitionTo(State.SERVER_LIST)
                return@launch
            }

            // 2. Parse token (hex, 8 chars → 4 bytes)
            val token = hexToBytes(tokenHex)
            if (token.size != 4 || key.size != 32) {
                binding.tvScanningLabel.text = "Error: invalid connection params"
                transitionTo(State.SERVER_LIST)
                return@launch
            }

            // 3. Open UDP socket
            if (!SatelliteNative.openSocket(server.ip, server.udpPort)) {
                transitionTo(State.SERVER_LIST)
                return@launch
            }

            // 4. Set connection params (token + encryption key)
            SatelliteNative.setConnectionParams(token, key)
            connectionId = connId

            // 5. Start ACK receiver BEFORE sending controller add (so we catch the ACK)
            SatelliteNative.resetControllerAck()
            ackReceiverJob =
                lifecycleScope.launch(Dispatchers.IO) {
                    while (isActive) {
                        SatelliteNative.receiveAck()
                    }
                }

            // 6. Start heartbeat
            SatelliteNative.startHeartbeat()

            // 7. Transition to CONNECTED and show initial status
            transitionTo(State.CONNECTED)
            binding.tvConnectedServer.text = server.name
            binding.tvConnectedIp.text = "${server.ip}  •  UDP:${server.udpPort}"
            binding.tvVigemStatus.text = "—"
            binding.tvVigemStatus.setTextColor(getColor(R.color.colorMuted))
            binding.tvControllerAckStatus.text = "—"
            binding.tvControllerAckStatus.setTextColor(getColor(R.color.colorMuted))
            updateControllerDot()

            // 8. If a gamepad is already connected, add virtual controller now
            if (controllerConnected) {
                SatelliteNative.controllerAdd(0, 0x0003)
                binding.tvControllerAckStatus.text = "PENDING"
                binding.tvControllerAckStatus.setTextColor(getColor(R.color.colorMuted))

                val ackResult =
                    withContext(Dispatchers.IO) {
                        var ack = -1
                        for (i in 0 until 30) { // 30 × 100ms = 3s
                            ack = SatelliteNative.getLastControllerAck()
                            if (ack != -1) break
                            Thread.sleep(100)
                        }
                        ack
                    }
                if (ackResult != -1 && (ackResult and 0xFF) == 0x00) {
                    vigemActive = true
                }
                updateControllerAckUI(ackResult)
            }

            // 10. Start alive monitor (also updates live server status)
            aliveMonitorJob =
                lifecycleScope.launch {
                    while (isActive) {
                        delay(1000)
                        if (!SatelliteNative.isConnectionAlive()) {
                            disconnect()
                            break
                        }
                        updateVigemStatusUI()
                    }
                }
        }
    }

    private fun updateControllerAckUI(packed: Int) {
        if (packed == -1) {
            binding.tvControllerAckStatus.text = "NO RESPONSE"
            binding.tvControllerAckStatus.setTextColor(getColor(R.color.colorError))
            return
        }
        val result = packed and 0xFF
        val (text, color) =
            when (result) {
                0x00 -> "ACTIVE" to R.color.colorSuccess
                0x01 -> "VIGEM UNAVAILABLE" to R.color.colorError
                0x02 -> "NO SLOTS" to R.color.colorError
                0x03 -> "ALREADY EXISTS" to R.color.colorMuted
                0x04 -> "NOT FOUND" to R.color.colorError
                0x05 -> "PLUGIN FAILED" to R.color.colorError
                else -> "UNKNOWN (0x${"%02X".format(result)})" to R.color.colorError
            }
        binding.tvControllerAckStatus.text = text
        binding.tvControllerAckStatus.setTextColor(getColor(color))
    }

    private fun updateVigemStatusUI() {
        val vigem = SatelliteNative.getVigemAvailable()
        val count = SatelliteNative.getActiveControllerCount()
        val (text, color) =
            when {
                vigem == -1 -> "—" to R.color.colorMuted
                vigem == 1 -> "OPEN · $count active" to R.color.colorSuccess
                count == 0 -> "IDLE · no devices" to R.color.colorMuted
                else -> "UNAVAILABLE" to R.color.colorError
            }
        binding.tvVigemStatus.text = text
        binding.tvVigemStatus.setTextColor(getColor(color))
    }

    private fun disconnect() {
        // Stop alive monitor first (prevents re-entrant disconnect)
        aliveMonitorJob?.cancel()
        aliveMonitorJob = null

        // Send controller remove only if we have an active virtual controller
        if (vigemActive) {
            SatelliteNative.controllerRemove(0)
            vigemActive = false
        }

        // Stop heartbeat and ACK receiver, close socket, wipe keys
        ackReceiverJob?.cancel()
        ackReceiverJob = null
        SatelliteNative.closeSocket()
        releaseLocks()

        // DELETE /api/connections/:id (fire-and-forget on IO)
        val server = selServer
        val connId = connectionId
        if (server != null && connId != null) {
            lifecycleScope.launch(Dispatchers.IO) {
                SatelliteNative.httpDisconnect(server.ip, server.httpPort, connId, deviceId)
            }
        }
        connectionId = null
        selServer = null
        startDiscovery()
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
