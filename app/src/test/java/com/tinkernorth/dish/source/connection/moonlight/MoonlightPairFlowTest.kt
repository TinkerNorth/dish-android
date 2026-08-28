// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.source.connection.moonlight

import android.content.Context
import android.content.SharedPreferences
import com.tinkernorth.dish.core.net.bytesToHex
import com.tinkernorth.dish.core.net.moonlight.MoonlightHost
import com.tinkernorth.dish.core.net.moonlight.MoonlightIdentity
import com.tinkernorth.dish.core.net.moonlight.MoonlightReferenceServer
import com.tinkernorth.dish.core.net.moonlight.RememberedMoonlight
import com.tinkernorth.dish.core.net.moonlight.ThrowawayIdentity
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.URLDecoder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * The five phases as the manager actually drives them, against a reference host
 * built from the same crypto primitives. Nothing here is stubbed hex: the host
 * derives its key from the salt and the PIN the dish minted, so a client that
 * skipped a check would be refused by the host rather than by an assertion.
 *
 * The PIN never crosses the wire, so this reads it the way the user does, off the
 * PairingPinReady event the manager emits before it makes any call. That watcher
 * runs on a real thread on purpose: the manager emits and then goes straight into
 * phase 1 without yielding, so a collector on the test dispatcher would not have
 * run by the time the reply it is needed for has to be built.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MoonlightPairFlowTest {
    private val dispatcher = StandardTestDispatcher()
    private val watcher = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var gateway: MoonlightHttpGateway
    private lateinit var manager: MoonlightConnectionManager

    private val rows = linkedMapOf<String, RememberedMoonlight>()
    private val entries = MutableStateFlow<List<RememberedMoonlight>>(emptyList())

    private val host = MoonlightHost(name = "PC", address = "192.168.68.98", httpPort = 47989, httpsPort = 47984)

    // One PIN per pairing attempt, handed from the watcher thread to whichever phase-1
    // reply is being built. The host cannot answer phase 2 without it.
    private val pins = LinkedBlockingQueue<String>()

    @Volatile private var server: MoonlightReferenceServer? = null

    private fun reply(body: String) = MoonlightHttpGateway.Reply(status = 200, body = body)

    private fun paramsOf(url: String): Map<String, String> =
        url
            .substringAfter('?')
            .split('&')
            .mapNotNull { pair ->
                val name = pair.substringBefore('=')
                name.takeIf { it.isNotEmpty() }?.to(URLDecoder.decode(pair.substringAfter('=', ""), "UTF-8"))
            }.toMap()

    /**
     * The host half, keyed off the phase each request identifies itself by. [stopAt]
     * refuses one named phase; the PIN is taken off the queue either way, because a
     * pairing that ends early still minted one and the next attempt mints its own.
     */
    private fun answerPair(
        url: String,
        stopAt: String? = null,
    ): MoonlightHttpGateway.Reply {
        val params = paramsOf(url)
        val refused = reply("""<root status_code="200"><paired>0</paired></root>""")
        return when {
            params["phrase"] == "getservercert" -> {
                val pin = pins.poll(PIN_WAIT_S, TimeUnit.SECONDS)
                assertNotNull("the PIN has to be on screen before phase 1 blocks on the human", pin)
                val fresh = MoonlightReferenceServer(pin!!, HOST, CLIENT.certificatePem)
                server = fresh
                val certHex = bytesToHex(fresh.getServerCert(params.getValue("salt")).toByteArray(Charsets.US_ASCII))
                if (stopAt == "phase 1") {
                    refused
                } else {
                    reply("""<root status_code="200"><paired>1</paired><plaincert>$certHex</plaincert></root>""")
                }
            }
            params.containsKey("clientchallenge") ->
                if (stopAt == "phase 2") {
                    refused
                } else {
                    reply(
                        "<root status_code=\"200\"><paired>1</paired><challengeresponse>" +
                            server!!.challengeResponse(params.getValue("clientchallenge")) +
                            "</challengeresponse></root>",
                    )
                }
            params.containsKey("serverchallengeresp") ->
                if (stopAt == "phase 3") {
                    refused
                } else {
                    reply(
                        "<root status_code=\"200\"><paired>1</paired><pairingsecret>" +
                            server!!.clientHashResponse(params.getValue("serverchallengeresp")) +
                            "</pairingsecret></root>",
                    )
                }
            params.containsKey("clientpairingsecret") -> {
                val ok = stopAt != "phase 4" && server!!.verifyClient(params.getValue("clientpairingsecret"))
                reply("""<root status_code="200"><paired>${if (ok) 1 else 0}</paired></root>""")
            }
            else -> reply("""<root status_code="400"/>""")
        }
    }

    @Before
    fun setUp() {
        val prefs = mockk<SharedPreferences>(relaxed = true)
        every { prefs.getString("uniqueid", null) } returns "7b5d0738cbb54d3e"
        val context = mockk<Context>(relaxed = true)
        every { context.getSharedPreferences(any(), any()) } returns prefs

        gateway = mockk(relaxed = true)
        // Nothing is paired yet, so the shortcut that confirms standing trust must not fire.
        every { gateway.getHttps(match { it.contains("/serverinfo") }, any()) } returns MoonlightHttpGateway.Reply(0, "")
        every { gateway.getHttp(match { it.contains("/serverinfo") }, any()) } returns
            reply("""<root status_code="200"><hostname>PC</hostname><PairStatus>0</PairStatus></root>""")
        every { gateway.getHttp(match { it.contains("/pair") }, any()) } answers { answerPair(firstArg()) }

        val store = mockk<com.tinkernorth.dish.repository.RememberedMoonlightRepository>(relaxed = true)
        every { store.get(any()) } answers { rows[firstArg<String>()] }
        every { store.entries } returns entries
        every { store.put(any<RememberedMoonlight>()) } answers {
            val row = firstArg<RememberedMoonlight>()
            rows[row.id] = row
            entries.value = rows.values.toList()
        }

        manager =
            MoonlightConnectionManager(
                context = context,
                scope = TestScope(dispatcher),
                ioDispatcher = dispatcher,
                discovery = mockk(relaxed = true),
                gateway = gateway,
                identity = CLIENT,
                store = store,
            )
    }

    @After
    fun tearDown() {
        watcher.cancel()
    }

    private fun watchForPin(): Job {
        val subscribed = CountDownLatch(1)
        val job =
            manager.events
                .onSubscription { subscribed.countDown() }
                .onEach { event -> if (event is MoonlightConnectionEvent.PairingPinReady) pins.put(event.pin) }
                .launchIn(watcher)
        check(subscribed.await(PIN_WAIT_S, TimeUnit.SECONDS)) { "the PIN watcher never subscribed" }
        return job
    }

    // B3. A never-paired host takes all five phases, and the record it ends in is the one
    // a confirmed pairing writes too: this is the only flow that makes both ledgers agree.
    @Test
    fun `a never-paired host runs all five phases and ends recorded as paired`() =
        runTest(dispatcher) {
            val watching = watchForPin()

            val paired = manager.pairHost(host)
            dispatcher.scheduler.advanceUntilIdle()

            assertTrue("the client says it paired", paired)
            assertTrue("and the host agrees", server!!.paired)
            val record = rows[host.id]
            assertNotNull("a completed pairing has to persist", record)
            assertTrue(record!!.paired)
            assertEquals(host.address, record.address)
            // Phase 5 is the first mutual-TLS call ever made to this host, and it comes last.
            verify { gateway.getHttps(match { it.contains("/pair") && it.contains("pairchallenge") }, host.id) }
            watching.cancel()
        }

    // B3. Phase 1 waits on a HUMAN, not on the network: the host parks the response until
    // the PIN is typed into its own web UI, and the ordinary probe budget tears that down
    // long before anybody could reach a browser.
    @Test
    fun `phase 1 is given the two-minute window the human needs`() =
        runTest(dispatcher) {
            val watching = watchForPin()

            manager.pairHost(host)
            dispatcher.scheduler.advanceUntilIdle()

            verify {
                gateway.getHttp(
                    match { it.contains("/pair") && it.contains("getservercert") },
                    MoonlightHttpGateway.PAIR_PIN_TIMEOUT_MS,
                )
            }
            assertTrue(MoonlightHttpGateway.PAIR_PIN_TIMEOUT_MS >= TWO_MINUTES_MS)
            watching.cancel()
        }

    // MOON-D14. Phases 1 to 4 proved the peer holds the PIN-derived key and signed with the
    // certificate it presented, which outranks a pin written for a host since rebuilt.
    // Without dropping it first, phase 5 is refused and nothing in the app can get past it.
    @Test
    fun `the pinned certificate is dropped once the PIN is proved, before phase 5`() =
        runTest(dispatcher) {
            val watching = watchForPin()

            manager.pairHost(host)
            dispatcher.scheduler.advanceUntilIdle()

            verifyOrder {
                gateway.getHttp(match { it.contains("clientpairingsecret") }, any())
                gateway.forgetPin(host.id)
                gateway.getHttps(match { it.contains("pairchallenge") }, host.id)
            }
            watching.cancel()
        }

    // B5. Six different things fail this flow and they used to arrive as one event with no
    // reason, so "the host was unplugged mid-pairing" told the user to check their typing.
    @Test
    fun `every phase that fails names itself and leaves no half-written record`() =
        runTest(dispatcher) {
            val watching = watchForPin()
            for (phase in listOf("phase 1", "phase 2", "phase 3", "phase 4")) {
                val seen = mutableListOf<MoonlightConnectionEvent>()
                val collector = launch { manager.events.toList(seen) }
                dispatcher.scheduler.runCurrent()
                stopAfter(phase)

                assertFalse(manager.pairHost(host))
                dispatcher.scheduler.advanceUntilIdle()

                val failure = seen.filterIsInstance<MoonlightConnectionEvent.PairingFailed>().single()
                assertTrue("$phase was reported as: ${failure.reason}", failure.reason.contains(phase))
                assertNull("$phase must leave nothing behind", rows[host.id])
                collector.cancel()
            }
            watching.cancel()
        }

    // B5. Cancel is the user's own doing, not a refusal. Letting it fall through the
    // catch-all raised "the host did not accept the PIN" the moment they pressed it.
    @Test
    fun `a cancelled pairing is not reported as a refusal`() =
        runTest(dispatcher) {
            val seen = mutableListOf<MoonlightConnectionEvent>()
            val collector = launch { manager.events.toList(seen) }
            dispatcher.scheduler.runCurrent()
            every { gateway.getHttp(match { it.contains("/pair") }, any()) } answers {
                throw kotlinx.coroutines.CancellationException("the user pressed Cancel")
            }

            val attempt = launch { runCatching { manager.pairHost(host) } }
            dispatcher.scheduler.advanceUntilIdle()

            assertTrue(seen.none { it is MoonlightConnectionEvent.PairingFailed })
            assertNull(rows[host.id])
            attempt.cancel()
            collector.cancel()
        }

    /** Refuse the named phase by answering it with a reply the client cannot use. */
    private fun stopAfter(phase: String) {
        every { gateway.getHttp(match { it.contains("/pair") }, any()) } answers { answerPair(firstArg(), stopAt = phase) }
    }

    private companion object {
        const val PIN_WAIT_S = 5L
        const val TWO_MINUTES_MS = 120_000

        // Minted once for the whole class: RSA-2048 keygen is the slowest thing here and
        // JUnit builds a fresh test instance per method.
        val CLIENT: MoonlightIdentity = ThrowawayIdentity.named("dish-pair-flow-client")
        val HOST: MoonlightIdentity = ThrowawayIdentity.named("dish-pair-flow-host")
    }
}
