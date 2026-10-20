// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.ui.donate

import com.tinkernorth.dish.repository.mapBackedPrefs
import com.tinkernorth.dish.source.billing.TipJarSource
import dagger.hilt.android.EntryPointAccessors
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PlaySupporterCheckTest {
    private val tipJar = mockk<TipJarSource>(relaxed = true)
    private val entryPoint = mockk<TipJarEntryPoint>()
    private val supporters: SupporterCheck get() = (TIP_OFFER as TipOffer.Ask).supporters

    @Before
    fun setUp() {
        every { entryPoint.tipJar() } returns tipJar
        mockkStatic(EntryPointAccessors::class)
        every { EntryPointAccessors.fromApplication(any(), TipJarEntryPoint::class.java) } returns entryPoint
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `a stored supporter is a supporter, so the Play pill stays away`() {
        val (supporter, _) = mapBackedPrefs(mutableMapOf("supporter_active" to true))

        assertTrue(supporters.isSupporter(supporter))
    }

    @Test
    fun `asking about a stored supporter has the tip jar re-verify the plan`() {
        val (supporter, _) = mapBackedPrefs(mutableMapOf("supporter_active" to true))

        supporters.isSupporter(supporter)

        verify { tipJar.verifySupporter() }
    }
}
