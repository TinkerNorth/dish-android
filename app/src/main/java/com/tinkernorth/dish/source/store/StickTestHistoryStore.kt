// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.source.store

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.tinkernorth.dish.architecture.abstracts.AbstractStateSource
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class StickTestRecord(
    val driftAtMs: Long? = null,
    val driftLeft: Float? = null,
    val driftRight: Float? = null,
    val suggestedDeadzone: Float? = null,
    val rangeAtMs: Long? = null,
    val reachLeft: Float? = null,
    val reachRight: Float? = null,
    val circularityLeft: Float? = null,
    val circularityRight: Float? = null,
)

@Singleton
class StickTestHistoryStore
    @Inject
    constructor(
        @ApplicationContext context: Context,
        private val json: Json,
    ) : AbstractStateSource<Map<String, StickTestRecord>>(readInitial(context, json)) {
        private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        fun recordFor(key: String): StickTestRecord? = state.value[key]

        fun noteDrift(
            key: String,
            left: Float,
            right: Float,
            suggestedDeadzone: Float,
            nowMs: Long = System.currentTimeMillis(),
        ) {
            update(key) { it.copy(driftAtMs = nowMs, driftLeft = left, driftRight = right, suggestedDeadzone = suggestedDeadzone) }
        }

        fun noteRange(
            key: String,
            reachLeft: Float,
            reachRight: Float,
            circularityLeft: Float?,
            circularityRight: Float?,
            nowMs: Long = System.currentTimeMillis(),
        ) {
            update(key) {
                it.copy(
                    rangeAtMs = nowMs,
                    reachLeft = reachLeft,
                    reachRight = reachRight,
                    circularityLeft = circularityLeft,
                    circularityRight = circularityRight,
                )
            }
        }

        private fun update(
            key: String,
            transform: (StickTestRecord) -> StickTestRecord,
        ) {
            setState { it + (key to transform(it[key] ?: StickTestRecord())) }
            prefs.edit { putString(KEY, json.encodeToString(SERIALIZER, state.value)) }
        }

        companion object {
            const val PREFS_NAME = "stick_test_history"
            const val KEY = "records"
            private val SERIALIZER = MapSerializer(String.serializer(), StickTestRecord.serializer())

            fun keyFor(
                vendorId: Int,
                productId: Int,
                name: String,
            ): String = if (vendorId != 0 && productId != 0) "$vendorId:$productId" else name

            private fun readInitial(
                context: Context,
                json: Json,
            ): Map<String, StickTestRecord> {
                val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(KEY, null) ?: return emptyMap()
                return runCatching { json.decodeFromString(SERIALIZER, raw) }.getOrDefault(emptyMap())
            }
        }
    }
