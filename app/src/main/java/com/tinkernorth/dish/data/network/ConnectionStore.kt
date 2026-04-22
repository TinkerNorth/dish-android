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
 * auto-reconnect on next launch: server address/ports for WiFi, MAC+profile
 * for Bluetooth HID hosts.
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

        // ── WiFi ──────────────────────────────────────────────────────────────

        fun remembered(): List<RememberedWifi> {
            val raw = prefs.getString(KEY_WIFI, null) ?: return emptyList()
            return runCatching {
                json.decodeFromString(ListSerializer(RememberedWifi.serializer()), raw)
            }.getOrDefault(emptyList())
        }

        fun rememberWifi(server: DiscoveredServer) {
            val list = remembered().toMutableList()
            val id = WifiConnection.idFor(server)
            list.removeAll { it.id == id }
            list +=
                RememberedWifi(
                    id = id,
                    name = server.name,
                    ip = server.ip,
                    udpPort = server.udpPort,
                    pairPort = server.pairPort,
                    httpPort = server.httpPort,
                )
            persistWifi(list)
        }

        fun forgetWifi(id: String) {
            val list = remembered().filterNot { it.id == id }
            persistWifi(list)
            prefs.edit().remove(wifiKeyPref(id)).apply()
        }

        private fun persistWifi(list: List<RememberedWifi>) {
            val raw = json.encodeToString(ListSerializer(RememberedWifi.serializer()), list)
            prefs.edit().putString(KEY_WIFI, raw).apply()
        }

        /**
         * Return the pair-derived shared key for [id], or null if we've never
         * paired with that server. Keys are stored per-server so pairing with a
         * second server can't clobber the first server's credential, which was a
         * latent bug in the pre-per-server-key build. Legacy migration from that
         * build lives in [WifiConnectionManager] since the legacy slot sits in a
         * different prefs file.
         */
        fun wifiSharedKey(id: String): String? = prefs.getString(wifiKeyPref(id), null)

        fun setWifiSharedKey(
            id: String,
            keyHex: String,
        ) {
            prefs.edit().putString(wifiKeyPref(id), keyHex).apply()
        }

        private fun wifiKeyPref(id: String): String = "$KEY_WIFI_SHARED_PREFIX$id"

        // ── Bluetooth ─────────────────────────────────────────────────────────

        fun rememberedBt(): List<RememberedBt> {
            val raw = prefs.getString(KEY_BT, null) ?: return emptyList()
            return runCatching {
                json.decodeFromString(ListSerializer(RememberedBt.serializer()), raw)
            }.getOrDefault(emptyList())
        }

        fun rememberBt(entry: RememberedBt) {
            val list = rememberedBt().toMutableList()
            list.removeAll { it.id == entry.id }
            list += entry
            persistBt(list)
        }

        fun forgetBt(id: String) {
            val list = rememberedBt().filterNot { it.id == id }
            persistBt(list)
        }

        private fun persistBt(list: List<RememberedBt>) {
            val raw = json.encodeToString(ListSerializer(RememberedBt.serializer()), list)
            prefs.edit().putString(KEY_BT, raw).apply()
        }

        companion object {
            private const val PREFS_NAME = "connection_store"
            private const val KEY_WIFI = "wifi_list"
            private const val KEY_BT = "bt_list"
            private const val KEY_WIFI_SHARED_PREFIX = "wifi_shared_key:"
        }
    }

@Serializable
data class RememberedWifi(
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
