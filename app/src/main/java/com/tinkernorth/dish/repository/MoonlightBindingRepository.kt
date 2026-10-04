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

// One pad's standing Moonlight binding. Keyed by the pad's descriptor (InputDevice.descriptor),
// which survives the reconnects and restarts that hand the pad a new device id.
@Serializable
data class RememberedBinding(
    val descriptor: String,
    val hostId: String,
    val controllerType: Int,
)

/**
 * The Moonlight bindings the user has made, kept across restarts so a pad goes back on the host it
 * was bound to when it appears again. The desktops keep the same list under the same key
 * (dish-windows MoonlightHostRepository); here a pad is named by its descriptor because its device
 * id is not stable. A JSON list in the shared connection_store prefs, like the host list.
 */
@Singleton
class MoonlightBindingRepository
    @Inject
    constructor(
        @ApplicationContext context: Context,
        private val json: Json,
    ) : KeyedRepository<String, RememberedBinding> {
        private val prefs by lazy {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        }
        private val writeLock = Any()

        override fun keyOf(value: RememberedBinding): String = value.descriptor

        override fun get(key: String): RememberedBinding? = all().firstOrNull { it.descriptor == key }

        /** Every pad remembered for [hostId]. */
        fun forHost(hostId: String): List<RememberedBinding> = all().filter { it.hostId == hostId }

        override fun all(): List<RememberedBinding> {
            val raw = prefs.getString(KEY_LIST, null) ?: return emptyList()
            return runCatching {
                json.decodeFromString(ListSerializer(RememberedBinding.serializer()), raw)
            }.getOrElse { err ->
                Log.w(
                    TAG,
                    "Failed to decode the Moonlight binding list; treating as empty. " +
                        "User-facing impact: no pad is put back on its host until it is bound again. " +
                        "Cause: ${err.javaClass.simpleName}: ${err.message}",
                )
                emptyList()
            }
        }

        override fun put(
            key: String,
            value: RememberedBinding,
        ) {
            synchronized(writeLock) {
                val list = all().toMutableList()
                list.removeAll { it.descriptor == key }
                list += value
                persist(list)
            }
        }

        override fun remove(key: String) {
            synchronized(writeLock) {
                persist(all().filterNot { it.descriptor == key })
            }
        }

        override fun clear() {
            synchronized(writeLock) {
                prefs.edit { remove(KEY_LIST) }
            }
        }

        private fun persist(list: List<RememberedBinding>) {
            val raw = json.encodeToString(ListSerializer(RememberedBinding.serializer()), list)
            prefs.edit { putString(KEY_LIST, raw) }
        }

        private companion object {
            const val TAG = "MoonlightBindingRepo"
            const val PREFS_NAME = "connection_store"
            const val KEY_LIST = "moonlight_binding_list"
        }
    }
