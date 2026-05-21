// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.repository

import android.content.Context
import com.tinkernorth.dish.architecture.interfaces.Repository
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Per-satellite pair-derived shared keys (32 bytes, hex-encoded). One row per satellite id;
 * the key is the satellite id, the value is the hex string the JNI side decodes.
 *
 * Stored as individual SharedPreferences entries under `"satellite_shared_key:<id>"` keys
 * — this lets a single forget-key call be a one-shot prefs edit without rewriting the
 * whole satellite list. Per-key writes are atomic at the SharedPreferences level so no
 * write-lock is needed.
 *
 * **Pattern:** [com.tinkernorth.dish.architecture.interfaces.Repository] — durable CRUD only.
 */
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
            prefs.edit().putString(keyPref(key), value).apply()
        }

        override fun remove(key: String) {
            prefs.edit().remove(keyPref(key)).apply()
        }

        override fun clear() {
            val editor = prefs.edit()
            for (k in prefs.all.keys.filter { it.startsWith(KEY_PREFIX) }) {
                editor.remove(k)
            }
            editor.apply()
        }

        private fun keyPref(id: String): String = "$KEY_PREFIX$id"

        private companion object {
            const val PREFS_NAME = "connection_store"
            const val KEY_PREFIX = "satellite_shared_key:"
        }
    }
