// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.repository

import android.content.Context
import android.util.Log
import com.tinkernorth.dish.architecture.interfaces.KeyedRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Durable per-satellite touchpad routing-mode preference set by the client.
 *
 * The client is the authoritative setter for the touchpad routing mode (one
 * of `off`, `ds4`, `mouse`). The satellite server stores and routes per the
 * mode but exposes a read-only mirror in its dashboard; the client picker
 * pushes via `POST /api/devices/touchpad-mode` and persists the selection
 * locally so the next session starts in the same routing without an extra
 * round-trip.
 *
 * Storage shape mirrors [MotionPreferenceRepository]: single
 * `SharedPreferences` entry holding a JSON list of [TouchpadModePreference]
 * records, one per paired satellite (keyed by `satelliteId`, which is the
 * paired-device id used by the satellite's `pairedDevices` config). See
 * that file's docstring for the rationale behind the JSON-list shape.
 *
 * Absence semantics: [get] returns null when this device has never had a
 * mode picked. Callers collapse null onto a default chosen by
 * [com.tinkernorth.dish.composer.TouchpadModeComposer] — typically `off`
 * (the safe baseline that matches the server's default) unless the server
 * advertises pad/mouse support and the client has reason to prefer one of
 * them (e.g. a connected DualSense + a Windows/Linux receiver).
 */
@Serializable
data class TouchpadModePreference(
    /** Paired-satellite id — matches the server's `PairedDevice.id`. */
    val satelliteId: String,
    /** One of `"off"`, `"ds4"`, `"mouse"`. Validated by the caller. */
    val mode: String,
)

/**
 * Per the [com.tinkernorth.dish.architecture.interfaces.Repository] contract:
 * dumb CRUD only — no flows, no lifecycle, no events. Reactive reads live in
 * [com.tinkernorth.dish.source.store.TouchpadModeStore] (the
 * `AbstractStateSource` wrapper); writes funnel through the store so the
 * in-memory mirror and the on-disk state stay in lock-step. Mirrors the
 * [MotionPreferenceRepository] + `MotionEnabledStore` split for the same
 * reasons.
 */
@Singleton
class TouchpadModeRepository
    @Inject
    constructor(
        @ApplicationContext context: Context,
        private val json: Json,
    ) : KeyedRepository<String, TouchpadModePreference> {
        private val prefs by lazy {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        }
        private val writeLock = Any()

        override fun keyOf(value: TouchpadModePreference): String = value.satelliteId

        override fun get(key: String): TouchpadModePreference? = all().firstOrNull { it.satelliteId == key }

        override fun all(): List<TouchpadModePreference> {
            val raw = prefs.getString(KEY_LIST, null) ?: return emptyList()
            return runCatching {
                json.decodeFromString(ListSerializer(TouchpadModePreference.serializer()), raw)
            }.getOrElse { err ->
                // Same defensive shape as MotionPreferenceRepository: a parse
                // failure here only loses the user's per-satellite touchpad
                // routing picks. Falling back to emptyList() means the next
                // session starts with the server's default (OFF) instead of
                // bricking the app with a crash on first read.
                Log.w(
                    TAG,
                    "Failed to decode touchpad-mode list; treating as empty. " +
                        "User-facing impact: every saved touchpad-routing pick reverts to default. " +
                        "Cause: ${err.javaClass.simpleName}: ${err.message}",
                )
                emptyList()
            }
        }

        override fun put(
            key: String,
            value: TouchpadModePreference,
        ) {
            synchronized(writeLock) {
                val list = all().toMutableList()
                list.removeAll { it.satelliteId == key }
                list += value
                persist(list)
            }
        }

        override fun remove(key: String) {
            synchronized(writeLock) {
                val list = all().filterNot { it.satelliteId == key }
                persist(list)
            }
        }

        override fun clear() {
            synchronized(writeLock) {
                prefs.edit().remove(KEY_LIST).apply()
            }
        }

        private fun persist(list: List<TouchpadModePreference>) {
            val raw = json.encodeToString(ListSerializer(TouchpadModePreference.serializer()), list)
            prefs.edit().putString(KEY_LIST, raw).apply()
        }

        private companion object {
            const val TAG = "TouchpadModeRepository"
            const val PREFS_NAME = "touchpad_mode_preferences"
            const val KEY_LIST = "preferences"
        }
    }

/**
 * Valid touchpad-mode wire values, matching `touchpadModeName()` in
 * `satellite/src/core/types.h`. Kept as plain constants (not an enum) so the
 * persistence layer round-trips strings without an extra mapping shim — the
 * server speaks these strings on the wire, so we hold them as strings end
 * to end and validate at the picker boundary.
 */
object TouchpadModeValue {
    const val OFF = "off"
    const val DS4 = "ds4"
    const val MOUSE = "mouse"

    val ALL: List<String> = listOf(OFF, DS4, MOUSE)

    fun isValid(s: String?): Boolean = s != null && s in ALL
}
