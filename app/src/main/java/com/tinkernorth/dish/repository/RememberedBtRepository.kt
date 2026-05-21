// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.repository

import android.content.Context
import com.tinkernorth.dish.architecture.interfaces.KeyedRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Durable list of remembered Bluetooth HID hosts. Backed by SharedPreferences (one
 * JSON-encoded list under `KEY_BT`). See [RememberedSatelliteRepository] for the
 * thread-safety pattern.
 *
 * **Pattern:** [com.tinkernorth.dish.architecture.interfaces.KeyedRepository] — durable CRUD only.
 */
@Singleton
class RememberedBtRepository
    @Inject
    constructor(
        @ApplicationContext context: Context,
        private val json: Json,
    ) : KeyedRepository<String, RememberedBt> {
        private val prefs by lazy {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        }

        private val writeLock = Any()

        override fun keyOf(value: RememberedBt): String = value.id

        override fun get(key: String): RememberedBt? = all().firstOrNull { it.id == key }

        override fun all(): List<RememberedBt> {
            val raw = prefs.getString(KEY_BT, null) ?: return emptyList()
            return runCatching {
                json.decodeFromString(ListSerializer(RememberedBt.serializer()), raw)
            }.getOrDefault(emptyList())
        }

        override fun put(
            key: String,
            value: RememberedBt,
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
                prefs.edit().remove(KEY_BT).apply()
            }
        }

        private fun persist(list: List<RememberedBt>) {
            val raw = json.encodeToString(ListSerializer(RememberedBt.serializer()), list)
            prefs.edit().putString(KEY_BT, raw).apply()
        }

        private companion object {
            const val PREFS_NAME = "connection_store"
            const val KEY_BT = "bt_list"
        }
    }
