// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.hotpath.input

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import android.hardware.input.InputManager
import android.hardware.lights.Light
import android.hardware.lights.LightsManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Vibrator
import android.os.VibratorManager
import android.view.InputDevice
import com.tinkernorth.dish.core.jni.PhysicalInputNative
import com.tinkernorth.dish.source.bluetooth.BluetoothConnections
import com.tinkernorth.dish.source.lights.COMPOSED_RGB_LIGHT_NAME
import com.tinkernorth.dish.source.usb.DirectClaimFailure
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.runs
import io.mockk.slot
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

// The InputDevice lifecycle callbacks and the model mutators the USB path drives: what the
// registry publishes when Android adds, changes or removes a pad, and what a path switch holds.
// The registry is built for API 24 unless a case says otherwise, so the touch-surface probe reads
// absent, the rumble probe takes the legacy vibrator, the capability these flows exercise, and the
// gyro and light-bar probes, whose per-device APIs start at 31, read absent.
@OptIn(ExperimentalCoroutinesApi::class)
class PhysicalGamepadRegistryLifecycleTest {
    private val dispatcher = StandardTestDispatcher()
    private val inputManager = mockk<InputManager>(relaxed = true)
    private val native = mockk<PhysicalInputNative>(relaxed = true)
    private val btConnections = mockk<BluetoothConnections>(relaxed = true)
    private val usbListing = hashMapOf<String, UsbDevice>()
    private val usb = mockk<UsbManager> { every { deviceList } returns usbListing }

    @Before
    fun listNoDevices() {
        mockkStatic(InputDevice::class)
        every { InputDevice.getDeviceIds() } returns intArrayOf()
    }

    @After
    fun unmockInputDevices() {
        unmockkStatic(InputDevice::class)
    }

    private fun buildRegistry(
        scope: CoroutineScope = CoroutineScope(dispatcher),
        sdkInt: Int = Build.VERSION_CODES.N,
    ): PhysicalGamepadRegistry {
        val ctx = mockk<Context>()
        every { ctx.getSystemService(Context.INPUT_SERVICE) } returns inputManager
        every { ctx.getSystemService(Context.USB_SERVICE) } returns usb
        return PhysicalGamepadRegistry(ctx, scope, native, btConnections, sdkInt)
    }

    private fun frameworkPad(
        deviceId: Int,
        vid: Int = VID,
        pid: Int = PID,
        name: String = NAME,
        motor: Vibrator = motor(present = false),
    ): InputDevice {
        val pad =
            mockk<InputDevice>(relaxed = true) {
                every { id } returns deviceId
                every { this@mockk.name } returns name
                every { sources } returns (InputDevice.SOURCE_GAMEPAD or InputDevice.SOURCE_JOYSTICK)
                every { keyboardType } returns InputDevice.KEYBOARD_TYPE_NON_ALPHABETIC
                every { vendorId } returns vid
                every { productId } returns pid
            }
        stubLegacyVibrator(pad, motor)
        return pad
    }

    // A pad's touch surface enumerated as a device of its own: a mouse of the pad's model.
    private fun surfaceOf(
        deviceId: Int,
        vid: Int = VID,
        pid: Int = PID,
    ): InputDevice =
        mockk(relaxed = true) {
            every { id } returns deviceId
            every { sources } returns InputDevice.SOURCE_MOUSE
            every { keyboardType } returns InputDevice.KEYBOARD_TYPE_NONE
            every { vendorId } returns vid
            every { productId } returns pid
        }

    private fun actuators(vararg ids: Int): VibratorManager = mockk { every { vibratorIds } returns ids }

    private fun surfaceOfPad(registry: PhysicalGamepadRegistry): Int? =
        registry.devices.value
            .getValue(PAD)
            .touchpadDeviceId

    private fun rumbleOfPad(registry: PhysicalGamepadRegistry): Boolean =
        registry.devices.value
            .getValue(PAD)
            .hasRumble

    private fun motor(present: Boolean): Vibrator = mockk { every { hasVibrator() } returns present }

