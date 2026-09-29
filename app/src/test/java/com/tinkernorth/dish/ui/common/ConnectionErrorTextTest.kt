// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.common

import com.tinkernorth.dish.R
import com.tinkernorth.dish.source.connection.ConnectionError
import com.tinkernorth.dish.source.connection.moonlight.MoonlightError
import com.tinkernorth.dish.ui.main.StringLookup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

private const val SERVER = "Living room PC"
private const val REASON = "no free pad"
private const val FAILURES = "#0: backendUnavailable"
private const val ADDRESS = "192.168.1.20"

private val EVERY_CONNECTION_ERROR: List<ConnectionError> =
    listOf(
        ConnectionError.ServerUnreachable,
        ConnectionError.RepairNeeded,
        ConnectionError.IdentityChanged,
        ConnectionError.WireFailed,
        ConnectionError.SatelliteUpdateRequired,
        ConnectionError.AppUpdateRequired,
        ConnectionError.ApprovalDeclined,
        ConnectionError.ApprovalTimedOut,
        ConnectionError.PairingFailed,
        ConnectionError.PairingRefused(REASON),
        ConnectionError.SessionFailed,
        ConnectionError.SessionRefused(REASON),
        ConnectionError.ApplyFailed(SERVER, FAILURES),
    )

private val EVERY_MOONLIGHT_ERROR: List<MoonlightError> =
    listOf(
        MoonlightError.NoHostAnswered(ADDRESS),
        MoonlightError.NoAppsAvailable(SERVER),
    )

// Every locale the app ships: lint fails the build on a string missing from any of them.
private val LOCALES = listOf("values", "values-es", "values-fr", "values-de", "values-bs", "values-pt-rBR")

private val PLACEHOLDER = Regex("%(\\d+)\\\$s")

class ConnectionErrorTextTest {
    private val resDir: File =
        generateSequence(File(checkNotNull(System.getProperty("user.dir"))).absoluteFile) { it.parentFile }
            .flatMap { sequenceOf(File(it, "src/main/res"), File(it, "app/src/main/res")) }
            .first { File(it, "values/strings.xml").exists() }

    @Test
    fun `an unreachable server reads as unreachable`() {
        assertEquals(
            Lookup(R.string.conn_error_server_unreachable),
            lookupOf { connectionErrorText(ConnectionError.ServerUnreachable, it) },
        )
    }

    @Test
    fun `a satellite that no longer knows this device asks for a re-pair`() {
        assertEquals(Lookup(R.string.conn_error_repair_needed), lookupOf { connectionErrorText(ConnectionError.RepairNeeded, it) })
    }

    @Test
    fun `a changed security identity says so`() {
        assertEquals(Lookup(R.string.conn_error_identity_changed), lookupOf { connectionErrorText(ConnectionError.IdentityChanged, it) })
    }

    @Test
    fun `a controller link that would not open says so`() {
        assertEquals(Lookup(R.string.conn_error_wire_failed), lookupOf { connectionErrorText(ConnectionError.WireFailed, it) })
    }

    @Test
    fun `a satellite too old for the app asks to update Satellite`() {
        assertEquals(
            Lookup(R.string.conn_error_satellite_update_required),
            lookupOf { connectionErrorText(ConnectionError.SatelliteUpdateRequired, it) },
        )
    }

    @Test
    fun `a satellite newer than the app asks to update Dish`() {
        assertEquals(
            Lookup(R.string.conn_error_app_update_required),
            lookupOf { connectionErrorText(ConnectionError.AppUpdateRequired, it) },
        )
    }

    @Test
    fun `a declined approval says the satellite declined`() {
        assertEquals(Lookup(R.string.conn_error_approval_declined), lookupOf { connectionErrorText(ConnectionError.ApprovalDeclined, it) })
    }

    @Test
    fun `an approval nobody answered says it timed out`() {
        assertEquals(Lookup(R.string.conn_error_approval_timed_out), lookupOf { connectionErrorText(ConnectionError.ApprovalTimedOut, it) })
    }

    @Test
    fun `a pairing failure without a reason reads as a plain failure`() {
        assertEquals(Lookup(R.string.conn_error_pairing_failed), lookupOf { connectionErrorText(ConnectionError.PairingFailed, it) })
    }

    @Test
    fun `a refused pairing quotes the satellite's reason`() {
        assertEquals(
            Lookup(R.string.conn_error_pairing_refused, listOf(REASON)),
            lookupOf { connectionErrorText(ConnectionError.PairingRefused(REASON), it) },
        )
    }

    @Test
    fun `a session refusal without a reason says the satellite refused`() {
        assertEquals(Lookup(R.string.conn_error_session_failed), lookupOf { connectionErrorText(ConnectionError.SessionFailed, it) })
    }

