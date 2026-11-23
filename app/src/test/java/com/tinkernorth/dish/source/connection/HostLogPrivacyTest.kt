// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.source.connection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

// The app's own log reaches the diagnostics file, which promises never to carry a PC's name, a
// host's unique id or a fingerprint; the scrub knows only the current hosts', so the log lines
// under the network, discovery and pairing code name a host by its address and nothing else.
class HostLogPrivacyTest {
    private val mainDir: File =
        generateSequence(File(System.getProperty("user.dir") ?: ".").absoluteFile) { it.parentFile }
            .flatMap { sequenceOf(File(it, "src/main"), File(it, "app/src/main")) }
            .first { File(it, "AndroidManifest.xml").exists() }

    private val sourceRoot = File(mainDir, "java/com/tinkernorth/dish")

    private fun kotlinSourcesUnder(dir: String): List<File> =
        File(sourceRoot, dir).walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    private fun relative(file: File): String = file.relativeTo(sourceRoot).path.replace(File.separatorChar, '/')

    private fun offendersIn(file: File): List<String> =
        logCallsOf(file.readText())
            .flatMap(::interpolatedNames)
            .filter(::namesAHost)
            .map { "${relative(file)}: $it" }

    @Test
    fun `no log line under the network, discovery and pairing code formats a host's name, unique id or fingerprint`() {
        val sources = HOST_PACKAGES.flatMap(::kotlinSourcesUnder)
        val offenders = sources.flatMap(::offendersIn)

        assertTrue("the scan read the tree", sources.size > MOST_FILES_A_BROKEN_SCAN_FINDS)
        assertEquals(emptyList<String>(), offenders)
    }

    @Test
    fun `the scan finds a host's name in a log line, on one line or several`() {
        val oneLine = "Log.i(TAG, \"paired with \${host.name} at \${host.address}\")"
        val several = "Log.w(\n    TAG,\n    \"resolve of \$serviceName failed \" +\n        \"(\${e.message})\",\n)"

        val names = (logCallsOf(oneLine) + logCallsOf(several)).flatMap(::interpolatedNames)

        assertEquals(listOf("host.name", "host.address", "serviceName", "e.message"), names)
        assertEquals(listOf("host.name", "serviceName"), names.filter(::namesAHost))
    }

    @Test
    fun `an exception's class name, a host's app name and a pad's model name are not a PC's name`() {
        val notAHost = listOf("e.javaClass.simpleName", "appName", "model.name", "e.message", "host.address")
        assertEquals(listOf<String>(), notAHost.filter(::namesAHost))
    }

    private companion object {
        val HOST_PACKAGES = listOf("source/connection", "core/net", "source/bluetooth", "repository")
        const val MOST_FILES_A_BROKEN_SCAN_FINDS = 10
    }
}

private val LOG_CALL_START = Regex("""\bLog\.[vdiwe]\(""")
private val INTERPOLATION = Regex("""\$\{([^}]*)}|\$([A-Za-z_][A-Za-z0-9_]*)""")
private val NAME_CHAIN = Regex("""[A-Za-z_][A-Za-z0-9_]*(?:\??\.[A-Za-z_][A-Za-z0-9_]*)*""")

// What a host is called, what it answers as and what it proved with; an exception's class, a
// host's app and a pad's model are named by other words.
private val HOST_WORDS = listOf("name", "uniqueid", "machineid", "satelliteid", "fingerprint", "label")
private val NOT_A_HOST_WORDS = setOf("simpleName", "appName")
private const val A_PADS_MODEL = "model.name"

// Each Log call's text from "Log.x(" to its closing parenthesis.
private fun logCallsOf(text: String): List<String> = LOG_CALL_START.findAll(text).map { callFrom(text, it.range.first) }.toList()

private fun callFrom(
    text: String,
    start: Int,
): String {
    var depth = 0
    var index = text.indexOf('(', start)
    while (index < text.length) {
        val c = text[index]
        if (c == '(') depth++
        if (c == ')') depth--
        if (depth == 0) return text.substring(start, index + 1)
        index++
    }
    return text.substring(start)
}

// Every property chain a call interpolates, "host?.name" read as "host.name".
private fun interpolatedNames(call: String): List<String> =
    INTERPOLATION
        .findAll(call)
        .flatMap { match -> NAME_CHAIN.findAll(match.groupValues[1] + match.groupValues[2]) }
        .map { chain -> chain.value.replace("?.", ".") }
        .toList()

private fun namesAHost(chain: String): Boolean {
    val last = chain.split('.').last()
    val isAHostWord = HOST_WORDS.any { last.lowercase().contains(it) }
    val isAnotherThing = last in NOT_A_HOST_WORDS || chain == A_PADS_MODEL
    return isAHostWord && !isAnotherThing
}