    private fun listUsbModel(
        vid: Int,
        pid: Int,
    ) {
        val listed =
            mockk<UsbDevice> {
                every { vendorId } returns vid
                every { productId } returns pid
            }
        usbListing["usb-$vid-$pid"] = listed
    }

    // The id is read before the stub is recorded: a call on another mock inside `every` would be
    // recorded as part of the stubbed chain instead of as its argument.
    private fun addPad(
        registry: PhysicalGamepadRegistry,
        pad: InputDevice,
    ) {
        val deviceId = pad.id
        every { InputDevice.getDevice(deviceId) } returns pad
        registry.onInputDeviceAdded(deviceId)
    }

    private fun holdAsPlaceholder(
        registry: PhysicalGamepadRegistry,
        deviceId: Int,
        vid: Int = VID,
        pid: Int = PID,
    ) {
        registry.beginModelTransition(vid, pid)
        registry.onInputDeviceRemoved(deviceId)
    }

    private fun countdownOf(
        registry: PhysicalGamepadRegistry,
        deviceId: Int,
    ): Int? = registry.devices.value[deviceId]?.disconnectingTimeLeftSec

    // ---- install ----

    @Test
    fun `install registers the input-device listener once, however often it is called`() {
        val registry = buildRegistry()
        registry.install()
        registry.install()
        verify(exactly = 1) { inputManager.registerInputDeviceListener(registry, null) }
    }

    // ---- onInputDeviceAdded ----

    @Test
    fun `a keyboard arriving is ignored`() {
        val registry = buildRegistry()
        addPad(registry, frameworkPad(PAD))
        val before = registry.devices.value
        val keyboard =
            mockk<InputDevice>(relaxed = true) {
                every { sources } returns InputDevice.SOURCE_KEYBOARD
                every { keyboardType } returns InputDevice.KEYBOARD_TYPE_ALPHABETIC
            }
        every { InputDevice.getDevice(KEYBOARD) } returns keyboard

        registry.onInputDeviceAdded(KEYBOARD)

        assertSame(before, registry.devices.value)
        verify(exactly = 1) { InputDevice.getDevice(PAD) }
    }

    @Test
    fun `a pointer-only device arriving re-resolves each held pad's touch surface`() {
        val registry = buildRegistry()
        addPad(registry, frameworkPad(PAD))
        val mouse =
            mockk<InputDevice>(relaxed = true) {
                every { sources } returns InputDevice.SOURCE_MOUSE
                every { keyboardType } returns InputDevice.KEYBOARD_TYPE_NONE
            }
        every { InputDevice.getDevice(MOUSE) } returns mouse

        registry.onInputDeviceAdded(MOUSE)

        assertEquals(setOf(PAD), registry.devices.value.keys)
        verify(exactly = 2) { InputDevice.getDevice(PAD) }
    }

    @Test
    fun `a separately enumerated surface arriving lands on its pad's card`() {
        val registry = buildRegistry(sdkInt = Build.VERSION_CODES.O)
        addPad(registry, frameworkPad(PAD))
        assertNull(surfaceOfPad(registry))
        every { InputDevice.getDeviceIds() } returns intArrayOf(PAD, MOUSE)
        every { InputDevice.getDevice(MOUSE) } returns surfaceOf(MOUSE)

        registry.onInputDeviceAdded(MOUSE)

        assertEquals(MOUSE, surfaceOfPad(registry))
    }

    @Test
    fun `a surface of another model arriving leaves the pad without one`() {
        val registry = buildRegistry(sdkInt = Build.VERSION_CODES.O)
        addPad(registry, frameworkPad(PAD))
        every { InputDevice.getDeviceIds() } returns intArrayOf(PAD, MOUSE)
        every { InputDevice.getDevice(MOUSE) } returns surfaceOf(MOUSE, pid = OTHER_PID)

        registry.onInputDeviceAdded(MOUSE)

        assertNull(surfaceOfPad(registry))
    }

