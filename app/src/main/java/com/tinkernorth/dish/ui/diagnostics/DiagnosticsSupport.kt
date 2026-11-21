// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.ui.diagnostics

import android.content.Context
import android.os.Build
import android.util.Log
import com.tinkernorth.dish.BuildConfig
import com.tinkernorth.dish.composer.BundleApp
import com.tinkernorth.dish.composer.BundleDevice
import com.tinkernorth.dish.composer.bundleFileName
import com.tinkernorth.dish.composer.diagnosticsBundleText
import com.tinkernorth.dish.di.IoDispatcher
import com.tinkernorth.dish.source.controllerdb.ControllerDbUpdater
import com.tinkernorth.dish.source.store.ControllerCaptureStore
import com.tinkernorth.dish.source.store.DiagnosticsLogStore
import com.tinkernorth.dish.source.system.LogcatReader
import com.tinkernorth.dish.source.usb.ControllerCapturer
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import javax.inject.Inject

// The Diagnostics screen's commands that reach past the report: the controller database's
// "Check now", "Capture this controller", and preparing and writing the bundle a report attaches.
class DiagnosticsSupport
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        val controllerDb: ControllerDbUpdater,
        private val logcat: LogcatReader,
        private val captures: ControllerCaptureStore,
        private val capturer: ControllerCapturer,
        private val log: DiagnosticsLogStore,
        private val report: ReportSources,
        @IoDispatcher private val io: CoroutineDispatcher,
    ) {
        // The bundle as it will be attached, with every host asked for its side and every fact
        // read now; the file is written only when the person continues.
        internal suspend fun prepare(
            world: DiagnosticsWorld,
            overview: DiagnosticsViewModel.Overview,
            draft: ReportDraft,
        ): PreparedReport {
            val now = System.currentTimeMillis()
            val extras = report.extras(overview, draft)
            val text =
                withContext(io) {
                    val envelope = BundleEnvelope(now, appFacts(), report.device(world.radios.wifi), logcat.ownLines())
                    val inputs =
                        bundleInputsOf(world, overview.controllers, overview.hosts, captures.snapshot(), log.state.value, envelope, extras)
                    diagnosticsBundleText(inputs)
                }
            return PreparedReport(text, bundleFileName(now), overview, world, draft)
        }

        // The bundle file in the cache directory the FileProvider serves; null when it could not
        // be written. Earlier bundles are removed first, so only the one being sent is there.
        internal suspend fun write(prepared: PreparedReport): File? = withContext(io) { writeFile(prepared.fileName, prepared.text) }

        // True once the pad's descriptors and reports are kept for the next bundle.
        suspend fun capture(
            deviceId: Int,
            slotId: String,
        ): Boolean {
            val capture = capturer.capture(deviceId) ?: return false
            captures.keep(slotId, capture)
            return true
        }

        private fun writeFile(
            name: String,
            text: String,
        ): File? =
            try {
                val dir = File(context.cacheDir, BUNDLE_DIR)
                dir.deleteRecursively()
                dir.mkdirs()
                File(dir, name).also { it.writeText(text) }
            } catch (e: IOException) {
                Log.w(TAG, "the diagnostics bundle could not be written", e)
                null
            }

        private companion object {
            const val TAG = "DiagnosticsSupport"

            // res/xml/diagnostics_paths.xml serves this directory.
            const val BUNDLE_DIR = "diagnostics"
        }
    }

// A report ready to preview or send: the file's text and name, and what the mail body is made of.
class PreparedReport internal constructor(
    val text: String,
    val fileName: String,
    val overview: DiagnosticsViewModel.Overview,
    internal val world: DiagnosticsWorld,
    val draft: ReportDraft,
)

// The app and phone facts the bundle opens with, read once by the caller.
internal data class BundleEnvelope(
    val createdAtMs: Long,
    val app: BundleApp,
    val device: BundleDevice,
    val logcat: String,
)

internal fun appFacts(): BundleApp = BundleApp(BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE.toString(), BuildConfig.FLAVOR)

internal fun deviceFacts(): BundleDevice =
    BundleDevice(
        model = Build.MODEL.orEmpty(),
        os = "Android ${Build.VERSION.RELEASE.orEmpty()}",
        arch = Build.SUPPORTED_ABIS.firstOrNull().orEmpty(),
    )
