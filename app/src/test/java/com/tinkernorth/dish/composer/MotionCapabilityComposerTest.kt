// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.composer

import com.tinkernorth.dish.architecture.testing.composerTest
import com.tinkernorth.dish.architecture.testing.probe
import com.tinkernorth.dish.hotpath.input.PhysicalGamepadRegistry
import com.tinkernorth.dish.source.sensor.PhoneMotionAvailability
import com.tinkernorth.dish.ui.main.VIRTUAL_SLOT_ID
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [MotionCapabilityComposer] — the per-slot
 * `MotionCapability` map that drives the `CAP_MOTION` bit at registration
 * time, the sensor-listener gating, and the on-screen pill.
 *
 * Headline regressions pinned here:
 *
 *  - The virtual slot is *always present in the map* (key
 *    [VIRTUAL_SLOT_ID]), regardless of whether any physical pad is attached
 *    or any satellite is up. This is the bit the overlay reads at construct
 *    time; if it weren't there, the pill would flicker UNAVAILABLE until the
 *    first hub emission.
 *  - `hasGyro` for a physical slot comes from
 *    [PhysicalGamepadRegistry.Device.hasGyro] verbatim — not re-probed by
 *    the composer (the probe is at device-add time, not on the hot
 *    capability-flow path).
 *  - `carriesOnConnection` is `kind == SATELLITE && live == Connected`,
 *    nothing else. Bluetooth-HID, Connecting, Unstable all resolve to false.
 *  - `toCapBits()` does *not* gate on `carriesOnConnection` — a satellite
 *    re-connect must not require a re-handshake to recover motion.
 */
class MotionCapabilityComposerTest {
    private fun summary(
        id: String,
        kind: ConnectionKind = ConnectionKind.SATELLITE,
        live: LinkState = LinkState.Connected,
    ) = ConnectionSummary(
        id = id,
        kind = kind,
        label = id,
        detail = "",
        live = live,
        boundSlotIds = emptyList(),
    )

    private fun device(
        id: Int,
        hasGyro: Boolean,
    ) = PhysicalGamepadRegistry.Device(id, "Pad-$id", hasGyro = hasGyro)

    /** Build a composer with mockable upstreams. */
    private fun composerFor(
        phoneAvailable: MutableStateFlow<Boolean>,
        devices: MutableStateFlow<Map<Int, PhysicalGamepadRegistry.Device>>,
        bindings: MutableStateFlow<Map<String, String>>,
        connections: MutableStateFlow<List<ConnectionSummary>>,
        scope: CoroutineScope,
    ): MotionCapabilityComposer {
        val availability: PhoneMotionAvailability =
            mockk { every { state } returns phoneAvailable }
        val registry: PhysicalGamepadRegistry =
            mockk { every { this@mockk.devices } returns devices }
        val hub: ConnectionHub =
            mockk {
                every { this@mockk.bindings } returns bindings
                every { this@mockk.connections } returns connections
            }
        return MotionCapabilityComposer(availability, registry, hub, scope)
    }

    // ── Pure data-class invariants ──────────────────────────────────────

    @Test
    fun `effective requires every axis true`() {
        // All-true is the only effective state. The three booleans are
        // orthogonal — exhaustively check the false-leaning cases.
        assertTrue(
            MotionCapability(hasGyro = true, carriesOnConnection = true, userEnabled = true).effective,
        )
        assertFalse(
            MotionCapability(hasGyro = false, carriesOnConnection = true, userEnabled = true).effective,
        )
        assertFalse(
            MotionCapability(hasGyro = true, carriesOnConnection = false, userEnabled = true).effective,
        )
        assertFalse(
            MotionCapability(hasGyro = true, carriesOnConnection = true, userEnabled = false).effective,
        )
    }

    @Test
    fun `toCapBits returns CAP_MOTION when the dish CAN emit motion`() {
        // The CAP bit describes the dish's *capability*, not the live link.
        // A connected, gyro-equipped, user-enabled slot ⇒ CAP_MOTION.
        val cap = MotionCapability(hasGyro = true, carriesOnConnection = true, userEnabled = true)
        assertEquals(MotionCapability.CAP_MOTION_BIT, cap.toCapBits())
    }