    @Test
    fun `the pad's surface going away clears it from the card`() {
        val registry = buildRegistry(sdkInt = Build.VERSION_CODES.O)
        every { InputDevice.getDeviceIds() } returns intArrayOf(PAD, MOUSE)
        every { InputDevice.getDevice(MOUSE) } returns surfaceOf(MOUSE)
        addPad(registry, frameworkPad(PAD))
        assertEquals(MOUSE, surfaceOfPad(registry))
        every { InputDevice.getDeviceIds() } returns intArrayOf(PAD)
        every { InputDevice.getDevice(MOUSE) } returns null

        registry.onInputDeviceRemoved(MOUSE)

        assertNull(surfaceOfPad(registry))
    }

    @Test
    fun `a pad re-added during the disconnect countdown keeps its card with no countdown`() =
        runTest(dispatcher) {
            val registry = buildRegistry(scope = this)
            val pad = frameworkPad(PAD)
            addPad(registry, pad)
            registry.onInputDeviceRemoved(PAD)
            dispatcher.scheduler.runCurrent()
            assertEquals(DISCONNECT_GRACE_SEC, countdownOf(registry, PAD))

            addPad(registry, pad)

            assertNull(countdownOf(registry, PAD))
            dispatcher.scheduler.advanceTimeBy(TWICE_THE_GRACE_MS)
            dispatcher.scheduler.runCurrent()
            assertTrue("the cancelled countdown must not reap the card later", PAD in registry.devices.value)
        }

    @Test
    fun `a re-enumerated pad leaves a held synthetic of the same model alone`() {
        val registry = buildRegistry()
        registry.addUsbSynthetic(SYNTHETIC, NAME, hasGyro = false, pollRateHz = 0, vendorId = VID, productId = PID)
        registry.setUsbSyntheticTransitioning(SYNTHETIC, true)

        addPad(registry, frameworkPad(OTHER_PAD))

        assertEquals(setOf(SYNTHETIC, OTHER_PAD), registry.devices.value.keys)
    }

    @Test
    fun `a re-enumerated pad of a model that failed Direct carries the failure on its card`() {
        val registry = buildRegistry()
        registry.markDirectFailed(VID, PID, DirectClaimFailure.Busy)

        addPad(registry, frameworkPad(PAD))

        assertEquals(
            DirectClaimFailure.Busy,
            registry.devices.value
                .getValue(PAD)
                .directFailure,
        )
    }

    // ---- what Standard offered, remembered per model ----

    @Test
    fun `frameworkCapsFor remembers what Standard offered after the device is claimed away`() {
        val registry = buildRegistry()
        addPad(registry, frameworkPad(PAD, motor = motor(present = true)))
        registry.forgetSupersededFramework(PAD)

        val caps = registry.frameworkCapsFor(VID, PID)

        assertEquals(PhysicalGamepadRegistry.FrameworkCaps(hasGyro = false, hasRumble = true), caps)
    }

    @Test
    fun `frameworkCapsFor is not recorded for a pad with no vid and pid`() {
        val registry = buildRegistry()
        addPad(registry, frameworkPad(PAD, vid = 0, pid = 0))
        assertNull(registry.frameworkCapsFor(0, 0))
    }

    // ---- the rumble probe ----

    @Test
    fun `a pad with a framework vibrator reports rumble`() {
        val registry = buildRegistry()
        addPad(registry, frameworkPad(PAD, motor = motor(present = true)))
        assertTrue(
            registry.devices.value
                .getValue(PAD)
                .hasRumble,
        )
    }

    @Test
    fun `a model whose framework rumble is unreliable reports no rumble`() {
        every { native.modelFrameworkRumbleUnreliable(VID, PID) } returns true
        val registry = buildRegistry()
        addPad(registry, frameworkPad(PAD, motor = motor(present = true)))
        assertFalse(
            registry.devices.value
                .getValue(PAD)
                .hasRumble,
        )
    }

    @Test
    fun `through API 30 the rumble probe asks the pad's legacy vibrator`() {
        val registry = buildRegistry(sdkInt = Build.VERSION_CODES.R)
        val pad = frameworkPad(PAD, motor = motor(present = true))
        every { pad.vibratorManager } returns actuators()

        addPad(registry, pad)

        assertTrue(rumbleOfPad(registry))
    }

