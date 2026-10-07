package com.example.vpn

enum class TunnelStatus {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    DISCONNECTING,
    ERROR
}

enum class VpnErrorType(val title: String, val message: String) {
    NO_INTERNET(
        "No Internet Connection",
        "No active network connection was detected. Please check your Wi-Fi or mobile data and try again."
    ),
    SERVER_UNAVAILABLE(
        "VPN Server Unavailable",
        "The selected VPN server is currently offline or undergoing maintenance. Please choose another server or use Fastest Server."
    ),
    CONNECTION_TIMEOUT(
        "Connection Timeout",
        "The encrypted WireGuard handshake timed out. Check your network firewall or switch to WireGuard (TCP)."
    ),
    AUTH_FAILURE(
        "Authentication Failure",
        "Failed to verify session or premium server entitlement. Please sign in or select a Free server location."
    ),
    SERVER_OVERLOADED(
        "Server Overloaded",
        "This server is currently above 90% load capacity. Please use Fastest Server for optimal performance."
    ),
    PERMISSION_DENIED(
        "VPN Permission Not Granted",
        "Android OS requires VPN tunnel authorization to establish an encrypted connection. Tap Retry to grant permission."
    )
}

data class WireGuardTunnelConfig(
    val serverId: String,
    val countryName: String,
    val flagEmoji: String,
    val city: String,
    val endpointHost: String,
    val endpointPort: Int,
    val protocol: String,
    val clientPublicKey: String,
    val serverPublicKey: String,
    val assignedTunnelIp: String = "10.66.66.2/32",
    val assignedIpv6: String = "fd00:66:66::2/128",
    val dnsServers: List<String> = listOf("1.1.1.1", "1.0.0.1"),
    val mtu: Int = 1280,
    val persistentKeepaliveSeconds: Int = 25,
    val dnsLeakProtection: Boolean = true,
    val ipv6LeakProtection: Boolean = true,
    val killSwitchEnabled: Boolean = true
) {
    /**
     * Generates a standard WireGuard wg-quick compatible configuration block
     * without ever embedding raw private keys in plain text logs.
     */
    fun toWgQuickConfigSummary(): String = buildString {
        appendLine("[Interface]")
        appendLine("PublicKey = $clientPublicKey")
        appendLine("Address = $assignedTunnelIp${if (ipv6LeakProtection) ", $assignedIpv6" else ""}")
        appendLine("DNS = ${if (dnsLeakProtection) dnsServers.joinToString(", ") else "8.8.8.8"}")
        appendLine("MTU = $mtu")
        appendLine()
        appendLine("[Peer]")
        appendLine("PublicKey = $serverPublicKey")
        appendLine("Endpoint = $endpointHost:$endpointPort")
        appendLine("AllowedIPs = 0.0.0.0/0${if (ipv6LeakProtection) ", ::/0" else ""}")
        appendLine("PersistentKeepalive = $persistentKeepaliveSeconds")
    }
}

data class VpnConnectionTelemetry(
    val status: TunnelStatus = TunnelStatus.DISCONNECTED,
    val activeConfig: WireGuardTunnelConfig? = null,
    val connectedSinceEpochMs: Long = 0L,
    val elapsedSeconds: Long = 0L,
    val downloadSpeedBps: Long = 0L,
    val uploadSpeedBps: Long = 0L,
    val totalDownloadedBytes: Long = 0L,
    val totalUploadedBytes: Long = 0L,
    val serverLoadPercent: Int = 0,
    val serverPingMs: Int = 0,
    val tunInterfaceEstablished: Boolean = false,
    val lastHandshakeEpochMs: Long = 0L,
    val reconnectAttemptCount: Int = 0,
    val error: VpnErrorType? = null
)
