// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.source.billing

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * The app asks Play for the products scripts/play_products.py creates from play/products.json. An
 * id that differs by one character is a tier the store silently never returns, so the two lists
 * are pinned to each other rather than to a copy of either.
 */
class TipCatalogTest {
    private val products: JsonObject =
        Json.parseToJsonElement(File(PRODUCTS_JSON).readText()).jsonObject

    private fun declaredTipSkus(): List<String> =
        products
            .getValue("tips")
            .jsonObject
            .getValue("products")
            .jsonArray
            .map(::skuOf)

    private fun skuOf(product: JsonElement): String =
        product.jsonObject
            .getValue("sku")
            .jsonPrimitive
            .content

    private fun declaredSubscriptionId(): String =
        products
            .getValue("subscription")
            .jsonObject
            .getValue("productId")
            .jsonPrimitive
            .content

    @Test
    fun `the app asks for every tip the product file creates, in its order`() {
        assertEquals(declaredTipSkus(), TIP_PRODUCT_IDS)
    }

    @Test
    fun `the app asks for the subscription the product file creates`() {
        assertEquals(declaredSubscriptionId(), SUBSCRIPTION_PRODUCT_ID)
    }

    private companion object {
        // Unit tests run with the app module as the working directory.
        const val PRODUCTS_JSON = "../play/products.json"
    }
}
