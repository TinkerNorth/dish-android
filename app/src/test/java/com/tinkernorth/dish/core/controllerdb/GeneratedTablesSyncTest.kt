// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.core.controllerdb

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

// The compiled identity tables come from scripts/gen_controller_tables.py; the rows the JVM hands
// native come from servedRowsOf. This holds the two to one mapping of the bundled database: the
// header the build generates must be the asset's usb records, in order, under the same native enum
// values the Kotlin side sends.
class GeneratedTablesSyncTest {
    private val dir = File("src/main/assets/controllers/v1")
    private val manifest = (parseManifest(File(dir, MANIFEST_FILE).readBytes(), testJson) as ParsedManifest.Ok).manifest
    private val db = (parseControllerDb(File(dir, CONTROLLERS_FILE).readBytes(), manifest.dbVersion, testJson) as ParsedDb.Ok).db
    private val english = parseStrings(File(dir, ENGLISH_STRINGS_FILE).readBytes(), testJson).orEmpty()
    private val header = File(System.getProperty(HEADER_PROPERTY) ?: error("$HEADER_PROPERTY is not set")).readText()

    private data class Row(
        val vid: Int,
        val pid: Int,
        val name: String,
        val parser: String,
        val init: String,
        val order: String,
    )

    private fun table(name: String): List<Row> {
        val body = header.substringAfter("KnownDevice $name[] = {").substringBefore("\n};")
        return ROW.findAll(body).map(::rowOf).toList()
    }

    private fun rowOf(match: MatchResult): Row {
        val groups = match.groupValues
        val name = groups[GROUP_NAME].replace("\\\"", "\"")
        return Row(
            groups[GROUP_VID].toInt(HEX),
            groups[GROUP_PID].toInt(HEX),
            name,
            groups[GROUP_PARSER],
            groups[GROUP_INIT],
            groups[GROUP_ORDER],
        )
    }

    private fun expectedRows(records: List<ControllerRecord>): List<Row> {
        val index = ControllerDbIndex(ControllerDbFile(1, db.dbVersion, records), ControllerStrings(emptyMap(), null, emptySet()))
        val served = servedRowsOf(index)
        val names = records.flatMap { record -> record.identity.usb.map { english.getValue("controller.${record.id}.name") } }
        return (0 until served.size).map { i ->
            Row(
                served.vendorIds[i],
                served.productIds[i],
                names[i],
                PARSER_NAMES.getValue(served.parsers[i]),
                INIT_NAMES.getValue(served.inits[i]),
                ORDER_NAMES.getValue(served.orders[i]),
            )
        }
    }

    private fun usb(verified: Boolean): List<ControllerRecord> =
        db.records.filter {
            val isUsb = it.support.verification.transport == TRANSPORT_USB
            val isVerified = it.support.verification.state == VERIFICATION_VERIFIED
            isUsb && isVerified == verified
        }

    @Test
    fun `the generated kKnown is the asset's verified usb records in order`() {
        assertEquals(expectedRows(usb(verified = true)), table("kKnown"))
    }

    @Test
    fun `the generated kImported is the asset's other usb records in order`() {
        assertEquals(expectedRows(usb(verified = false)), table("kImported"))
    }

    @Test
    fun `the generated kBluetoothKnown is the asset's bluetooth identities`() {
        val bluetooth = db.records.filter { it.support.verification.transport == TRANSPORT_BLUETOOTH }
        val identities = bluetooth.flatMap { r -> r.identity.bluetooth.map { parseIdentity(it) } }
        assertEquals(identities, table("kBluetoothKnown").map { it.vid to it.pid })
    }

    @Test
    fun `the header is generated from the bundled dbVersion`() {
        assertEquals(true, header.contains("kGeneratedDbVersion = ${db.dbVersion};"))
    }

    private companion object {
        const val HEADER_PROPERTY = "dish.controllerTablesHeader"
        const val HEX = 16
        const val GROUP_VID = 1
        const val GROUP_PID = 2
        const val GROUP_NAME = 3
        const val GROUP_PARSER = 4
        const val GROUP_INIT = 5
        const val GROUP_ORDER = 6
        val ROW =
            Regex(
                "\\{0x([0-9A-F]{4}), 0x([0-9A-F]{4}), \"((?:[^\"\\\\]|\\\\.)*)\", Parser::(\\w+), InitKind::(\\w+), ButtonOrder::(\\w+)\\}",
            )

        // usb_parsers.h's enums by value, as the generated header spells them.
        val PARSER_NAMES =
            mapOf(
                1 to "XINPUT_360",
                2 to "XBOX_ONE_GIP",
                3 to "DUALSHOCK4",
                4 to "DUALSENSE",
                5 to "SWITCH_PRO_USB",
                6 to "STADIA",
                7 to "GENERIC_HID_GAMEPAD",
                8 to "XINPUT_360_WIRELESS",
                9 to "STEAM_CONTROLLER",
                10 to "SWITCH_N64_USB",
                11 to "STEAM_TRITON",
                12 to "STEAM_CONTROLLER_BLE",
            )
        val INIT_NAMES =
            mapOf(
                0 to "NONE",
                1 to "XBOX_ONE_POWERON",
                2 to "SWITCH_PRO_HANDSHAKE",
                3 to "XBOX_ONE_S",
                4 to "STEAM_QUIET",
                5 to "STEAM_TRITON_QUIET",
            )
        val ORDER_NAMES = mapOf(0 to "WESTERN", 1 to "SWITCH", 2 to "EIGHTBITDO", 3 to "PLAYSTATION_CLASSIC")
    }
}
