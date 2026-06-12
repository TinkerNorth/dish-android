// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.core.model

import androidx.annotation.StringRes
import com.tinkernorth.dish.R
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

// Not a wire field, assigned client-side by the discovery merge.
enum class DiscoverySource(
    @param:StringRes val labelRes: Int,
) {
    BROADCAST(R.string.discovery_source_broadcast),
    MDNS(R.string.discovery_source_mdns),
    BOTH(R.string.discovery_source_both),
    MANUAL(R.string.discovery_source_manual),
}

@Serializable
data class DiscoveredServer(
    val name: String = "",
    val ip: String = "",
    val udpPort: Int = 9876,
    // Pairing and the connection API share the satellite HTTPS server on 9443.
    val pairPort: Int = 9443,
    val httpPort: Int = 9443,
    // Stable per-install id from the broadcast beacon ("machineId") / mDNS TXT
    // ("mid"). Empty for satellites that predate it. See [stableKey].
    val machineId: String = "",
    @Transient val source: DiscoverySource = DiscoverySource.BROADCAST,
)

/**
 * The stable identity a dish keys a satellite on. Prefers [DiscoveredServer.machineId],
 * a persisted per-install id that survives DHCP address changes, and falls back to
 * ip:udpPort for older satellites that don't advertise one. Both discovery paths and
 * the remembered-satellite store key on this, so the same physical receiver collapses
 * to a single entry instead of one row per IP.
 */
val DiscoveredServer.stableKey: String
    get() = if (machineId.isNotBlank()) "mid:$machineId" else "$ip:$udpPort"

@Serializable
data class PairResponse(
    val ok: Boolean = false,
    val error: String? = null,
    val sharedKey: String? = null,
)

// Wire DTOs for the declarative session contract (satellite docs/contract.md).
// `result` / `code` / feature slugs are protocol constants, never localized;
// the client maps the ones it knows onto UI strings and shows the raw code
// otherwise.

@Serializable
data class SessionMotionDto(
    val sinkSupportedForType: Boolean = false,
    val backendOk: Boolean = false,
)

@Serializable
data class ControllerApplyDto(
    val ctrlIdx: Int = 0,
    val result: String = "",
    val appliedType: Int = 0,
    val motion: SessionMotionDto = SessionMotionDto(),
) {
    val ok: Boolean get() = result == APPLY_OK

    // replugFailed leaves the PREVIOUS pad untouched and working
    // (appliedType reports it): the slot is still live, just not the
    // requested type. Streams must keep flowing to it.
    val slotIsLive: Boolean get() = ok || result == APPLY_REPLUG_FAILED

    companion object {
        const val APPLY_OK = "ok"
        const val APPLY_REPLUG_FAILED = "replugFailed"
    }
}

@Serializable
data class HostFeatureGrantDto(
    val granted: Boolean = false,
    val reason: String? = null,
)

@Serializable
data class HostFeaturesDto(
    val mouseControl: HostFeatureGrantDto = HostFeatureGrantDto(),
)

/** PUT /api/connections response. Also doubles as the error body (`error`/`code`). */
@Serializable
data class SessionResponse(
    val connectionId: String? = null,
    val token: String? = null,
    val sessionSalt: String? = null,
    val epoch: Int = 0,
    val maxControllers: Int = 16,
    val protocolVersion: Int = 1,
    val controllers: List<ControllerApplyDto> = emptyList(),
    val hostFeatures: HostFeaturesDto = HostFeaturesDto(),
    val error: String? = null,
    // Machine-readable 401 cause: NOT_PAIRED | BAD_PROOF. Either is terminal:
    // stop retrying and surface "re-pair needed".
    val code: String? = null,
) {
    val unauthorized: Boolean get() = code == CODE_NOT_PAIRED || code == CODE_BAD_PROOF

    companion object {
        const val CODE_NOT_PAIRED = "NOT_PAIRED"
        const val CODE_BAD_PROOF = "BAD_PROOF"
    }
}

/** PUT /api/connections/{id}/controllers/{idx} response. */
@Serializable
data class ControllerPutResponse(
    val epoch: Int = 0,
    val controller: ControllerApplyDto? = null,
    val error: String? = null,
    val code: String? = null,
)

@Serializable
data class SessionViewControllerDto(
    val ctrlIdx: Int = 0,
    val active: Boolean = false,
    val appliedType: Int = 0,
    val touchpadMode: String = "off",
)

/** GET /api/connections/{id}: the reconcile endpoint's applied state. */
@Serializable
data class SessionViewDto(
    val connectionId: String? = null,
    val epoch: Int = 0,
    val controllers: List<SessionViewControllerDto> = emptyList(),
    val hostFeatures: HostFeaturesDto = HostFeaturesDto(),
    val error: String? = null,
    val code: String? = null,
)

// GET /api/catalog: the satellite's localized controller-type catalog. Type
// NAMES/descriptions render from here (server-owned emulation targets; new
// types must work on old apps); feature slugs are capability data the client
// only offers when it has code for them.

@Serializable
data class CatalogFeatureDto(
    val supported: Boolean = false,
    val requires: String? = null,
)

@Serializable
data class CatalogImageDto(
    val href: String = "",
    val etag: String = "",
)

@Serializable
data class CatalogTypeDto(
    val id: Int = 0,
    val slug: String = "",
    val name: String = "",
    val shortName: String = "",
    val description: String = "",
    val image: CatalogImageDto = CatalogImageDto(),
    val features: Map<String, CatalogFeatureDto> = emptyMap(),
)

@Serializable
data class CatalogHostFeatureDto(
    val supported: Boolean = false,
    val modes: List<String> = emptyList(),
)

@Serializable
data class CatalogDto(
    val locale: String = "en",
    val protocolVersion: Int = 1,
    val serverVersion: String = "",
    val controllerTypes: List<CatalogTypeDto> = emptyList(),
    val hostFeatures: Map<String, CatalogHostFeatureDto> = emptyMap(),
)

data class ControllerEntry(
    val id: Int,
    val name: String,
    val controllerIndex: Int,
    val isDisconnected: Boolean = false,
    val disconnectTimeLeft: Int = 0,
)