    @Test
    fun `from API 31 a pad with an actuator reports rumble`() {
        val registry = buildRegistry(sdkInt = Build.VERSION_CODES.S)
        val pad = frameworkPad(PAD, motor = motor(present = false))
        every { pad.vibratorManager } returns actuators(ACTUATOR)

        addPad(registry, pad)

        assertTrue(rumbleOfPad(registry))
    }

    @Test
    fun `from API 31 a pad with no actuator reports no rumble whatever its legacy vibrator says`() {
        val registry = buildRegistry(sdkInt = Build.VERSION_CODES.S)
        val pad = frameworkPad(PAD, motor = motor(present = true))
        every { pad.vibratorManager } returns actuators()

        addPad(registry, pad)

        assertFalse(rumbleOfPad(registry))
    }

    // ---- the gyro and light-bar probes: both per-device APIs start at 31 ----

    private fun gyroOfPad(registry: PhysicalGamepadRegistry): Boolean =
        registry.devices.value
            .getValue(PAD)
            .hasGyro

    private fun lightbarOfPad(registry: PhysicalGamepadRegistry): Boolean =
        registry.devices.value
            .getValue(PAD)
            .hasLightbar

    private fun sensors(gyroscope: Sensor?): SensorManager =
        mockk { every { getDefaultSensor(Sensor.TYPE_GYROSCOPE) } returns gyroscope }

    // A DualShock 4's light bar as hid-sony composes it, the way API 31 to 33 names it.
    private fun composedLightbar(): Light =
        mockk {
            every { id } returns LIGHT_BAR
            every { name } returns COMPOSED_RGB_LIGHT_NAME
            every { type } returns Light.LIGHT_TYPE_INPUT
            every { hasRgbControl() } returns true
        }

    private fun lights(vararg listed: Light): LightsManager = mockk { every { lights } returns listed.toList() }

    @Test
    fun `through API 30 a pad reports no gyro and its sensors are never asked`() {
        val registry = buildRegistry(sdkInt = Build.VERSION_CODES.R)
        val pad = frameworkPad(PAD)
        every { pad.sensorManager } returns sensors(gyroscope = mockk())

        addPad(registry, pad)

        assertFalse(gyroOfPad(registry))
        verify(exactly = 0) { pad.sensorManager }
    }

    @Test
    fun `from API 31 a pad with a gyroscope reports gyro`() {
        val registry = buildRegistry(sdkInt = Build.VERSION_CODES.S)
        val pad = frameworkPad(PAD)
        every { pad.sensorManager } returns sensors(gyroscope = mockk())

        addPad(registry, pad)

        assertTrue(gyroOfPad(registry))
    }

    @Test
    fun `from API 31 a gyroscope that enumerates late lands on the re-probe`() {
        val registry = buildRegistry(sdkInt = Build.VERSION_CODES.S)
        val pad = frameworkPad(PAD)
        every { pad.sensorManager } returns sensors(gyroscope = null)
        addPad(registry, pad)
        assertFalse(gyroOfPad(registry))

        every { pad.sensorManager } returns sensors(gyroscope = mockk())
        registry.onInputDeviceChanged(PAD)

        assertTrue(gyroOfPad(registry))
    }

    @Test
    fun `through API 30 a pad reports no light bar and its lights are never listed`() {
        val registry = buildRegistry(sdkInt = Build.VERSION_CODES.R)
        val pad = frameworkPad(PAD)
        every { pad.lightsManager } returns lights(composedLightbar())

        addPad(registry, pad)

        assertFalse(lightbarOfPad(registry))
        verify(exactly = 0) { pad.lightsManager }
    }

    @Test
    fun `from API 31 a pad with a light bar reports it`() {
        val registry = buildRegistry(sdkInt = Build.VERSION_CODES.S)
        val pad = frameworkPad(PAD)
        every { pad.lightsManager } returns lights(composedLightbar())

        addPad(registry, pad)

        assertTrue(lightbarOfPad(registry))
    }

