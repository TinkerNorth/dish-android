// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.source.system

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import androidx.lifecycle.LifecycleOwner
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.slot
import io.mockk.unmockkConstructor
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class NetworkStateObserverTest {
    // The platform builder is a stub here: it has to hand back itself and then a request, or
    // registering the callback dies before the observer gets to its own code.
    @Before
    fun stubRequestBuilder() {
        mockkConstructor(NetworkRequest.Builder::class)
        every { anyConstructed<NetworkRequest.Builder>().addCapability(any()) } answers { self as NetworkRequest.Builder }
        every { anyConstructed<NetworkRequest.Builder>().build() } returns mockk()
    }

    @After
    fun unstubRequestBuilder() {
        unmockkConstructor(NetworkRequest.Builder::class)
    }

    @Test
    fun `a network without internet reads none even over wifi`() {
        assertEquals(NetworkState.NONE, networkStateOf(hasInternet = false, wifi = true, ethernet = false, cellular = false))
    }

    @Test
    fun `wifi reads wifi`() {
        assertEquals(NetworkState.WIFI, networkStateOf(hasInternet = true, wifi = true, ethernet = false, cellular = false))
    }

    @Test
    fun `ethernet reads as wifi`() {
        assertEquals(NetworkState.WIFI, networkStateOf(hasInternet = true, wifi = false, ethernet = true, cellular = false))
    }

    @Test
    fun `cellular reads cellular`() {
        assertEquals(NetworkState.CELLULAR, networkStateOf(hasInternet = true, wifi = false, ethernet = false, cellular = true))
    }

    @Test
    fun `wifi outranks cellular on a dual transport`() {
        assertEquals(NetworkState.WIFI, networkStateOf(hasInternet = true, wifi = true, ethernet = false, cellular = true))
    }

    @Test
    fun `internet over an unknown transport reads none`() {
        assertEquals(NetworkState.NONE, networkStateOf(hasInternet = true, wifi = false, ethernet = false, cellular = false))
    }

    @Test
    fun `leaving wifi counts one drop`() {
        assertEquals(1, wifiDropsAfter(NetworkState.WIFI, NetworkState.NONE, drops = 0))
        assertEquals(3, wifiDropsAfter(NetworkState.WIFI, NetworkState.CELLULAR, drops = 2))
    }

    @Test
    fun `staying on wifi counts nothing`() {
        assertEquals(2, wifiDropsAfter(NetworkState.WIFI, NetworkState.WIFI, drops = 2))
    }

    @Test
    fun `returning to wifi counts nothing`() {
        assertEquals(1, wifiDropsAfter(NetworkState.NONE, NetworkState.WIFI, drops = 1))
    }

    @Test
    fun `a change between other transports counts nothing`() {
        assertEquals(0, wifiDropsAfter(NetworkState.NONE, NetworkState.CELLULAR, drops = 0))
    }

    private val cm = mockk<ConnectivityManager>(relaxed = true)
    private val context =
        mockk<Context> {
            every { getSystemService(Context.CONNECTIVITY_SERVICE) } returns cm
        }

    private fun wifiIsActive() {
        val network = mockk<Network>()
        val caps = mockk<NetworkCapabilities>()
        every { caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) } returns true
        every { caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) } returns true
        every { caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) } returns false
        every { caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) } returns false
        every { cm.activeNetwork } returns network
        every { cm.getNetworkCapabilities(network) } returns caps
    }

    private fun nothingIsActive() {
        every { cm.activeNetwork } returns null
    }

    @Test
    fun `no active network reads none at construction`() {
        nothingIsActive()

        assertEquals(NetworkState.NONE, NetworkStateObserver(context).state.value)
    }

    @Test
    fun `an active wifi network reads wifi at construction`() {
        wifiIsActive()

        assertEquals(NetworkState.WIFI, NetworkStateObserver(context).state.value)
    }

    @Test
    fun `losing the wifi network after start counts one drop`() {
        wifiIsActive()
        val observer = NetworkStateObserver(context)
        val callback = slot<ConnectivityManager.NetworkCallback>()
        every { cm.registerNetworkCallback(any(), capture(callback)) } returns Unit
        observer.onStart(mockk<LifecycleOwner>())

        nothingIsActive()
        callback.captured.onLost(mockk())

        assertEquals(NetworkState.NONE, observer.state.value)
        assertEquals(1, observer.wifiDrops.value)
    }

    @Test
    fun `a second onStart registers once and onStop unregisters once`() {
        nothingIsActive()
        val observer = NetworkStateObserver(context)
        val owner = mockk<LifecycleOwner>()

        observer.onStart(owner)
        observer.onStart(owner)
        observer.onStop(owner)
        observer.onStop(owner)

        verify(exactly = 1) { cm.registerNetworkCallback(any(), any<ConnectivityManager.NetworkCallback>()) }
        verify(exactly = 1) { cm.unregisterNetworkCallback(any<ConnectivityManager.NetworkCallback>()) }
    }
}
