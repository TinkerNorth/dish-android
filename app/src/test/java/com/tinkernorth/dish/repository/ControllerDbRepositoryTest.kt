// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.repository

import com.tinkernorth.dish.core.controllerdb.CheckOutcome
import com.tinkernorth.dish.core.controllerdb.ControllerDbCheckResult
import com.tinkernorth.dish.core.controllerdb.FakeControllerDbGateway
import com.tinkernorth.dish.core.controllerdb.runControllerDbCheck
import com.tinkernorth.dish.core.controllerdb.servedFiles
import com.tinkernorth.dish.core.controllerdb.testJson
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ControllerDbRepositoryTest {
    @get:Rule val tmp = TemporaryFolder()

    private val assets = File("src/main/assets/controllers/v1")

    private fun repo(root: File = tmp.root) = ControllerDbRepository(root, { File(assets, it).readBytes() }, testJson)

    private suspend fun downloadOf(version: Int): CheckOutcome {
        val gateway = FakeControllerDbGateway(files = servedFiles(version, "newpad"), version = version)
        return runControllerDbCheck(gateway, activeVersion = 1, etag = null, json = testJson)
    }

    @Test
    fun `the bundled copy loads from the assets`() {
        assertEquals(1, repo().bundled().db.dbVersion)
    }

    @Test
    fun `with nothing downloaded there is no cache`() {
        assertNull(repo().cached())
    }

    @Test
    fun `a stored download reads back verified`() =
        runTest {
            val outcome = downloadOf(2)
            assertEquals(ControllerDbCheckResult.UPDATED, outcome.result)
            repo().store(requireNotNull(outcome.download))
            assertEquals(2, repo().cached()?.db?.dbVersion)
            assertEquals(false, File(tmp.root, "staging").exists())
        }

    @Test
    fun `a later download replaces the earlier one`() =
        runTest {
            repo().store(requireNotNull(downloadOf(2).download))
            repo().store(requireNotNull(downloadOf(3).download))
            assertEquals(3, repo().cached()?.db?.dbVersion)
            assertEquals(false, File(tmp.root, "previous").exists())
        }

    @Test
    fun `a cached file changed on disk is not loaded`() =
        runTest {
            repo().store(requireNotNull(downloadOf(2).download))
            File(tmp.root, "current/strings/en.json").writeText("{\"controller.newpad.name\":\"tampered\"}")
            assertNull(repo().cached())
        }

    @Test
    fun `a swap interrupted after the old copy was set aside still reads the old copy`() =
        runTest {
            repo().store(requireNotNull(downloadOf(2).download))
            File(tmp.root, "current").renameTo(File(tmp.root, "previous"))
            assertEquals(2, repo().cached()?.db?.dbVersion)
        }
}
