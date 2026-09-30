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
}

// Persisted in cloud-backed user_preferences; add values, never rename existing ones.
private const val STORAGE_DIRECT = "direct"
private const val STORAGE_STANDARD = "standard"

// Null is Auto: nothing stored, or a value from a build this one does not know.
internal fun pathChoiceFromStorage(value: String?): PathChoice? =
    when (value) {
        STORAGE_DIRECT -> PathChoice.Direct
        STORAGE_STANDARD -> PathChoice.Standard
        else -> null
    }