    @Test
    fun `from API 31 a light bar that enumerates late lands on the re-probe`() {
        val registry = buildRegistry(sdkInt = Build.VERSION_CODES.S)
        val pad = frameworkPad(PAD)
        every { pad.lightsManager } returns lights()
        addPad(registry, pad)
        assertFalse(lightbarOfPad(registry))

        every { pad.lightsManager } returns lights(composedLightbar())
        registry.onInputDeviceChanged(PAD)

        assertTrue(lightbarOfPad(registry))
    }

    // ---- transport ----

    @Test
    fun `a pad the Bluetooth registry lists by name is Bluetooth even when USB lists the model too`() {
        every { btConnections.isConnected(NAME) } returns true
        listUsbModel(VID, PID)
        val registry = buildRegistry()
        addPad(registry, frameworkPad(PAD))
        assertEquals(
            Transport.Bluetooth,
            registry.devices.value
                .getValue(PAD)
                .transport,
        )
    }

    @Test
    fun `a Bluetooth link change re-resolves framework transports but not synthetics`() {
        val onLinkChanged = slot<() -> Unit>()
        every { btConnections.start(capture(onLinkChanged)) } just runs
        listUsbModel(VID, PID)
        val registry = buildRegistry()
        registry.install()
        addPad(registry, frameworkPad(PAD))
        registry.addUsbSynthetic(SYNTHETIC, NAME, hasGyro = false, pollRateHz = 0, vendorId = VID, productId = PID)
        assertEquals(
            Transport.Usb,
            registry.devices.value
                .getValue(PAD)
                .transport,
        )

        every { btConnections.isConnected(NAME) } returns true
        onLinkChanged.captured()

        assertEquals(
            Transport.Bluetooth,
            registry.devices.value
                .getValue(PAD)
                .transport,
        )
        assertEquals(
            Transport.Usb,
            registry.devices.value
                .getValue(SYNTHETIC)
                .transport,
        )
    }

    // ---- the Direct failure shown on the model's card ----

    @Test
    fun `markDirectFailed shows the cause on the model's framework card`() {
        val registry = buildRegistry()
        addPad(registry, frameworkPad(PAD))
        registry.markDirectFailed(VID, PID, DirectClaimFailure.Busy)
        assertEquals(
            DirectClaimFailure.Busy,
            registry.devices.value
                .getValue(PAD)
                .directFailure,
        )
    }

    @Test
    fun `clearDirectFailed clears the card's cause and leaves other models alone`() {
        val registry = buildRegistry()
        addPad(registry, frameworkPad(PAD))
        addPad(registry, frameworkPad(OTHER_PAD, pid = OTHER_PID))
        registry.markDirectFailed(VID, PID, DirectClaimFailure.Busy)
        registry.markDirectFailed(VID, OTHER_PID, DirectClaimFailure.Busy)

        registry.clearDirectFailed(VID, PID)

        assertNull(
            registry.devices.value
                .getValue(PAD)
                .directFailure,
        )
        assertEquals(
            DirectClaimFailure.Busy,
            registry.devices.value
                .getValue(OTHER_PAD)
                .directFailure,
        )
    }

    // ---- the placeholder a path switch holds ----

    @Test
    fun `endModelTransition drops the stale placeholder`() {
        val registry = buildRegistry()
        addPad(registry, frameworkPad(PAD))
        holdAsPlaceholder(registry, PAD)

        registry.endModelTransition(VID, PID)

        assertTrue(registry.devices.value.isEmpty())
    }

    @Test
    fun `endModelTransition leaves a live re-enumerated pad and another model's placeholder alone`() {
        val registry = buildRegistry()
        addPad(registry, frameworkPad(PAD))
        addPad(registry, frameworkPad(OTHER_PAD, pid = OTHER_PID))
        holdAsPlaceholder(registry, OTHER_PAD, pid = OTHER_PID)

        registry.endModelTransition(VID, PID)

        assertEquals(setOf(PAD, OTHER_PAD), registry.devices.value.keys)
    }