    @Test
    fun `toCapBits ignores carriesOnConnection — link-down is not a capability change`() {
        // A satellite that is momentarily Connecting / Unstable must not flip
        // the cap bit — otherwise every reconnect would force an unregister /
        // re-register round trip. The cap bit only depends on hasGyro AND
        // userEnabled.
        val cap = MotionCapability(hasGyro = true, carriesOnConnection = false, userEnabled = true)
        assertEquals(MotionCapability.CAP_MOTION_BIT, cap.toCapBits())
    }

    @Test
    fun `toCapBits is zero when the user has disabled motion`() {
        // Flipping the user toggle off is a real capability change — the
        // dish stops emitting motion, so it should stop advertising CAP_MOTION.
        val cap = MotionCapability(hasGyro = true, carriesOnConnection = true, userEnabled = false)
        assertEquals(0, cap.toCapBits())
    }

    @Test
    fun `toCapBits is zero on hardware without a gyro — even if the user enabled it`() {
        // No gyro means no motion, period; never lie to the receiver.
        val cap = MotionCapability(hasGyro = false, carriesOnConnection = true, userEnabled = true)
        assertEquals(0, cap.toCapBits())
    }

    // ── Composer derivation ──────────────────────────────────────────────

    @Test
    fun `virtual slot is always present in the map`() =
        composerTest {
            // No physical pads, no satellite — the touch overlay still has a
            // capability entry to read at construct time. This is the pill's
            // first-paint contract.
            val phoneAvail = MutableStateFlow(false)
            val devices = MutableStateFlow<Map<Int, PhysicalGamepadRegistry.Device>>(emptyMap())
            val bindings = MutableStateFlow<Map<String, String>>(emptyMap())
            val conns = MutableStateFlow<List<ConnectionSummary>>(emptyList())

            val probe = composerFor(phoneAvail, devices, bindings, conns, backgroundScope).probe(this)
            testScheduler.runCurrent()

            val virtual = probe.latest[VIRTUAL_SLOT_ID]
            assertEquals(
                MotionCapability(hasGyro = false, carriesOnConnection = false, userEnabled = true),
                virtual,
            )
        }

    @Test
    fun `virtual slot hasGyro mirrors PhoneMotionAvailability`() =
        composerTest {
            val phoneAvail = MutableStateFlow(true)
            val devices = MutableStateFlow<Map<Int, PhysicalGamepadRegistry.Device>>(emptyMap())
            val bindings = MutableStateFlow<Map<String, String>>(emptyMap())
            val conns = MutableStateFlow<List<ConnectionSummary>>(emptyList())

            val probe = composerFor(phoneAvail, devices, bindings, conns, backgroundScope).probe(this)
            testScheduler.runCurrent()
            assertEquals(true, probe.latest[VIRTUAL_SLOT_ID]?.hasGyro)

            // Flip the source's value — the composer must re-derive.
            phoneAvail.value = false
            testScheduler.runCurrent()
            assertEquals(false, probe.latest[VIRTUAL_SLOT_ID]?.hasGyro)
        }

    @Test
    fun `virtual slot carriesOnConnection is true when bound to a Connected satellite`() =
        composerTest {
            val phoneAvail = MutableStateFlow(true)
            val devices = MutableStateFlow<Map<Int, PhysicalGamepadRegistry.Device>>(emptyMap())
            val bindings = MutableStateFlow(mapOf(VIRTUAL_SLOT_ID to "sat-A"))
            val conns = MutableStateFlow(listOf(summary("sat-A")))

            val probe = composerFor(phoneAvail, devices, bindings, conns, backgroundScope).probe(this)
            testScheduler.runCurrent()
            assertTrue(probe.latest[VIRTUAL_SLOT_ID]?.carriesOnConnection == true)
        }

    @Test
    fun `virtual slot carriesOnConnection is false on a Bluetooth-HID binding`() =
        composerTest {
            // BT-HID has no MSG_MOTION channel, so the pill must read
            // NOT_FORWARDED. The composer's carriesOnConnection is the truth
            // table's gate.
            val phoneAvail = MutableStateFlow(true)
            val devices = MutableStateFlow<Map<Int, PhysicalGamepadRegistry.Device>>(emptyMap())
            val bindings = MutableStateFlow(mapOf(VIRTUAL_SLOT_ID to "bt-A"))
            val conns = MutableStateFlow(listOf(summary("bt-A", kind = ConnectionKind.BLUETOOTH)))

            val probe = composerFor(phoneAvail, devices, bindings, conns, backgroundScope).probe(this)
            testScheduler.runCurrent()
            assertFalse(probe.latest[VIRTUAL_SLOT_ID]?.carriesOnConnection == true)
        }

