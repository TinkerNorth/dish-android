// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.source.bluetooth

import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor

internal class FakeSteamGatt(
    var characteristics: List<UUID> = emptyList(),
    var connects: Boolean = true,
    // What the controller answers a write with; null for a write it never answers.
    var writeAnswer: Boolean? = true,
) : SteamGatt {
    sealed interface Call {
        data object Connect : Call

        data object DiscoverServices : Call

        data class RequestMtu(
            val mtu: Int,
        ) : Call

        data class EnableNotifications(
            val characteristic: UUID,
        ) : Call

        data class Write(
            val characteristic: UUID,
            val value: List<Byte>,
        ) : Call

        data object Close : Call
    }

    // A test's writer thread adds to it while the test reads it.
    val calls: MutableList<Call> = CopyOnWriteArrayList()
    var events: SteamGattEvents? = null

    override fun connect(events: SteamGattEvents): Boolean {
        this.events = events
        calls += Call.Connect
        return connects
    }

    override fun discoverServices(): Boolean {
        calls += Call.DiscoverServices
        return true
    }

    override fun valveCharacteristics(): List<UUID> = characteristics

    override fun requestMtu(mtu: Int): Boolean {
        calls += Call.RequestMtu(mtu)
        return true
    }

    override fun enableNotifications(characteristic: UUID): Boolean {
        calls += Call.EnableNotifications(characteristic)
        return true
    }

    // Answers at once, as a controller on a live link does.
    override fun write(
        characteristic: UUID,
        value: ByteArray,
    ): Boolean {
        calls += Call.Write(characteristic, value.toList())
        writeAnswer?.let { events?.onWritten(it) }
        return true
    }

    override fun close() {
        calls += Call.Close
    }
}

internal class FakeSteamBleClaims(
    var nextPadId: Int = -1000,
    var starts: Boolean = true,
) : SteamBleClaims {
    sealed interface Call {
        data class Attach(
            val model: SteamBleModel,
        ) : Call

        data class Start(
            val padId: Int,
        ) : Call

        data class Report(
            val padId: Int,
            val value: List<Byte>,
        ) : Call

        data class Detach(
            val padId: Int,
            val restore: Boolean,
        ) : Call
    }

    val calls: MutableList<Call> = mutableListOf()
    var writer: SteamBleWriter? = null

    // What the pad's writes answered during its start, as native's quiet writes go through it.
    var startWrites: List<ByteArray> = emptyList()
    val startAnswers: MutableList<Boolean> = mutableListOf()

    override fun attach(
        model: SteamBleModel,
        writer: SteamBleWriter,
    ): Int {
        calls += Call.Attach(model)
        this.writer = writer
        return nextPadId
    }

    override fun start(
        padId: Int,
        model: SteamBleModel,
    ): Boolean {
        calls += Call.Start(padId)
        startWrites.forEach { startAnswers += writer?.write(STEAM_BLE_FEATURE_TARGET, it) == true }
        return starts
    }

    override fun report(
        padId: Int,
        value: ByteArray,
    ) {
        calls += Call.Report(padId, value.toList())
    }

    override fun detach(
        padId: Int,
        restore: Boolean,
    ) {
        calls += Call.Detach(padId, restore)
    }

    fun detaches(): List<Call.Detach> = calls.filterIsInstance<Call.Detach>()

    fun reports(): List<Call.Report> = calls.filterIsInstance<Call.Report>()
}

// Runs each piece of work where it is handed over, so a test reads the outcome on the next line.
internal class InlineExecutor : Executor {
    override fun execute(command: Runnable) = command.run()
}
