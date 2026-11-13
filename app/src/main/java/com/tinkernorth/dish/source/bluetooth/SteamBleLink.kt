// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.source.bluetooth

import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** The GATT client calls a Steam Controller link makes, behind a seam so the link runs on the JVM. */
internal interface SteamGatt {
    fun connect(events: SteamGattEvents): Boolean

    fun discoverServices(): Boolean

    // The Valve service's characteristics in the order the controller lists them; empty when it
    // serves none.
    fun valveCharacteristics(): List<UUID>

    fun requestMtu(mtu: Int): Boolean

    fun enableNotifications(characteristic: UUID): Boolean

    // One write, which SteamGattEvents.onWritten answers.
    fun write(
        characteristic: UUID,
        value: ByteArray,
    ): Boolean

    fun close()
}

/** What the GATT client tells the link, one callback at a time. */
internal interface SteamGattEvents {
    fun onConnected()

    fun onDisconnected()

    fun onServicesDiscovered(ok: Boolean)

    fun onMtuChanged()

    fun onNotificationsEnabled(ok: Boolean)

    fun onWritten(ok: Boolean)

    fun onNotification(
        characteristic: UUID,
        value: ByteArray,
    )
}

/** Where a claimed pad's writes go: one at a time, each answered before the next. */
internal fun interface SteamBleWriter {
    fun write(
        target: Int,
        value: ByteArray,
    ): Boolean
}

/** A Steam Controller's Direct claim: native's pad, and its card in the slot list. */
internal interface SteamBleClaims {
    // The pad's id, whose writes go to [writer]; NO_STEAM_BLE_PAD when native refused it.
    fun attach(
        model: SteamBleModel,
        writer: SteamBleWriter,
    ): Int

    // Takes the pad out of lizard mode and lists it; false when a write failed.
    fun start(
        padId: Int,
        model: SteamBleModel,
    ): Boolean

    fun report(
        padId: Int,
        value: ByteArray,
    )

    // [restore] gives the 2015 pad its stand-alone settings back first, over a link still up.
    fun detach(
        padId: Int,
        restore: Boolean,
    )
}

internal const val NO_STEAM_BLE_PAD = 0

// Long past a write's round trip on a live link; a write still unanswered then went nowhere.
private const val WRITE_ANSWER_TIMEOUT_MS = 1_000L

internal enum class SteamBleStage {
    CONNECTING,
    DISCOVERING,
    SIZING_MTU,
    SUBSCRIBING,
    CLAIMING,
    CLAIMED,
    ENDED,
}

/**
 * One Steam Controller on Bluetooth LE, as SDL's HIDDeviceBLESteamController brings it up: connect,
 * discover the Valve service, ask a 2026 controller for the large MTU, subscribe to the input
 * characteristic, then claim it as a Direct pad, whose notifications go to native from then on.
 * The claim and its end run on [work], one at a time and off the GATT callbacks, because their
 * writes wait for answers those callbacks bring. Every stage moves by compare-and-set: a release
 * from the main thread can land between two callbacks.
 */
