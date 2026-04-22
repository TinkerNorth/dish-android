package com.tinkernorth.dish.data.repository

import com.tinkernorth.dish.data.network.SatelliteNative
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Thin wrapper around [SatelliteNative] that forwards every call with the
 * session [handle] returned from [openSocket]. Multiple sessions run in
 * parallel; state is kept inside the native library keyed by handle.
 */
@Singleton
class ControllerRepository @Inject constructor() {

    /** Returns a session handle >= 1 on success, or -1 on failure. */
    fun openSocket(ip: String, port: Int): Int {
        return SatelliteNative.openSocket(ip, port)
    }

    fun closeSocket(handle: Int) {
        SatelliteNative.closeSocket(handle)
    }

    fun setConnectionParams(handle: Int, token: ByteArray, key: ByteArray) {
        SatelliteNative.setConnectionParams(handle, token, key)
    }

    fun sendReport(
        handle: Int, index: Int, buttons: Int, lt: Int, rt: Int, lx: Int, ly: Int, rx: Int, ry: Int
    ) {
        SatelliteNative.sendReport(handle, index, buttons, lt, rt, lx, ly, rx, ry)
    }

    fun addController(handle: Int, index: Int, capabilities: Int) {
        SatelliteNative.controllerAdd(handle, index, capabilities)
    }

    fun removeController(handle: Int, index: Int) {
        SatelliteNative.controllerRemove(handle, index)
    }

    fun getVigemAvailable(handle: Int): Int = SatelliteNative.getVigemAvailable(handle)

    fun getActiveControllerCount(handle: Int): Int = SatelliteNative.getActiveControllerCount(handle)

    fun getLastControllerAck(handle: Int): Int = SatelliteNative.getLastControllerAck(handle)

    fun sendControllerType(handle: Int, index: Int, type: Int) {
        SatelliteNative.sendControllerType(handle, index, type)
    }

    fun resetControllerAck(handle: Int) {
        SatelliteNative.resetControllerAck(handle)
    }

    fun startHeartbeat(handle: Int) {
        SatelliteNative.startHeartbeat(handle)
    }

    fun stopHeartbeat(handle: Int) {
        SatelliteNative.stopHeartbeat(handle)
    }

    fun isConnectionAlive(handle: Int): Boolean = SatelliteNative.isConnectionAlive(handle)

    fun receiveAck(handle: Int) {
        SatelliteNative.receiveAck(handle)
    }
}
