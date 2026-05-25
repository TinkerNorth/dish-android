// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.repository

import android.content.Context
import androidx.core.content.edit
import com.tinkernorth.dish.architecture.interfaces.Repository
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

// Per-key storage (vs. one JSON list) so forget is a single prefs edit; per-key writes are atomic.
@Singleton
class SatelliteSharedKeyRepository
    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) : Repository<String, String> {
        private val prefs by lazy {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        }

        override fun get(key: String): String? = prefs.getString(keyPref(key), null)

        override fun all(): List<String> =
            prefs.all.keys
                .filter { it.startsWith(KEY_PREFIX) }
                .mapNotNull { prefs.getString(it, null) }

        override fun put(
            key: String,
            value: String,
        ) {
            prefs.edit { putString(keyPref(key), value) }
        }

        override fun remove(key: String) {
            prefs.edit { remove(keyPref(key)) }
        }

        override fun clear() {
            prefs.edit {
                for (k in prefs.all.keys.filter { it.startsWith(KEY_PREFIX) }) {
                    remove(k)
                }
            }
        }

        private fun keyPref(id: String): String = "$KEY_PREFIX$id"

        private companion object {
            const val PREFS_NAME = "connection_store"
            const val KEY_PREFIX = "satellite_shared_key:"
        }
    }
