// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.repository

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import com.tinkernorth.dish.architecture.interfaces.KeyedRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RememberedSatelliteRepository
    @Inject
    constructor(
        @ApplicationContext context: Context,
        private val json: Json,
    ) : KeyedRepository<String, RememberedSatellite> {
        private val prefs by lazy {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        }

        // SharedPreferences is atomic per key; read-modify-write of the list is not.
        private val writeLock = Any()

        private val _entries = MutableStateFlow(all())

        // Observable mirror so derived state reacts to remember/forget instead of re-reading prefs on a tick.
        val entries: StateFlow<List<RememberedSatellite>> = _entries.asStateFlow()

        override fun keyOf(value: RememberedSatellite): String = value.id

        override fun get(key: String): RememberedSatellite? = all().firstOrNull { it.id == key }

        override fun all(): List<RememberedSatellite> {
            val raw = prefs.getString(KEY_SATELLITES, null) ?: return emptyList()
            return runCatching {
                json.decodeFromString(ListSerializer(RememberedSatellite.serializer()), raw)
            }.getOrElse { err ->
                // Fall back to empty on parse failure: forgetting satellites beats crashing on corrupt prefs.
                Log.w(
                    TAG,
                    "Failed to decode satellite list; treating as empty. " +
                        "User-facing impact: every remembered satellite is forgotten. " +
                        "Cause: ${err.javaClass.simpleName}: ${err.message}",
                )
                emptyList()
            }
        }

        override fun put(
            key: String,
            value: RememberedSatellite,
        ) {
            synchronized(writeLock) {
                val list = all().toMutableList()
                list.removeAll { it.id == key }
                list += value
                persist(list)
                _entries.value = list.toList()
            }
        }

        override fun remove(key: String) {
            synchronized(writeLock) {
                val list = all().filterNot { it.id == key }
                persist(list)
                _entries.value = list
            }
        }

        override fun clear() {
            synchronized(writeLock) {
                prefs.edit { remove(KEY_SATELLITES) }
                _entries.value = emptyList()
            }
        }

        private fun persist(list: List<RememberedSatellite>) {
            val raw = json.encodeToString(ListSerializer(RememberedSatellite.serializer()), list)
            prefs.edit { putString(KEY_SATELLITES, raw) }
        }

        private companion object {
            const val TAG = "RememberedSatelliteRepo"
            const val PREFS_NAME = "connection_store"
            const val KEY_SATELLITES = "satellite_list"
        }
    }
