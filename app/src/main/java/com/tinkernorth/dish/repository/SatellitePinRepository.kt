// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.repository

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

// Result of comparing a presented cert fingerprint against the pinned one.
enum class TofuVerdict { TRUST_FIRST_USE, MATCH, MISMATCH }

// Pure TOFU decision: no prior pin trusts this first contact; otherwise the
// presented fingerprint must equal the stored one (hex, case-insensitive).
fun tofuVerdict(
    stored: String?,
    presented: String,
): TofuVerdict =
    when {
        stored == null -> TofuVerdict.TRUST_FIRST_USE
        stored.equals(presented, ignoreCase = true) -> TofuVerdict.MATCH
        else -> TofuVerdict.MISMATCH
    }

// Lowercase hex SHA-256 of a DER-encoded cert. Pure so the verifier and tests share it.
fun sha256FingerprintHex(certDer: ByteArray): String =
    MessageDigest
        .getInstance("SHA-256")
        .digest(certDer)
        .joinToString("") { "%02x".format(it) }

// One SHA-256 cert fingerprint per satellite id (TOFU pin). Per-key storage (vs. one
// JSON list) so forget is a single prefs edit and per-key writes are atomic. Co-tenants
// the connection_store prefs with SatelliteSharedKeyRepository.
@Singleton
class SatellitePinRepository
    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) {
        private val prefs by lazy {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        }

        fun pinnedFingerprint(satelliteId: String): String? = prefs.getString(keyPref(satelliteId), null)

        fun pin(
            satelliteId: String,
            fingerprintHex: String,
        ) {
            prefs.edit { putString(keyPref(satelliteId), fingerprintHex) }
        }

        fun forget(satelliteId: String) {
            prefs.edit { remove(keyPref(satelliteId)) }
        }

        private fun keyPref(id: String): String = "$KEY_PREFIX$id"

        private companion object {
            const val PREFS_NAME = "connection_store"
            const val KEY_PREFIX = "satellite_cert_pin:"
        }
    }
