// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.source.store

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.tinkernorth.dish.architecture.abstracts.AbstractStateSource
import com.tinkernorth.dish.source.usb.PathChoice
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UsbPathPreferenceStore
    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) : AbstractStateSource<Map<String, PathChoice>>(
            initialState = readInitial(context),
        ) {
        private val prefs: SharedPreferences =
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        fun choiceFor(
            vendorId: Int,
            productId: Int,
        ): PathChoice? = state.value[keyFor(vendorId, productId)]

        fun setChoice(
            vendorId: Int,
            productId: Int,
            choice: PathChoice,
        ) {
            val key = keyFor(vendorId, productId)
            if (state.value[key] == choice) return
            val next = state.value + (key to choice)
            persist(next)
            setState(next)
        }

        fun clear(
            vendorId: Int,
            productId: Int,
        ) {
            val key = keyFor(vendorId, productId)
            if (key !in state.value) return
            val next = state.value - key
            persist(next)
            setState(next)
        }

        private fun persist(map: Map<String, PathChoice>) {
            val raw = Json.encodeToString(map.mapValues { it.value.toStorageValue() })
            prefs.edit { putString(KEY_CHOICES, raw) }
        }

        companion object {
            const val PREFS_NAME = "user_preferences"
            const val KEY_CHOICES = "usb_path_choices"

            fun keyFor(
                vendorId: Int,
                productId: Int,
            ): String = "%04x:%04x".format(vendorId and 0xFFFF, productId and 0xFFFF)

            private fun readInitial(context: Context): Map<String, PathChoice> {
                val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                val raw = prefs.getString(KEY_CHOICES, null) ?: return emptyMap()
                return runCatching {
                    Json
                        .decodeFromString<Map<String, String>>(raw)
                        .mapNotNull { (k, v) -> PathChoice.fromStorageValue(v)?.let { k to it } }
                        .toMap()
                }.getOrDefault(emptyMap())
            }
        }
    }
