// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

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

/**
 * Durable per-slot motion preference: should the user-facing "stream gyro"
 * toggle be on for this slot?
 *
 * **Why per-slot, not global:** a player may have a DualSense pad (motion
 * great for shooters) routed to one satellite and an Xbox-typed virtual
 * controller (motion not useful) routed to another. The receiver-side
 * "Xbox virtual pads have no IMU surface" reality (see satellite
 * `vigem_adapter.cpp::submitMotion`) makes a global toggle wrong by
 * default. Per-slot lets the same dish be honest about each slot.
 *
 * **Absence semantics:** [get] returns null when the slot has never been
 * touched. Callers ([MotionEnabledStore]) collapse null onto a global
 * default (currently true — "motion on unless I turned it off"). The
 * repository itself does NOT bake in the default; that's a policy
 * decision that belongs in the in-memory store.
 *
 * Storage shape: single `SharedPreferences` entry holding a JSON list of
 * `MotionPreference` records. The same shape and write-lock discipline as
 * [RememberedSatelliteRepository] — see that file for the rationale.
 */
@Serializable
data class MotionPreference(
    /** Slot id — `VIRTUAL_SLOT_ID` for the touch overlay, or the integer-as-string `InputDevice` id for a physical pad. */
    val slotId: String,
    /** True ⇒ the dish should stream motion for this slot when otherwise capable. */
    val enabled: Boolean,
)

@Singleton
class MotionPreferenceRepository
    @Inject
    constructor(
        @ApplicationContext context: Context,
        private val json: Json,
    ) : KeyedRepository<String, MotionPreference> {
        private val prefs by lazy {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        }
        private val writeLock = Any()

        override fun keyOf(value: MotionPreference): String = value.slotId

        override fun get(key: String): MotionPreference? = all().firstOrNull { it.slotId == key }

        override fun all(): List<MotionPreference> {
            val raw = prefs.getString(KEY_LIST, null) ?: return emptyList()
            return runCatching {
                json.decodeFromString(ListSerializer(MotionPreference.serializer()), raw)
            }.getOrElse { err ->
                // Surface a one-line WARN when we can't parse what we
                // previously wrote — without this the user silently loses
                // every motion toggle they've configured and there's no
                // breadcrumb pointing at the prefs file. The repository
                // still falls back to emptyList() so app startup keeps
                // working (the alternative — a crash on first read —
                // would brick the dish on a corrupted prefs).
                Log.w(
                    TAG,
                    "Failed to decode motion-preference list; treating as empty. " +
                        "User-facing impact: all per-slot motion toggles reset to default. " +
                        "Cause: ${err.javaClass.simpleName}: ${err.message}",
                )
                emptyList()
            }
        }

        override fun put(
            key: String,
            value: MotionPreference,
        ) {
            synchronized(writeLock) {
                val list = all().toMutableList()
                list.removeAll { it.slotId == key }
                list += value
                persist(list)
            }
        }

        override fun remove(key: String) {
            synchronized(writeLock) {
                val list = all().filterNot { it.slotId == key }
                persist(list)
            }
        }

        override fun clear() {
            synchronized(writeLock) {
                prefs.edit { remove(KEY_LIST) }
            }
        }

        private fun persist(list: List<MotionPreference>) {
            val raw = json.encodeToString(ListSerializer(MotionPreference.serializer()), list)
            prefs.edit { putString(KEY_LIST, raw) }
        }

        private companion object {
            const val TAG = "MotionPreferenceRepository"
            const val PREFS_NAME = "motion_preferences"
            const val KEY_LIST = "preferences"
        }
    }