    @Test
    fun `markNeedsReplug settles only the held framework placeholder`() {
        val registry = buildRegistry()
        addPad(registry, frameworkPad(PAD))
        holdAsPlaceholder(registry, PAD)
        registry.addUsbSynthetic(SYNTHETIC, NAME, hasGyro = false, pollRateHz = 0, vendorId = VID, productId = PID)
        registry.setUsbSyntheticTransitioning(SYNTHETIC, true)

        registry.markNeedsReplug(VID, PID)

        val framework = registry.devices.value.getValue(PAD)
        assertTrue(framework.needsReplug)
        assertFalse(framework.transitioning)
        val synthetic = registry.devices.value.getValue(SYNTHETIC)
        assertTrue(synthetic.transitioning)
        assertFalse(synthetic.needsReplug)
    }

    @Test
    fun `after markNeedsReplug a later removal of the model reaps normally instead of holding`() {
        val registry = buildRegistry()
        addPad(registry, frameworkPad(PAD))
        registry.beginModelTransition(VID, PID)
        registry.markNeedsReplug(VID, PID)

        registry.onInputDeviceRemoved(PAD)
        dispatcher.scheduler.runCurrent()

        assertFalse(
            registry.devices.value
                .getValue(PAD)
                .transitioning,
        )
        assertEquals(DISCONNECT_GRACE_SEC, countdownOf(registry, PAD))
    }

    @Test
    fun `clearRestoreStuck returns the synthetic to the loader look`() {
        val registry = buildRegistry()
        registry.addUsbSynthetic(SYNTHETIC, NAME, hasGyro = false, pollRateHz = 0, vendorId = VID, productId = PID)
        registry.markRestoreStuck(VID, PID)

        registry.clearRestoreStuck(VID, PID)

        val synthetic = registry.devices.value.getValue(SYNTHETIC)
        assertFalse(synthetic.restoreStuck)
        assertTrue(synthetic.transitioning)
    }

    @Test
    fun `setUsbSyntheticTransitioning with the same value does not re-emit`() {
        val registry = buildRegistry()
        registry.addUsbSynthetic(SYNTHETIC, NAME, hasGyro = false, pollRateHz = 0, vendorId = VID, productId = PID)
        val before = registry.devices.value
        registry.setUsbSyntheticTransitioning(SYNTHETIC, false)
        assertSame(before, registry.devices.value)
    }

    @Test
    fun `setUsbSyntheticTransitioning for an unknown device is a no-op`() {
        val registry = buildRegistry()
        val before = registry.devices.value
        registry.setUsbSyntheticTransitioning(SYNTHETIC, true)
        assertSame(before, registry.devices.value)
    }

    // ---- onInputDeviceRemoved ----

    @Test
    fun `onInputDeviceRemoved for a pad whose model is switching holds it`() {
        val registry = buildRegistry()
        addPad(registry, frameworkPad(PAD))

        holdAsPlaceholder(registry, PAD)

        assertTrue(
            registry.devices.value
                .getValue(PAD)
                .transitioning,
        )
        assertNull(countdownOf(registry, PAD))
    }

    @Test
    fun `onInputDeviceRemoved for a plain pad starts the disconnect countdown`() {
        val registry = buildRegistry()
        addPad(registry, frameworkPad(PAD))

        registry.onInputDeviceRemoved(PAD)
        dispatcher.scheduler.runCurrent()

        assertEquals(DISCONNECT_GRACE_SEC, countdownOf(registry, PAD))
    }

    @Test
    fun `onInputDeviceRemoved for an id the registry never held is ignored`() {
        val registry = buildRegistry()
        addPad(registry, frameworkPad(PAD))
        val before = registry.devices.value
        registry.onInputDeviceRemoved(OTHER_PAD)
        assertSame(before, registry.devices.value)
    }

