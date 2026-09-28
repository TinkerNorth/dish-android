// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.hotpath.overlay

import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.tinkernorth.dish.composer.CapabilityComposer
import com.tinkernorth.dish.composer.PhysicalReachabilityComposer
import com.tinkernorth.dish.hotpath.input.PadTouchFrame
import com.tinkernorth.dish.hotpath.input.PhysicalGamepadRegistry
import com.tinkernorth.dish.hotpath.input.capturedSurfaceTableOf
import com.tinkernorth.dish.hotpath.input.frame
import com.tinkernorth.dish.hotpath.input.routes
import com.tinkernorth.dish.hotpath.input.shouldCapture
import com.tinkernorth.dish.hotpath.input.slotForEvent
import com.tinkernorth.dish.source.connection.TelemetrySink
import com.tinkernorth.dish.ui.common.ResendPacer
import com.tinkernorth.dish.ui.common.TouchpadReportBuffer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * A framework pad's own touch surface, read through pointer capture and forwarded as the slot's
 * MSG_TOUCHPAD stream.
 *
 * On the framework paths (a USB pad left in Standard mode, any Bluetooth pad) Android reads a
 * DualShock 4 or DualSense's surface as a system mouse and hands the app nothing of it. Pointer
 * capture is the platform's door to the surface itself: while a view holds it, the touchpad
 * arrives "unscaled" as SOURCE_TOUCHPAD events carrying each finger's raw position, and the
 * cursor stops moving. [shouldCapture] says when that trade is worth making (a routed pad, a
 * focused window); this class makes the platform calls, maps each event through [frame], and
 * heals a lost final frame the way the phone-screen overlays do, with the same [ResendPacer]
 * burst on a thread of its own. The frames go to the slot's [TelemetrySink] exactly as the
 * on-screen touchpad's would, so the satellite and a Moonlight host see one shape whichever
 * surface produced it.
 *
 * The events are taken at the activity's `dispatchGenericMotionEvent`, not through a view's
 * captured-pointer listener: the platform hands a captured touchpad event down the FOCUS chain
 * (`ViewGroup.dispatchCapturedPointerEvent` forwards to the focused child), so a listener on
 * the root only fires while the root itself is the focused view, and what no view consumed
 * falls through to the activity as an ordinary generic motion event. The activity path holds
 * whatever the focus is, which on these screens is a button or a list.
 *
 * Pre-26 devices have no pointer capture; there the surface stays a system mouse and the
 * capability layer never offers it (the registry reports no surface).
 */
