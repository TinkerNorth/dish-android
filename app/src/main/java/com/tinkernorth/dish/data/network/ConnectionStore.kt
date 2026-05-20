// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.data.network

import android.content.Context
import com.tinkernorth.dish.data.model.DiscoveredServer
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persistent registry of remembered connections. Stores just enough to
 * auto-reconnect on next launch: server address/ports for satellites,
 * MAC+profile for Bluetooth HID hosts.
 */
@Singleton
class ConnectionStore
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val json: Json,
    ) {
        private val prefs by lazy {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        }

        // SharedPreferences itself is thread-safe for reads, but a
        // read-modify-write list mutation (remembered() → mutate → persist)
        // can race with another thread doing the same; the loser's edits
        // disappear. Funnel every list mutation through a single monitor.
        private val writeLock = Any()

        // ── Satellites ────────────────────────────────────────────────────────

        fun remembered(): List<RememberedSatellite> {
            val raw = prefs.getString(KEY_SATELLITES, null) ?: return emptyList()
            return runCatching {
                json.decodeFromString(ListSerializer(RememberedSatellite.serializer()), raw)
            }.getOrDefault(emptyList())
        }

        fun rememberSatellite(server: DiscoveredServer) {
            synchronized(writeLock) {
                val list = remembered().toMutableList()
                val id = SatelliteConnection.idFor(server)
                list.removeAll { it.id == id }
                list +=
                    RememberedSatellite(
                        id = id,
                        name = server.name,
                        ip = server.ip,
                        udpPort = server.udpPort,
                        pairPort = server.pairPort,
                        httpPort = server.httpPort,
                    )
                persistSatellitesLocked(list)
            }
        }

        fun forgetSatellite(id: String) {
            synchronized(writeLock) {
                val list = remembered().filterNot { it.id == id }
                persistSatellitesLocked(list)
                prefs.edit().remove(satelliteKeyPref(id)).apply()
            }
        }

        private fun persistSatellitesLocked(list: List<RememberedSatellite>) {
            val raw = json.encodeToString(ListSerializer(RememberedSatellite.serializer()), list)
            prefs.edit().putString(KEY_SATELLITES, raw).apply()
        }

        /**
         * Return the pair-derived shared key for [id], or null if we've never
         * paired with that satellite. Keys are stored per-satellite so pairing
         * with a second satellite can't clobber the first satellite's
         * credential, which was a latent bug in the pre-per-server-key build.
         * Legacy migration from that build lives in [SatelliteConnectionManager]
         * since the legacy slot sits in a different prefs file.
         */
        fun satelliteSharedKey(id: String): String? = prefs.getString(satelliteKeyPref(id), null)

        fun setSatelliteSharedKey(
            id: String,
            keyHex: String,
        ) {
            prefs.edit().putString(satelliteKeyPref(id), keyHex).apply()
        }

        /**
         * Drop the cached shared key for [id]. Called from
         * [SatelliteConnectionManager] when a session-open auth-shape failure
         * makes it clear the key the server expects has rotated — without this
         * call, every subsequent auto-reconnect would re-attempt with the
         * stale key and the user would be stuck in a permanent broken state
         * requiring a manual Forget + re-add.
         */
        fun forgetSatelliteSharedKey(id: String) {
            prefs.edit().remove(satelliteKeyPref(id)).apply()
        }

        private fun satelliteKeyPref(id: String): String = "$KEY_SATELLITE_SHARED_PREFIX$id"

        // ── Bluetooth ─────────────────────────────────────────────────────────

        fun rememberedBt(): List<RememberedBt> {
            val raw = prefs.getString(KEY_BT, null) ?: return emptyList()
            return runCatching {
                json.decodeFromString(ListSerializer(RememberedBt.serializer()), raw)
            }.getOrDefault(emptyList())
        }

        fun rememberBt(entry: RememberedBt) {
            synchronized(writeLock) {
                val list = rememberedBt().toMutableList()
                list.removeAll { it.id == entry.id }
                list += entry
                persistBtLocked(list)
            }
        }

        fun forgetBt(id: String) {
            synchronized(writeLock) {
                val list = rememberedBt().filterNot { it.id == id }
                persistBtLocked(list)
            }
        }

        private fun persistBtLocked(list: List<RememberedBt>) {
            val raw = json.encodeToString(ListSerializer(RememberedBt.serializer()), list)
            prefs.edit().putString(KEY_BT, raw).apply()
        }

        companion object {
            private const val PREFS_NAME = "connection_store"
            private const val KEY_SATELLITES = "satellite_list"
            private const val KEY_BT = "bt_list"
            private const val KEY_SATELLITE_SHARED_PREFIX = "satellite_shared_key:"
        }
    }

@Serializable
data class RememberedSatellite(
    val id: String,
    val name: String,
    val ip: String,
    val udpPort: Int,
    val pairPort: Int,
    val httpPort: Int,
) {
    fun toDiscovered(): DiscoveredServer =
        DiscoveredServer(
            name = name,
            ip = ip,
            udpPort = udpPort,
            pairPort = pairPort,
            httpPort = httpPort,
        )
}

@Serializable
data class RememberedBt(
    /** Stable id derived from MAC. */
    val id: String,
    val name: String,
    val mac: String,
    val profileName: String,
)
