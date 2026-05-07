// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.data.network

import android.bluetooth.BluetoothClass
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Behavior tests for [BondedHostsRepo]. The repo is snapshot-based so each
 * test arranges a [FakeBluetoothEnvironment], exercises the public surface,
 * then asserts on `repo.state.value`. No coroutines needed — the StateFlow is
 * updated synchronously.
 */
class BondedHostsRepoTest {
    private val store: ConnectionStore = mockk(relaxed = true)
    private lateinit var env: FakeBluetoothEnvironment
    private lateinit var repo: BondedHostsRepo

    @Before
    fun setUp() {
        env = FakeBluetoothEnvironment()
        every { store.rememberedBt() } returns emptyList()
        repo = BondedHostsRepo(env, store)
    }

    // ── Permission gate ──────────────────────────────────────────────────

    @Test
    fun `denied permission produces an empty list with permission=DENIED`() {
        env.hasPermission = false
        env.bonded = listOf(snap("AA", "PC", BluetoothClass.Device.Major.COMPUTER))
        repo.refresh()

        val state = repo.state.value
        assertEquals(BondedHostsRepo.Permission.DENIED, state.permission)
        assertTrue(state.all.isEmpty())
        assertTrue(state.visible.isEmpty())
    }

    @Test
    fun `granting permission and refreshing surfaces the bonded list`() {
        env.hasPermission = false
        env.bonded = listOf(snap("AA", "PC", BluetoothClass.Device.Major.COMPUTER))
        repo.refresh()
        assertEquals(BondedHostsRepo.Permission.DENIED, repo.state.value.permission)

        env.hasPermission = true
        repo.refresh()

        assertEquals(BondedHostsRepo.Permission.GRANTED, repo.state.value.permission)
        assertEquals(
            listOf("AA"),
            repo.state.value.all
                .map { it.mac },
        )
    }

    // ── Filtering ────────────────────────────────────────────────────────

    @Test
    fun `excluded accessory majors are dropped even with show all on`() {
        env.bonded =
            listOf(
                snap("PERI", "Mouse", BluetoothClass.Device.Major.PERIPHERAL),
                snap("WEAR", "Watch", BluetoothClass.Device.Major.WEARABLE),
                snap("TOY", "Toy", BluetoothClass.Device.Major.TOY),
                snap("HEALTH", "Scale", BluetoothClass.Device.Major.HEALTH),
                snap("PC", "MacBook", BluetoothClass.Device.Major.COMPUTER),
            )
        repo.setShowAll(true)

        val macs =
            repo.state.value.all
                .map { it.mac }
                .toSet()
        assertFalse("PERI" in macs)
        assertFalse("WEAR" in macs)
        assertFalse("TOY" in macs)
        assertFalse("HEALTH" in macs)
        assertTrue("PC" in macs)
    }

    @Test
    fun `default tier hides OTHER but show-all surfaces them`() {
        env.bonded =
            listOf(
                snap("PC", "PC", BluetoothClass.Device.Major.COMPUTER),
                snap("CONSOLE", "PS5", BluetoothClass.Device.Major.AUDIO_VIDEO),
                snap("MISC", "Mystery", BluetoothClass.Device.Major.UNCATEGORIZED),
            )

        repo.refresh()
        assertEquals(
            setOf("PC", "CONSOLE"),
            repo.state.value.visible
                .map { it.mac }
                .toSet(),
        )

        repo.setShowAll(true)
        assertEquals(
            setOf("PC", "CONSOLE", "MISC"),
            repo.state.value.visible
                .map { it.mac }
                .toSet(),
        )
    }

    @Test
    fun `setShowAll is idempotent — toggling to the current value does not rebuild`() {
        env.bonded = listOf(snap("PC", "PC", BluetoothClass.Device.Major.COMPUTER))
        repo.refresh()
        val before = repo.state.value
        repo.setShowAll(false)
        // Same instance because no new snapshot was taken.
        assertTrue(before === repo.state.value)
    }

    // ── Remembered exclusion ─────────────────────────────────────────────