class PadTouchpadCapture(
    private val rootView: View,
    private val registry: PhysicalGamepadRegistry,
    private val reachability: PhysicalReachabilityComposer,
    private val capabilities: CapabilityComposer,
    private val scope: CoroutineScope,
) : DefaultLifecycleObserver {
    @Volatile private var routes: Map<Int, String> = emptyMap()

    @Volatile private var surfaces = capturedSurfaceTableOf(emptyMap())

    // Main-thread only: each captured event is read through it in place.
    private val touchpad = MotionEventTouchpad()

    @Volatile private var focused = false

    // Last frame per slot: written on the main thread (the captured event), read on the resend
    // thread. Frames are immutable, so a reader never sees a torn one.
    private val lastFrame = ConcurrentHashMap<String, PadTouchFrame>()

    // Dedicated URGENT_AUDIO thread so edge-burst resends aren't jittered by the shared Default
    // pool, the same shape as the overlays'. Started on the first capture, since every screen
    // hosts one of these and most never capture anything.
    private var resendThread: HandlerThread? = null

    @Volatile private var resendJob: Job? = null

    // Resend-thread-only.
    private val pacers = HashMap<String, ResendPacer>()
    private val lastResent = HashMap<String, PadTouchFrame>()

    // The wire frame, one per sending thread: the main thread's captured events and lifts, and
    // the resend thread's ticks.
    private val mainReport = TouchpadReportBuffer()
    private val resendReport = TouchpadReportBuffer()

    private var warnedNoRange = false

    fun install() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        combine(registry.devices, reachability.state) { devices, reachable ->
            routes(devices, reachable.keys, capabilities::touchpadSource)
        }.distinctUntilChanged()
            .onEach(::installRoutes)
            .launchIn(scope)
    }

    // The captured surfaces the composer routes now; capture follows them and the window focus.
    internal fun installRoutes(next: Map<Int, String>) {
        routes = next
        surfaces = capturedSurfaceTableOf(next)
        apply()
    }

    fun onWindowFocusChanged(hasFocus: Boolean) {
        focused = hasFocus
        // Capture goes with focus, and a finger down at that moment never gets its UP: lift
        // it ourselves so the host is not left holding a touch.
        if (!hasFocus) liftAll()
        apply()
    }

    override fun onDestroy(owner: LifecycleOwner) {
        stopResend()
        resendThread?.quitSafely()
        resendThread = null
    }

    private fun apply() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (shouldCapture(routes, focused)) {
            if (!rootView.hasPointerCapture()) rootView.requestPointerCapture()
            startResend()
        } else {
            if (rootView.hasPointerCapture()) rootView.releasePointerCapture()
            // The lift is a final frame like any other, and the resend loop
            // is what heals a lost one: it keeps ticking until the burst has
            // gone out and forgets the slot itself (resendDue). Stopping it
            // here would send the lift exactly once.
            liftAll()
        }
    }

    /**
     * The activity's generic-motion hook, ahead of the gamepad forwarding. True consumes the
     * event: a captured touchpad event for a routed pad is the pad's, and letting it fall
     * through would hand the UI a motion event it has no use for. Anything else (a joystick
     * axis, a mouse the app never captured, a surface it does not route) is left alone.
     */
    fun onGenericMotionEvent(event: MotionEvent): Boolean {
        val slotId = slotForEvent(surfaces, event.source, event.deviceId) ?: return false
        val device = event.device
        val xRange = device?.getMotionRange(MotionEvent.AXIS_X, InputDevice.SOURCE_TOUCHPAD)
        val yRange = device?.getMotionRange(MotionEvent.AXIS_Y, InputDevice.SOURCE_TOUCHPAD)
        if (xRange == null || yRange == null) {
            warnOnceAboutMissingRange(event.deviceId)
            return true
        }
        touchpad.bind(event, xRange, yRange)
        val frame =
            frame(
                event = touchpad,
                liftingIndex = liftingIndexOf(event.actionMasked, event.actionIndex),
                buttonPressed = event.buttonState and MotionEvent.BUTTON_PRIMARY != 0,
                eventTimeMs = event.eventTime,
            )
        lastFrame[slotId] = frame
        reachability.state.value[slotId]?.let { send(it, slotId, frame, mainReport) }
        return true
    }

    // Once per process: a pad that reports no range will report none for every frame it sends.
    private fun warnOnceAboutMissingRange(deviceId: Int) {
        if (warnedNoRange) return
        warnedNoRange = true
        Log.w(TAG, "captured touchpad on device $deviceId reports no axis range; dropping its frames")
    }

    private fun liftAll() {
        val now = SystemClock.uptimeMillis()
        for ((slotId, lifted) in liftedFrames(lastFrame, now)) {
            lastFrame[slotId] = lifted
            reachability.state.value[slotId]?.let { send(it, slotId, lifted, mainReport) }
        }
    }

    private fun startResend() {
        if (resendJob?.isActive == true) return
        val thread =
            resendThread ?: HandlerThread("dish-pad-touch-resend", Process.THREAD_PRIORITY_URGENT_AUDIO).also {
                it.start()
                resendThread = it
            }
        resendJob =
            scope.launch(Handler(thread.looper).asCoroutineDispatcher()) {
                var nextTickNs = System.nanoTime() + RESEND_INTERVAL_NS
                while (isActive) {
                    val now = System.nanoTime()
                    if (now - nextTickNs > RESEND_INTERVAL_NS * MAX_BACKLOG_FACTOR) nextTickNs = now + RESEND_INTERVAL_NS
                    val waitMs = (nextTickNs - now) / NS_PER_MS
                    if (waitMs > 0) delay(waitMs)
                    nextTickNs += RESEND_INTERVAL_NS
                    resendDue()
                }
            }
    }

    private fun stopResend() {
        resendJob?.cancel()
        resendJob = null
    }

    // Resend thread. A changed frame is re-sent EDGE_BURST_RESENDS ticks in a row, then on the
    // slow keepalive, so a lost finger-up heals at the next tick; the receiver drops a duplicate
    // by its equal event time. With every slot forgotten the loop stops itself; the next capture
    // starts it again.
    internal fun resendDue() {
        val routedSlots = routes.values.toSet()
        for ((slotId, frame) in lastFrame) {
            val sink = reachability.state.value[slotId]
            val changed = frame != lastResent[slotId]
            if (changed) lastResent[slotId] = frame
            val pacer = pacers.getOrPut(slotId) { ResendPacer() }
            val due = pacer.resendDue(changed)
            when (resendStepFor(due, hasSink = sink != null, routed = slotId in routedSlots)) {
                ResendStep.SEND -> if (sink != null) send(sink, slotId, frame, resendReport)
                ResendStep.FORGET -> forgetSlot(slotId)
                ResendStep.KEEP -> Unit
            }
        }
        if (lastFrame.isEmpty() && !shouldCapture(routes, focused)) stopResend()
    }

    private fun forgetSlot(slotId: String) {
        lastFrame.remove(slotId)
        lastResent.remove(slotId)
        pacers.remove(slotId)
    }

    private fun send(
        sink: TelemetrySink,
        slotId: String,
        frame: PadTouchFrame,
        buffer: TouchpadReportBuffer,
    ) {
        sink.sendTouchpad(slotId, buffer.reportOf(frame))
    }

    private companion object {
        const val TAG = "PadTouchpadCapture"
        const val RESEND_INTERVAL_NS = 50_000_000L
        const val MAX_BACKLOG_FACTOR = 5L
        const val NS_PER_MS = 1_000_000L
    }
}
