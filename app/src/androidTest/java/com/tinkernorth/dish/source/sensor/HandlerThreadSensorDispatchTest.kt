// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.source.sensor

import android.os.Handler
import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class HandlerThreadSensorDispatchTest {
    @Test
    fun sensor_callbacks_run_at_the_priority_of_the_other_input_senders() {
        val dispatch = HandlerThreadSensorDispatch("sensor-dispatch-under-test")
        val handler = dispatch.acquire()
        try {
            assertEquals(Process.THREAD_PRIORITY_URGENT_AUDIO, priorityOfTheThreadRunning(handler))
        } finally {
            dispatch.release()
        }
    }

    // Read inside a message: a HandlerThread applies its priority only after it has handed out its
    // looper, so what a message sees is what a sensor callback delivered on that looper sees.
    private fun priorityOfTheThreadRunning(handler: Handler): Int {
        val priority = CompletableFuture<Int>()
        handler.post { priority.complete(Process.getThreadPriority(Process.myTid())) }
        return priority.get(MESSAGE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    }

    private companion object {
        const val MESSAGE_TIMEOUT_SECONDS = 5L
    }
}