    @Test
    fun `devices already in rememberedBt are not surfaced`() {
        every { store.rememberedBt() } returns
            listOf(
                RememberedBt(id = "bt:AA", name = "Already known", mac = "AA", profileName = "Xbox"),
            )
        env.bonded =
            listOf(
                snap("AA", "Already known", BluetoothClass.Device.Major.COMPUTER),
                snap("BB", "New PC", BluetoothClass.Device.Major.COMPUTER),
            )
        repo.refresh()

        assertEquals(
            listOf("BB"),
            repo.state.value.all
                .map { it.mac },
        )
    }

    // ── Naming ───────────────────────────────────────────────────────────

    @Test
    fun `null name falls back to MAC`() {
        env.bonded = listOf(snap("AA:BB", null, BluetoothClass.Device.Major.COMPUTER))
        repo.refresh()
        assertEquals(
            "AA:BB",
            repo.state.value.all
                .first()
                .name,
        )
    }

    @Test
    fun `blank name falls back to MAC`() {
        env.bonded = listOf(snap("AA:BB", "   ", BluetoothClass.Device.Major.COMPUTER))
        repo.refresh()
        assertEquals(
            "AA:BB",
            repo.state.value.all
                .first()
                .name,
        )
    }

    // ── Sorting / dedup ──────────────────────────────────────────────────

    @Test
    fun `OTHER kinds sort after host kinds, then by name`() {
        env.bonded =
            listOf(
                snap("Z", "Zeus", BluetoothClass.Device.Major.UNCATEGORIZED),
                snap("A", "alpha", BluetoothClass.Device.Major.COMPUTER),
                snap("B", "Bravo", BluetoothClass.Device.Major.AUDIO_VIDEO),
            )
        repo.setShowAll(true)

        assertEquals(
            listOf("A", "B", "Z"),
            repo.state.value.all
                .map { it.mac },
        )
    }

    @Test
    fun `duplicate MACs are de-duplicated`() {
        env.bonded =
            listOf(
                snap("AA", "First", BluetoothClass.Device.Major.COMPUTER),
                snap("AA", "Dupe", BluetoothClass.Device.Major.COMPUTER),
            )
        repo.refresh()

        assertEquals(1, repo.state.value.all.size)
    }

    // ── State construction ───────────────────────────────────────────────

    @Test
    fun `initial snapshot is taken on construction without any refresh`() {
        env.hasPermission = true
        env.bonded = listOf(snap("AA", "PC", BluetoothClass.Device.Major.COMPUTER))
        every { store.rememberedBt() } returns emptyList()

        val freshRepo = BondedHostsRepo(env, store)

        assertEquals(BondedHostsRepo.Permission.GRANTED, freshRepo.state.value.permission)
        assertEquals(1, freshRepo.state.value.all.size)
    }

    @Test
    fun `kind label survives into the BondedHost`() {
        env.bonded =
            listOf(
                snap("AA", "PS5", BluetoothClass.Device.Major.AUDIO_VIDEO),
                snap("BB", "PC", BluetoothClass.Device.Major.COMPUTER),
            )
        repo.refresh()

        val byMac =
            repo.state.value.all
                .associateBy { it.mac }
        assertEquals(BondedHostKind.CONSOLE, byMac["AA"]?.kind)
        assertEquals(BondedHostKind.COMPUTER, byMac["BB"]?.kind)
    }

    @Test
    fun `lookups against an empty bonded set still produce a valid state`() {
        env.bonded = emptyList()
        repo.refresh()
        val s = repo.state.value
        assertEquals(BondedHostsRepo.Permission.GRANTED, s.permission)
        assertTrue(s.all.isEmpty())
        assertTrue(s.visible.isEmpty())
        assertNull(s.all.firstOrNull())
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private fun snap(
        mac: String,
        name: String?,
        major: Int,
    ) = BondedDeviceSnapshot(mac = mac, name = name, majorClass = major, minorClass = 0)
}

private class FakeBluetoothEnvironment(
    var hasPermission: Boolean = true,
    var bonded: List<BondedDeviceSnapshot> = emptyList(),
    var connectedHidHosts: Set<String> = emptySet(),
) : BluetoothEnvironment {
    override fun hasConnectPermission(): Boolean = hasPermission

    override fun bondedDevices(): List<BondedDeviceSnapshot> = bonded

    override fun connectedHidHostMacs(): Set<String> = connectedHidHosts
}
