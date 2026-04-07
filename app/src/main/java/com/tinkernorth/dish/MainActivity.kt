package com.tinkernorth.dish

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.hardware.input.InputManager
import android.os.Bundle
import android.os.Process
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewGroup
import androidx.lifecycle.lifecycleScope
import com.google.androidgamesdk.GameActivity
import com.tinkernorth.dish.databinding.ActivityMainBinding

class MainActivity :
    GameActivity(),
    InputManager.InputDeviceListener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var inputManager: InputManager

    private lateinit var input: GamepadInputProcessor
    private lateinit var controllers: ControllerManager
    private lateinit var server: ServerConnectionManager
    private lateinit var dashboard: DashboardCardRenderer
    private lateinit var wakeLockMgr: WakeLockManager
    private lateinit var lowPower: LowPowerManager
    private lateinit var telemetry: TelemetryTracker

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)

        binding = ActivityMainBinding.inflate(layoutInflater)
        findViewById<ViewGroup>(android.R.id.content).addView(
            binding.root,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
        inputManager = getSystemService(Context.INPUT_SERVICE) as InputManager

        createManagers()
        wireCallbacks()
        bindViews()

        binding.dotServer.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL; setColor(getColor(R.color.colorMuted))
        }
        inputManager.registerInputDeviceListener(this, null)
        binding.btnDisconnectAll.setOnClickListener { disconnectAll() }
        binding.btnBluetoothGamepad.setOnClickListener { launchBluetoothGamepad() }

        controllers.scanExistingGamepads()
        refreshDashboard()
        telemetry.start()
    }

    override fun onDestroy() {
        super.onDestroy()
        telemetry.stop(); lowPower.cancel()
        inputManager.unregisterInputDeviceListener(this)
        controllers.destroyAll(); server.disconnect(); wakeLockMgr.release()
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.action == MotionEvent.ACTION_DOWN && wakeLockMgr.screenLockActive)
            lowPower.onUserInteraction()
        return super.dispatchTouchEvent(ev)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean =
        input.handleKeyEvent(event) ?: super.dispatchKeyEvent(event)

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.source and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK)
            window.decorView.requestUnbufferedDispatch(event)
        return input.handleMotionEvent(event) ?: super.dispatchGenericMotionEvent(event)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) { input.zeroAxes(); input.trySend() }
    }

    override fun onInputDeviceAdded(id: Int) = controllers.onDeviceAdded(id)
    override fun onInputDeviceRemoved(id: Int) = controllers.onDeviceRemoved(id)
    override fun onInputDeviceChanged(id: Int) = controllers.onDeviceChanged(id)

    private fun createManagers() {
        server = ServerConnectionManager(this, lifecycleScope)
        controllers = ControllerManager(server, lifecycleScope)
        input = GamepadInputProcessor()
        dashboard = DashboardCardRenderer(this)
        wakeLockMgr = WakeLockManager(this, window)
        lowPower = LowPowerManager(window)
        telemetry = TelemetryTracker(input)
    }


    @Suppress("LongMethod")
    private fun wireCallbacks() {
        input.reportSender = GamepadInputProcessor.ReportSender { wButtons, bLT, bRT, sLX, sLY, sRX, sRY ->
            if (!server.isConnected) return@ReportSender
            val idx = controllers.activeControllerIndex()
            if (idx < 0) return@ReportSender
            SatelliteNative.sendReport(idx, wButtons, bLT, bRT, sLX, sLY, sRX, sRY)
        }

        server.onConnected = {
            controllers.onServerConnected()
            refreshDashboard()
        }
        server.onDisconnected = {
            controllers.onServerDisconnected()
            wakeLockMgr.update(false)
            lowPower.cancel()
            refreshDashboard()
        }
        server.onScanStarted = {
            controllers.entries.forEach {
                if (it.cardState == ControllerCardState.NEED_SERVER)
                    it.cardState = ControllerCardState.SCANNING
            }
            refreshDashboard()
        }
        server.onScanEmpty = {
            controllers.entries.forEach {
                if (it.cardState == ControllerCardState.SCANNING)
                    it.cardState = ControllerCardState.NEED_SERVER
            }
            refreshDashboard()
        }
        server.onServersDiscovered = { servers ->
            controllers.entries.forEach {
                if (it.cardState == ControllerCardState.SCANNING)
                    it.cardState = ControllerCardState.SERVER_LIST
            }
            refreshDashboard()
            controllers.entries.forEach { entry ->
                if (entry.cardState == ControllerCardState.SERVER_LIST) {
                    dashboard.populateServerList(entry, servers)
                }
            }
        }

        controllers.onControllersChanged = { refreshDashboard() }
        controllers.onAllControllersGone = { disconnectAll() }

        dashboard.onScanRequested = { server.startDiscovery() }
        dashboard.onServerSelected = { srv -> server.selectServer(srv) }
        dashboard.onRescanRequested = { server.startDiscovery() }
        dashboard.onDisconnectNow = { entry -> controllers.finalizeDisconnect(entry) }
        dashboard.onControllerTypeChanged = { entry, type ->
            controllers.setControllerType(entry, type)
            dashboard.rebuildCardContent(entry)
        }

        wakeLockMgr.onLockStateChanged = { active -> lowPower.onLockStateChanged(active) }
        lowPower.activeControllerCount = { controllers.entries.count { it.vigemActive } }
    }

    private fun bindViews() {
        dashboard.views = DashboardCardRenderer.Views(
            llEmptyState = binding.llEmptyState,
            llControllerCards = binding.llControllerCards,
            dotServer = binding.dotServer,
            tvServerStatus = binding.tvServerStatus,
            btnDisconnectAll = binding.btnDisconnectAll,
        )
        wakeLockMgr.views = WakeLockManager.Views(
            tvScreenLock = binding.tvScreenLock,
            tvWakeLock = binding.tvWakeLock,
        )
        lowPower.views = LowPowerManager.Views(
            llCountdownBanner = binding.llCountdownBanner,
            tvCountdownSeconds = binding.tvCountdownSeconds,
            flLowPowerOverlay = binding.flLowPowerOverlay,
            tvLowPowerTime = binding.tvLowPowerTime,
            tvLowPowerStatus = binding.tvLowPowerStatus,
        )
        telemetry.views = TelemetryTracker.Views(
            tvEventRate = binding.tvTelEventRate,
            tvSampleRate = binding.tvTelSampleRate,
            tvSendRate = binding.tvTelSendRate,
            tvTotalSent = binding.tvTelTotalSent,
            tvLX = binding.tvTelLX, tvLY = binding.tvTelLY,
            tvRX = binding.tvTelRX, tvRY = binding.tvTelRY,
            tvLT = binding.tvTelLT, tvRT = binding.tvTelRT,
            tvBtns = binding.tvTelBtns,
        )
    }

    private fun refreshDashboard() {
        dashboard.refresh(controllers.entries, server.isConnected, server.connectedServer?.name)
        wakeLockMgr.update(server.isConnected && controllers.hasActiveControllers)
    }

    private fun disconnectAll() {
        input.zeroAxes()
        controllers.destroyAll()
        server.disconnect()
        refreshDashboard()
    }

    private fun launchBluetoothGamepad() {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.P) {
            android.widget.Toast.makeText(
                this, "Bluetooth gamepad requires Android 9+", android.widget.Toast.LENGTH_LONG,
            ).show()
            return
        }
        startActivity(android.content.Intent(this, BluetoothGamepadActivity::class.java))
    }
}
