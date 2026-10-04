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
        ConnectionError.Ipv6Unsupported,
    )

private val EVERY_MOONLIGHT_ERROR: List<MoonlightError> =
    listOf(
        MoonlightError.NoHostAnswered(ADDRESS),
        MoonlightError.NoAppsAvailable(SERVER),
        MoonlightError.AppRemoved(SERVER, "Steam"),
    )

// Every locale the app ships: lint fails the build on a string missing from any of them.
private val LOCALES = listOf("values", "values-es", "values-fr", "values-de", "values-bs", "values-pt-rBR")

private val PLACEHOLDER = Regex("%(\\d+)\\\$s")

class ConnectionErrorWordsTest {
    private val resDir: File =
        generateSequence(File(checkNotNull(System.getProperty("user.dir"))).absoluteFile) { it.parentFile }
            .flatMap { sequenceOf(File(it, "src/main/res"), File(it, "app/src/main/res")) }
            .first { File(it, "values/strings.xml").exists() }

    @Test
    fun `an unreachable server says the satellite is not responding, and why that may be`() {
        assertEquals(
            Words(R.string.notif_server_unreachable_title, Lookup(R.string.conn_error_server_unreachable)),
            wordsOf(ConnectionError.ServerUnreachable),
        )
    }

    @Test
    fun `a satellite that no longer knows this device says so and asks for a re-pair`() {
        assertEquals(
            Words(R.string.conn_error_title_repair_needed, Lookup(R.string.conn_error_repair_needed)),
            wordsOf(ConnectionError.RepairNeeded),
        )
    }

    @Test
    fun `a changed security identity says the satellite has a new identity`() {
        assertEquals(
            Words(R.string.conn_error_title_identity_changed, Lookup(R.string.conn_error_identity_changed)),
            wordsOf(ConnectionError.IdentityChanged),
        )
    }

    @Test
    fun `a controller link that would not open says the link failed`() {
        assertEquals(
            Words(R.string.conn_error_title_wire_failed, Lookup(R.string.conn_error_wire_failed)),
            wordsOf(ConnectionError.WireFailed),
        )
    }

    @Test
    fun `an IPv6 satellite address says to reach the satellite over IPv4`() {
        assertEquals(
            Words(R.string.conn_error_title_ipv6_unsupported, Lookup(R.string.conn_error_ipv6_unsupported)),
            wordsOf(ConnectionError.Ipv6Unsupported),
        )
    }

    @Test
    fun `a satellite too old for the app says the satellite needs an update`() {
        assertEquals(
            Words(R.string.conn_error_title_satellite_update_required, Lookup(R.string.conn_error_satellite_update_required)),
            wordsOf(ConnectionError.SatelliteUpdateRequired),
        )
    }

    @Test
    fun `a satellite newer than the app says Dish needs an update`() {
        assertEquals(
            Words(R.string.conn_error_title_app_update_required, Lookup(R.string.conn_error_app_update_required)),
            wordsOf(ConnectionError.AppUpdateRequired),
        )
    }

    @Test
    fun `a declined approval says the satellite declined`() {
        assertEquals(
            Words(R.string.conn_error_title_approval_declined, Lookup(R.string.conn_error_approval_declined)),
            wordsOf(ConnectionError.ApprovalDeclined),
        )
    }

    @Test
    fun `an approval nobody answered says there was no answer`() {
        assertEquals(
            Words(R.string.conn_error_title_approval_timed_out, Lookup(R.string.conn_error_approval_timed_out)),
            wordsOf(ConnectionError.ApprovalTimedOut),
        )
    }

    @Test
    fun `a pairing failure without a reason says the pairing failed`() {
        assertEquals(
            Words(R.string.conn_error_title_pairing_failed, Lookup(R.string.conn_error_pairing_failed)),
            wordsOf(ConnectionError.PairingFailed),
        )
    }

    @Test
    fun `a refused pairing says the pairing failed and quotes the satellite's reason`() {
        assertEquals(
            Words(R.string.conn_error_title_pairing_failed, Lookup(R.string.conn_error_pairing_refused, listOf(REASON))),
            wordsOf(ConnectionError.PairingRefused(REASON)),
        )
    }

    @Test
    fun `a session refusal without a reason says the connection failed`() {
        assertEquals(
            Words(R.string.conn_error_title_session_failed, Lookup(R.string.conn_error_session_failed)),
            wordsOf(ConnectionError.SessionFailed),
        )
    }

    @Test
    fun `a refused session says the connection failed and quotes the satellite's reason`() {
        assertEquals(
            Words(R.string.conn_error_title_session_failed, Lookup(R.string.conn_error_session_refused, listOf(REASON))),
            wordsOf(ConnectionError.SessionRefused(REASON)),
        )
    }

    @Test
    fun `a controller the satellite could not apply names the satellite, then the failures`() {
        assertEquals(
            Words(R.string.conn_error_title_apply_failed, Lookup(R.string.conn_error_apply_failed, listOf(SERVER, FAILURES))),
            wordsOf(ConnectionError.ApplyFailed(SERVER, FAILURES)),
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
    fun `a pick a Moonlight host no longer lists names the app and then the host`() {
        assertEquals(
            Lookup(R.string.ml_error_app_removed, listOf("Steam", SERVER)),
            lookupOf { moonlightErrorText(MoonlightError.AppRemoved(SERVER, "Steam"), it) },
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
        val resources = EVERY_CONNECTION_ERROR.map(::wordsOf).map { it.body.res }
        assertEquals(resources.size, resources.toSet().size)
    }

    @Test
    fun `every string takes exactly the arguments its text passes, in every locale`() {
        val lookups =
            EVERY_CONNECTION_ERROR.map(::wordsOf).map { it.body } +
                EVERY_CONNECTION_ERROR.map(::wordsOf).map { Lookup(it.title, listOf(SERVER)) } +
                EVERY_MOONLIGHT_ERROR.map(::moonlightLookup) +
                lookupOf { appCloseRequestedText(SERVER, it) }
        val mismatches = LOCALES.flatMap { locale -> mismatchesIn(locale, lookups) }
        assertTrue("strings whose placeholders do not match their arguments: $mismatches", mismatches.isEmpty())
    }

    // The title's only placeholder is the satellite's name, which the screen fills in.
    private fun wordsOf(error: ConnectionError): Words {
        var title = 0
        val body = lookupOf { strings -> connectionErrorWords(error, strings).also { title = it.title }.body }
        return Words(title, body)
    }

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

private data class Words(
    val title: Int,
    val body: Lookup,
)

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
