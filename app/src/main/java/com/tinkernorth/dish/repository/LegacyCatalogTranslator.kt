// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.repository

import com.tinkernorth.dish.composer.BundledCatalog
import com.tinkernorth.dish.core.model.CatalogDto
import com.tinkernorth.dish.core.model.CatalogFeatureDto
import com.tinkernorth.dish.core.model.CatalogTypeDto
import com.tinkernorth.dish.core.model.Feature
import javax.inject.Inject

/**
 * Owns every catalog-version→canonical mapping so the rest of the app stays
 * version-agnostic. A current-or-newer catalog passes through untouched; a
 * legacy one (older schema, or the field absent → parsed as 1) is replaced with
 * this app's own known representation of that version. The legacy body's
 * controllerTypes are NOT trusted — only its version is read; locale, server
 * version and host features pass through.
 */
class LegacyCatalogTranslator
    @Inject
    constructor() {
        fun normalize(fetched: CatalogDto): CatalogDto =
            if (fetched.catalogVersion >= CATALOG_VERSION_CURRENT) fetched else legacyV1(fetched)

        // v1 is the sole legacy schema: xbox360 + ds4 (ds4 touchpad in "ds4" mode), no
        // emulates / dualsense / switchpro. Only controllerTypes are hardcoded; their
        // capability sets reuse BundledCatalog rather than re-listing feature slugs.
        private fun legacyV1(fetched: CatalogDto): CatalogDto =
            fetched.copy(
                catalogVersion = LEGACY_V1,
                controllerTypes =
                    listOf(
                        legacyType(id = 0, slug = BundledCatalog.SLUG_XBOX360),
                        legacyType(id = 1, slug = BundledCatalog.SLUG_DS4),
                    ),
            )

        private fun legacyType(
            id: Int,
            slug: String,
        ): CatalogTypeDto = CatalogTypeDto(id = id, slug = slug, features = legacyFeatures(slug))

        private fun legacyFeatures(slug: String): Map<String, CatalogFeatureDto> {
            val caps = BundledCatalog.typeCapabilities(slug) ?: return emptyMap()
            return buildMap {
                for (feature in caps.features) {
                    val featureSlug = feature.catalogSlug ?: continue
                    // Touchpad is the DS4 pad mode: the resolver gates it on the "ds4" mode slug.
                    val modes = if (feature == Feature.TOUCHPAD) listOf(TouchpadModeValue.DS4) else emptyList()
                    put(featureSlug, CatalogFeatureDto(supported = true, modes = modes))
                }
            }
        }

        companion object {
            const val CATALOG_VERSION_CURRENT = 2
            private const val LEGACY_V1 = 1
        }
    }