internal class SteamBleLink(
    private val gatt: SteamGatt,
    private val claims: SteamBleClaims,
    private val work: Executor,
) : SteamGattEvents {
    private val stageNow = AtomicReference(SteamBleStage.CONNECTING)

    @Volatile private var model: SteamBleModel? = null

    @Volatile private var padId = NO_STEAM_BLE_PAD

    @Volatile private var pendingWrite: WriteAnswer? = null

    private class WriteAnswer {
        val answered = CountDownLatch(1)

        @Volatile var ok = false
    }

    val stage: SteamBleStage get() = stageNow.get()

    val isLive: Boolean get() = stage != SteamBleStage.ENDED

    fun start(): Boolean {
        val connecting = gatt.connect(this)
        if (!connecting) endLink()
        return connecting
    }

    override fun onConnected() {
        if (!advance(SteamBleStage.CONNECTING, SteamBleStage.DISCOVERING)) return
        if (!gatt.discoverServices()) end()
    }

    override fun onServicesDiscovered(ok: Boolean) {
        if (stage != SteamBleStage.DISCOVERING) return
        val found = if (ok) steamBleModelOf(gatt.valveCharacteristics()) else null
        if (found == null) return end()
        model = found
        if (found.needsLargeMtu) sizeMtu() else subscribe(found, SteamBleStage.DISCOVERING)
    }

    // SDL subscribes whatever the MTU came to.
    override fun onMtuChanged() {
        val sized = model ?: return
        subscribe(sized, SteamBleStage.SIZING_MTU)
    }

    override fun onNotificationsEnabled(ok: Boolean) {
        if (stage != SteamBleStage.SUBSCRIBING) return
        if (!ok) return end()
        if (advance(SteamBleStage.SUBSCRIBING, SteamBleStage.CLAIMING)) work.execute(::claim)
    }

    override fun onNotification(
        characteristic: UUID,
        value: ByteArray,
    ) {
        val isTheInput = characteristic == model?.input
        if (stage == SteamBleStage.CLAIMED && isTheInput) claims.report(padId, value)
    }

    override fun onWritten(ok: Boolean) {
        val answer = pendingWrite ?: return
        answer.ok = ok
        answer.answered.countDown()
    }

    override fun onDisconnected() = end()

    // Bluetooth went off or the permission went away: the link ends without a word to the pad.
    fun release() = end()

    // One write of a claimed pad's, answered by the controller or given up on.
    fun write(
        target: Int,
        value: ByteArray,
    ): Boolean {
        if (!isLive) return false
        val answer = WriteAnswer()
        pendingWrite = answer
        val sent = gatt.write(steamBleWriteCharacteristic(target), value)
        val answered = sent && answer.answered.await(WRITE_ANSWER_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        return answered && answer.ok
    }

    private fun advance(
        from: SteamBleStage,
        to: SteamBleStage,
    ): Boolean = stageNow.compareAndSet(from, to)

    private fun sizeMtu() {
        if (!advance(SteamBleStage.DISCOVERING, SteamBleStage.SIZING_MTU)) return
        if (!gatt.requestMtu(STEAM_BLE_LARGE_MTU)) end()
    }

    private fun subscribe(
        found: SteamBleModel,
        from: SteamBleStage,
    ) {
        if (!advance(from, SteamBleStage.SUBSCRIBING)) return
        if (!gatt.enableNotifications(found.input)) end()
    }

    private fun claim() {
        val claimed = model ?: return
        val id = claims.attach(claimed, ::write)
        if (id == NO_STEAM_BLE_PAD) return end()
        padId = id
        if (!claims.start(id, claimed)) return giveBack(id)
        if (!advance(SteamBleStage.CLAIMING, SteamBleStage.CLAIMED)) detachClaim(id, restore = false)
    }

    // A start that failed part way still changed the pad, so it goes back restored.
    private fun giveBack(id: Int) {
        detachClaim(id, restore = isLive)
        endLink()
    }

    private fun end() {
        val claimed = padId
        endLink()
        if (claimed != NO_STEAM_BLE_PAD) work.execute { detachClaim(claimed, restore = false) }
    }

    private fun endLink() {
        val prior = stageNow.getAndSet(SteamBleStage.ENDED)
        if (prior == SteamBleStage.ENDED) return
        failPendingWrite()
        gatt.close()
    }

    // Every detach runs on [work], so a claim and its end never overlap and none runs twice.
    private fun detachClaim(
        id: Int,
        restore: Boolean,
    ) {
        if (padId != id) return
        padId = NO_STEAM_BLE_PAD
        claims.detach(id, restore)
    }

    private fun failPendingWrite() {
        val answer = pendingWrite ?: return
        answer.ok = false
        answer.answered.countDown()
    }
}
