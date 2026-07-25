// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.repository

import com.tinkernorth.dish.core.model.CatalogDto
import com.tinkernorth.dish.core.model.DiscoveredServer
import com.tinkernorth.dish.core.model.HostFeatureSet
import com.tinkernorth.dish.core.net.DiscoveryGateway
import com.tinkernorth.dish.source.store.SatelliteHostFeaturesStore
import kotlinx.serialization.json.Json
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Per-satellite controller-type catalog (GET /api/catalog). Static per server
 * version + locale, so it is fetched once and revalidated via ETag; a 304
 * serves the cache. The "Emulate" picker renders from this instead of a
 * hardcoded enum: a controller type newer than the app still gets a name and
 * description (server-provided strings; bundled overrides apply only to slugs
 * the app recognizes).
 */
@Singleton
class SatelliteCatalogRepository
    @Inject
    constructor(
        private val gateway: DiscoveryGateway,
        private val json: Json,
        private val hostFeaturesStore: SatelliteHostFeaturesStore,
        private val legacyCatalogTranslator: LegacyCatalogTranslator,
    ) {
        private data class CacheEntry(
            val etag: String?,
            val catalog: CatalogDto,
        )

        private val cache = java.util.concurrent.ConcurrentHashMap<String, CacheEntry>()

        /**
         * The catalog for [server], or null when it has never been reachable.
         * Stale-on-error: a transient fetch failure serves the last good copy
         * rather than degrading the picker.
         */
        suspend fun catalogFor(
            server: DiscoveredServer,
            satelliteId: String,
        ): CatalogDto? {
            val cached = cache[satelliteId]
            val reply =
                runCatching {
                    gateway.catalog(
                        server.ip,
                        server.httpPort,
                        acceptLanguage = Locale.getDefault().toLanguageTag(),
                        etag = cached?.etag,
                    )
                }.getOrNull() ?: return cached?.catalog
            if (reply.notModified) {
                // 304: the cache is still authoritative, so re-publish its host features for late observers.
                cached?.catalog?.let { hostFeaturesStore.setFeatures(satelliteId, HostFeatureSet.fromCatalog(it)) }
                return cached?.catalog
            }
            if (reply.status != 200 || reply.body.isBlank()) return cached?.catalog
            val catalog =
                runCatching { json.decodeFromString(CatalogDto.serializer(), reply.body) }
                    .getOrNull() ?: return cached?.catalog
            // All catalogVersion handling is the translator's: legacy/absent is substituted for a
            // known catalog here, so the cache, host features and every caller see the normalized
            // shape and the rest of the app never branches on the version.
            val normalized = legacyCatalogTranslator.normalize(catalog)
            cache[satelliteId] = CacheEntry(reply.etag, normalized)
            hostFeaturesStore.setFeatures(satelliteId, HostFeatureSet.fromCatalog(normalized))
            return normalized
        }

        fun cached(satelliteId: String): CatalogDto? = cache[satelliteId]?.catalog
    }
