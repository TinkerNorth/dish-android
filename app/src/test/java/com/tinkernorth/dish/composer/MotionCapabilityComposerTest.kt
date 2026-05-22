// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.composer

import com.tinkernorth.dish.architecture.testing.composerTest
import com.tinkernorth.dish.architecture.testing.probe
import com.tinkernorth.dish.hotpath.input.PhysicalGamepadRegistry
import com.tinkernorth.dish.source.sensor.PhoneMotionAvailability
import com.tinkernorth.dish.source.store.MotionEnabledStore
import com.tinkernorth.dish.source.store.SatelliteMotionBackendStatus
import com.tinkernorth.dish.source.store.SatelliteMotionBackendStatusStore
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
        satelliteControllerTypes: Map<String, Int> = emptyMap(),
    ) = ConnectionSummary(
        id = id,
        kind = kind,
        label = id,
        detail = "",
        live = live,
        boundSlotIds = emptyList(),
        satelliteControllerTypes = satelliteControllerTypes,
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
        motionEnabled: MutableStateFlow<Map<String, Boolean>> = MutableStateFlow(emptyMap()),
        satelliteBackendStatus: MutableStateFlow<Map<Pair<String, String>, SatelliteMotionBackendStatus>> =
            MutableStateFlow(emptyMap()),
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
        val store: MotionEnabledStore =
            mockk { every { state } returns motionEnabled }
        val backendStatusStore: SatelliteMotionBackendStatusStore =
            mockk { every { state } returns satelliteBackendStatus }
        return MotionCapabilityComposer(availability, registry, hub, store, backendStatusStore, scope)
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
    fun `userEnabled defaults to true for an unwritten slot`() =
        composerTest {
            // The store collapses an absent key onto DEFAULT_ENABLED = true.
            // The composer must respect the same default — otherwise a fresh
            // install would silently advertise CAP_MOTION = 0 for every slot
            // until the user toggled each one. Pin the default through the
            // composer's userEnabled field.
            val phoneAvail = MutableStateFlow(true)
            val devices = MutableStateFlow<Map<Int, PhysicalGamepadRegistry.Device>>(emptyMap())
            val bindings = MutableStateFlow<Map<String, String>>(emptyMap())
            val conns = MutableStateFlow<List<ConnectionSummary>>(emptyList())
            val motionEnabled = MutableStateFlow<Map<String, Boolean>>(emptyMap())

            val probe =
                composerFor(phoneAvail, devices, bindings, conns, backgroundScope, motionEnabled).probe(this)
            testScheduler.runCurrent()

            assertEquals(true, probe.latest[VIRTUAL_SLOT_ID]?.userEnabled)
        }

    @Test
    fun `userEnabled flips when MotionEnabledStore writes false for a slot`() =
        composerTest {
            // The toggle is the user-visible switch — flipping it must
            // immediately propagate to the composer (and from there to
            // SatelliteConnection's cap bit and the listener gate). A
            // regression where the store write doesn't reach the composer
            // would make the toggle look broken in the UI.
            val phoneAvail = MutableStateFlow(true)
            val devices = MutableStateFlow<Map<Int, PhysicalGamepadRegistry.Device>>(emptyMap())
            val bindings = MutableStateFlow<Map<String, String>>(emptyMap())
            val conns = MutableStateFlow<List<ConnectionSummary>>(emptyList())
            val motionEnabled = MutableStateFlow<Map<String, Boolean>>(emptyMap())

            val composer =
                composerFor(phoneAvail, devices, bindings, conns, backgroundScope, motionEnabled)
            val probe = composer.probe(this)
            testScheduler.runCurrent()
            assertTrue(probe.latest[VIRTUAL_SLOT_ID]?.userEnabled == true)

            motionEnabled.value = mapOf(VIRTUAL_SLOT_ID to false)
            testScheduler.runCurrent()
            assertFalse(probe.latest[VIRTUAL_SLOT_ID]?.userEnabled == true)
        }

    @Test
    fun `toCapBits is zero on a slot the user has disabled — even if hasGyro is true`() =
        composerTest {
            // End-to-end pin: phone has gyro, satellite is up, but the user
            // toggled motion off — the cap bit on the wire must be zero so
            // the receiver's web UI is honest about which slots stream motion.
            val phoneAvail = MutableStateFlow(true)
            val devices = MutableStateFlow<Map<Int, PhysicalGamepadRegistry.Device>>(emptyMap())
            val bindings = MutableStateFlow(mapOf(VIRTUAL_SLOT_ID to "sat-A"))
            val conns = MutableStateFlow(listOf(summary("sat-A")))
            val motionEnabled = MutableStateFlow(mapOf(VIRTUAL_SLOT_ID to false))

            val probe =
                composerFor(phoneAvail, devices, bindings, conns, backgroundScope, motionEnabled).probe(this)
            testScheduler.runCurrent()

            assertEquals(0, probe.latest[VIRTUAL_SLOT_ID]?.toCapBits())
        }

    @Test
    fun `capabilityFor returns the latest derived value for a slot`() =
        composerTest {
            // SatelliteConnection.registerController is synchronous on its IO
            // dispatcher; the composer's state.collect would be awkward there.
            // capabilityFor is the synchronous accessor — pin that it returns
            // the latest derived value, not stale Initial.
            val phoneAvail = MutableStateFlow(true)
            val devices = MutableStateFlow<Map<Int, PhysicalGamepadRegistry.Device>>(emptyMap())
            val bindings = MutableStateFlow(mapOf(VIRTUAL_SLOT_ID to "sat-A"))
            val conns = MutableStateFlow(listOf(summary("sat-A")))

            val composer =
                composerFor(phoneAvail, devices, bindings, conns, backgroundScope)
            composer.probe(this)
            testScheduler.runCurrent()

            val cap = composer.capabilityFor(VIRTUAL_SLOT_ID)
            assertTrue(cap.hasGyro)
            assertTrue(cap.carriesOnConnection)
            assertEquals(MotionCapability.CAP_MOTION_BIT, cap.toCapBits())
        }

    @Test
    fun `capabilityFor returns Off for a slot not in the map`() =
        composerTest {
            // A controller-add for an unknown slot (race with registry
            // disconnect) must not NPE — capabilityFor returns Off so the
            // cap word is 0, which is correct: we don't know that slot has
            // motion, so don't advertise it.
            val phoneAvail = MutableStateFlow(true)
            val devices = MutableStateFlow<Map<Int, PhysicalGamepadRegistry.Device>>(emptyMap())
            val bindings = MutableStateFlow<Map<String, String>>(emptyMap())
            val conns = MutableStateFlow<List<ConnectionSummary>>(emptyList())

            val composer = composerFor(phoneAvail, devices, bindings, conns, backgroundScope)
            composer.probe(this)
            testScheduler.runCurrent()

            assertEquals(MotionCapability.Off, composer.capabilityFor("ghost-slot"))
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

    // ── hostHasSinkForType — the B.3 per-type sink heuristic ───────────────

    @Test
    fun `hostHasSinkForType is true for a PlayStation-typed satellite slot`() =
        composerTest {
            // PS-typed slot → DS4 emulation on Windows ViGEm / Linux uinput,
            // both of which carry gyro/accel. Pin the positive case so a
            // regression that flips the polarity (or accidentally returns
            // false for all types) would fail.
            val phoneAvail = MutableStateFlow(true)
            val devices = MutableStateFlow<Map<Int, PhysicalGamepadRegistry.Device>>(emptyMap())
            val bindings = MutableStateFlow(mapOf(VIRTUAL_SLOT_ID to "sat-A"))
            val conns =
                MutableStateFlow(
                    listOf(
                        summary(
                            "sat-A",
                            satelliteControllerTypes = mapOf(VIRTUAL_SLOT_ID to CONTROLLER_TYPE_PLAYSTATION),
                        ),
                    ),
                )
            val probe = composerFor(phoneAvail, devices, bindings, conns, backgroundScope).probe(this)
            testScheduler.runCurrent()

            assertEquals(true, probe.latest[VIRTUAL_SLOT_ID]?.hostHasSinkForType)
        }

    @Test
    fun `hostHasSinkForType is false for an Xbox-typed satellite slot — the headline B3 case`() =
        composerTest {
            // The headline B.3 deliverable: Xbox-typed virtual pads have no
            // IMU surface on any backend (XInput / XUSB_REPORT has no gyro
            // fields). The dish needs to know this so the pill can warn the
            // user up front instead of "streaming" while the bytes silently
            // disappear at the receiver's virtual-gamepad layer.
            val phoneAvail = MutableStateFlow(true)
            val devices = MutableStateFlow<Map<Int, PhysicalGamepadRegistry.Device>>(emptyMap())
            val bindings = MutableStateFlow(mapOf(VIRTUAL_SLOT_ID to "sat-A"))
            val conns =
                MutableStateFlow(
                    listOf(
                        summary(
                            "sat-A",
                            satelliteControllerTypes = mapOf(VIRTUAL_SLOT_ID to CONTROLLER_TYPE_XBOX),
                        ),
                    ),
                )
            val probe = composerFor(phoneAvail, devices, bindings, conns, backgroundScope).probe(this)
            testScheduler.runCurrent()

            assertEquals(false, probe.latest[VIRTUAL_SLOT_ID]?.hostHasSinkForType)
        }

    @Test
    fun `hostHasSinkForType flips when the user changes the slot type mid-session`() =
        composerTest {
            // The toggle on the controller row (Xbox ↔ PS) must propagate to
            // the composer the moment the ConnectionSummary re-emits. Without
            // this, the pill stays on STREAMING after a user swaps to Xbox
            // and motion silently stops working without a warning.
            val phoneAvail = MutableStateFlow(true)
            val devices = MutableStateFlow<Map<Int, PhysicalGamepadRegistry.Device>>(emptyMap())
            val bindings = MutableStateFlow(mapOf(VIRTUAL_SLOT_ID to "sat-A"))
            val conns =
                MutableStateFlow(
                    listOf(
                        summary(
                            "sat-A",
                            satelliteControllerTypes = mapOf(VIRTUAL_SLOT_ID to CONTROLLER_TYPE_PLAYSTATION),
                        ),
                    ),
                )

            val probe = composerFor(phoneAvail, devices, bindings, conns, backgroundScope).probe(this)
            testScheduler.runCurrent()
            assertEquals(true, probe.latest[VIRTUAL_SLOT_ID]?.hostHasSinkForType)

            // User flips Xbox.
            conns.value =
                listOf(
                    summary(
                        "sat-A",
                        satelliteControllerTypes = mapOf(VIRTUAL_SLOT_ID to CONTROLLER_TYPE_XBOX),
                    ),
                )
            testScheduler.runCurrent()
            assertEquals(false, probe.latest[VIRTUAL_SLOT_ID]?.hostHasSinkForType)
        }

    @Test
    fun `hostHasSinkForType is true for a slot with unknown type — no false warnings`() =
        composerTest {
            // ConnectionSummary.satelliteControllerTypes is empty until the
            // user has explicitly chosen a type, OR for a connection that
            // hasn't fully resolved yet. Returning false in that window
            // would flash a "no host sink" warning that goes away on its
            // own — bad UX. Conservatively assume yes.
            val phoneAvail = MutableStateFlow(true)
            val devices = MutableStateFlow<Map<Int, PhysicalGamepadRegistry.Device>>(emptyMap())
            val bindings = MutableStateFlow(mapOf(VIRTUAL_SLOT_ID to "sat-A"))
            val conns = MutableStateFlow(listOf(summary("sat-A"))) // empty satelliteControllerTypes
            val probe = composerFor(phoneAvail, devices, bindings, conns, backgroundScope).probe(this)
            testScheduler.runCurrent()

            assertEquals(true, probe.latest[VIRTUAL_SLOT_ID]?.hostHasSinkForType)
        }

    @Test
    fun `hostHasSinkForType is true for a Bluetooth-HID-bound slot — limit is connection kind`() =
        composerTest {
            // BT-HID is handled by NOT_FORWARDED, which has higher
            // precedence in the pill. The composer's hostHasSinkForType
            // returns true to avoid spurious NO_HOST_SINK noise stacked on
            // top of the connection-kind limit.
            val phoneAvail = MutableStateFlow(true)
            val devices = MutableStateFlow<Map<Int, PhysicalGamepadRegistry.Device>>(emptyMap())
            val bindings = MutableStateFlow(mapOf(VIRTUAL_SLOT_ID to "bt-A"))
            val conns =
                MutableStateFlow(
                    listOf(
                        summary(
                            "bt-A",
                            kind = ConnectionKind.BLUETOOTH,
                            satelliteControllerTypes = mapOf(VIRTUAL_SLOT_ID to CONTROLLER_TYPE_XBOX),
                        ),
                    ),
                )
            val probe = composerFor(phoneAvail, devices, bindings, conns, backgroundScope).probe(this)
            testScheduler.runCurrent()

            assertEquals(true, probe.latest[VIRTUAL_SLOT_ID]?.hostHasSinkForType)
        }

    // ── satelliteBackendStatus — receiver-truth threading ──────────────────

    @Test
    fun `satelliteBackendStatus is null when no observation has landed`() =
        composerTest {
            // Bound to a satellite but the store has no entry yet — the
            // composer must surface null (unknown), NOT a default value.
            // Anything else would mis-read a pre-extension satellite or a
            // pre-registration moment as a positive observation.
            val phoneAvail = MutableStateFlow(true)
            val devices = MutableStateFlow<Map<Int, PhysicalGamepadRegistry.Device>>(emptyMap())
            val bindings = MutableStateFlow(mapOf(VIRTUAL_SLOT_ID to "sat-A"))
            val conns = MutableStateFlow(listOf(summary("sat-A")))
            val probe = composerFor(phoneAvail, devices, bindings, conns, backgroundScope).probe(this)
            testScheduler.runCurrent()

            assertNull(probe.latest[VIRTUAL_SLOT_ID]?.satelliteBackendStatus)
        }

    @Test
    fun `satelliteBackendStatus propagates from the store for the bound connection`() =
        composerTest {
            val phoneAvail = MutableStateFlow(true)
            val devices = MutableStateFlow<Map<Int, PhysicalGamepadRegistry.Device>>(emptyMap())
            val bindings = MutableStateFlow(mapOf(VIRTUAL_SLOT_ID to "sat-A"))
            val conns = MutableStateFlow(listOf(summary("sat-A")))
            val status = SatelliteMotionBackendStatus(sinkSupportedForType = true, backendOk = false)
            val backendStatus =
                MutableStateFlow<Map<Pair<String, String>, SatelliteMotionBackendStatus>>(
                    mapOf(("sat-A" to VIRTUAL_SLOT_ID) to status),
                )
            val probe =
                composerFor(
                    phoneAvail,
                    devices,
                    bindings,
                    conns,
                    backgroundScope,
                    satelliteBackendStatus = backendStatus,
                ).probe(this)
            testScheduler.runCurrent()

            assertEquals(status, probe.latest[VIRTUAL_SLOT_ID]?.satelliteBackendStatus)
        }

    @Test
    fun `satelliteBackendStatus is keyed on the bound connection — wrong-connection entries are ignored`() =
        composerTest {
            // The slot is bound to "sat-A" but the store also carries an
            // (unrelated) status under "sat-B". A leaky implementation
            // could collapse on slotId alone and pick up the wrong entry —
            // pin that the composer only reads the bound connection's
            // entry.
            val phoneAvail = MutableStateFlow(true)
            val devices = MutableStateFlow<Map<Int, PhysicalGamepadRegistry.Device>>(emptyMap())
            val bindings = MutableStateFlow(mapOf(VIRTUAL_SLOT_ID to "sat-A"))
            val conns = MutableStateFlow(listOf(summary("sat-A")))
            val wrongStatus = SatelliteMotionBackendStatus(sinkSupportedForType = false, backendOk = false)
            val backendStatus =
                MutableStateFlow<Map<Pair<String, String>, SatelliteMotionBackendStatus>>(
                    mapOf(("sat-B" to VIRTUAL_SLOT_ID) to wrongStatus),
                )
            val probe =
                composerFor(
                    phoneAvail,
                    devices,
                    bindings,
                    conns,
                    backgroundScope,
                    satelliteBackendStatus = backendStatus,
                ).probe(this)
            testScheduler.runCurrent()

            assertNull(probe.latest[VIRTUAL_SLOT_ID]?.satelliteBackendStatus)
        }

    @Test
    fun `satelliteBackendStatus updates reactively when the store re-emits`() =
        composerTest {
            // The pill needs to repaint when the receiver's truth flips.
            // Simulate a backend that initially reports broken, then the
            // operator fixes /dev/uinput and a re-registration succeeds —
            // the new status flows through the composer.
            val phoneAvail = MutableStateFlow(true)
            val devices = MutableStateFlow<Map<Int, PhysicalGamepadRegistry.Device>>(emptyMap())
            val bindings = MutableStateFlow(mapOf(VIRTUAL_SLOT_ID to "sat-A"))
            val conns = MutableStateFlow(listOf(summary("sat-A")))
            val broken = SatelliteMotionBackendStatus(sinkSupportedForType = true, backendOk = false)
            val fixed = SatelliteMotionBackendStatus(sinkSupportedForType = true, backendOk = true)
            val backendStatus =
                MutableStateFlow<Map<Pair<String, String>, SatelliteMotionBackendStatus>>(
                    mapOf(("sat-A" to VIRTUAL_SLOT_ID) to broken),
                )
            val probe =
                composerFor(
                    phoneAvail,
                    devices,
                    bindings,
                    conns,
                    backgroundScope,
                    satelliteBackendStatus = backendStatus,
                ).probe(this)
            testScheduler.runCurrent()
            assertEquals(broken, probe.latest[VIRTUAL_SLOT_ID]?.satelliteBackendStatus)

            backendStatus.value = mapOf(("sat-A" to VIRTUAL_SLOT_ID) to fixed)
            testScheduler.runCurrent()
            assertEquals(fixed, probe.latest[VIRTUAL_SLOT_ID]?.satelliteBackendStatus)
        }

    @Test
    fun `toCapBits is unaffected by satelliteBackendStatus — dish honesty is independent of receiver health`() {
        // Even if the receiver tells us its sink is broken, the dish is
        // still capable of streaming motion (the bytes will just not
        // land). toCapBits must keep advertising CAP_MOTION so that a
        // satellite that recovers (operator fixes /dev/uinput) doesn't
        // require a re-handshake to get motion flowing again.
        val cap =
            MotionCapability(
                hasGyro = true,
                carriesOnConnection = true,
                userEnabled = true,
                satelliteBackendStatus =
                    SatelliteMotionBackendStatus(sinkSupportedForType = true, backendOk = false),
            )
        assertEquals(MotionCapability.CAP_MOTION_BIT, cap.toCapBits())
    }

    @Test
    fun `effective is unaffected by satelliteBackendStatus — local listener gating doesn't change`() {
        // effective is the dish's own gate: "should I capture and stream
        // gyro samples?" That's a dish-local question — the receiver's
        // sink health is irrelevant. The listener stays on so a recovered
        // receiver picks up bytes immediately.
        val cap =
            MotionCapability(
                hasGyro = true,
                carriesOnConnection = true,
                userEnabled = true,
                satelliteBackendStatus =
                    SatelliteMotionBackendStatus(sinkSupportedForType = true, backendOk = false),
            )
        assertTrue(cap.effective)
    }
}
