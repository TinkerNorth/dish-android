// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.composer

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

// What the person says the report is about, by the names every Dish client and Satellite share.
enum class ReportCategory(
    val wire: String,
) {
    NOT_RECOGNISED("notRecognised"),
    NO_FEEDBACK("noFeedback"),
    CONNECTION("connection"),
    AUDIO("audio"),
    OTHER("other"),
    QUESTION("question"),
}

// The person's own report: their category, their words as they wrote them, and the controller it
// concerns (its record id, or the name it gives itself), or null.
data class BundleReport(
    val category: ReportCategory,
    val text: String,
    val controller: String?,
)

internal fun reportJson(report: BundleReport): JsonObject =
    buildJsonObject {
        put("category", report.category.wire)
        put("text", report.text)
        put("controller", report.controller)
    }
