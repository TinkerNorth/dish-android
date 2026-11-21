// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.source.system

import android.os.Process
import android.util.Log
import com.tinkernorth.dish.di.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

// The app's own log lines for the diagnostics bundle: `logcat -d` filtered to this process, the
// last few thousand lines. An app reads only its own lines without READ_LOGS; nothing else is
// asked for.
@Singleton
class LogcatReader
    @Inject
    constructor(
        @IoDispatcher private val io: CoroutineDispatcher,
    ) {
        suspend fun ownLines(): String = withContext(io) { readBlocking() }

        private fun readBlocking(): String =
            try {
                val command = listOf("logcat", "-d", "-v", "threadtime", "--pid=${Process.myPid()}", "-t", LINES.toString())
                val process = ProcessBuilder(command).redirectErrorStream(true).start()
                val text = process.inputStream.bufferedReader().use { it.readText() }
                process.waitFor()
                text.takeLast(MAX_CHARS)
            } catch (e: IOException) {
                Log.w(TAG, "logcat could not be read", e)
                ""
            }

        private companion object {
            const val TAG = "LogcatReader"
            const val LINES = 2000
            const val MAX_CHARS = 512 * 1024
        }
    }
