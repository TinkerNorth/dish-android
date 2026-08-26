// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.source.connection.moonlight

import android.content.Context
import android.content.SharedPreferences
import com.tinkernorth.dish.core.net.moonlight.MoonlightHost
import com.tinkernorth.dish.core.net.moonlight.MoonlightIdentity
import com.tinkernorth.dish.core.net.moonlight.RememberedMoonlight
import com.tinkernorth.dish.repository.RememberedMoonlightRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The whole trust half of the Moonlight flow, end to end: discovery, pairing,
 * re-pairing, forget, and what each leaves behind on THIS side.
 *
 * The two live failures this suite locks down were both about state that was never
 * written. A host the host itself still trusted was confirmed and not recorded, so
 * pairing appeared to do nothing forever; and a host that had never completed a
 * pairing was never recorded at all, so it lived only in the discovery list and took
 * any binding pointing at it down with it on the next scan.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MoonlightTrustFlowTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var gateway: MoonlightHttpGateway
    private lateinit var discovery: MdnsMoonlightDiscovery
    private lateinit var store: RememberedMoonlightRepository
    private lateinit var manager: MoonlightConnectionManager

    /** What the fake store holds, so a test can assert on the record and not on a call. */
    private val rows = linkedMapOf<String, RememberedMoonlight>()
    private val entries = MutableStateFlow<List<RememberedMoonlight>>(emptyList())

    // The address-keyed form, because the live hosts publish no uniqueid TXT record and
    // that is the id every one of them is actually filed under.
    private val host =
        MoonlightHost(name = "PC", address = "192.168.68.98", httpPort = 47989, httpsPort = 47984)

    private val pairedInfo =
        """<root status_code="200"><hostname>PC</hostname><uniqueid>host-1</uniqueid>
           <PairStatus>1</PairStatus><currentgame>0</currentgame></root>"""

    private val unpairedInfo =
        """<root status_code="200"><hostname>PC</hostname><uniqueid>host-1</uniqueid>
           <PairStatus>0</PairStatus><currentgame>0</currentgame></root>"""

    private val appList =
        """<root status_code="200"><App><AppTitle>Desktop</AppTitle><ID>1</ID></App></root>"""

    private fun reply(body: String) = MoonlightHttpGateway.Reply(status = 200, body = body)

    private fun unreachable() = MoonlightHttpGateway.Reply(status = 0, body = "")

    @Before
    fun setUp() {
        val prefs = mockk<SharedPreferences>(relaxed = true)
        every { prefs.getString("uniqueid", null) } returns "7b5d0738cbb54d3e"
        val context = mockk<Context>(relaxed = true)
        every { context.getSharedPreferences(any(), any()) } returns prefs

        gateway = mockk(relaxed = true)
        every { gateway.getHttp(match { it.contains("/serverinfo") }, any()) } returns reply(pairedInfo)
        every { gateway.getHttps(match { it.contains("/serverinfo") }, any()) } returns reply(pairedInfo)
        every { gateway.getHttps(match { it.contains("/applist") }, any()) } returns reply(appList)
        every { gateway.getHttps(match { it.contains("/cancel") }, any()) } returns
            reply("""<root status_code="200"><cancel>1</cancel></root>""")

        discovery = mockk(relaxed = true)

        // A store backed by a real map: these tests are about what survives a flow, and a
        // relaxed mock would answer every read with null no matter what the flow wrote.
        store = mockk(relaxed = true)
        every { store.get(any()) } answers { rows[firstArg<String>()] }
        every { store.all() } answers { rows.values.toList() }
        every { store.entries } returns entries
        every { store.put(any<RememberedMoonlight>()) } answers {
            val row = firstArg<RememberedMoonlight>()
            rows[row.id] = row
            entries.value = rows.values.toList()
        }
        every { store.remove(any<String>()) } answers {
            rows.remove(firstArg<String>())
            entries.value = rows.values.toList()
        }

        manager = newManager()
    }

    private fun newManager() =
        MoonlightConnectionManager(
            context =
                mockk<Context>(relaxed = true).also { ctx ->
                    val prefs = mockk<SharedPreferences>(relaxed = true)
                    every { prefs.getString("uniqueid", null) } returns "7b5d0738cbb54d3e"
                    every { ctx.getSharedPreferences(any(), any()) } returns prefs
                },
            scope = TestScope(dispatcher),
            ioDispatcher = dispatcher,
            discovery = discovery,
            gateway = gateway,
            identity = mockk<MoonlightIdentity>(relaxed = true),
            store = store,
        )

    // ── Pairing ────────────────────────────────────────────────────────────────

    // THE SYMPTOM-B REGRESSION. The host authorises by client certificate, so a device
    // that forgot a host the host still trusts is answered without a PIN. That answer used
    // to be emitted and thrown away: nothing was written, the row kept saying Not paired,
    // and the only trace of the whole action was a /serverinfo in the host's own log.
    @Test
    fun `a host that already trusts this device is recorded, not just announced`() =
        runTest(dispatcher) {
            val seen = mutableListOf<MoonlightConnectionEvent>()
            val collector = launch { manager.events.toList(seen) }
            dispatcher.scheduler.runCurrent()

            assertTrue(manager.pairHost(host))
            dispatcher.scheduler.advanceUntilIdle()

            val record = rows[host.id]
            assertNotNull("the confirmed pairing has to persist", record)
            assertTrue("a confirmed pairing is a pairing", record!!.paired)
            assertEquals(host.address, record.address)
            assertTrue(seen.any { it is MoonlightConnectionEvent.Paired })
            collector.cancel()
        }

    @Test
    fun `confirming trust needs no PIN exchange at all`() =
        runTest(dispatcher) {
            manager.pairHost(host)
            dispatcher.scheduler.advanceUntilIdle()

            verify(exactly = 0) { gateway.getHttp(match { it.contains("/pair") }, any()) }
        }

    // The host was verified this visit, which is the only proof there is that the pairing
    // stands; the hosts screen reads it so a successful pair visibly changes the row.
    @Test
    fun `a confirmed host is marked verified for this process`() =
        runTest(dispatcher) {
            assertFalse(host.id in manager.verifiedHostIds.value)

            manager.pairHost(host)
            dispatcher.scheduler.advanceUntilIdle()

            assertTrue(host.id in manager.verifiedHostIds.value)
        }

    @Test
    fun `a host that refuses phase 1 fails with a reason and writes nothing`() =
        runTest(dispatcher) {
            every { gateway.getHttps(match { it.contains("/serverinfo") }, any()) } returns unreachable()
            every { gateway.getHttp(match { it.contains("/pair") }, any()) } returns reply("""<root status_code="400"/>""")
            val seen = mutableListOf<MoonlightConnectionEvent>()
            val collector = launch { manager.events.toList(seen) }
            dispatcher.scheduler.runCurrent()

            assertFalse(manager.pairHost(host))
            dispatcher.scheduler.advanceUntilIdle()

            val failure = seen.filterIsInstance<MoonlightConnectionEvent.PairingFailed>().single()
            assertTrue("the reason has to name the step", failure.reason.contains("phase 1"))
            assertNull(rows[host.id])
            collector.cancel()
        }

    @Test
    fun `a PIN is offered before phase 1 blocks on the human`() =
        runTest(dispatcher) {
            every { gateway.getHttps(match { it.contains("/serverinfo") }, any()) } returns unreachable()
            every { gateway.getHttp(match { it.contains("/pair") }, any()) } returns reply("""<root status_code="400"/>""")
            val seen = mutableListOf<MoonlightConnectionEvent>()
            val collector = launch { manager.events.toList(seen) }
            dispatcher.scheduler.runCurrent()

            manager.pairHost(host)
            dispatcher.scheduler.advanceUntilIdle()

            val pin = seen.filterIsInstance<MoonlightConnectionEvent.PairingPinReady>().single()
            assertEquals(4, pin.pin.length)
            collector.cancel()
        }

    // ── Forget ─────────────────────────────────────────────────────────────────

    @Test
    fun `forget leaves no record, no pin and no verification behind`() =
        runTest(dispatcher) {
            manager.pairHost(host)
            dispatcher.scheduler.advanceUntilIdle()
            assertNotNull(rows[host.id])

            manager.forget(host.id)
            dispatcher.scheduler.advanceUntilIdle()

            assertNull("the record must go", rows[host.id])
            assertFalse("the verification must go", host.id in manager.verifiedHostIds.value)
            assertTrue("the discovery row must go", manager.discovered.value.none { it.id == host.id })
            // The pin used to survive a forget, so a host that rotated its certificate
            // afterwards was refused with no way past it from inside the app.
            verify { gateway.forgetPin(host.id) }
        }

    @Test
    fun `forgetting a host with a live session closes the app it is running`() =
        runTest(dispatcher) {
            openLiveSession()

            manager.forget(host.id)
            dispatcher.scheduler.advanceUntilIdle()

            verify(atLeast = 1) { gateway.getHttps(match { it.contains("/cancel") }, any()) }
        }

    @Test
    fun `forgetting a host that never had a session cancels nothing`() =
        runTest(dispatcher) {
            manager.pairHost(host)
            dispatcher.scheduler.advanceUntilIdle()

            manager.forget(host.id)
            dispatcher.scheduler.advanceUntilIdle()

            verify(exactly = 0) { gateway.getHttps(match { it.contains("/cancel") }, any()) }
        }

    // ── Re-pairing after forget: the exact state the user was stranded in ──────

    // Pair, forget, pair again. The host never stops trusting this device (the protocol has
    // no unpair verb), so the second pairing takes the confirm branch, and before the fix
    // that branch wrote nothing: the user could press Pair forever with no change anywhere.
    @Test
    fun `pairing again after a forget puts the two sides back into agreement`() =
        runTest(dispatcher) {
            manager.pairHost(host)
            dispatcher.scheduler.advanceUntilIdle()
            manager.forget(host.id)
            dispatcher.scheduler.advanceUntilIdle()
            assertNull(rows[host.id])

            assertTrue(manager.pairHost(host))
            dispatcher.scheduler.advanceUntilIdle()

            assertNotNull("the second pairing has to restore the record", rows[host.id])
            assertTrue(rows[host.id]!!.paired)
            assertTrue(host.id in manager.verifiedHostIds.value)
        }

    // ── Discovery ──────────────────────────────────────────────────────────────

    // THE SYMPTOM-A REGRESSION. A scan used to assign its result outright, so a browse that
    // missed erased every host that was only ever discovered, and a binding pointing at one
    // lost its connection summary, its pads and its session with it.
    @Test
    fun `a scan that finds nothing keeps what the last scan found`() =
        runTest(dispatcher) {
            coEvery { discovery.discover(any()) } returns listOf(host)
            manager.startDiscovery()
            dispatcher.scheduler.advanceUntilIdle()
            assertEquals(listOf(host.id), manager.discovered.value.map { it.id })

            coEvery { discovery.discover(any()) } returns emptyList()
            manager.startDiscovery()
            dispatcher.scheduler.advanceUntilIdle()

            assertEquals(listOf(host.id), manager.discovered.value.map { it.id })
        }

    @Test
    fun `a scan that throws keeps what the last scan found`() =
        runTest(dispatcher) {
            coEvery { discovery.discover(any()) } returns listOf(host)
            manager.startDiscovery()
            dispatcher.scheduler.advanceUntilIdle()

            coEvery { discovery.discover(any()) } throws java.io.IOException("no multicast")
            manager.startDiscovery()
            dispatcher.scheduler.advanceUntilIdle()

            assertEquals(listOf(host.id), manager.discovered.value.map { it.id })
        }

    @Test
    fun `a re-scan refreshes a host in place instead of duplicating it`() =
        runTest(dispatcher) {
            coEvery { discovery.discover(any()) } returns listOf(host)
            manager.startDiscovery()
            dispatcher.scheduler.advanceUntilIdle()

            coEvery { discovery.discover(any()) } returns listOf(host.copy(name = "PC renamed"))
            manager.startDiscovery()
            dispatcher.scheduler.advanceUntilIdle()

            assertEquals(listOf("PC renamed"), manager.discovered.value.map { it.name })
        }

    // Typing an address is durable intent, so the host outlives the discovery list it would
    // otherwise be the only copy of.
    @Test
    fun `a manually added host is remembered without claiming a pairing`() =
        runTest(dispatcher) {
            manager.addManualHost("192.168.68.98")
            dispatcher.scheduler.advanceUntilIdle()

            val record = rows.values.single()
            assertEquals("192.168.68.98", record.address)
            assertFalse("adding is not pairing", record.paired)
        }

    @Test
    fun `an address nothing answers is reported and not remembered`() =
        runTest(dispatcher) {
            every { gateway.getHttp(match { it.contains("/serverinfo") }, any()) } returns unreachable()
            val seen = mutableListOf<MoonlightConnectionEvent>()
            val collector = launch { manager.events.toList(seen) }
            dispatcher.scheduler.runCurrent()

            manager.addManualHost("192.168.68.5")
            dispatcher.scheduler.advanceUntilIdle()

            assertTrue(seen.any { it is MoonlightConnectionEvent.Error })
            assertTrue(rows.isEmpty())
            collector.cancel()
        }

    // ── Durable interest ───────────────────────────────────────────────────────

    @Test
    fun `remembering interest never promotes a host to paired`() =
        runTest(dispatcher) {
            manager.rememberInterest(host)
            dispatcher.scheduler.advanceUntilIdle()

            assertFalse(rows.getValue(host.id).paired)
        }

    @Test
    fun `remembering interest never demotes a host that is already paired`() =
        runTest(dispatcher) {
            manager.pairHost(host)
            dispatcher.scheduler.advanceUntilIdle()

            manager.rememberInterest(host)
            dispatcher.scheduler.advanceUntilIdle()

            assertTrue(rows.getValue(host.id).paired)
        }

    @Test
    fun `a host known only from a scan can be remembered by id`() =
        runTest(dispatcher) {
            coEvery { discovery.discover(any()) } returns listOf(host)
            manager.startDiscovery()
            dispatcher.scheduler.advanceUntilIdle()

            manager.rememberInterest(host.id)
            dispatcher.scheduler.advanceUntilIdle()

            assertNotNull(rows[host.id])
        }

    // ── The app pick ───────────────────────────────────────────────────────────

    // The pick used to be dropped for any host with no record, which was every host the
    // user had only discovered: the row rendered as chosen and the session then started
    // whatever the host listed first.
    @Test
    fun `an app picked on a host with no record yet is kept`() =
        runTest(dispatcher) {
            coEvery { discovery.discover(any()) } returns listOf(host)
            manager.startDiscovery()
            dispatcher.scheduler.advanceUntilIdle()

            manager.rememberApp(host.id, "1", "Desktop")
            dispatcher.scheduler.advanceUntilIdle()

            assertEquals("1", manager.rememberedAppId(host.id))
            assertEquals("Desktop", manager.rememberedAppName(host.id))
        }

    @Test
    fun `an app picked on a paired host does not disturb its trust`() =
        runTest(dispatcher) {
            manager.pairHost(host)
            dispatcher.scheduler.advanceUntilIdle()

            manager.rememberApp(host.id, "7", "Steam")
            dispatcher.scheduler.advanceUntilIdle()

            assertTrue(rows.getValue(host.id).paired)
            assertEquals("7", rows.getValue(host.id).lastAppId)
        }

    // ── Probe verdicts ─────────────────────────────────────────────────────────

    @Test
    fun `a paired host that stops answering is remembered, not unknown`() =
        runTest(dispatcher) {
            manager.pairHost(host)
            dispatcher.scheduler.advanceUntilIdle()
            every { gateway.getHttp(match { it.contains("/serverinfo") }, any()) } returns unreachable()

            assertEquals(MoonlightTrustState.REMEMBERED, manager.probe(host).trust)
        }

    // A record written for interest is not a pairing, so the honest verdict for a host
    // nothing answers for is still "never paired".
    @Test
    fun `a host remembered only as interest reads as unreachable when it goes quiet`() =
        runTest(dispatcher) {
            manager.rememberInterest(host)
            every { gateway.getHttp(match { it.contains("/serverinfo") }, any()) } returns unreachable()

            assertEquals(MoonlightTrustState.UNREACHABLE, manager.probe(host).trust)
        }

    @Test
    fun `a host that answers unpaired with a pairing stored has lost trust`() =
        runTest(dispatcher) {
            manager.pairHost(host)
            dispatcher.scheduler.advanceUntilIdle()
            every { gateway.getHttp(match { it.contains("/serverinfo") }, any()) } returns reply(unpairedInfo)

            assertEquals(MoonlightTrustState.TRUST_LOST, manager.probe(host).trust)
        }

    @Test
    fun `a host that answers unpaired with nothing stored has simply never paired`() =
        runTest(dispatcher) {
            every { gateway.getHttp(match { it.contains("/serverinfo") }, any()) } returns reply(unpairedInfo)

            assertEquals(MoonlightTrustState.NOT_PAIRED, manager.probe(host).trust)
        }

    @Test
    fun `a host answering under a new identity is replaced, not merely untrusted`() =
        runTest(dispatcher) {
            manager.pairHost(host.copy(uniqueId = "host-1"))
            dispatcher.scheduler.advanceUntilIdle()
            val replaced =
                """<root status_code="200"><uniqueid>host-2</uniqueid><PairStatus>1</PairStatus>
                   <currentgame>0</currentgame></root>"""
            every { gateway.getHttp(match { it.contains("/serverinfo") }, any()) } returns reply(replaced)

            assertEquals(
                MoonlightTrustState.REPLACED,
                manager.probe(host.copy(uniqueId = "host-1")).trust,
            )
        }

    @Test
    fun `a host that will not answer mutual TLS has lost trust`() =
        runTest(dispatcher) {
            manager.pairHost(host)
            dispatcher.scheduler.advanceUntilIdle()
            every { gateway.getHttps(match { it.contains("/serverinfo") }, any()) } returns unreachable()

            assertEquals(MoonlightTrustState.TRUST_LOST, manager.probe(host).trust)
        }

    @Test
    fun `a paired host whose app list will not load still reads as paired`() =
        runTest(dispatcher) {
            every { gateway.getHttps(match { it.contains("/applist") }, any()) } returns
                MoonlightHttpGateway.Reply(status = 401, body = "")

            val probe = manager.probe(host)

            assertEquals(MoonlightTrustState.PAIRED, probe.trust)
            assertTrue(probe.appsFailed)
            assertFalse(probe.appsFetched)
        }

    private suspend fun openLiveSession() {
        every { gateway.getHttps(match { it.contains("/launch") }, any()) } returns
            reply("""<root status_code="200"><sessionUrl0>rtsp://192.168.68.98:48010</sessionUrl0></root>""")
        manager.pairHost(host)
        dispatcher.scheduler.advanceUntilIdle()
        manager.applyDesired(
            mapOf(
                host.id to
                    listOf(
                        MoonlightPadRequest(slotId = "a", emulatedType = 1, capabilities = 0x03, supportedButtons = 0xFFFF),
                    ),
            ),
        )
        dispatcher.scheduler.advanceUntilIdle()
    }
}