    @Test
    fun `virtual slot carriesOnConnection is false while still Connecting`() =
        composerTest {
            // A satellite that hasn't reached Connected yet can't sink
            // motion. The cap bit doesn't gate on this; the pill does.
            val phoneAvail = MutableStateFlow(true)
            val devices = MutableStateFlow<Map<Int, PhysicalGamepadRegistry.Device>>(emptyMap())
            val bindings = MutableStateFlow(mapOf(VIRTUAL_SLOT_ID to "sat-A"))
            val conns = MutableStateFlow(listOf(summary("sat-A", live = LinkState.Connecting)))

            val probe = composerFor(phoneAvail, devices, bindings, conns, backgroundScope).probe(this)
            testScheduler.runCurrent()
            assertFalse(probe.latest[VIRTUAL_SLOT_ID]?.carriesOnConnection == true)
        }

    @Test
    fun `physical slot uses Device hasGyro verbatim — no re-probe`() =
        composerTest {
            // The composer never calls PhysicalMotionProbe itself; the truth
            // is whatever the registry recorded at add time. This pin
            // catches a regression where someone "helpfully" re-probes on
            // every emission and slows the hot capability-flow path.
            val phoneAvail = MutableStateFlow(false)
            val devices =
                MutableStateFlow(
                    mapOf(
                        9 to device(9, hasGyro = true),
                        11 to device(11, hasGyro = false),
                    ),
                )
            val bindings = MutableStateFlow<Map<String, String>>(emptyMap())
            val conns = MutableStateFlow<List<ConnectionSummary>>(emptyList())

            val probe = composerFor(phoneAvail, devices, bindings, conns, backgroundScope).probe(this)
            testScheduler.runCurrent()
            assertEquals(true, probe.latest["9"]?.hasGyro)
            assertEquals(false, probe.latest["11"]?.hasGyro)
        }

    @Test
    fun `physical slot drops from the map when the device leaves the registry`() =
        composerTest {
            // The registry's disconnect grace removes Devices from its flow
            // when the grace expires. The composer must drop the slot too,
            // not stash a stale "still capable" entry.
            val phoneAvail = MutableStateFlow(false)
            val devices = MutableStateFlow(mapOf(9 to device(9, hasGyro = true)))
            val bindings = MutableStateFlow<Map<String, String>>(emptyMap())
            val conns = MutableStateFlow<List<ConnectionSummary>>(emptyList())

            val probe = composerFor(phoneAvail, devices, bindings, conns, backgroundScope).probe(this)
            testScheduler.runCurrent()
            assertEquals(true, probe.latest["9"]?.hasGyro)

            devices.value = emptyMap()
            testScheduler.runCurrent()
            assertNull(probe.latest["9"])
        }

    @Test
    fun `the map re-emits when the routing flips kind from satellite to bluetooth`() =
        composerTest {
            // Mid-game the user changes a slot from satellite to BT-HID. The
            // capability for that slot must flip carriesOnConnection from
            // true → false on the next emission.
            val phoneAvail = MutableStateFlow(true)
            val devices = MutableStateFlow(mapOf(9 to device(9, hasGyro = true)))
            val bindings = MutableStateFlow(mapOf("9" to "sat-A"))
            val conns = MutableStateFlow(listOf(summary("sat-A")))

            val probe = composerFor(phoneAvail, devices, bindings, conns, backgroundScope).probe(this)
            testScheduler.runCurrent()
            assertTrue(probe.latest["9"]?.carriesOnConnection == true)

            bindings.value = mapOf("9" to "bt-A")
            conns.value = listOf(summary("bt-A", kind = ConnectionKind.BLUETOOTH))
            testScheduler.runCurrent()
            assertFalse(probe.latest["9"]?.carriesOnConnection == true)
        }
}
