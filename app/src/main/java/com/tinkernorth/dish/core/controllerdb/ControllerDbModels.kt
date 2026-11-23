// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.core.controllerdb

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

// The controller database, schema 1 (dish-website docs/controller-db.md). Every field a schema 1
// reader does not name is ignored, and the reserved ones (section 11) are never read.

@Serializable
data class ControllerDbManifest(
    val schema: Int,
    val dbVersion: Int,
    val publishedAt: String = "",
    val files: Map<String, ManifestFile> = emptyMap(),
)

@Serializable
data class ManifestFile(
    val sha256: String,
    val bytes: Long,
)

@Serializable
data class ControllerDbFile(
    val schema: Int,
    val dbVersion: Int,
    val records: List<ControllerRecord> = emptyList(),
)

@Serializable
data class ControllerRecord(
    val id: String,
    val requires: RecordRequires = RecordRequires(),
    val identity: RecordIdentity = RecordIdentity(),
    val input: RecordInput = RecordInput(),
    val output: RecordOutput = RecordOutput(),
    val expect: RecordExpect = RecordExpect(),
    // Per-platform overrides, read as whatever JSON they are: a platform no build knows, or a shape
    // a later schema gives one, must not cost the record (directRuleOf reads Android's).
    val platforms: JsonElement? = null,
    val support: RecordSupport = RecordSupport(),
)

@Serializable
data class RecordRequires(
    val families: List<String> = emptyList(),
    val ops: List<String> = emptyList(),
)

@Serializable
data class RecordIdentity(
    val usb: List<String> = emptyList(),
    val bluetooth: List<String> = emptyList(),
    val siblings: List<String> = emptyList(),
)

// mapping is a string in schema 1 and reserved as an object for later schemas, so it is read as
// whatever JSON it is and only a string is taken (mappingName).
@Serializable
data class RecordInput(
    val protocol: RecordProtocol = RecordProtocol(),
    val mapping: JsonElement? = null,
    val direct: RecordDirect = RecordDirect(),
)

@Serializable
data class RecordProtocol(
    val ref: String = "",
    val streamsUnprompted: Boolean = false,
)

@Serializable
data class RecordDirect(
    val rule: String = DIRECT_RULE_OPT_IN,
)

@Serializable
data class RecordOutput(
    val route: String? = null,
    val init: RecordInit = RecordInit(),
)

@Serializable
data class RecordInit(
    val kind: String = INIT_NONE,
)

@Serializable
data class RecordExpect(
    val endpoints: ExpectedEndpoints = ExpectedEndpoints(),
)

@Serializable
data class ExpectedEndpoints(
    @SerialName("in") val hasIn: Boolean? = null,
    @SerialName("out") val hasOut: Boolean? = null,
)

@Serializable
data class RecordSupport(
    val verification: RecordVerification = RecordVerification(),
    val issues: List<RecordIssue> = emptyList(),
)

@Serializable
data class RecordVerification(
    val state: String = VERIFICATION_GUESSED,
    val transport: String = TRANSPORT_USB,
)

@Serializable
data class RecordIssue(
    val id: String,
    val device: String? = null,
    val os: JsonObject? = null,
    val transport: String? = null,
    val lane: String? = null,
    val status: String? = null,
)

// What USB enumeration found on the pad's claimed interface.
data class FoundEndpoints(
    val hasIn: Boolean,
    val hasOut: Boolean,
)

const val CONTROLLER_DB_SCHEMA = 1
const val DIRECT_RULE_OPT_IN = "optIn"
const val DIRECT_RULE_AUTO = "auto"
const val INIT_NONE = "none"
const val TRANSPORT_USB = "usb"
const val TRANSPORT_BLUETOOTH = "bluetooth"
const val VERIFICATION_VERIFIED = "verified"
const val VERIFICATION_IMPORTED = "imported"
const val VERIFICATION_FLAGGED = "flagged"
const val VERIFICATION_GUESSED = "guessed"
const val FAMILY_HID = "hid"
const val MAPPING_WESTERN = "western"
