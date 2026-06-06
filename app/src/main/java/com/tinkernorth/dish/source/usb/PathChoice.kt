// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.source.usb

// A user's explicit per-model path override. Absence of a stored value means Auto: verified models
// resolve to Direct, everything else to Standard.
enum class PathChoice {
    Direct,
    Standard,
    ;

    fun toStorageValue(): String =
        when (this) {
            Direct -> STORAGE_DIRECT
            Standard -> STORAGE_STANDARD
        }

    companion object {
        // Persisted in cloud-backed user_preferences; add values, never rename existing ones.
        private const val STORAGE_DIRECT = "direct"
        private const val STORAGE_STANDARD = "standard"

        fun fromStorageValue(value: String?): PathChoice? =
            when (value) {
                STORAGE_DIRECT -> Direct
                STORAGE_STANDARD -> Standard
                else -> null
            }
    }
}
