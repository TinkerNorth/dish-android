// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.core.controllerdb

// The strings of the active database with docs/controller-db.md section 5's rules: a locale's own
// string, then English; the bundled copy's string for an id the app ships, a served one for an id
// it does not, and a served translation where the bundled copy has none.
class ControllerStrings(
    private val bundled: Map<String, Map<String, String>>,
    private val served: Map<String, Map<String, String>>?,
    private val bundledIds: Set<String>,
) {
    fun lookup(
        key: String,
        locale: String,
    ): String? {
        val bundledLocal = bundled[locale]?.get(key)
        if (bundledLocal != null) return bundledLocal
        val servedLocal = served?.get(locale)?.get(key)
        val appShipsTheKey = bundled[ENGLISH]?.containsKey(key) == true || idOf(key) in bundledIds
        if (servedLocal != null && !appShipsTheKey) return servedLocal
        val bundledEnglish = bundled[ENGLISH]?.get(key)
        if (appShipsTheKey) return servedLocalOrEnglish(servedLocal, bundledEnglish, locale)
        return served?.get(ENGLISH)?.get(key) ?: bundledEnglish
    }

    // A served translation fills in a locale the bundled copy lacks; English stays the bundled one.
    private fun servedLocalOrEnglish(
        servedLocal: String?,
        bundledEnglish: String?,
        locale: String,
    ): String? {
        val isEnglish = locale == ENGLISH
        if (servedLocal != null && !isEnglish) return servedLocal
        return bundledEnglish ?: servedLocal
    }

    fun controllerName(
        id: String,
        locale: String,
    ): String? = lookup("controller.$id.name", locale)

    fun issueTitle(
        id: String,
        locale: String,
    ): String? = lookup("issue.$id.title", locale)

    fun issueBody(
        id: String,
        locale: String,
    ): String? = lookup("issue.$id.body", locale)
}

private const val KEY_SEPARATOR = '.'

private fun idOf(key: String): String = key.substringAfter(KEY_SEPARATOR).substringBeforeLast(KEY_SEPARATOR)

private const val PORTUGUESE = "pt"
private const val BRAZILIAN_PORTUGUESE = "pt-BR"
private val STRINGS_LOCALES = setOf(ENGLISH, "bs", "de", "es", "fr", BRAZILIAN_PORTUGUESE)

// The strings file for a device locale: the app ships Brazilian Portuguese only, so every
// Portuguese reads it, and a language the database has no file for reads English.
fun stringsLocaleFor(
    language: String,
    country: String,
): String {
    if (language == PORTUGUESE) return BRAZILIAN_PORTUGUESE
    val withCountry = "$language-$country"
    if (withCountry in STRINGS_LOCALES) return withCountry
    return if (language in STRINGS_LOCALES) language else ENGLISH
}
