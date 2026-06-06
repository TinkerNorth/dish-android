// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.source.connection

import com.tinkernorth.dish.core.net.jsonGet
import kotlin.random.Random

/**
 * Reverse-pairing helper (Path B): the dish shows its own PIN and asks the
 * operator to accept it on the satellite. Kept pure so the PIN format and the
 * `/api/pair/status` classification are unit-testable without a device.
 */
internal object PairingApproval {
    /**
     * Four digits, matching the satellite's own PIN format so the two screens
     * read identically when the operator compares them. [random] is injectable
     * so a test can assert the shape deterministically.
     */
    fun generatePin(random: Random = Random.Default): String = buildString { repeat(PIN_DIGITS) { append(random.nextInt(10)) } }

    sealed interface Status {
        data class Approved(
            val sharedKeyHex: String,
        ) : Status

        object Pending : Status

        // Denied, expired, consumed, or an unparseable reply — all mean "stop polling".
        object Declined : Status
    }

    /**
     * Classify a `/api/pair/status` body. Approved only when the satellite both
     * says so AND hands back a full 32-byte (64-hex) key, so a malformed reply
     * can never be mistaken for a usable session key.
     */
    fun classifyStatus(json: String): Status {
        val status = jsonGet(json, "status")
        val key = jsonGet(json, "sharedKey")
        return when {
            status == "approved" && key != null && key.length == SHARED_KEY_HEX_LEN ->
                Status.Approved(key)
            status == "pending" -> Status.Pending
            else -> Status.Declined
        }
    }

    private const val PIN_DIGITS = 4
    private const val SHARED_KEY_HEX_LEN = 64
}
