// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.data.network

import com.tinkernorth.dish.ui.bluetooth.BluetoothGamepadRegistry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Surfaces the set of bonded Bluetooth devices on the phone that aren't
 * already managed by the app — i.e. devices the user can "Claim" back into
 * Dish without re-pairing. Snapshot-based: the Android framework doesn't push
 * us a stream of bonded-set changes, so the activity calls [refresh] on
 * relevant lifecycle hooks (resume, BT state changes, after Forget/Claim).
 *
 * Filtering: CoD-driven. By default only devices whose major class plausibly
 * acts as a HID host show up; the user can flip [setShowAll] to see every
 * non-accessory bond. See [BondedHostFilter] for the rules.
 */
@Singleton
class BondedHostsRepo
    @Inject
    constructor(
        private val env: BluetoothEnvironment,
        private val store: ConnectionStore,
    ) {
        enum class Permission { GRANTED, DENIED }

        data class State(
            val permission: Permission,
            val showAll: Boolean,
            /** Every bonded host that survived the accessory filter and isn't already remembered. */
            val all: List<BondedHost>,
        ) {
            /** Subset visible in the default-filtered tier. */
            val visible: List<BondedHost>
                get() =
                    if (showAll) {
                        all
                    } else {
                        all.filter { BondedHostFilter.isLikelyHost(it.kind) }
                    }
        }

        private val _state = MutableStateFlow(snapshot(showAll = false))
        val state: StateFlow<State> = _state.asStateFlow()

        fun refresh() {
            _state.value = snapshot(showAll = _state.value.showAll)
        }

        fun setShowAll(showAll: Boolean) {
            if (_state.value.showAll == showAll) return
            _state.value = snapshot(showAll = showAll)
        }

        private fun snapshot(showAll: Boolean): State {
            if (!env.hasConnectPermission()) {
                return State(Permission.DENIED, showAll, emptyList())
            }
            val rememberedIds = store.rememberedBt().map { it.id }.toSet()
            val list =
                env
                    .bondedDevices()
                    .filterNot { BondedHostFilter.isExcludedAccessory(it.majorClass) }
                    .map { d ->
                        BondedHost(
                            mac = d.mac,
                            name = d.name?.takeIf { it.isNotBlank() } ?: d.mac,
                            kind = BondedHostFilter.classify(d.majorClass),
                        )
                    }.filterNot { BluetoothGamepadRegistry.idFor(it.mac) in rememberedIds }
                    .distinctBy { it.mac }
                    .sortedWith(compareBy({ it.kind == BondedHostKind.OTHER }, { it.name.lowercase() }))
            return State(Permission.GRANTED, showAll, list)
        }
    }
