// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.core.net.moonlight

import java.net.URLEncoder

/**
 * Builds the Moonlight HTTP/HTTPS request URLs and query strings (Wolf
 * rest/servers.cpp routes). Pure string work so it is unit-tested without a
 * socket; the gateway opens the connections.
 *
 * Ports are never hardcoded here: the caller passes the port it read from
 * /serverinfo (HTTP 47989 and HTTPS 47984 are only the documented defaults).
 */
object MoonlightUrls {
    fun serverInfoHttp(
        address: String,
        httpPort: Int,
        uniqueId: String,
    ): String = "http://$address:$httpPort/serverinfo?" + query(mapOf("uniqueid" to uniqueId))

    fun serverInfoHttps(
        address: String,
        httpsPort: Int,
        uniqueId: String,
    ): String = "https://$address:$httpsPort/serverinfo?" + query(mapOf("uniqueid" to uniqueId))

    fun pairHttp(
        address: String,
        httpPort: Int,
        params: Map<String, String>,
    ): String = "http://$address:$httpPort/pair?" + query(params)

    fun pairHttps(
        address: String,
        httpsPort: Int,
        params: Map<String, String>,
    ): String = "https://$address:$httpsPort/pair?" + query(params)

    fun appList(
        address: String,
        httpsPort: Int,
        uniqueId: String,
    ): String = "https://$address:$httpsPort/applist?" + query(mapOf("uniqueid" to uniqueId))

    /**
     * /launch carries the app id plus the client-generated rikey (hex) and
     * rikeyid (u32) that key the control stream, a minimal display mode, and the
     * audio play mode (Wolf endpoints.hpp create_run_session).
     */
    @Suppress("LongParameterList")
    fun launch(
        address: String,
        httpsPort: Int,
        uniqueId: String,
        appId: String,
        rikeyHex: String,
        rikeyId: Int,
        mode: String,
    ): String =
        "https://$address:$httpsPort/launch?" +
            query(
                mapOf(
                    "uniqueid" to uniqueId,
                    "appid" to appId,
                    "mode" to mode,
                    "additionalStates" to "1",
                    "sops" to "0",
                    "rikey" to rikeyHex,
                    "rikeyid" to rikeyId.toString(),
                    "localAudioPlayMode" to "1",
                    "surroundAudioInfo" to "65538",
                ),
            )

    fun resume(
        address: String,
        httpsPort: Int,
        uniqueId: String,
        rikeyHex: String,
        rikeyId: Int,
    ): String =
        "https://$address:$httpsPort/resume?" +
            query(
                mapOf(
                    "uniqueid" to uniqueId,
                    "rikey" to rikeyHex,
                    "rikeyid" to rikeyId.toString(),
                ),
            )

    fun cancel(
        address: String,
        httpsPort: Int,
        uniqueId: String,
    ): String = "https://$address:$httpsPort/cancel?" + query(mapOf("uniqueid" to uniqueId))

    private fun query(params: Map<String, String>): String =
        params.entries.joinToString("&") { (k, v) ->
            "$k=" + URLEncoder.encode(v, "UTF-8")
        }
}
