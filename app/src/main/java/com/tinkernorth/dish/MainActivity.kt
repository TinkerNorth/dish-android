package com.tinkernorth.dish

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.hardware.input.InputManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import com.google.androidgamesdk.GameActivity
import com.tinkernorth.dish.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

data class DiscoveredServer(
    val name: String, val ip: String,
    val udpPort: Int, val pairPort: Int
)

class MainActivity : GameActivity(), InputManager.InputDeviceListener {

    private enum class State { SCANNING, SERVER_LIST, PAIRING, CONNECTED }

    private lateinit var binding: ActivityMainBinding
    private lateinit var inputManager: InputManager

    // Controller axes / buttons (written on main thread by input handlers)
    private var wButtons = 0
    private var bLT = 0; private var bRT = 0
    private var sLX = 0; private var sLY = 0; private var sRX = 0; private var sRY = 0
    private var controllerConnected = false

    // App state
    private var appState   = State.SCANNING
    private var selServer: DiscoveredServer? = null

    // Telemetry — per-second window counters (main thread only)
    private var telEventCount  = 0
    private var telSampleCount = 0
    private var telHistTotal   = 0
    private var telSendCount   = 0
    private var telTotalSent   = 0L
    private var telLastEventMs = 0L

    private val telHandler  = android.os.Handler(android.os.Looper.getMainLooper())
    private val telRunnable = object : Runnable {
        override fun run() {
            updateTelemetry()
            telHandler.postDelayed(this, 1000)
        }
    }

    private val deviceId   by lazy { getOrCreateDeviceId() }
    private val deviceName by lazy { Build.MODEL }

