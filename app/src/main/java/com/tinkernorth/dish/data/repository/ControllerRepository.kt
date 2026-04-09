package com.tinkernorth.dish.data.repository

import com.tinkernorth.dish.data.network.SatelliteNative
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository handling controller management and report streaming via SatelliteNative.
 */
@Singleton
class ControllerRepository @Inject constructor() {
    private val mutex = Mutex()

    fun openSocket(ip: String, port: Int): Boolean {
        // Simple call, but could be guarded if we're worried about concurrent socket management
        return SatelliteNative.openSocket(ip, port)
    }

    fun closeSocket() {
        SatelliteNative.closeSocket()
    }

    fun setConnectionParams(token: ByteArray, key: ByteArray) {
        SatelliteNative.setConnectionParams(token, key)
    }

    fun sendReport(
        index: Int, buttons: Int, lt: Int, rt: Int, lx: Int, ly: Int, rx: Int, ry: Int
    ) {
        // Gamepad reports are high-frequency; mutex might add latency,
        // but let's ensure thread-safety if called from multiple coroutines.
        SatelliteNative.sendReport(index, buttons, lt, rt, lx, ly, rx, ry)
    }

    fun addController(index: Int, capabilities: Int) {
        SatelliteNative.controllerAdd(index, capabilities)
    }

    fun removeController(index: Int) {
        SatelliteNative.controllerRemove(index)
    }

    fun getVigemAvailable(): Int {
        return SatelliteNative.getVigemAvailable()
    }

    fun getActiveControllerCount(): Int {
        return SatelliteNative.getActiveControllerCount()
    }

    fun getLastControllerAck(): Int {
        return SatelliteNative.getLastControllerAck()
    }

    fun sendControllerType(index: Int, type: Int) {
        SatelliteNative.sendControllerType(index, type)
    }

    fun resetControllerAck() {
        SatelliteNative.resetControllerAck()
    }

    fun startHeartbeat() {
        SatelliteNative.startHeartbeat()
    }

    fun stopHeartbeat() {
        SatelliteNative.stopHeartbeat()
    }

    fun isConnectionAlive(): Boolean {
        return SatelliteNative.isConnectionAlive()
    }

    fun receiveAck() {
        SatelliteNative.receiveAck()
    }
}
