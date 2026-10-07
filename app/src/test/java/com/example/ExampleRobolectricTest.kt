package com.example

import android.app.Notification
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.PenVpnDatabase
import com.example.data.repository.PenVpnRepository
import com.example.security.CryptoKeyManager
import com.example.vpn.PenVpnService
import com.example.vpn.TunnelStatus
import com.example.vpn.WireGuardVpnServiceWrapper
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows

@RunWith(RobolectricTestRunner::class)
class ExampleRobolectricTest {

    private lateinit var context: Context
    private lateinit var repository: PenVpnRepository
    private lateinit var vpnWrapper: WireGuardVpnServiceWrapper

    @Before
    fun setUp() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        val db = PenVpnDatabase.getInstance(context)
        repository = PenVpnRepository(context, db.penVpnDao())
        vpnWrapper = WireGuardVpnServiceWrapper(context)
        repository.ensureSeeded()
        PenVpnService.disconnectVerifiedSession()
    }

    @After
    fun tearDown() {
        vpnWrapper.stopTunnel()
    }

    @Test
    fun serverSeed_containsAll25GlobalCountries() = runBlocking {
        val servers = repository.allServersFlow.first()
        assertTrue("Expected at least 25 global servers", servers.size >= 25)
        val countries = servers.map { it.countryName }.toSet()
        assertTrue(countries.contains("United States"))
        assertTrue(countries.contains("United Kingdom"))
        assertTrue(countries.contains("Germany"))
        assertTrue(countries.contains("Japan"))
        assertTrue(countries.contains("Pakistan"))
        assertTrue(countries.contains("South Africa"))
    }

    @Test
    fun fastestServerSelection_picksLowLatencyOnlineNode() = runBlocking {
        val fastestFree = repository.findFastestServer(isPremiumUser = false)
        assertNotNull(fastestFree)
        assertTrue(fastestFree!!.isOnline)
        assertFalse(fastestFree.isPremiumOnly)
    }

    @Test
    fun passwordAndKeyStorage_neverStoresPlainText() = runBlocking {
        val plainPassword = "SuperSecretPassword!99"
        val userResult = repository.signUp("Alice Security", "alice@example.com", plainPassword)
        assertTrue(userResult.isSuccess)
        val user = userResult.getOrNull()!!
        assertNotEquals("Password hash must never equal plain text", plainPassword, user.passwordHash)
        assertTrue("Encrypted auth token must not be empty", user.encryptedAuthToken.isNotBlank())

        val (pubKey, encryptedPrivKey) = CryptoKeyManager.generateWireGuardKeyPair(context)
        assertTrue(pubKey.isNotBlank())
        assertTrue(encryptedPrivKey.isNotBlank())
        assertNotEquals(pubKey, encryptedPrivKey)
    }

    @Test
    fun wireGuardVpnServiceWrapper_establishesAndDisconnectsTunnel() = runBlocking {
        val configRes = repository.requestWireGuardConfig(
            serverId = "us-nyc-01",
            useFastest = false,
            isPremiumUser = false,
            protocol = "WireGuard (UDP)",
            dnsLeakProtection = true,
            ipv6LeakProtection = true,
            killSwitch = true
        )
        assertTrue(configRes.isSuccess)
        val (config, server) = configRes.getOrNull()!!
        assertTrue(config.toWgQuickConfigSummary().contains("[Interface]"))
        assertTrue(config.toWgQuickConfigSummary().contains("[Peer]"))

        vpnWrapper.startTunnel(
            config = config,
            serverLoad = server.loadPercent,
            serverPing = server.pingMs,
            forceHeadlessFallback = true
        )
        val connectedState = vpnWrapper.telemetryState.value
        assertEquals(TunnelStatus.CONNECTED, connectedState.status)
        assertTrue(connectedState.tunInterfaceEstablished)
        assertEquals("United States", connectedState.activeConfig?.countryName)

        vpnWrapper.stopTunnel()
        assertEquals(TunnelStatus.DISCONNECTED, vpnWrapper.telemetryState.value.status)
    }

    @Test
    fun persistentForegroundNotification_showsRealTimeStatusAndQuickDisconnectButton() = runBlocking {
        val configRes = repository.requestWireGuardConfig(
            serverId = "us-nyc-01",
            useFastest = false,
            isPremiumUser = false,
            protocol = "WireGuard (UDP)",
            dnsLeakProtection = true,
            ipv6LeakProtection = true,
            killSwitch = true
        )
        val (config, server) = configRes.getOrNull()!!
        vpnWrapper.startTunnel(
            config = config,
            serverLoad = server.loadPercent,
            serverPing = server.pingMs,
            forceHeadlessFallback = true
        )

        val telemetry = vpnWrapper.telemetryState.value
        PenVpnService.ensureNotificationChannel(context)
        val notification = PenVpnService.buildStatusNotification(context, telemetry)

        val title = notification.extras.getString(Notification.EXTRA_TITLE) ?: ""
        val text = notification.extras.getString(Notification.EXTRA_TEXT) ?: ""

        assertTrue("Notification title should show CONNECTED and location", title.contains("CONNECTED"))
        assertTrue("Notification title should show United States", title.contains("United States"))
        assertTrue("Notification body should show real-time speed and load", text.contains("Load:"))
        assertTrue("Notification must be ongoing/persistent", (notification.flags and Notification.FLAG_ONGOING_EVENT) != 0)

        // Verify quick-action Disconnect button exists and dispatches ACTION_DISCONNECT to PenVpnService
        assertNotNull("Notification actions must not be null", notification.actions)
        assertEquals(1, notification.actions.size)
        val disconnectAction = notification.actions[0]
        assertEquals("Disconnect", disconnectAction.title.toString())

        val shadowPendingIntent = Shadows.shadowOf(disconnectAction.actionIntent)
        val triggeredIntent = shadowPendingIntent.savedIntent
        assertEquals(PenVpnService.ACTION_DISCONNECT, triggeredIntent.action)

        // Execute ACTION_DISCONNECT on PenVpnService and verify tunnel disconnects
        val service = Robolectric.setupService(PenVpnService::class.java)
        service.onStartCommand(Intent(context, PenVpnService::class.java).apply {
            action = PenVpnService.ACTION_DISCONNECT
        }, 0, 1)

        assertEquals(TunnelStatus.DISCONNECTED, vpnWrapper.telemetryState.value.status)
    }
}
