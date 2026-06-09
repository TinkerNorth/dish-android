// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.repository

import android.content.Context
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

        private val _entries = MutableStateFlow(all())

        // Observable mirror so derived state reacts to remember/forget instead of re-reading prefs on a tick.
        val entries: StateFlow<List<RememberedBt>> = _entries.asStateFlow()

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
                prefs.edit { remove(KEY_BT) }
                _entries.value = emptyList()
            }
        }

        private fun persist(list: List<RememberedBt>) {
            val raw = json.encodeToString(ListSerializer(RememberedBt.serializer()), list)
            prefs.edit { putString(KEY_BT, raw) }
        }

        private companion object {
            const val PREFS_NAME = "connection_store"
            const val KEY_BT = "bt_list"
        }
    }
