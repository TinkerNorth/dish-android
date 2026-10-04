// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.source.connection.moonlight

import android.content.Context
import android.content.SharedPreferences
import com.tinkernorth.dish.core.net.moonlight.MoonlightHost
import com.tinkernorth.dish.core.net.moonlight.MoonlightIdentity
import com.tinkernorth.dish.core.net.moonlight.RememberedMoonlight
import com.tinkernorth.dish.repository.RememberedMoonlightRepository
import com.tinkernorth.dish.source.store.MoonlightHostFactsStore
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * B6. A forget that lands while a screen is still asking the host about itself leaves the host
 * forgotten: whichever of the probe's questions was on the wire when it landed, the answer writes
 * nothing back, and no question is asked after it.
 */
class MoonlightForgetRaceTest {
    // Probes and forgets run on worker threads, as in the app: a probe blocks on its socket.
    private val workers = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val rows = ConcurrentHashMap<String, RememberedMoonlight>()
    private val entries = MutableStateFlow<List<RememberedMoonlight>>(emptyList())
    private val facts = MoonlightHostFactsStore()
    private val host = MoonlightHost(name = "PC", address = "192.168.68.98")
    private val held = CopyOnWriteArrayList<HeldCall>()
    private lateinit var gateway: MoonlightHttpGateway
    private lateinit var manager: MoonlightConnectionManager

    private fun reply(body: String) = MoonlightHttpGateway.Reply(status = 200, body = body)

    /** A question the host is slow to answer: the probe waits on it until the test answers. */
    private class HeldCall {
        private val asked = CountDownLatch(1)
        private val answer = CompletableFuture<MoonlightHttpGateway.Reply>()

        fun wait(): MoonlightHttpGateway.Reply {
            asked.countDown()
            return answer.join()
        }

        fun awaitAsked(): Boolean = asked.await(WAIT_S, TimeUnit.SECONDS)

        fun answer(reply: MoonlightHttpGateway.Reply) {
            answer.complete(reply)
        }
    }

    @Before
    fun setUp() {
        gateway = mockk(relaxed = true)
        answerEveryLineAlike(gateway)
        every { gateway.getHttp(match { it.contains("/serverinfo") }, any()) } returns reply(PAIRED_INFO)
        every { gateway.getHttps(match { it.contains("/serverinfo") }, any()) } returns reply(PAIRED_INFO)
        every { gateway.getHttps(match { it.contains("/applist") }, any()) } returns reply(APP_LIST)

        val store = mockk<RememberedMoonlightRepository>(relaxed = true)
        every { store.get(any()) } answers { rows[firstArg<String>()] }
        every { store.all() } answers { rows.values.toList() }
        every { store.entries } returns entries
        every { store.put(any<RememberedMoonlight>()) } answers {
            val row = firstArg<RememberedMoonlight>()
            rows[row.id] = row
        }
        every { store.remove(any<String>()) } answers { rows.remove(firstArg<String>()) }

        val prefs = mockk<SharedPreferences>(relaxed = true)
        every { prefs.getString("uniqueid", null) } returns "7b5d0738cbb54d3e"
        val context = mockk<Context>(relaxed = true)
        every { context.getSharedPreferences(any(), any()) } returns prefs

        manager =
            MoonlightConnectionManager(
                context = context,
                scope = workers,
                ioDispatcher = Dispatchers.IO,
                discovery = mockk(relaxed = true),
                gateway = gateway,
                identity = mockk<MoonlightIdentity>(relaxed = true),
                store = store,
                bindings = mockk(relaxed = true),
                hostFacts = facts,
            )
        rows[host.id] = RememberedMoonlight(id = host.id, name = "PC", address = host.address, paired = true)
    }

    @After
    fun tearDown() {
        held.forEach { it.answer(MoonlightHttpGateway.Reply(status = 0, body = "")) }
        workers.cancel()
    }

    private fun holdPlainServerInfo(): HeldCall {
        val call = HeldCall().also(held::add)
        every { gateway.getHttp(match { it.contains("/serverinfo") }, any()) } answers { call.wait() }
        return call
    }

    private fun holdMutualTlsServerInfo(): HeldCall {
        val call = HeldCall().also(held::add)
        every { gateway.getHttps(match { it.contains("/serverinfo") }, any()) } answers { call.wait() }
        return call
    }

    private fun holdAppList(): HeldCall {
        val call = HeldCall().also(held::add)
        every { gateway.getHttps(match { it.contains("/applist") }, any()) } answers { call.wait() }
        return call
    }

    // Probes, lets the forget land while [call] is on the wire, then has the host answer it.
    private fun forgetWhileAsking(
        call: HeldCall,
        answer: String,
    ) {
        val probe = workers.async { manager.probe(host) }
        assertTrue("the host is being asked", call.awaitAsked())

        manager.forget(host.id)
        verify(timeout = WAIT_MS) { gateway.forgetPin(host.id) }
        call.answer(reply(answer))
        runBlocking { withTimeout(WAIT_MS) { probe.await() } }
    }

    private fun assertNothingWrittenBack() {
        assertFalse("the host is not verified again", host.id in manager.verifiedHostIds.value)
        assertNull("nor is what it said about itself kept", facts.factsFor(host.id))
    }

    @Test
    fun `a host forgotten while a probe asks who it is is not asked anything more`() {
        forgetWhileAsking(holdPlainServerInfo(), PAIRED_INFO)

        assertNothingWrittenBack()
        // A mutual-TLS call would pin the host's certificate again, on first use.
        verify(exactly = 0) { gateway.getHttps(any(), any()) }
    }

    @Test
    fun `a host forgotten while a probe asks it over mutual TLS is not asked anything more`() {
        forgetWhileAsking(holdMutualTlsServerInfo(), PAIRED_INFO)

        assertNothingWrittenBack()
        verify(exactly = 0) { gateway.getHttps(match { it.contains("/applist") }, any()) }
    }

    @Test
    fun `a host forgotten while a probe lists its apps is not verified again`() {
        forgetWhileAsking(holdAppList(), APP_LIST)

        assertNothingWrittenBack()
    }

    private companion object {
        const val WAIT_S = 10L
        const val WAIT_MS = 10_000L
        const val PAIRED_INFO =
            """<root status_code="200"><hostname>PC</hostname><uniqueid>host-1</uniqueid><PairStatus>1</PairStatus>
               <currentgame>0</currentgame></root>"""
        const val APP_LIST = """<root status_code="200"><App><AppTitle>Desktop</AppTitle><ID>1</ID></App></root>"""
    }
}
