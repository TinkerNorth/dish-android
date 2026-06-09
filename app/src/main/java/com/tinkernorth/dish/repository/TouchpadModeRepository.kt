// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.repository

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import com.tinkernorth.dish.architecture.interfaces.KeyedRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class TouchpadModePreference(
    val satelliteId: String,
    val mode: String,
)

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
                // Fall back to empty on parse failure: losing picks beats crashing on corrupt prefs.
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
            // Reject unknown modes so a typo never persists a value the satellite cannot route.
            if (!TouchpadModeValue.isValid(value.mode)) {
                Log.w(TAG, "Ignoring put of invalid touchpad mode '${value.mode}' for $key")
                return
            }
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
                prefs.edit { remove(KEY_LIST) }
            }
        }

        private fun persist(list: List<TouchpadModePreference>) {
            val raw = json.encodeToString(ListSerializer(TouchpadModePreference.serializer()), list)
            prefs.edit { putString(KEY_LIST, raw) }
        }

        private companion object {
            const val TAG = "TouchpadModeRepository"
            const val PREFS_NAME = "touchpad_mode_preferences"
            const val KEY_LIST = "preferences"
        }
    }

// Wire values match `touchpadModeName()` in `satellite/src/core/types.h`; kept as strings to round-trip without a mapping shim.
object TouchpadModeValue {
    const val OFF = "off"
    const val DS4 = "ds4"
    const val MOUSE = "mouse"

    val ALL: List<String> = listOf(OFF, DS4, MOUSE)

    fun isValid(s: String?): Boolean = s != null && s in ALL
}
