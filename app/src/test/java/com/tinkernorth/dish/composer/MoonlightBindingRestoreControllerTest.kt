// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.composer

import androidx.lifecycle.LifecycleOwner
import com.tinkernorth.dish.core.net.moonlight.RememberedMoonlight
import com.tinkernorth.dish.hotpath.input.PhysicalGamepadRegistry
import com.tinkernorth.dish.repository.RememberedBinding
import com.tinkernorth.dish.source.connection.moonlight.MoonlightConnectionManager
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

// The rule is the pure planner; the controller is the rule on the live flows.
@OptIn(ExperimentalCoroutinesApi::class)
class MoonlightBindingRestoreControllerTest {
    private val dispatcher = StandardTestDispatcher()
    private val devices = MutableStateFlow<Map<Int, PhysicalGamepadRegistry.Device>>(emptyMap())
    private val bindings = MutableStateFlow<Map<String, String>>(emptyMap())
    private val hosts = MutableStateFlow<List<RememberedMoonlight>>(emptyList())
    private val registry: PhysicalGamepadRegistry =
        mockk { every { devices } returns this@MoonlightBindingRestoreControllerTest.devices }
    private val hub: ConnectionCoordinator =
        mockk(relaxed = true) { every { bindings } returns this@MoonlightBindingRestoreControllerTest.bindings }
    private val memory = mutableListOf<RememberedBinding>()
    private val moonlight: MoonlightConnectionManager =
        mockk {
            every { remembered } returns hosts
            every { rememberedBindings } answers { memory.toList() }
        }
    private val owner: LifecycleOwner = mockk(relaxed = true)

    private val pc = RememberedMoonlight(id = "moonlight:10.0.0.5", name = "PC", address = "10.0.0.5")
    private val padOnPc = RememberedBinding(descriptor = "usb:054c:0ce6:1", hostId = pc.id, controllerType = CONTROLLER_TYPE_PLAYSTATION)

    private fun pad(
        id: Int,
        descriptor: String = padOnPc.descriptor,
        disconnectingTimeLeftSec: Int? = null,
        transitioning: Boolean = false,
    ) = PhysicalGamepadRegistry.Device(
        id = id,
        name = "Pad",
        descriptor = descriptor,
        disconnectingTimeLeftSec = disconnectingTimeLeftSec,
        transitioning = transitioning,
    )

    private fun controller() = MoonlightBindingRestoreController(registry, hub, moonlight, TestScope(dispatcher))

    private fun plan(
        vararg present: PhysicalGamepadRegistry.Device,
        bound: Map<String, String> = emptyMap(),
        remembered: List<RememberedBinding> = listOf(padOnPc),
        hostIds: Set<String> = setOf(pc.id),
    ) = planMoonlightBindingRestore(present.toList(), bound, remembered, hostIds)

    private val nothing = emptyList<BindingRestore>()

    @Test
    fun `a present unbound pad remembered for a known host is put back with its type`() {
        assertEquals(listOf(BindingRestore("7", pc.id, CONTROLLER_TYPE_PLAYSTATION)), plan(pad(7)))
    }

    @Test
    fun `a pad that is bound, has no identity, or is not remembered is left alone`() {
        assertEquals(nothing, plan(pad(7), bound = mapOf("7" to "sat:1")))
        assertEquals(nothing, plan(pad(7, descriptor = ""), remembered = listOf(padOnPc.copy(descriptor = ""))))
        assertEquals(nothing, plan(pad(7, descriptor = "usb:045e:02ea:2")))
    }

    @Test
    fun `a pad remembered for a host no longer remembered is left alone`() {
        assertEquals(nothing, plan(pad(7), hostIds = emptySet()))
    }

    @Test
    fun `a pad on its way out or mid-claim is left to the change that follows it`() {
        assertEquals(nothing, plan(pad(7, disconnectingTimeLeftSec = 3)))
        assertEquals(nothing, plan(pad(7, transitioning = true)))
    }

    @Test
    fun `only the remembered pads among those present are put back`() {
        assertEquals(listOf(BindingRestore("7", pc.id, CONTROLLER_TYPE_PLAYSTATION)), plan(pad(9, descriptor = "usb:045e:02ea:2"), pad(7)))
    }

    @Test
    fun `a remembered pad present at start is bound back to its host`() =
        runTest(dispatcher) {
            memory += padOnPc
            hosts.value = listOf(pc)
            devices.value = mapOf(7 to pad(7))

            controller().onStart(owner)
            dispatcher.scheduler.advanceUntilIdle()

            verify(exactly = 1) { hub.bind("7", pc.id, CONTROLLER_TYPE_PLAYSTATION) }
        }

    @Test
    fun `a remembered pad that appears later is bound when it appears`() =
        runTest(dispatcher) {
            memory += padOnPc
            hosts.value = listOf(pc)
            controller().onStart(owner)
            dispatcher.scheduler.advanceUntilIdle()
            verify(exactly = 0) { hub.bind(any(), any(), any()) }

            devices.value = mapOf(11 to pad(11))
            dispatcher.scheduler.advanceUntilIdle()

            verify(exactly = 1) { hub.bind("11", pc.id, CONTROLLER_TYPE_PLAYSTATION) }
        }

    @Test
    fun `a pad already bound is not bound again`() =
        runTest(dispatcher) {
            memory += padOnPc
            hosts.value = listOf(pc)
            devices.value = mapOf(7 to pad(7))
            bindings.value = mapOf("7" to pc.id)

            controller().onStart(owner)
            dispatcher.scheduler.advanceUntilIdle()

            verify(exactly = 0) { hub.bind(any(), any(), any()) }
        }

    @Test
    fun `a pad that arrived while stopped is bound on the next start`() =
        runTest(dispatcher) {
            memory += padOnPc
            hosts.value = listOf(pc)
            val controller = controller()
            controller.onStart(owner)
            dispatcher.scheduler.advanceUntilIdle()
            controller.onStop(owner)

            devices.value = mapOf(7 to pad(7))
            dispatcher.scheduler.advanceUntilIdle()
            verify(exactly = 0) { hub.bind(any(), any(), any()) }

            controller.onStart(owner)
            dispatcher.scheduler.advanceUntilIdle()
            verify(exactly = 1) { hub.bind("7", pc.id, CONTROLLER_TYPE_PLAYSTATION) }
        }
}
