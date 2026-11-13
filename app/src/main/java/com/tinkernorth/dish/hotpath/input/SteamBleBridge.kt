// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.hotpath.input

/**
 * Native -> Kotlin upcall for a Bluetooth LE Steam Controller's writes: its settings, its refresh
 * and its rumble, built and timed by native as a USB pad's are, written over the app's own GATT
 * client. Each call blocks its native caller until the controller answers, as a USB pad's
 * control transfer does.
 */
object SteamBleBridge {
    init {
        System.loadLibrary("satellite")
    }

    fun interface Writer {
        fun write(
            padId: Int,
            target: Int,
            value: ByteArray,
        ): Boolean
    }

    @Volatile private var writer: Writer? = null

    // Must run from a JVM call so the app classloader is on the stack (FindClass in JNI_OnLoad
    // would fail).
    fun install(writer: Writer) {
        this.writer = writer
        nativeInstall()
    }

    @JvmStatic
    private external fun nativeInstall()

    @JvmStatic
    fun write(
        padId: Int,
        target: Int,
        value: ByteArray,
    ): Boolean = writer?.write(padId, target, value) ?: false
}