    companion object {
        private val BUTTON_MAP = mapOf(
            KeyEvent.KEYCODE_BUTTON_A      to 0x1000,
            KeyEvent.KEYCODE_BUTTON_B      to 0x2000,
            KeyEvent.KEYCODE_BUTTON_X      to 0x4000,
            KeyEvent.KEYCODE_BUTTON_Y      to 0x8000,
            KeyEvent.KEYCODE_BUTTON_L1     to 0x0100,
            KeyEvent.KEYCODE_BUTTON_R1     to 0x0200,
            KeyEvent.KEYCODE_BUTTON_THUMBL to 0x0040,
            KeyEvent.KEYCODE_BUTTON_THUMBR to 0x0080,
            KeyEvent.KEYCODE_BUTTON_START  to 0x0010,
            KeyEvent.KEYCODE_BUTTON_SELECT to 0x0020,
            KeyEvent.KEYCODE_DPAD_UP       to 0x0001,
            KeyEvent.KEYCODE_DPAD_DOWN     to 0x0002,
            KeyEvent.KEYCODE_DPAD_LEFT     to 0x0004,
            KeyEvent.KEYCODE_DPAD_RIGHT    to 0x0008,
        )
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // GameActivity.onCreate already calls setContentView with its own FrameLayout.
        // Add our UI layout on top so the SurfaceView stays in the hierarchy.
        binding = ActivityMainBinding.inflate(layoutInflater)
        val root = findViewById<ViewGroup>(android.R.id.content)
        root.addView(binding.root, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))
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
        SatelliteNative.closeSocket()
    }

    // ── Controller dot shape ──────────────────────────────────────────────────

    private fun initDotDrawable() {
        val d = GradientDrawable()
        d.shape = GradientDrawable.OVAL
        d.setColor(getColor(R.color.colorMuted))
        binding.dotController.background = d
    }

    private fun updateControllerDot() {
        val color = if (controllerConnected) getColor(R.color.colorSuccess)
                    else                     getColor(R.color.colorMuted)
        (binding.dotController.background as? GradientDrawable)?.setColor(color)
        binding.tvControllerStatus.text = if (controllerConnected) "CONNECTED" else "NONE"
    }

    // ── InputManager callbacks ────────────────────────────────────────────────

    private fun checkGamepadConnected() {
        controllerConnected = InputDevice.getDeviceIds().any { id ->
            val dev = InputDevice.getDevice(id) ?: return@any false
            (dev.sources and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
        }
        updateControllerDot()
    }

    override fun onInputDeviceAdded(deviceId: Int) {
        val dev = InputDevice.getDevice(deviceId) ?: return
        if ((dev.sources and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD) {
            controllerConnected = true; updateControllerDot()
        }
    }
    override fun onInputDeviceRemoved(deviceId: Int) { checkGamepadConnected() }
    override fun onInputDeviceChanged(deviceId: Int)  { checkGamepadConnected() }

    // ── Gamepad input ─────────────────────────────────────────────────────────
    // Direct Java→JNI path: extract axes/buttons, call sendReport() immediately.
    // This bypasses GameActivity's native input buffer (which doesn't work for
    // hybrid non-game layouts) and gives equivalent or better latency since
    // sendto() fires on the calling thread with zero buffering.

    // Override dispatchKeyEvent to intercept gamepad keys BEFORE GameActivity's
    // InputEnabledSurfaceView / GameTextInput InputConnection can consume them.
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.source and InputDevice.SOURCE_GAMEPAD != 0 ||
            event.source and InputDevice.SOURCE_JOYSTICK != 0) {
            return if (event.action == KeyEvent.ACTION_DOWN)
                handleKeyDown(event.keyCode, event)
            else
                handleKeyUp(event.keyCode, event)
        }
        return super.dispatchKeyEvent(event)
    }

    private fun handleKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_BUTTON_L2 -> { bLT = 255; trySend(); return true }
            KeyEvent.KEYCODE_BUTTON_R2 -> { bRT = 255; trySend(); return true }
        }
        BUTTON_MAP[keyCode]?.let { bit -> wButtons = wButtons or bit; trySend(); return true }
        return false
    }

    private fun handleKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_BUTTON_L2 -> { bLT = 0; trySend(); return true }
            KeyEvent.KEYCODE_BUTTON_R2 -> { bRT = 0; trySend(); return true }
        }
        BUTTON_MAP[keyCode]?.let { bit -> wButtons = wButtons and bit.inv(); trySend(); return true }
        return false
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.source and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK) {
            window.decorView.requestUnbufferedDispatch(event)
            when (event.action) {
                MotionEvent.ACTION_CANCEL -> { zeroAxes(); trySend(); return true }
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

    private fun zeroAxes() {
        sLX = 0; sLY = 0; sRX = 0; sRY = 0; bLT = 0; bRT = 0; wButtons = 0
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) { zeroAxes(); trySend() }
    }

    private fun processJoystickInput(event: MotionEvent, histPos: Int) {
        fun axis(a: Int) = if (histPos < 0) event.getAxisValue(a)
                           else event.getHistoricalAxisValue(a, histPos)
        fun flat(a: Int) = event.device?.getMotionRange(a, event.source)?.flat ?: 0f
        fun dead(v: Float, a: Int) = if (kotlin.math.abs(v) > flat(a)) v else 0f
        sLX = (dead(axis(MotionEvent.AXIS_X),  MotionEvent.AXIS_X)  *  32767f).toInt().coerceIn(-32768, 32767)
        sLY = (dead(axis(MotionEvent.AXIS_Y),  MotionEvent.AXIS_Y)  * -32767f).toInt().coerceIn(-32768, 32767)
        sRX = (dead(axis(MotionEvent.AXIS_Z),  MotionEvent.AXIS_Z)  *  32767f).toInt().coerceIn(-32768, 32767)
        sRY = (dead(axis(MotionEvent.AXIS_RZ), MotionEvent.AXIS_RZ) * -32767f).toInt().coerceIn(-32768, 32767)
        bLT = (maxOf(axis(MotionEvent.AXIS_LTRIGGER), axis(MotionEvent.AXIS_BRAKE))  * 255f).toInt().coerceIn(0, 255)
        bRT = (maxOf(axis(MotionEvent.AXIS_RTRIGGER), axis(MotionEvent.AXIS_GAS))    * 255f).toInt().coerceIn(0, 255)
        val hx = axis(MotionEvent.AXIS_HAT_X); val hy = axis(MotionEvent.AXIS_HAT_Y)
        wButtons = wButtons and 0x000F.inv()
        if (hx < -0.5f) wButtons = wButtons or 0x0004
        if (hx >  0.5f) wButtons = wButtons or 0x0008
        if (hy < -0.5f) wButtons = wButtons or 0x0001
        if (hy >  0.5f) wButtons = wButtons or 0x0002
        telSampleCount++
        trySend()
    }

    private fun trySend() {
        if (appState == State.CONNECTED) {
            SatelliteNative.sendReport(wButtons, bLT, bRT, sLX, sLY, sRX, sRY)
            telSendCount++
            telTotalSent++
        }
    }

    // ── Telemetry ─────────────────────────────────────────────────────────────

    private fun updateTelemetry() {
        val events  = telEventCount
        val samples = telSampleCount
        val sends   = telSendCount
        val hist    = telHistTotal
        telEventCount = 0; telSampleCount = 0; telSendCount = 0; telHistTotal = 0

        val histAvg = if (events > 0) hist.toFloat() / events else 0f
        val ageSec  = if (telLastEventMs == 0L) "—"
                      else "${SystemClock.elapsedRealtime() - telLastEventMs} ms ago"

        binding.tvTelEventRate .text = "$events / s"
        binding.tvTelSampleRate.text = "$samples / s"
        binding.tvTelHistAvg   .text = "%.1f".format(histAvg)
        binding.tvTelSendRate  .text = "$sends / s"
        binding.tvTelTotalSent .text = "$telTotalSent"
        binding.tvTelLastEvent .text = ageSec

        binding.tvTelLX  .text = "%+6d".format(sLX)
        binding.tvTelLY  .text = "%+6d".format(sLY)
        binding.tvTelRX  .text = "%+6d".format(sRX)
        binding.tvTelRY  .text = "%+6d".format(sRY)
        binding.tvTelLT  .text = "%3d".format(bLT)
        binding.tvTelRT  .text = "%3d".format(bRT)
        binding.tvTelBtns.text = "0x%04X".format(wButtons)
    }

    // ── UI button wiring ──────────────────────────────────────────────────────

    private fun setupButtons() {
        binding.btnRescan.setOnClickListener { startDiscovery() }
        binding.btnCancel.setOnClickListener { selServer = null; startDiscovery() }
        binding.btnPair.setOnClickListener   { attemptPairing() }
        binding.btnDisconnect.setOnClickListener { disconnect() }
    }

    // ── State machine ─────────────────────────────────────────────────────────

    private fun transitionTo(state: State) {
        appState = state
        binding.llScanning.visibility   = if (state == State.SCANNING)     View.VISIBLE else View.GONE
        binding.llServerList.visibility = if (state == State.SERVER_LIST)  View.VISIBLE else View.GONE
        binding.llPairing.visibility    = if (state == State.PAIRING)      View.VISIBLE else View.GONE
        binding.llConnected.visibility  = if (state == State.CONNECTED)    View.VISIBLE else View.GONE
    }

    // ── Discovery ─────────────────────────────────────────────────────────────

    private fun startDiscovery() {
        transitionTo(State.SCANNING)
        binding.tvScanningLabel.text = "Scanning for servers on LAN…"
        lifecycleScope.launch {
            val json    = withContext(Dispatchers.IO) { SatelliteNative.discoverServers(9879, 4000) }
            val servers = parseServers(json)
            transitionTo(State.SERVER_LIST)
            if (servers.isEmpty()) {
                binding.tvNoServers.visibility = View.VISIBLE
                binding.llServers.visibility   = View.GONE
            } else {
                binding.tvNoServers.visibility = View.GONE
                binding.llServers.visibility   = View.VISIBLE
                populateServerList(servers)
            }
        }
    }

    private fun populateServerList(servers: List<DiscoveredServer>) {
        binding.llServers.removeAllViews()
        val dp = resources.displayMetrics.density
        servers.forEach { server ->
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                val pad = (12 * dp).toInt()
                setPadding(pad, pad, pad, pad)
                val bg = GradientDrawable().apply {
                    setColor(getColor(R.color.colorBackground))
                    cornerRadius = 4 * dp
                    setStroke((1 * dp).toInt(), getColor(R.color.colorOutline))
                }
                background = bg
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                lp.bottomMargin = (8 * dp).toInt()
                layoutParams = lp
                setOnClickListener { onServerSelected(server) }
            }
            val tvName = TextView(this).apply {
                text = server.name
                setTextColor(getColor(R.color.colorOnSurface))
                textSize = 15f; setTypeface(null, android.graphics.Typeface.BOLD)
            }
            val tvMeta = TextView(this).apply {
                text = "${server.ip}  •  UDP:${server.udpPort}"
                setTextColor(getColor(R.color.colorMuted))
                textSize = 12f; typeface = android.graphics.Typeface.MONOSPACE
            }
            item.addView(tvName); item.addView(tvMeta)
            binding.llServers.addView(item)
        }
    }

    // ── Server selection & pairing ────────────────────────────────────────────

    private fun onServerSelected(server: DiscoveredServer) {
        selServer = server
        transitionTo(State.SCANNING)
        binding.tvScanningLabel.text = "Connecting to ${server.name}…"
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                SatelliteNative.pair(server.ip, server.pairPort, deviceId, deviceName, "")
            }
            if (result.contains("\"ok\":true")) connectToServer(server)
            else {
                transitionTo(State.PAIRING)
                binding.tvPairingServer.text = server.name
                binding.etPin.text?.clear()
                binding.tvPairError.text = ""
            }
        }
    }

    private fun attemptPairing() {
        val server = selServer ?: return
        val pin = binding.etPin.text.toString().trim()
        if (pin.isEmpty()) { binding.tvPairError.text = "Enter the PIN shown on the server"; return }
        binding.btnPair.isEnabled = false; binding.tvPairError.text = ""
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                SatelliteNative.pair(server.ip, server.pairPort, deviceId, deviceName, pin)
            }
            binding.btnPair.isEnabled = true
            if (result.contains("\"ok\":true")) connectToServer(server)
            else binding.tvPairError.text = jsonGet(result, "error") ?: "Pairing failed"
        }
    }

    private fun connectToServer(server: DiscoveredServer) {
        if (!SatelliteNative.openSocket(server.ip, server.udpPort)) {
            transitionTo(State.SERVER_LIST); return
        }
        transitionTo(State.CONNECTED)
        binding.tvConnectedServer.text = server.name
        binding.tvConnectedIp.text = "${server.ip}  •  UDP:${server.udpPort}"
        updateControllerDot()
    }

    private fun disconnect() {
        SatelliteNative.closeSocket(); selServer = null; startDiscovery()
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun getOrCreateDeviceId(): String {
        val p = getSharedPreferences("satellite", Context.MODE_PRIVATE)
        return p.getString("deviceId", null) ?: UUID.randomUUID().toString().replace("-", "")
            .also { p.edit().putString("deviceId", it).apply() }
    }

    private fun jsonGet(json: String, key: String): String? {
        val needle = "\"$key\":"
        val idx = json.indexOf(needle); if (idx < 0) return null
        var pos = idx + needle.length
        while (pos < json.length && json[pos] == ' ') pos++
        if (pos >= json.length) return null
        return if (json[pos] == '"') {
            val end = json.indexOf('"', pos + 1); if (end < 0) null else json.substring(pos + 1, end)
        } else {
            val end = json.substring(pos).indexOfFirst { it == ',' || it == '}' }
            if (end < 0) json.substring(pos) else json.substring(pos, pos + end)
        }
    }

    private fun parseServers(json: String): List<DiscoveredServer> {
        val stripped = json.trim().removePrefix("[").removeSuffix("]").trim()
        if (stripped.isEmpty()) return emptyList()
        val result = mutableListOf<DiscoveredServer>()
        var depth = 0; var start = 0
        for (i in stripped.indices) {
            when (stripped[i]) {
                '{' -> { if (depth == 0) start = i; depth++ }
                '}' -> { depth--
                    if (depth == 0) {
                        val obj      = stripped.substring(start, i + 1)
                        val name     = jsonGet(obj, "name") ?: continue
                        val ip       = jsonGet(obj, "ip")   ?: continue
                        val udpPort  = jsonGet(obj, "udpPort")  ?.toIntOrNull() ?: 9876
                        val pairPort = jsonGet(obj, "pairPort") ?.toIntOrNull() ?: 9878
                        result.add(DiscoveredServer(name, ip, udpPort, pairPort))
                    }
                }
            }
        }
        return result
    }
}
