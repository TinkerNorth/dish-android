// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.integration

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tinkernorth.dish.source.connection.SatelliteConnection
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Exercises the read-only catalog surface (GET /api/catalog, unauthenticated,
 * ETag-cached) through the real SatelliteCatalogRepository against the fake:
 * type list rendering, host-feature publication, and the 304 revalidation
 * that keeps the picker from re-parsing an unchanged catalog.
 */
@RunWith(AndroidJUnit4::class)
class CatalogIntegrationTest {
    private var fake: FakeSatellite? = null

    @Before
    fun setUp() {
        AppSingletons.resetConnections()
    }

    @After
    fun tearDown() {
        AppSingletons.resetConnections()
        fake?.close()
        fake = null
    }

    @Test
    fun catalog_fetchReturnsTheTypeListAndPublishesHostFeatures() {
        val satellite = FakeSatellite().also { fake = it }
        val server = satellite.server()
        val id = SatelliteConnection.idFor(server)

        val catalog = runBlocking { AppSingletons.catalogRepo.catalogFor(server, id) }
        assertNotNull("catalog must be fetched from the satellite", catalog)
        assertEquals("en", catalog!!.locale)
        val slugs = catalog.controllerTypes.map { it.slug }
        assertTrue(
            "catalog carries all four bundled types",
            slugs.containsAll(listOf("xbox360", "ds4", "dualsense", "switchpro")),
        )
        // Switch Pro is the one pad with no analog triggers; the fixture must round-trip that.
        val switchpro = catalog.controllerTypes.first { it.slug == "switchpro" }
        assertTrue("Switch Pro reports motion", switchpro.features["motion"]?.supported == true)
        assertFalse("Switch Pro has no analog triggers", switchpro.features["analogTriggers"]?.supported == true)

        val hostFeatures = AppSingletons.hostFeaturesStore.featuresFor(id)
        assertNotNull("catalog fetch must publish host features", hostFeatures)
        assertTrue("the fake advertises host mouseControl", hostFeatures!!.mouseControl)
    }

    @Test
    fun catalog_secondFetchRevalidatesWithEtagAndServesCache() {
        val satellite = FakeSatellite().also { fake = it }
        val server = satellite.server()
        val id = SatelliteConnection.idFor(server)

        val first = runBlocking { AppSingletons.catalogRepo.catalogFor(server, id) }
        val second = runBlocking { AppSingletons.catalogRepo.catalogFor(server, id) }

        assertNotNull(first)
        assertNotNull(second)
        assertEquals("the 304 path serves the same cached catalog", first!!.controllerTypes.size, second!!.controllerTypes.size)
        assertEquals("both fetches hit the satellite; the second is an If-None-Match revalidation", 2, satellite.catalogRequests)
    }
}
