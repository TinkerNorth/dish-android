// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.repository

import android.content.Context
import com.tinkernorth.dish.core.controllerdb.CONTROLLERS_FILE
import com.tinkernorth.dish.core.controllerdb.ControllerDbManifest
import com.tinkernorth.dish.core.controllerdb.DownloadedControllerDb
import com.tinkernorth.dish.core.controllerdb.ENGLISH
import com.tinkernorth.dish.core.controllerdb.FileCheck
import com.tinkernorth.dish.core.controllerdb.LoadedControllerDb
import com.tinkernorth.dish.core.controllerdb.MANIFEST_FILE
import com.tinkernorth.dish.core.controllerdb.ParsedDb
import com.tinkernorth.dish.core.controllerdb.ParsedManifest
import com.tinkernorth.dish.core.controllerdb.checkFile
import com.tinkernorth.dish.core.controllerdb.parseControllerDb
import com.tinkernorth.dish.core.controllerdb.parseManifest
import com.tinkernorth.dish.core.controllerdb.stringsOf
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

// The controller database's two copies: the bundled one in the APK's assets, and the last verified
// download under the app's files directory. A download is written whole into a directory beside
// the cache and renamed over it, so a reader sees the old copy or the new one, never half of one,
// and every file is checked against its manifest again when it is read.
@Singleton
class ControllerDbRepository internal constructor(
    private val cacheRoot: File,
    private val readBundled: (String) -> ByteArray,
    private val json: Json,
) {
    @Inject
    constructor(
        @ApplicationContext context: Context,
        json: Json,
    ) : this(
        cacheRoot = File(context.filesDir, CACHE_DIR),
        readBundled = { path -> readAsset(context, path) },
        json = json,
    )

    private val writeLock = Any()

    // The copy built from the same file the native tables were generated from; a bundled copy that
    // does not load is a broken APK.
    fun bundled(): LoadedControllerDb = loadFrom(readBundled) ?: error("the bundled controller database does not load")

    // The last verified download, or null when there is none or it no longer checks out.
    fun cached(): LoadedControllerDb? {
        val current = File(cacheRoot, CURRENT)
        val dir = if (current.isDirectory) current else File(cacheRoot, PREVIOUS)
        if (!dir.isDirectory) return null
        return loadFrom { path -> readIfThere(File(dir, path)) }
    }

    // Writes beside, then renames over: the previous copy is kept until the new one is in place.
    fun store(download: DownloadedControllerDb) =
        synchronized(writeLock) {
            val staging = File(cacheRoot, STAGING)
            staging.deleteRecursively()
            download.files.forEach { (path, bytes) -> writeFile(File(staging, path), bytes) }
            writeFile(File(staging, MANIFEST_FILE), download.manifestBytes)
            swapIn(staging)
        }

    private fun swapIn(staging: File) {
        val current = File(cacheRoot, CURRENT)
        val previous = File(cacheRoot, PREVIOUS)
        previous.deleteRecursively()
        if (current.exists() && !current.renameTo(previous)) throw IOException("cannot set the old controller database aside")
        if (!staging.renameTo(current)) throw IOException("cannot move the new controller database in")
        previous.deleteRecursively()
    }

    private fun writeFile(
        file: File,
        bytes: ByteArray,
    ) {
        file.parentFile?.mkdirs()
        file.writeBytes(bytes)
    }

    private fun loadFrom(read: (String) -> ByteArray?): LoadedControllerDb? {
        val manifest = read(MANIFEST_FILE)?.let { parseManifest(it, json) } as? ParsedManifest.Ok ?: return null
        val files = verifiedFiles(manifest.manifest, read) ?: return null
        return loadedOf(manifest.manifest, files)
    }

    // The database and every strings file, English among them; null when any is not what it says.
    private fun loadedOf(
        manifest: ControllerDbManifest,
        files: Map<String, ByteArray>,
    ): LoadedControllerDb? {
        val db = parseControllerDb(files.getValue(CONTROLLERS_FILE), manifest.dbVersion, json) as? ParsedDb.Ok ?: return null
        val strings = stringsOf(files, json)?.takeIf { ENGLISH in it } ?: return null
        return LoadedControllerDb(db.db, strings)
    }

    private fun verifiedFiles(
        manifest: ControllerDbManifest,
        read: (String) -> ByteArray?,
    ): Map<String, ByteArray>? {
        val files = LinkedHashMap<String, ByteArray>()
        for ((path, expected) in manifest.files) {
            val bytes = read(path) ?: return null
            if (checkFile(bytes, expected) != FileCheck.Ok) return null
            files[path] = bytes
        }
        return files
    }

    private companion object {
        const val CACHE_DIR = "controller-db/v1"
        const val CURRENT = "current"
        const val PREVIOUS = "previous"
        const val STAGING = "staging"
    }
}

private const val ASSET_DIR = "controllers/v1"

private fun readIfThere(file: File): ByteArray? = if (file.isFile) file.readBytes() else null

// A bundled file is part of the APK; one that is missing is a broken build and throws.
private fun readAsset(
    context: Context,
    path: String,
): ByteArray = context.assets.open("$ASSET_DIR/$path").use { it.readBytes() }
