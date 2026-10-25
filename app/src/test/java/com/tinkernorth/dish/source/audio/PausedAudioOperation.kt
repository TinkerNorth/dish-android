// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.source.audio

import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

internal class PausedAudioOperation {
    private val entered = CountDownLatch(1)
    private val released = CountDownLatch(1)

    fun pause() {
        entered.countDown()
        check(released.await(AUDIO_TEST_TIMEOUT_MS, TimeUnit.MILLISECONDS))
    }

    fun awaitEntered() {
        check(entered.await(AUDIO_TEST_TIMEOUT_MS, TimeUnit.MILLISECONDS))
    }

    fun release() = released.countDown()
}

internal fun awaitAudioJob(job: Job) =
    runBlocking {
        withTimeout(AUDIO_TEST_TIMEOUT_MS) { job.join() }
    }

internal const val AUDIO_TEST_TIMEOUT_MS = 10_000L
