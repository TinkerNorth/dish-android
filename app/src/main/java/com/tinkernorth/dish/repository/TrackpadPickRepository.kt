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
data class TrackpadPickPreference(
    val slotId: String,
    val pick: String,
)

@Singleton
class TrackpadPickRepository
    @Inject
    constructor(
        @ApplicationContext context: Context,
        private val json: Json,
    ) : KeyedRepository<String, TrackpadPickPreference> {
        private val prefs by lazy {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        }
        private val writeLock = Any()

        override fun keyOf(value: TrackpadPickPreference): String = value.slotId

        override fun get(key: String): TrackpadPickPreference? = all().firstOrNull { it.slotId == key }

        override fun all(): List<TrackpadPickPreference> {
            val raw = prefs.getString(KEY_LIST, null) ?: return emptyList()
            return runCatching {
                json.decodeFromString(ListSerializer(TrackpadPickPreference.serializer()), raw)
            }.getOrElse { err ->
                Log.w(
                    TAG,
                    "Failed to decode trackpad-pick list; treating as empty. " +
                        "User-facing impact: every Steam Controller's trackpad pick resets to its default. " +
                        "Cause: ${err.javaClass.simpleName}: ${err.message}",
                )
                emptyList()
            }
        }

        override fun put(
            key: String,
            value: TrackpadPickPreference,
        ) {
            synchronized(writeLock) {
                val list = all().toMutableList()
                list.removeAll { it.slotId == key }
                list += value
                persist(list)
            }
        }

        override fun remove(key: String) {
            synchronized(writeLock) {
                val list = all().filterNot { it.slotId == key }
                persist(list)
            }
        }

        override fun clear() {
            synchronized(writeLock) {
                prefs.edit { remove(KEY_LIST) }
            }
        }

        private fun persist(list: List<TrackpadPickPreference>) {
            val raw = json.encodeToString(ListSerializer(TrackpadPickPreference.serializer()), list)
            prefs.edit { putString(KEY_LIST, raw) }
        }

        private companion object {
            const val TAG = "TrackpadPickRepository"
            const val PREFS_NAME = "trackpad_pick_preferences"
            const val KEY_LIST = "preferences"
        }
    }
