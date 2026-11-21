// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.core.controllerdb

enum class ControllerDbSource(
    val wire: String,
) {
    BUNDLED("bundled"),
    CACHE("cache"),
}

// One database as loaded: the bundled copy or a verified download.
class LoadedControllerDb(
    val db: ControllerDbFile,
    val strings: Map<String, Map<String, String>>,
)

// The database the app reads: its index, which copy it is, and the bundled copy's version.
class ActiveControllerDb(
    val index: ControllerDbIndex,
    val source: ControllerDbSource,
    val bundledVersion: Int,
) {
    val activeVersion: Int get() = index.dbVersion
}

// Section 6.2: bundled first, then the cached download when its dbVersion is greater. A newer
// database replaces the whole set; a served record that is inert keeps the bundled record's input
// and output for its id while its own issues show.
fun activeControllerDb(
    bundled: LoadedControllerDb,
    cached: LoadedControllerDb?,
): ActiveControllerDb {
    val bundledIds =
        bundled.db.records
            .map { it.id }
            .toSet()
    val isNewer = cached != null && cached.db.dbVersion > bundled.db.dbVersion
    if (cached == null || !isNewer) {
        val strings = ControllerStrings(bundled.strings, null, bundledIds)
        return ActiveControllerDb(ControllerDbIndex(bundled.db, strings), ControllerDbSource.BUNDLED, bundled.db.dbVersion)
    }
    val served = cached
    val bundledById = bundled.db.records.associateBy { it.id }
    val records = served.db.records.map { keepBundledWhereInert(it, bundledById) }
    val strings = ControllerStrings(bundled.strings, served.strings, bundledIds)
    val index = ControllerDbIndex(served.db.copy(records = records), strings)
    return ActiveControllerDb(index, ControllerDbSource.CACHE, bundled.db.dbVersion)
}

private fun keepBundledWhereInert(
    served: ControllerRecord,
    bundledById: Map<String, ControllerRecord>,
): ControllerRecord {
    val bundled = bundledById[served.id] ?: return served
    if (!isInert(served)) return served
    return bundled.copy(support = served.support)
}
