// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.source.sensor

import android.os.Handler
import android.os.HandlerThread
import android.os.Process

interface SensorDispatch {
    fun acquire(): Handler

    fun release()
}

class HandlerThreadSensorDispatch(
    private val threadName: String,
) : SensorDispatch {
    private var thread: HandlerThread? = null

    override fun acquire(): Handler {
        thread?.let { return Handler(it.looper) }
        val started = HandlerThread(threadName, Process.THREAD_PRIORITY_URGENT_AUDIO).apply { start() }
        thread = started
        return Handler(started.looper)
    }

    override fun release() {
        thread?.quitSafely()
        thread = null
    }
}
