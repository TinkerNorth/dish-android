// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.source.store

import com.tinkernorth.dish.architecture.abstracts.AbstractStateSource
import com.tinkernorth.dish.composer.ConnectionSummary
import com.tinkernorth.dish.composer.LinkState
import javax.inject.Inject
import javax.inject.Singleton

data class LinkHistory(
    val connects: Int = 0,
    val drops: Int = 0,
    val upSinceMs: Long? = null,
    val lastDropAtMs: Long? = null,
)

data class LinkHistoryState(
    val connections: Map<String, LinkHistory> = emptyMap(),
    val boundSinceMs: Map<String, Long> = emptyMap(),
    val boundTo: Map<String, String> = emptyMap(),
)

object LinkHistoryPolicy {
    fun isUp(state: LinkState): Boolean = state == LinkState.Connected || state == LinkState.Unstable

    fun onConnections(
        prev: LinkHistoryState,
        summaries: List<ConnectionSummary>,
        nowMs: Long,
    ): LinkHistoryState {
        val next = prev.connections.toMutableMap()
        for (summary in summaries) {
            val history = next[summary.id] ?: LinkHistory()
            val wasUp = history.upSinceMs != null
            val up = isUp(summary.live)
            next[summary.id] =
                when {
                    up && !wasUp -> history.copy(connects = history.connects + 1, upSinceMs = nowMs)
                    !up && wasUp -> history.copy(drops = history.drops + 1, upSinceMs = null, lastDropAtMs = nowMs)
                    else -> history
                }
        }
        return prev.copy(connections = next)
    }

    fun onBindings(
        prev: LinkHistoryState,
        bindings: Map<String, String>,
        nowMs: Long,
    ): LinkHistoryState {
        val since = prev.boundSinceMs.filterKeys { it in bindings }.toMutableMap()
        for ((slotId, connId) in bindings) {
            if (prev.boundTo[slotId] != connId || slotId !in since) since[slotId] = nowMs
        }
        return prev.copy(boundSinceMs = since, boundTo = bindings)
    }
}

@Singleton
class LinkHistoryStore
    @Inject
    constructor() : AbstractStateSource<LinkHistoryState>(LinkHistoryState()) {
        fun update(transform: (LinkHistoryState) -> LinkHistoryState) {
            setState(transform)
        }
    }
