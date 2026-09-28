// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.core.update

// The updater's one version grammar and ordering: strict MAJOR.MINOR.PATCH
// parsed to an int triple and compared as a tuple. No prefix, no prerelease
// tag, no build metadata: the release permalink never points at those, and a
// looser grammar is how "0.10.0-rc1" ends up ordered before "0.9.0". The same
// rules as dish-windows' UpdateVersion, so one manifest reads alike everywhere.
data class UpdateVersion(
    val major: Int,
    val minor: Int,
    val patch: Int,
) : Comparable<UpdateVersion> {
    override fun compareTo(other: UpdateVersion): Int = compareValuesBy(this, other, { it.major }, { it.minor }, { it.patch })

    override fun toString(): String = "$major.$minor.$patch"
}

private val STRICT_VERSION = Regex("^([0-9]+)\\.([0-9]+)\\.([0-9]+)$")

// The build's own versionName is `git describe`: the tag, or the tag
// followed by "-<commits>-g<hash>" (and "-dirty") on a build past it.
// Such a build reports the tag's version, the one its versionCode is
// derived from, so a developer build is offered the same releases as
// the tag it came from.
private val DESCRIBED_BUILD = Regex("^([0-9]+\\.[0-9]+\\.[0-9]+)(?:-[0-9]+-g[0-9a-f]+)?(?:-dirty)?$")

// Strict `^\d+\.\d+\.\d+$`. Rejects anything else: empty parts,
// signs, spaces, prefixes, suffixes, or a component that overflows Int.
internal fun parseUpdateVersion(text: String): UpdateVersion? {
    val match = STRICT_VERSION.matchEntire(text) ?: return null
    val (a, b, c) = match.destructured
    val major = a.toIntOrNull()
    val minor = b.toIntOrNull()
    val patch = c.toIntOrNull()
    return if (major == null || minor == null || patch == null) null else UpdateVersion(major, minor, patch)
}

internal fun updateVersionOfBuild(versionName: String): UpdateVersion? =
    DESCRIBED_BUILD.matchEntire(versionName)?.let { parseUpdateVersion(it.groupValues[1]) }

fun isValidVersion(text: String): Boolean = parseUpdateVersion(text) != null

// True iff BOTH parse and candidate > baseline: malformed input can never rank
// above a real version.
fun isStrictlyNewer(
    candidate: String,
    baseline: String,
): Boolean {
    val c = parseUpdateVersion(candidate) ?: return false
    val b = parseUpdateVersion(baseline) ?: return false
    return c > b
}
