// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.repository

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import com.tinkernorth.dish.architecture.interfaces.Repository
import com.tinkernorth.dish.core.net.bytesToHex
import com.tinkernorth.dish.core.net.hexToBytes
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

// Per-key storage (vs. one JSON list) so forget is a single prefs edit; per-key writes are atomic.
// Every key is sealed before it reaches the prefs file, which anything with the app's files can
// read. A key an older build wrote in the clear is read once as it is and sealed on that read.
@Singleton
class SatelliteSharedKeyRepository
    @Inject
    constructor(
        @ApplicationContext context: Context,
        private val sealer: SecretSealer,
    ) : Repository<String, String> {
        private val prefs by lazy {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        }

        override fun get(key: String): String? = opened(keyPref(key), prefs.getString(keyPref(key), null))

        override fun all(): List<String> =
            prefs.all.keys
                .filter { it.startsWith(KEY_PREFIX) }
                .mapNotNull { opened(it, prefs.getString(it, null)) }

        override fun put(
            key: String,
            value: String,
        ) {
            prefs.edit { putString(keyPref(key), sealed(value)) }
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

        private fun sealed(keyHex: String): String = SEALED_PREFIX + bytesToHex(sealer.seal(keyHex.toByteArray(Charsets.US_ASCII)))

        // The key a stored value holds, or null when it holds none: a sealed value that will not
        // open is dropped, since nothing will ever read it again, and the satellite reads as one
        // to pair afresh.
        private fun opened(
            pref: String,
            stored: String?,
        ): String? {
            if (stored == null) return null
            if (!stored.startsWith(SEALED_PREFIX)) {
                prefs.edit { putString(pref, sealed(stored)) }
                return stored
            }
            val plain = sealer.open(hexToBytes(stored.removePrefix(SEALED_PREFIX)))
            if (plain == null) {
                Log.w(TAG, "dropping a sealed key that no longer opens: $pref")
                prefs.edit { remove(pref) }
                return null
            }
            return String(plain, Charsets.US_ASCII)
        }

        private fun keyPref(id: String): String = "$KEY_PREFIX$id"

        private companion object {
            const val TAG = "SatelliteSharedKeys"
            const val PREFS_NAME = "connection_store"
            const val KEY_PREFIX = "satellite_shared_key:"
            const val SEALED_PREFIX = "sealed:"
        }
    }
