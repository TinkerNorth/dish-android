// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.core.net.moonlight

import kotlinx.serialization.Serializable

/**
 * A Moonlight-compatible host (Sunshine / Apollo / Vibepollo / Wolf) the dish
 * can pair with and stream input to. Discovered over mDNS (`_nvstream._tcp`) or
 * entered manually.
 */
@Serializable
data class MoonlightHost(
    val name: String,
    val address: String,
    // 47989 (HTTP) and 47984 (HTTPS) are the documented defaults; both are read
    // from /serverinfo when known and never assumed elsewhere.
    val httpPort: Int = DEFAULT_HTTP_PORT,
    val httpsPort: Int = DEFAULT_HTTPS_PORT,
    // The uniqueid the host answered /serverinfo with, empty until it has been asked: the witness
    // that tells the machine this client paired with from another one behind the same address.
    val uniqueId: String = "",
    val manual: Boolean = false,
) {
    val id: String get() = moonlightHostIdFor(address)

    companion object {
        const val DEFAULT_HTTP_PORT = 47989
        const val DEFAULT_HTTPS_PORT = 47984
        const val ID_PREFIX = "moonlight:"
    }
}

// The address, and never the uniqueid: a scan meets a host before it has been asked who it is and
// a typed address meets it after, so an id built from the answer named one host twice.
internal fun moonlightHostIdFor(address: String): String = "${MoonlightHost.ID_PREFIX}$address"

@Serializable
data class RememberedMoonlight(
    val id: String,
    val name: String,
    val address: String,
    val httpPort: Int = MoonlightHost.DEFAULT_HTTP_PORT,
    val httpsPort: Int = MoonlightHost.DEFAULT_HTTPS_PORT,
    val uniqueId: String = "",
    // The app id and title the session creator settled on. Per host, not per
    // binding: every controller on this host shares the one session.
    val lastAppId: String = "",
    val lastAppName: String = "",
    // The emulated-device pick (CONTROLLER_ARRIVAL type): Auto/Xbox/PS/Nintendo.
    val emulatedType: Int = AUTO,
    // Whether the host has ever accepted this device, as opposed to one the user has
    // only shown durable interest in (added by address, or bound to). Both belong in
    // this list; only the first is trust. Defaults true because every record written
    // before this field existed was written by a completed pairing.
    val paired: Boolean = true,
) {
    fun toHost(): MoonlightHost =
        MoonlightHost(
            name = name,
            address = address,
            httpPort = httpPort,
            httpsPort = httpsPort,
            uniqueId = uniqueId,
        )
}

/**
 * One host's two records as one: [refiled] under the id [filed] already has. The machine this device
 * trusts is described whole by one of them, [trustedOf] the two, so the uniqueid and the pairing come
 * from that one; a pairing whose two records hold pins that disagree ([pinsAgree] false) is not kept.
 * The app pick comes from [refiled] where it has one.
 */
internal fun foldedRecords(
    filed: RememberedMoonlight?,
    refiled: RememberedMoonlight,
    pinsAgree: Boolean,
): RememberedMoonlight {
    if (filed == null) return refiled
    val trusted = trustedOf(filed, refiled)
    val refiledPickedAnApp = refiled.lastAppId.isNotEmpty()
    return refiled.copy(
        uniqueId = trusted.uniqueId,
        lastAppId = if (refiledPickedAnApp) refiled.lastAppId else filed.lastAppId,
        lastAppName = if (refiledPickedAnApp) refiled.lastAppName else filed.lastAppName,
        paired = trusted.paired && pinsAgree,
    )
}

/** Of one host's two records, the one that describes the machine it trusts: the one that paired, else [refiled]. */
internal fun trustedOf(
    filed: RememberedMoonlight,
    refiled: RememberedMoonlight,
): RememberedMoonlight {
    val onlyTheFiledOnePaired = filed.paired && !refiled.paired
    return if (onlyTheFiledOnePaired) filed else refiled
}

/**
 * The user-facing emulated-device picker mapped onto CONTROLLER_ARRIVAL types.
 * AUTO is a client convenience (Wolf control.hpp uses 0xFF): the session resolves
 * it to the type that best matches the local controller before it hits the wire.
 * It is 0xFF and never 0, because 0 is CONTROLLER_TYPE_UNKNOWN on the wire and
 * the satellite's own CONTROLLER_TYPE_XBOX as well, so a stored 0 is ambiguous
 * twice over; [fromStored] migrates one back to Auto on read.
 */
const val AUTO = 0xFF
const val XBOX = CONTROLLER_TYPE_XBOX
const val PLAYSTATION = CONTROLLER_TYPE_PS
const val NINTENDO = CONTROLLER_TYPE_NINTENDO

val ORDER = listOf(AUTO, XBOX, PLAYSTATION, NINTENDO)

fun fromStored(stored: Int): Int = if (stored == CONTROLLER_TYPE_UNKNOWN) AUTO else stored

fun resolveMoonlightEmulatedType(
    picked: Int,
    sourceHasMotion: Boolean,
): Int =
    when {
        picked != AUTO -> picked
        sourceHasMotion -> PLAYSTATION
        else -> XBOX
    }

fun typeMaximum(type: Int): Int = if (type == PLAYSTATION) PLAYSTATION_MAXIMUM else BASE_MAXIMUM

fun capabilityBits(
    type: Int,
    sourceBits: Int,
): Int = typeMaximum(type) and sourceBits

// The capability bits the host reads when a pad arrives, and only then. Wolf's create_new_joypad
// (control/input_handler.cpp) reads the accelerometer and gyro bits to apply its per-client
// motion override, to promote an unknown type to PlayStation, and to ask the client for motion
// events at all; it reads no other bit, nor the supported buttons. The pad it builds follows the
// type alone: every PlayStation pad is one DualSense with a touchpad and a lightbar, and
// controller_touch places a touch on any of them.
const val BITS_READ_AT_ARRIVAL = CAP_ACCELEROMETER or CAP_GYRO

fun supportedButtons(capabilities: Int): Int =
    if (capabilities and CAP_TOUCHPAD != 0) {
        BASE_BUTTONS or BTN_TOUCHPAD
    } else {
        BASE_BUTTONS
    }

// Trigger rumble and battery describe the PHYSICAL pad's surfaces, not the
// emulated identity, so every type may carry them (moonlight-qt advertises the
// same way); the source bits decide whether they actually ride.
private const val BASE_MAXIMUM =
    CAP_ANALOG_TRIGGERS or CAP_RUMBLE or
        CAP_TRIGGER_RUMBLE or CAP_BATTERY

private const val PLAYSTATION_MAXIMUM = 0xFF

private const val BASE_BUTTONS = 0xFFFF
