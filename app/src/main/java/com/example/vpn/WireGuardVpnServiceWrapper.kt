package com.example.vpn

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.Build
import kotlinx.coroutines.flow.StateFlow

/**
 * High-level WireGuard VPN Service Wrapper around Android's native [VpnService] API
 * ([PenVpnService]). Handles:
 * - OS VPN permission preparation ([prepareVpnPermissionIntent])
 * - Tunnel establishment & configuration dispatch ([startTunnel])
 * - Graceful tunnel teardown ([stopTunnel])
 * - Automatic network change monitoring & auto-reconnect when enabled
 * - Reactive state & telemetry observation ([telemetryState])
 */
class WireGuardVpnServiceWrapper(private val appContext: Context) {

    val telemetryState: StateFlow<VpnConnectionTelemetry> = PenVpnService.telemetryState

    private var networkCallbackRegistered = false
    private var autoReconnectEnabled = true

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            val current = telemetryState.value
            if (autoReconnectEnabled &&
                current.status == TunnelStatus.ERROR &&
                current.error == VpnErrorType.NO_INTERNET &&
                current.activeConfig != null
            ) {
                startTunnel(
                    config = current.activeConfig,
                    serverLoad = current.serverLoadPercent,
                    serverPing = current.serverPingMs
                )
            }
        }

        override fun onLost(network: Network) {
            val current = telemetryState.value
            if (current.status == TunnelStatus.CONNECTED && !PenVpnService.hasInternetConnection(appContext)) {
                if (current.activeConfig?.killSwitchEnabled == true) {
                    PenVpnService.updateErrorState(VpnErrorType.NO_INTERNET)
                }
            }
        }
    }

    /**
     * Checks Android's [VpnService.prepare] to see if the user has granted VPN tunnel permission.
     * Returns a system consent [Intent] if permission is needed, or `null` if already granted.
     */
    fun prepareVpnPermissionIntent(context: Context = appContext): Intent? {
        return try {
            VpnService.prepare(context)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Dispatches a connection command to [PenVpnService] with the supplied [WireGuardTunnelConfig]
     * to establish the OS-level TUN interface and start encrypted WireGuard packet routing.
     */
    fun startTunnel(
        config: WireGuardTunnelConfig,
        serverLoad: Int,
        serverPing: Int,
        forceHeadlessFallback: Boolean = false
    ) {
        registerNetworkMonitorIfNeeded()

        if (forceHeadlessFallback) {
            PenVpnService.establishTunnelForVerifiedSession(
                config = config,
                serverLoad = serverLoad,
                serverPing = serverPing
            )
            return
        }

        val intent = Intent(appContext, PenVpnService::class.java).apply {
            action = PenVpnService.ACTION_CONNECT
            putExtra(PenVpnService.EXTRA_SERVER_ID, config.serverId)
            putExtra(PenVpnService.EXTRA_COUNTRY_NAME, config.countryName)
            putExtra(PenVpnService.EXTRA_FLAG_EMOJI, config.flagEmoji)
            putExtra(PenVpnService.EXTRA_CITY, config.city)
            putExtra(PenVpnService.EXTRA_ENDPOINT_HOST, config.endpointHost)
            putExtra(PenVpnService.EXTRA_ENDPOINT_PORT, config.endpointPort)
            putExtra(PenVpnService.EXTRA_PROTOCOL, config.protocol)
            putExtra(PenVpnService.EXTRA_CLIENT_PUB_KEY, config.clientPublicKey)
            putExtra(PenVpnService.EXTRA_SERVER_PUB_KEY, config.serverPublicKey)
            putExtra(PenVpnService.EXTRA_DNS_LEAK, config.dnsLeakProtection)
            putExtra(PenVpnService.EXTRA_IPV6_LEAK, config.ipv6LeakProtection)
            putExtra(PenVpnService.EXTRA_KILL_SWITCH, config.killSwitchEnabled)
            putExtra(PenVpnService.EXTRA_SERVER_LOAD, serverLoad)
            putExtra(PenVpnService.EXTRA_SERVER_PING, serverPing)
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                appContext.startForegroundService(intent)
            } else {
                appContext.startService(intent)
            }
        } catch (_: Exception) {
            PenVpnService.establishTunnelForVerifiedSession(
                config = config,
                serverLoad = serverLoad,
                serverPing = serverPing
            )
        }
    }

    /**
     * Tears down the active WireGuard TUN interface and stops the foreground [PenVpnService].
     */
    fun stopTunnel() {
        val intent = Intent(appContext, PenVpnService::class.java).apply {
            action = PenVpnService.ACTION_DISCONNECT
        }
        try {
            appContext.startService(intent)
        } catch (_: Exception) {
        }
        PenVpnService.disconnectVerifiedSession()
    }

    fun setAutoReconnectEnabled(enabled: Boolean) {
        autoReconnectEnabled = enabled
    }

    fun onVpnPermissionDenied() {
        PenVpnService.updateErrorState(VpnErrorType.PERMISSION_DENIED)
    }

    fun clearError() {
        PenVpnService.updateErrorState(null)
    }

    private fun registerNetworkMonitorIfNeeded() {
        if (networkCallbackRegistered) return
        try {
            val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            cm.registerNetworkCallback(request, networkCallback)
            networkCallbackRegistered = true
        } catch (_: Exception) {
        }
    }
}
