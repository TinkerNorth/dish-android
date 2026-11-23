// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.core.controllerdb

import kotlinx.serialization.json.Json

internal val testJson = Json { ignoreUnknownKeys = true }

internal fun record(
    id: String,
    usb: List<String> = emptyList(),
    bluetooth: List<String> = emptyList(),
    family: String = "xinput360",
    state: String = VERIFICATION_VERIFIED,
    transport: String = TRANSPORT_USB,
    init: String = INIT_NONE,
    mapping: String? = null,
    issues: List<RecordIssue> = emptyList(),
): ControllerRecord =
    ControllerRecord(
        id = id,
        requires = RecordRequires(listOf(family), emptyList()),
        identity = RecordIdentity(usb = usb, bluetooth = bluetooth),
        input =
            RecordInput(
                protocol = RecordProtocol(ref = family),
                mapping = mapping?.let { kotlinx.serialization.json.JsonPrimitive(it) },
                direct = RecordDirect(if (state == VERIFICATION_VERIFIED) DIRECT_RULE_AUTO else DIRECT_RULE_OPT_IN),
            ),
        output = RecordOutput(route = "interruptOut", init = RecordInit(init)),
        expect = RecordExpect(ExpectedEndpoints(hasIn = true, hasOut = true)),
        support = RecordSupport(RecordVerification(state, transport), issues),
    )

internal fun ControllerRecord.expecting(
    hasIn: Boolean?,
    hasOut: Boolean?,
): ControllerRecord = copy(expect = RecordExpect(ExpectedEndpoints(hasIn, hasOut)))

internal fun ControllerRecord.requiring(
    families: List<String>,
    ops: List<String> = emptyList(),
): ControllerRecord = copy(requires = RecordRequires(families, ops))

internal fun db(
    version: Int,
    vararg records: ControllerRecord,
): ControllerDbFile = ControllerDbFile(schema = CONTROLLER_DB_SCHEMA, dbVersion = version, records = records.toList())