    @Test
    fun `a removed pad counts down five seconds then disappears`() =
        runTest(dispatcher) {
            val registry = buildRegistry(scope = this)
            addPad(registry, frameworkPad(PAD))

            registry.onInputDeviceRemoved(PAD)
            dispatcher.scheduler.runCurrent()
            assertEquals(DISCONNECT_GRACE_SEC, countdownOf(registry, PAD))

            dispatcher.scheduler.advanceTimeBy(TWO_TICKS_MS)
            dispatcher.scheduler.runCurrent()
            assertEquals(DISCONNECT_GRACE_SEC - 2, countdownOf(registry, PAD))

            dispatcher.scheduler.advanceTimeBy(THREE_TICKS_MS)
            dispatcher.scheduler.runCurrent()
            assertFalse(PAD in registry.devices.value)
        }

    // ---- onInputDeviceChanged ----

    @Test
    fun `onInputDeviceChanged when nothing changed does not re-emit`() {
        val registry = buildRegistry()
        addPad(registry, frameworkPad(PAD))
        val before = registry.devices.value

        registry.onInputDeviceChanged(PAD)

        assertSame(before, registry.devices.value)
    }

    @Test
    fun `onInputDeviceChanged when a rumble motor enumerates late republishes the pad`() {
        val registry = buildRegistry()
        val lateMotor = motor(present = false)
        addPad(registry, frameworkPad(PAD, motor = lateMotor))
        assertFalse(
            registry.devices.value
                .getValue(PAD)
                .hasRumble,
        )

        every { lateMotor.hasVibrator() } returns true
        registry.onInputDeviceChanged(PAD)

        assertTrue(
            registry.devices.value
                .getValue(PAD)
                .hasRumble,
        )
    }

    @Test
    fun `onInputDeviceChanged for a vanished pad outside a transition starts the countdown`() {
        val registry = buildRegistry()
        addPad(registry, frameworkPad(PAD))

        every { InputDevice.getDevice(PAD) } returns null
        registry.onInputDeviceChanged(PAD)
        dispatcher.scheduler.runCurrent()

        assertEquals(DISCONNECT_GRACE_SEC, countdownOf(registry, PAD))
    }

    @Test
    fun `onInputDeviceChanged for a pad mid-countdown that is back cancels the countdown`() {
        val registry = buildRegistry()
        addPad(registry, frameworkPad(PAD))
        registry.onInputDeviceRemoved(PAD)
        dispatcher.scheduler.runCurrent()
        assertEquals(DISCONNECT_GRACE_SEC, countdownOf(registry, PAD))

        registry.onInputDeviceChanged(PAD)

        assertNull(countdownOf(registry, PAD))
    }

    @Test
    fun `re-resolving the touch surfaces skips placeholders and synthetics`() {
        val registry = buildRegistry()
        addPad(registry, frameworkPad(PAD))
        holdAsPlaceholder(registry, PAD)
        registry.addUsbSynthetic(SYNTHETIC, NAME, hasGyro = false, pollRateHz = 0, vendorId = VID, productId = PID)
        val mouse =
            mockk<InputDevice>(relaxed = true) {
                every { sources } returns InputDevice.SOURCE_MOUSE
                every { keyboardType } returns InputDevice.KEYBOARD_TYPE_NONE
            }
        every { InputDevice.getDevice(MOUSE) } returns mouse

        registry.onInputDeviceAdded(MOUSE)

        verify(exactly = 1) { InputDevice.getDevice(PAD) }
        verify(exactly = 0) { InputDevice.getDevice(SYNTHETIC) }
    }

    private companion object {
        const val PAD = 7
        const val OTHER_PAD = 9
        const val KEYBOARD = 20
        const val MOUSE = 21
        const val SYNTHETIC = -1000
        const val ACTUATOR = 1
        const val LIGHT_BAR = 4
        const val VID = 0x054C
        const val PID = 0x0CE6
        const val OTHER_PID = 0x09CC
        const val NAME = "Wireless Controller"
        const val DISCONNECT_GRACE_SEC = 5
        const val TWO_TICKS_MS = 2_000L
        const val THREE_TICKS_MS = 3_000L
        const val TWICE_THE_GRACE_MS = 10_000L
    }
}
