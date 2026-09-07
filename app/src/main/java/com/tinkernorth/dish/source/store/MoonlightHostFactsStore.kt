// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.source.store

import com.tinkernorth.dish.architecture.abstracts.AbstractStateSource
import com.tinkernorth.dish.core.net.moonlight.MoonlightXml
import javax.inject.Inject
import javax.inject.Singleton

data class MoonlightHostFacts(
    val hostname: String,
    val state: String,
    val appVersion: String?,
    val gfeVersion: String?,
    val currentGame: Int,
    val probedAtMs: Long,
)

@Singleton
class MoonlightHostFactsStore
    @Inject
    constructor() : AbstractStateSource<Map<String, MoonlightHostFacts>>(emptyMap()) {
        fun note(
            hostId: String,
            info: MoonlightXml.ServerInfo,
            nowMs: Long = System.currentTimeMillis(),
        ) {
            val facts =
                MoonlightHostFacts(
                    hostname = info.hostname,
                    state = info.state,
                    appVersion = info.appVersion,
                    gfeVersion = info.gfeVersion,
                    currentGame = info.currentGame,
                    probedAtMs = nowMs,
                )
            setState { it + (hostId to facts) }
        }

        fun factsFor(hostId: String): MoonlightHostFacts? = state.value[hostId]
    }