    @Test
    fun `a refused session quotes the satellite's reason`() {
        assertEquals(
            Lookup(R.string.conn_error_session_refused, listOf(REASON)),
            lookupOf { connectionErrorText(ConnectionError.SessionRefused(REASON), it) },
        )
    }

    @Test
    fun `a controller the satellite could not apply names the satellite, then the failures`() {
        assertEquals(
            Lookup(R.string.conn_error_apply_failed, listOf(SERVER, FAILURES)),
            lookupOf { connectionErrorText(ConnectionError.ApplyFailed(SERVER, FAILURES), it) },
        )
    }

    @Test
    fun `an address no Moonlight host answered names the address`() {
        assertEquals(
            Lookup(R.string.ml_error_no_host_answered, listOf(ADDRESS)),
            lookupOf { moonlightErrorText(MoonlightError.NoHostAnswered(ADDRESS), it) },
        )
    }

    @Test
    fun `a Moonlight host with no apps names the host`() {
        assertEquals(
            Lookup(R.string.ml_error_no_apps, listOf(SERVER)),
            lookupOf { moonlightErrorText(MoonlightError.NoAppsAvailable(SERVER), it) },
        )
    }

    @Test
    fun `a close request sent to a Moonlight host names the host`() {
        assertEquals(Lookup(R.string.ml_notice_app_close_requested, listOf(SERVER)), lookupOf { appCloseRequestedText(SERVER, it) })
    }

    @Test
    fun `the sample list names every connection error case`() {
        assertEquals(casesOf(ConnectionError::class.java), EVERY_CONNECTION_ERROR.map { it.javaClass }.toSet())
    }

    @Test
    fun `the sample list names every Moonlight error case`() {
        assertEquals(casesOf(MoonlightError::class.java), EVERY_MOONLIGHT_ERROR.map { it.javaClass }.toSet())
    }

    @Test
    fun `every connection error reads as its own string`() {
        val resources = EVERY_CONNECTION_ERROR.map(::connectionLookup).map { it.res }
        assertEquals(resources.size, resources.toSet().size)
    }

    @Test
    fun `every string takes exactly the arguments its text passes, in every locale`() {
        val lookups =
            EVERY_CONNECTION_ERROR.map(::connectionLookup) +
                EVERY_MOONLIGHT_ERROR.map(::moonlightLookup) +
                lookupOf { appCloseRequestedText(SERVER, it) }
        val mismatches = LOCALES.flatMap { locale -> mismatchesIn(locale, lookups) }
        assertTrue("strings whose placeholders do not match their arguments: $mismatches", mismatches.isEmpty())
    }

    private fun connectionLookup(error: ConnectionError): Lookup = lookupOf { connectionErrorText(error, it) }

    private fun moonlightLookup(error: MoonlightError): Lookup = lookupOf { moonlightErrorText(error, it) }

    private fun mismatchesIn(
        locale: String,
        lookups: List<Lookup>,
    ): List<String> {
        val strings = stringsIn(locale)
        return lookups.mapNotNull { lookup -> mismatchOf(locale, strings, lookup) }
    }

    private fun mismatchOf(
        locale: String,
        strings: Map<String, String>,
        lookup: Lookup,
    ): String? {
        val name = stringName(lookup.res)
        val text = strings[name] ?: return "$locale/$name is missing"
        val placeholders = PLACEHOLDER.findAll(text).map { it.groupValues[1].toInt() }.toSet()
        val expected = (1..lookup.args.size).toSet()
        return if (placeholders == expected) null else "$locale/$name has $placeholders, the text passes ${lookup.args.size}"
    }

    private fun stringsIn(locale: String): Map<String, String> {
        val builder = DocumentBuilderFactory.newInstance().newDocumentBuilder()
        val nodes = builder.parse(File(resDir, "$locale/strings.xml")).getElementsByTagName("string")
        return (0 until nodes.length)
            .map { nodes.item(it) as Element }
            .associate { it.getAttribute("name") to it.textContent }
    }

    private fun stringName(res: Int): String {
        val field = R.string::class.java.fields.first { it.getInt(null) == res }
        return field.name
    }

    private fun casesOf(sealed: Class<*>): Set<Class<*>> = sealed.declaredClasses.filter { sealed.isAssignableFrom(it) }.toSet()
}

// The resource and arguments a text function asked the lookup for.
private data class Lookup(
    val res: Int,
    val args: List<Any> = emptyList(),
)

private fun lookupOf(text: (StringLookup) -> String): Lookup {
    var asked: Lookup? = null
    val recorder = StringLookup { res, args -> "".also { asked = Lookup(res, args.toList()) } }
    text(recorder)
    return checkNotNull(asked)
}
