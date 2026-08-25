// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.repository

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import com.tinkernorth.dish.architecture.interfaces.KeyedRepository
import com.tinkernorth.dish.core.net.moonlight.RememberedMoonlight
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Remembers paired Moonlight hosts. A byte-for-byte structural twin of
 * [RememberedSatelliteRepository]: a single JSON list in the shared
 * connection_store prefs, guarded by a write lock, with an observable mirror so
 * the connections composer reacts to remember/forget without a prefs re-read.
 */
@Singleton
class RememberedMoonlightRepository
    @Inject
    constructor(
        @ApplicationContext context: Context,
        private val json: Json,
    ) : KeyedRepository<String, RememberedMoonlight> {
        private val prefs by lazy {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        }

        private val writeLock = Any()

        private val _entries = MutableStateFlow(all())
        val entries: StateFlow<List<RememberedMoonlight>> = _entries.asStateFlow()

        override fun keyOf(value: RememberedMoonlight): String = value.id

        override fun get(key: String): RememberedMoonlight? = all().firstOrNull { it.id == key }

        override fun all(): List<RememberedMoonlight> {
            val raw = prefs.getString(KEY_HOSTS, null) ?: return emptyList()
            return runCatching {
                json.decodeFromString(ListSerializer(RememberedMoonlight.serializer()), raw)
            }.getOrElse { err ->
                Log.w(TAG, "Failed to decode moonlight host list; treating as empty. Cause: ${err.javaClass.simpleName}")
                emptyList()
            }
        }

        override fun put(
            key: String,
            value: RememberedMoonlight,
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
                prefs.edit { remove(KEY_HOSTS) }
                _entries.value = emptyList()
            }
        }

        private fun persist(list: List<RememberedMoonlight>) {
            prefs.edit { putString(KEY_HOSTS, json.encodeToString(ListSerializer(RememberedMoonlight.serializer()), list)) }
        }

        private companion object {
            const val TAG = "RememberedMoonlightRepo"
            const val PREFS_NAME = "connection_store"
            const val KEY_HOSTS = "moonlight_host_list"
        }
    }
