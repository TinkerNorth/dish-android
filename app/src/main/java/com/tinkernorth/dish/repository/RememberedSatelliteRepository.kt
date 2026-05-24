// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.repository

import android.content.Context
import androidx.core.content.edit
import com.tinkernorth.dish.architecture.interfaces.KeyedRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Durable list of remembered Satellite hosts. Backed by SharedPreferences (one JSON-encoded
 * list under `KEY_SATELLITES`). Thread-safe for concurrent put / remove via a single monitor
 * — read-modify-write of the list cannot interleave.
 *
 * **Pattern:** [com.tinkernorth.dish.architecture.interfaces.KeyedRepository] — durable CRUD, no flows, no
 * lifecycle. Reactive observers wrap this; do not fold a `StateFlow` into the repository
 * itself.
 */
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

        // Serializes list mutations. SharedPreferences itself is atomic per key, but
        // `read → mutate → write` is not.
        private val writeLock = Any()

        override fun keyOf(value: RememberedSatellite): String = value.id

        override fun get(key: String): RememberedSatellite? = all().firstOrNull { it.id == key }

        override fun all(): List<RememberedSatellite> {
            val raw = prefs.getString(KEY_SATELLITES, null) ?: return emptyList()
            return runCatching {
                json.decodeFromString(ListSerializer(RememberedSatellite.serializer()), raw)
            }.getOrDefault(emptyList())
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
            }
        }

        override fun remove(key: String) {
            synchronized(writeLock) {
                val list = all().filterNot { it.id == key }
                persist(list)
            }
        }

        override fun clear() {
            synchronized(writeLock) {
                prefs.edit { remove(KEY_SATELLITES) }
            }
        }

        private fun persist(list: List<RememberedSatellite>) {
            val raw = json.encodeToString(ListSerializer(RememberedSatellite.serializer()), list)
            prefs.edit { putString(KEY_SATELLITES, raw) }
        }

        private companion object {
            const val PREFS_NAME = "connection_store"
            const val KEY_SATELLITES = "satellite_list"
        }
    }
